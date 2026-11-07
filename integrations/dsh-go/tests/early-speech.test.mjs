import assert from 'node:assert/strict';
import {createHmac} from 'node:crypto';
import {setTimeout as wait} from 'node:timers/promises';
import test from 'node:test';
import {LocalConnector} from '../package/carrier.mjs';
import {challengeId, LOCAL_ORIGIN, LOCAL_PREFIX} from '../package/identity.mjs';

const requestId = 'fixture-request';
const challenge = 'a'.repeat(64);
const grant = {bootstrap_challenge: challenge, connector_token: 'b'.repeat(48), binding_id: 'fixture-binding'};
const sample = '好呀，见到你真开心！';
const cleanFinal = sample + '今天慢慢聊。';

async function until(predicate) {
  for (let i = 0; i < 100; i++) {
    if (predicate()) return;
    await wait(5);
  }
  assert.fail('Expected event did not arrive');
}

async function fixture(t, {capable = true, optIn = true, language = 'zh', text = '今天心情怎么样？'} = {}) {
  const sent = [];
  const handlers = new Map();
  const initial = [{type: 'snapshot', header: {id: 'fixture-session'}, cursor: 0}];
  let resolveFollow;
  let sequence = 0;
  let revision = 0;
  let index = 0;
  let attemptId = '';
  let submitted = 0;
  let promptBody;
  const agent = {id: 'fixture-session', ctx: {on(kind, listener) {
    handlers.set(kind, listener);
    return () => handlers.delete(kind);
  }}};
  function journal(type, data) {
    const value = {type: 'event', event: {seq: ++sequence, type, data}};
    if (resolveFollow) {
      const resolve = resolveFollow;
      resolveFollow = null;
      resolve({value, done: false});
    } else initial.push(value);
  }
  const frame = value => handlers.get('agent/assistant-stream')({agent, frame: {...value, revision: ++revision}});
  const controller = {
    async create() {return {sessionId: agent.id};},
    async resolveAgent() {return {agent};},
    follow() {return {[Symbol.asyncIterator]: () => ({
      next: () => initial.length ? Promise.resolve({value: initial.shift(), done: false}) :
        new Promise(resolve => {resolveFollow = resolve;}),
      return: async () => ({done: true}),
    })};},
    async prompt(input) {
      submitted++;
      promptBody = input.content[0].text;
      const messages = [{source: {kind: 'user', rpcId: input.requestId}}];
      await handlers.get('agent/pre-step')({agent, turn: 1}, async () => ({kind: 'enter', messages}));
      await handlers.get('agent/request')({agent, turn: 1, step: 1},
        async () => ({provider: 'fixture', model: 'fixture'}));
      journal('turn/start', {turn: 1});
      journal('user/message', messages[0]);
    },
    async cancel() {
      journal('turn/end', {turn: 1, reason: {kind: 'aborted'}});
    },
  };
  const fetch = async (raw, options) => {
    const url = new URL(raw);
    assert.equal(url.origin, LOCAL_ORIGIN);
    const path = url.pathname.slice(LOCAL_PREFIX.length);
    if (path === '/discover') {
      const nonce = url.searchParams.get('nonce');
      const proof = createHmac('sha256', challenge)
        .update(`live2d.local.v1|discover|${challengeId(challenge)}|${nonce}`).digest('hex');
      return Response.json({protocol: 'live2d.local.v1', app: 'com.prts.client', nonce, bootstrap_proof: proof,
        presentation: 'live2d.presentation.v1', ...(capable ? {early_speech: 'live2d.early-speech.v1'} : {})});
    }
    assert.equal(options.headers.authorization, 'Bearer ' + grant.connector_token);
    if (path === '/connector/status') return Response.json({connected: true, binding_id: grant.binding_id});
    if (path === '/connector/poll') return new Promise((_, reject) => {
      options.signal.addEventListener('abort', () => reject(options.signal.reason), {once: true});
    });
    assert.equal(path, '/connector/event');
    sent.push(JSON.parse(options.body));
    return Response.json({ok: true});
  };
  const store = {read: async () => ({...grant, relay_url: LOCAL_ORIGIN}), save: async () => {}};
  const connector = new LocalConnector(controller, store, grant, {fetch});
  t.after(() => connector.dispose());
  await connector.start();
  await until(() => connector.paired);
  const request = {type: 'request', request_id: requestId, session_id: 'phone-session',
    text, reply_language: language, ...(optIn ? {early_speech: true} : {})};
  await connector.accept(JSON.stringify(request));
  await until(() => submitted === 1 && promptBody);
  // Wait until the actual request hook has installed the attempt correlation.
  await until(() => connector.jobs.get(requestId)?.active?.callPolicyMetrics);
  return {
    start(id = 'fixture-session:1', step = 1) {
      attemptId = id; index = 0;
      frame({type: 'start', attemptId, turn: 1, step});
    },
    chunk(type, text, more = {}) {
      frame({type: 'chunk', attemptId, index: index++, chunk: {type, text, index: 0, ...more}});
    },
    end(eventType = 'assistant/message') {
      frame({type: 'end', attemptId, index, outcome: {kind: 'committed', eventType}});
    },
    async cancel() {
      await connector.accept(JSON.stringify({type: 'cancel', request_id: requestId}));
    },
    async commit(body = cleanFinal) {
      journal('assistant/message', {turn: 1, message: {content: [
        {type: 'reasoning', text: 'PRIVATE_REASONING'},
        {type: 'text', text: '[happy][gesture:bow]' + body},
      ]}});
      journal('turn/end', {turn: 1, reason: {kind: 'completed'}});
      await until(() => sent.some(value => value.type === 'done'));
      assert.equal(submitted, 1);
    },
    get prompt() {return promptBody;},
    get done() {return sent.find(value => value.type === 'done');},
    get events() {return sent.map(value => value.event).filter(event => event && event.type !== 'status');},
  };
}

test('capable opted-in rc.4 call prewarms one closed Han clause, then commits one clean body', async t => {
  const call = await fixture(t);
  assert.match(call.prompt, /一句完整的中文口语短句/);
  call.start();
  call.chunk('reasoning-delta', 'PRIVATE_REASONING。');
  call.chunk('text-delta', '[hap', {index: 1});
  call.chunk('text-delta', 'py][gesture:bow]好呀，见到你真开', {index: 1});
  assert.equal(call.events.length, 0);
  call.chunk('text-delta', '心！', {index: 1});
  await until(() => call.events.length === 1);
  assert.deepEqual(call.events, [{type: 'early_text', text: sample}]);
  assert.equal(call.done, undefined);
  call.chunk('text-delta', '今天慢慢聊。', {index: 1});
  call.end();
  await call.commit();
  assert.deepEqual(call.events, [
    {type: 'early_text', text: sample},
    {type: 'emotion', emotion: 'happy', gesture: 'bow'},
    {type: 'text', text: cleanFinal},
  ]);
  assert.equal(call.done.success, true);
});

test('tool, failed attempt, continuation and final mismatch invalidate rather than replaying preview', async t => {
  for (const reason of ['tool', 'retry', 'continuation', 'mismatch']) {
    await t.test(reason, async sub => {
      const call = await fixture(sub);
      call.start();
      call.chunk('text-delta', sample);
      await until(() => call.events.length === 1);
      if (reason === 'tool') call.chunk('tool-call-delta', undefined);
      if (reason === 'retry') call.end('assistant/attempt');
      if (reason === 'continuation') {
        call.end();
        call.start('fixture-session:2', 2);
      }
      if (reason !== 'mismatch') {
        if (reason !== 'continuation') call.start('fixture-session:2');
        call.chunk('text-delta', '不会再次预生成。');
        await until(() => call.events.length === 2);
      }
      const final = reason === 'mismatch' ? '换一句话吧。' : cleanFinal;
      await call.commit(final);
      assert.deepEqual(call.events, [
        {type: 'early_text', text: sample},
        {type: 'early_text_cancel'},
        {type: 'emotion', emotion: 'happy', gesture: 'bow'},
        {type: 'text', text: final},
      ]);
    });
  }
});

test('old phone, non-opt-in, bilingual, tool request and unsafe prefix remain committed only', async t => {
  for (const options of [{capable: false}, {optIn: false}, {language: 'ja'},
    {text: '请联网查询一下最新新闻。'}, {unsafe: true}, {code: true}]) {
    await t.test(JSON.stringify(options), async sub => {
      const call = await fixture(sub, options);
      call.start();
      call.chunk('reasoning-delta', 'PRIVATE_REASONING。');
      const prefix = options.unsafe ? '<think>思考过程。' : options.code ? '`代码`。' : sample;
      call.chunk('text-delta', prefix);
      call.end();
      await call.commit();
      assert.deepEqual(call.events, [
        {type: 'emotion', emotion: 'happy', gesture: 'bow'},
        {type: 'text', text: cleanFinal},
      ]);
    });
  }
});

test('interrupt leaves any provisional event uncommitted and sends no new text', async t => {
  const call = await fixture(t);
  call.start();
  call.chunk('text-delta', sample);
  await until(() => call.events.length === 1);
  await call.cancel();
  await until(() => Boolean(call.done));
  call.chunk('text-delta', '中断以后不要出声。');
  assert.deepEqual(call.events, [{type: 'early_text', text: sample}]);
  assert.equal(call.done.success, false);
});
