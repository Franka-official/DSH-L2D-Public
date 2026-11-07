import test from 'node:test';
import assert from 'node:assert/strict';
import { MobileCallAdapter, PREFIX, TurnCollector, visibleReply, presentation } from '../adapter.mjs';
import { apply } from '../index.js';

const req = (suffix, payload = {}, signal) => new Request('http://localhost' + PREFIX + '/' + suffix,
  {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify(payload), signal});
const event = (seq, type, data) => ({type: 'event', event: {seq, type, data, time: 0}});
const message = text => ({role: 'assistant', content: [{type: 'text', text}], source: {kind: 'model'}});
function queue() {
  const pending = []; const values = []; let ended = false;
  return {
    push(value) { if (pending.length) pending.shift()({value, done: false}); else values.push(value); },
    end() { ended = true; for (const resolve of pending.splice(0)) resolve({done: true}); },
    next() { if (values.length) return Promise.resolve({value: values.shift(), done: false});
      if (ended) return Promise.resolve({done: true}); return new Promise(resolve => pending.push(resolve)); },
    return() { this.end(); return Promise.resolve({done: true}); },
    [Symbol.asyncIterator]() { return this; },
  };
}
function fakeController(mode = 'normal') {
  let seq = 10; const feed = queue(); const calls = []; let lastRequest;
  const controller = {
    calls, feed,
    async resolveAgent(id) {return {agent: {id, ctx: {on() {return () => {};}}}};},
    async create(input) { calls.push(['create', input]); return {sessionId: 'session-1'}; },
    follow(input, signal) {
      calls.push(['follow', input]);
      feed.push({type: 'snapshot', header: {id: 'session-1'}, cursor: seq, records: [
        {type: 'event', event: {seq: 8, type: 'assistant/message', data: {turn: 0, message: message('OLD SECRET')}}},
      ], projections: {values: {}}, hasMore: false});
      signal.addEventListener('abort', () => feed.end(), {once: true});
      return feed;
    },
    async prompt(input) {
      calls.push(['prompt', input]); lastRequest = input;
      if (mode === 'reject') throw new Error('secret internal stack');
      if (mode === 'pending') return {accepted: true};
      controller.enter();
      if (mode !== 'waiting') controller.finish();
      return {accepted: true};
    },
    enter() {
      feed.push(event(++seq, 'turn/start', {turn: 1}));
      feed.push(event(++seq, 'user/message', {role: 'user', content: lastRequest.content,
        source: {kind: 'user', rpcId: lastRequest.requestId}}));
    },
    finish(reason = 'completed') {
      feed.push(event(++seq, 'assistant/attempt', {turn: 1, stream: [{type: 'reasoning-delta', text: 'HIDDEN'}]}));
      feed.push(event(++seq, 'assistant/message', {turn: 1, step: 1, message: {content: [
        {type: 'text', text: 'TOOL INTERMEDIATE'}, {type: 'tool-call', name: 'shell', arguments: '{}'},
      ]}}));
      feed.push(event(++seq, 'assistant/message', {turn: 1, step: 2, message: {content: [
        {type: 'reasoning', text: 'PRIVATE'}, {type: 'text', text: '[happy] Hello phone.'},
      ]}}));
      feed.push(event(++seq, 'turn/end', {turn: 1, reason: {kind: reason}}));
    },
    async cancel(input) { calls.push(['cancel', input]);
      if (mode !== 'pending') feed.push(event(++seq, 'turn/end', {turn: 1, reason: {kind: 'aborted', reason: {kind: 'user'}}}));
      return {accepted: true}; },
    push(type, data) { feed.push(event(++seq, type, data)); },
  };
  return controller;
}
function handler(adapter, suffix) { return adapter.routes().find(route => route.path.endsWith('/' + suffix)).fetch; }
async function create(adapter) { return (await handler(adapter, 'session')(req('session'))).json(); }
async function streamEvents(response) {
  const raw = await response.text();
  return raw.trim().split('\n\n').filter(Boolean).map(chunk => {
    const lines = chunk.split('\n');
    return {event: lines[0].slice(7), data: JSON.parse(lines[1].slice(6))};
  });
}
const promptBody = {session_id: 'session-1', request_id: 'request-1', text: 'Hello'};
const tick = () => new Promise(resolve => setImmediate(resolve));

test('routes register only through the authenticated owner-only connection API', () => {
  const routes = []; const disposers = []; const services = fakeController();
  apply({inject(keys, install) {
    assert.deepEqual(keys, ['connection', 'sessionController']);
    install({sessionController: services, connection: {fetch: {register(route) { routes.push(route); return () => {}; }}},
      effect(fn) { disposers.push(fn()); }, on(name) { assert.equal(name, 'dispose'); }});
  }});
  assert.equal(routes.length, 4);
  assert.ok(routes.every(route => route.path.startsWith('/api/mobile-call/v1/')));
  assert.equal(disposers.length, 4);
});

test('capabilities are non-inference; session creates only with default configuration', async () => {
  const c = fakeController(); const a = new MobileCallAdapter(c);
  const caps = await (await handler(a, 'capabilities')(new Request('http://localhost'))).json();
  assert.equal(caps.protocol, 'live2d.call.v1'); assert.equal(caps.supports_native_approvals, false);
  assert.equal(c.calls.length, 0);
  assert.deepEqual(await create(a), {session_id: 'session-1'});
  assert.deepEqual(c.calls, [['create', {}]]);
});

test('follow ready precedes prompt; only committed final text is delivered', async () => {
  const c = fakeController(); const a = new MobileCallAdapter(c); await create(a);
  const response = await handler(a, 'prompt')(req('prompt', promptBody));
  assert.equal(response.headers.get('content-type'), 'text/event-stream; charset=utf-8');
  const events = await streamEvents(response);
  assert.deepEqual(c.calls.map(call => call[0]), ['create', 'follow', 'prompt']);
  assert.deepEqual(c.calls[2][1], {sessionId: 'session-1', requestId: 'request-1', mode: 'queue', content: [{type: 'text', text: 'Hello'}]});
  assert.deepEqual(events, [
    {event: 'status', data: {status: 'running'}},
    {event: 'emotion', data: {emotion: 'happy'}},
    {event: 'text', data: {text: 'Hello phone.', delta: false}},
    {event: 'status', data: {status: 'completed'}},
    {event: 'done', data: {session_id: 'session-1', request_id: 'request-1'}},
  ]);
  assert.equal(JSON.stringify(events).includes('PRIVATE'), false);
});

test('parallel turn and reused request ID never resubmit', async () => {
  const c = fakeController('waiting'); const a = new MobileCallAdapter(c); await create(a);
  const response = await handler(a, 'prompt')(req('prompt', promptBody)); await tick();
  assert.equal((await handler(a, 'prompt')(req('prompt', {...promptBody, request_id: 'other'}))).status, 409);
  c.finish(); await streamEvents(response);
  assert.equal((await handler(a, 'prompt')(req('prompt', promptBody))).status, 409);
  assert.equal(c.calls.filter(call => call[0] === 'prompt').length, 1);
});

test('approval waits stay native; no approval mutation is exposed', async () => {
  const c = fakeController('waiting'); const a = new MobileCallAdapter(c); await create(a);
  const response = await handler(a, 'prompt')(req('prompt', promptBody)); await tick();
  c.push('approval/asked', {id: 'approval-1', toolName: 'shell'});
  c.push('approval/decided', {id: 'approval-1', outcome: 'allowed-once'});
  c.finish(); const events = await streamEvents(response);
  assert.equal(events.filter(item => item.data.status === 'needs_interaction').length, 1);
  assert.deepEqual(c.calls.map(call => call[0]), ['create', 'follow', 'prompt']);
  assert.equal(a.routes().some(route => /approve|answer/.test(route.path)), false);
});

test('question tool waits are detected from official tool events and returned by call ID', async () => {
  const c = fakeController('waiting'); const a = new MobileCallAdapter(c); await create(a);
  const response = await handler(a, 'prompt')(req('prompt', promptBody)); await tick();
  c.push('tool/call', {turn: 1, step: 1, callId: 'question-1', name: 'ask_user_question', arguments: '{}'});
  c.push('tool/result', {turn: 1, step: 1, message: {role: 'tool', toolCallId: 'question-1', content: []}});
  c.finish(); const events = await streamEvents(response);
  assert.equal(events.filter(item => item.data.status === 'needs_interaction').length, 1);
  assert.equal(events.filter(item => item.data.status === 'running').length, 2);
});

test('multiple pending interactions do not prematurely report running', () => {
  const c = new TurnCollector('mine', 0);
  c.accept(event(1, 'turn/start', {turn: 1}));
  c.accept(event(2, 'user/message', {source: {kind: 'user', rpcId: 'mine'}}));
  c.accept(event(3, 'approval/asked', {id: 'a'}));
  c.accept(event(4, 'approval/asked', {id: 'b'}));
  assert.equal(c.accept(event(5, 'approval/decided', {id: 'a'})), null);
  assert.equal(c.accept(event(6, 'approval/decided', {id: 'b'})).kind, 'resumed');
});

test('retired idle sessions are reclaimed without touching unrelated sessions', async () => {
  const c = fakeController(); const a = new MobileCallAdapter(c, {maxSessions: 1});
  await create(a);
  assert.equal((await handler(a, 'session')(req('session'))).status, 429);
  await handler(a, 'cancel')(req('cancel', {session_id: 'session-1'}));
  c.create = async () => ({sessionId: 'session-2'});
  assert.deepEqual(await create(a), {session_id: 'session-2'});
  assert.equal(a.sessions.has('session-1'), false);
});

test('concurrent creation reserves its slot before the controller returns', async () => {
  let finish;
  const c = fakeController(); c.create = () => new Promise(resolve => { finish = resolve; });
  const a = new MobileCallAdapter(c, {maxSessions: 1});
  const first = handler(a, 'session')(req('session')); await tick();
  assert.equal((await handler(a, 'session')(req('session'))).status, 429);
  finish({sessionId: 'session-1'});
  assert.equal((await first).status, 200);
});

test('cancel returns acknowledged and retires the session only after observing turn end', async () => {
  const c = fakeController('waiting'); const a = new MobileCallAdapter(c); await create(a);
  const response = await handler(a, 'prompt')(req('prompt', promptBody)); await tick();
  assert.deepEqual(await (await handler(a, 'cancel')(req('cancel', {session_id: 'session-1'}))).json(), {accepted: true});
  const events = await streamEvents(response);
  assert.equal(events.find(item => item.event === 'error').data.code, 'cancelled');
  assert.equal(events.some(item => item.event === 'text'), false);
  assert.equal((await handler(a, 'prompt')(req('prompt', {...promptBody, request_id: 'new'}))).status, 409);
});

test('cancel before queued turn starts cancels again when actual turn starts', async () => {
  const c = fakeController('pending'); const a = new MobileCallAdapter(c); await create(a);
  const response = await handler(a, 'prompt')(req('prompt', promptBody)); await tick();
  await handler(a, 'cancel')(req('cancel', {session_id: 'session-1'}));
  assert.notEqual(a.sessions.get('session-1').active, null);
  c.enter(); await tick();
  assert.equal(c.calls.filter(call => call[0] === 'cancel').length, 2);
  c.finish('aborted'); const events = await streamEvents(response);
  assert.equal(events.find(item => item.event === 'error').data.code, 'cancelled');
});

test('HTTP disconnect cancels its owned turn and emits no further output', async () => {
  const c = fakeController('waiting'); const a = new MobileCallAdapter(c); await create(a);
  const stop = new AbortController();
  const response = await handler(a, 'prompt')(req('prompt', promptBody, stop.signal)); await tick();
  stop.abort(); await tick();
  assert.equal(c.calls.some(call => call[0] === 'cancel'), true);
  assert.equal(a.sessions.get('session-1').retired, true);
  await response.text();
});

test('unknown submission failure reports safe error and is never retried', async () => {
  const c = fakeController('reject'); const a = new MobileCallAdapter(c); await create(a);
  const events = await streamEvents(await handler(a, 'prompt')(req('prompt', promptBody)));
  assert.equal(events.find(item => item.event === 'error').data.code, 'submission_unknown_result');
  assert.equal(JSON.stringify(events).includes('secret internal'), false);
  assert.equal(c.calls.filter(call => call[0] === 'prompt').length, 1);
  assert.equal(a.sessions.get('session-1').retired, true);
});

test('other turns and stale snapshots do not become the requested final response', () => {
  const c = new TurnCollector('mine', 10);
  assert.equal(c.accept(event(9, 'turn/start', {turn: 1})), null);
  c.accept(event(11, 'turn/start', {turn: 2}));
  c.accept(event(12, 'user/message', {source: {kind: 'user', rpcId: 'other'}}));
  assert.equal(c.accept(event(13, 'turn/end', {turn: 2, reason: {kind: 'completed'}})), null);
  c.accept(event(14, 'turn/start', {turn: 3}));
  c.accept(event(15, 'user/message', {source: {kind: 'user', rpcId: 'mine'}}));
  c.accept(event(16, 'assistant/message', {turn: 2, message: message('unrelated')}));
  assert.equal(c.accept(event(17, 'turn/end', {turn: 3, reason: {kind: 'completed'}})).text, '');
});

test('max-token and error turns do not masquerade as completed speech', async () => {
  for (const reason of ['max-tokens', 'error', 'blocked']) {
    const c = fakeController('waiting'); const a = new MobileCallAdapter(c); await create(a);
    const response = await handler(a, 'prompt')(req('prompt', promptBody)); await tick(); c.finish(reason);
    const events = await streamEvents(response);
    assert.equal(events.some(item => item.event === 'text'), false);
    assert.ok(events.some(item => item.event === 'error'));
  }
});

test('only canonical leading expression is consumed; reasoning and tools excluded', () => {
  assert.deepEqual(presentation('[shy] hi'), {emotion: 'shy', text: 'hi'});
  assert.deepEqual(presentation('[joy] hi'), {emotion: 'neutral', text: '[joy] hi'});
  assert.equal(visibleReply({content: [{type: 'reasoning', text: 'secret'}, {type: 'text', text: 'body'}]}), 'body');
  assert.equal(visibleReply({content: [{type: 'text', text: 'call'}, {type: 'tool-call'}]}), '');
});

test('input scope rejects foreign session, extra options, empty and oversized bodies', async () => {
  const c = fakeController(); const a = new MobileCallAdapter(c); await create(a);
  assert.equal((await handler(a, 'prompt')(req('prompt', {...promptBody, session_id: 'foreign'}))).status, 404);
  assert.equal((await handler(a, 'prompt')(req('prompt', {...promptBody, model: 'other'}))).status, 400);
  assert.equal((await handler(a, 'prompt')(req('prompt', {...promptBody, text: ' '}))).status, 400);
  assert.equal((await handler(a, 'prompt')(req('prompt', {...promptBody, text: 'x'.repeat(21000)}))).status, 413);
  assert.equal((await handler(a, 'session')(req('session', {cwd: '/private'}))).status, 400);
  assert.equal(c.calls.filter(call => call[0] === 'prompt').length, 0);
});
