import test from 'node:test';
import assert from 'node:assert/strict';
import {CallReasoningPolicy as HarnessPolicy} from '../call-policy.mjs';
import {CallReasoningPolicy as GoPolicy} from '../../dsh-go/package/call-policy.mjs';
import {localCarrier} from '../../dsh-go/package/carrier.mjs';

function scope() {
  const hooks = new Map();
  return {hooks, on(name, callback, options = {}) {
    const rows = hooks.get(name) || []; hooks.set(name, rows);
    options.prepend ? rows.unshift(callback) : rows.push(callback);
    return () => {const index = rows.indexOf(callback); if (index >= 0) rows.splice(index, 1);};
  }, async waterfall(name, payload, result) {
    const rows = [...hooks.get(name) || []];
    const next = () => rows.length ? rows.shift()(payload, next) : Promise.resolve(result);
    return next();
  }, emit(name, payload) {for (const callback of hooks.get(name) || []) callback(payload);}};
}
function fixture(Policy, options = {}) {
  const ctx = scope(); const agent = {id: 'owned', ctx}; const events = [];
  const session = {id: agent.id, active: {requestId: 'mine', emit: (type, data) => events.push({type, data})}};
  const owned = new Set([session]); let current = agent;
  const controller = {resolveAgent: async () => ({agent: current}), selectModel() {throw Error('must_not_save_defaults');}};
  const policy = new Policy(controller, {isOwned: item => owned.has(item),
    inspectModel: async () => ({reasoning: {efforts: [{id: 'off'}, {id: 'high'}]}}), ...options});
  return {ctx, agent, session, events, owned, controller, policy, replace: next => {current = next;}};
}
async function admit(f, rpcId = 'mine', turn = 1) {
  return f.ctx.waterfall('agent/pre-step', {agent: f.agent, turn, step: 1},
    {kind: 'enter', messages: [{source: {kind: 'user', rpcId}}]});
}
for (const [name, Policy] of [['Harness', HarnessPolicy], ['Go', GoPolicy]]) {
  test(name + ': own RPC gets off after host selection and preserves all other controls', async () => {
    const f = fixture(Policy); const config = {provider: 'p', model: 'm', reasoningEffort: 'xhigh', maxTokens: 32768,
      temperature: .5, tools: [{name: 'same-tool'}], persona: {same: true}};
    f.ctx.on('agent/request', async (_payload, next) => ({...await next(), reasoningEffort: 'high'}));
    await f.policy.bind(f.session); await admit(f);
    const actual = await f.ctx.waterfall('agent/request', {agent: f.agent, turn: 1, step: 1}, config);
    assert.deepEqual(actual, {...config, reasoningEffort: 'off'});
    assert.equal(actual.tools, config.tools); assert.equal(actual.persona, config.persona);
    assert.equal(config.reasoningEffort, 'xhigh'); assert.deepEqual(f.events, []); f.policy.dispose();
  });
  test(name + ': unrelated RPC/agent remains unchanged and own tool continuation stays off', async () => {
    const f = fixture(Policy); await f.policy.bind(f.session); await admit(f, 'normal-chat');
    const config = {provider: 'p', model: 'm', reasoningEffort: 'high'};
    assert.equal(await f.ctx.waterfall('agent/request', {agent: f.agent, turn: 1}, config), config);
    await admit(f, 'mine', 2);
    await f.ctx.waterfall('agent/pre-step', {agent: f.agent, turn: 2, step: 2}, {kind: 'enter', messages: []});
    assert.equal((await f.ctx.waterfall('agent/request', {agent: f.agent, turn: 2, step: 2}, config)).reasoningEffort, 'off');
    assert.equal(await f.ctx.waterfall('agent/request', {agent: {id: 'ordinary'}, turn: 2}, config), config); f.policy.dispose();
  });
  test(name + ': telemetry contains metadata/counts only and excludes foreign/duplicate stream chunks', async () => {
    const f = fixture(Policy, {diagnostics: true, inspectModel: async () => ({
      reasoning: {efforts: [{id: 'off'}, {id: 'high'}], defaultEffort: 'high'}, headers: {authorization: 'PRIVATE_KEY'}, body: 'PRIVATE_BODY'})});
    await f.policy.bind(f.session); await admit(f);
    await f.ctx.waterfall('agent/request', {agent: f.agent, turn: 1, step: 1}, {provider: 'p', model: 'm', reasoningEffort: 'high'});
    const emit = frame => f.ctx.emit('agent/assistant-stream', {agent: f.agent, frame});
    emit({type: 'start', attemptId: 'a', revision: 1, turn: 99});
    emit({type: 'chunk', attemptId: 'a', revision: 2, index: 0, chunk: {type: 'reasoning-delta', text: 'FOREIGN'}});
    emit({type: 'start', attemptId: 'b', revision: 3, turn: 1});
    const chunk = {type: 'chunk', attemptId: 'b', revision: 4, index: 0, chunk: {type: 'reasoning-delta', text: 'PRIVATE'}};
    emit(chunk); emit(chunk);
    emit({type: 'chunk', attemptId: 'b', revision: 5, index: 1, chunk: {type: 'text-delta', text: 'VISIBLE_NOT_LOGGED'}});
    emit({type: 'end', attemptId: 'b', revision: 6, index: 2}); f.policy.finish(f.session, f.session.active);
    assert.equal(f.events[0].type, 'call_options'); assert.equal(f.events[0].data.effective_effort, 'off');
    assert.deepEqual(f.events[0].data.effort_ids, ['off', 'high']);
    assert.equal(f.events[0].data.override_applied, true); assert.equal(f.events[0].data.override_reason, 'advertised_off');
    assert.equal(f.events[1].type, 'call_metrics'); assert.equal(f.events[1].data.thinking_chars, 7);
    assert.equal(f.events[1].data.attempts, 1); assert.equal(f.events[1].data.source, 'agent_assistant_stream');
    assert.equal(/PRIVATE|VISIBLE|FOREIGN|authorization|headers/.test(JSON.stringify(f.events)), false); f.policy.dispose();
  });
  test(name + ': metadata failure preserves the original request and reports no override', async () => {
    const f = fixture(Policy, {diagnostics: true, inspectModel: async () => {throw Error('PRIVATE_ERROR');}});
    await f.policy.bind(f.session); await admit(f);
    const config = Object.freeze({provider: 'p', model: 'm'});
    const result = await f.ctx.waterfall('agent/request', {agent: f.agent, turn: 1}, config);
    assert.equal(result, config); assert.equal('reasoningEffort' in result, false);
    assert.equal(f.events[0].data.metadata_status, 'unknown');
    assert.equal(f.events[0].data.effective_effort, null); assert.equal(f.events[0].data.override_applied, false);
    assert.equal(f.events[0].data.override_reason, 'metadata_unavailable');
    assert.deepEqual(f.events[0].data.effort_ids, []); assert.equal(JSON.stringify(f.events).includes('PRIVATE_ERROR'), false); f.policy.dispose();
  });
  test(name + ': known unsupported model stays usable without inventing off support', async () => {
    for (const info of [{}, {reasoning: {efforts: []}}, {reasoning: {efforts: [{id: 'high'}]}}]) {
      const f = fixture(Policy, {diagnostics: true, inspectModel: async () => info});
      await f.policy.bind(f.session); await admit(f);
      const config = Object.freeze({provider: 'custom-route', model: 'same-wire-model', temperature: .7, maxTokens: 1234});
      const result = await f.ctx.waterfall('agent/request', {agent: f.agent, turn: 1}, config);
      assert.equal(result, config); assert.equal('reasoningEffort' in result, false);
      assert.equal(f.events[0].data.metadata_status, 'known');
      assert.equal(f.events[0].data.effective_effort, null); assert.equal(f.events[0].data.override_applied, false);
      assert.equal(f.events[0].data.override_reason, 'off_not_advertised'); f.policy.dispose();
    }
  });
  test(name + ': unsupported route preserves a previously selected effort exactly', async () => {
    const f = fixture(Policy, {diagnostics: true, inspectModel: async () => ({reasoning: {efforts: [{id: 'high'}]}})});
    await f.policy.bind(f.session); await admit(f);
    const config = Object.freeze({provider: 'p', model: 'm', reasoningEffort: 'high'});
    assert.equal(await f.ctx.waterfall('agent/request', {agent: f.agent, turn: 1}, config), config);
    assert.equal(f.events[0].data.effective_effort, 'high'); assert.equal(f.events[0].data.override_applied, false);
    f.policy.dispose();
  });
  test(name + ': capability checking is required even when diagnostics are disabled', async () => {
    let inspected = 0;
    const f = fixture(Policy, {inspectModel: async () => {inspected++; return {};}});
    await f.policy.bind(f.session); await admit(f);
    const config = {provider: 'p', model: 'm'};
    assert.equal(await f.ctx.waterfall('agent/request', {agent: f.agent, turn: 1}, config), config);
    assert.equal(inspected, 1); assert.deepEqual(f.events, []); f.policy.dispose();
    const missing = fixture(Policy, {inspectModel: undefined}); await missing.policy.bind(missing.session); await admit(missing);
    assert.equal(await missing.ctx.waterfall('agent/request', {agent: missing.agent, turn: 1}, config), config); missing.policy.dispose();
  });
  test(name + ': metadata resolving after disposal cannot apply off or emit stale diagnostics', async () => {
    let release; const ready = new Promise(resolve => {release = resolve;});
    const f = fixture(Policy, {diagnostics: true, inspectModel: () => ready});
    await f.policy.bind(f.session); await admit(f);
    const config = {provider: 'p', model: 'm', reasoningEffort: 'high'};
    const pending = f.ctx.waterfall('agent/request', {agent: f.agent, turn: 1}, config);
    await new Promise(resolve => setImmediate(resolve)); f.policy.dispose();
    release({reasoning: {efforts: [{id: 'off'}]}});
    assert.equal(await pending, config); assert.deepEqual(f.events, []);
  });
  test(name + ': replaced/disposed policies remove hooks and late binding cannot attach', async () => {
    const f = fixture(Policy); await f.policy.bind(f.session);
    const next = {id: 'owned', ctx: scope()}; f.replace(next); await f.policy.bind(f.session);
    assert.ok([...f.ctx.hooks.values()].every(rows => rows.length === 0)); f.policy.dispose();
    assert.ok([...next.ctx.hooks.values()].every(rows => rows.length === 0));
    await assert.rejects(f.policy.bind(f.session), /call_session_not_owned/);
    const late = fixture(Policy); let release;
    late.controller.resolveAgent = () => new Promise(resolve => {release = resolve;});
    const binding = late.policy.bind(late.session); late.policy.dispose(); release({agent: late.agent});
    await assert.rejects(binding, /call_session_not_owned/); assert.equal(late.ctx.hooks.size, 0);
  });
}
test('old-client diagnostic gating and failed telemetry cannot hold or close normal text', async () => {
  const calls = []; let rejectDiagnostic;
  const fetch = async (_url, options) => {
    const body = JSON.parse(options.body); calls.push(body);
    if (body.event?.type === 'call_options') return new Promise((_resolve, reject) => {rejectDiagnostic = reject;});
    return {ok: true, json: async () => ({accepted: true})};
  };
  const Carrier = localCarrier({connector_token: 'a'.repeat(32)}, fetch); const socket = new Carrier();
  socket.readyState = 1; socket.hello = true;
  const telemetry = JSON.stringify({type: 'event', request_id: 'r', event: {type: 'call_options', effective_effort: 'off'}});
  socket.send(telemetry); assert.equal(calls.length, 0); socket.callDiagnostics = true; socket.send(telemetry);
  socket.send(JSON.stringify({type: 'event', request_id: 'r', event: {type: 'text', text: 'normal reply'}}));
  await socket.queue; assert.equal(calls.length, 2); assert.equal(calls[1].event.type, 'text');
  rejectDiagnostic(Error('old client rejection')); await new Promise(resolve => setImmediate(resolve));
  assert.equal(socket.readyState, 1); socket.close();
});
