import test from 'node:test';
import assert from 'node:assert/strict';
import { MobileRelayConnector, relayAddress, replyPrompt } from '../relay.mjs';
import { apply } from '../index.js';

test('English and bilingual caption turns keep their requested envelope once', () => {
  assert.match(replyPrompt('Hello', 'en'), /请用英语回复/);
  for (const language of ['ja', 'en']) {
    const text = `[手机双语字幕:v1;target=${language}]\n本轮输出两份字幕。\n你好`;
    const prompt = replyPrompt(text, language);
    assert.ok(prompt.startsWith(text + '\n\n'));
    assert.equal(prompt.split('通话时用自然短句').length - 1, 1);
    assert.equal(replyPrompt(prompt, language), prompt);
  }
});

const token = 'test_connector_token_not_a_live_secret';
const tick = () => new Promise(resolve => setImmediate(resolve));
async function wait(test) { for (let i = 0; i < 100; i++) { if (test()) return; await tick(); } throw new Error('condition_not_met'); }
class Socket {
  static all = [];
  constructor(url) { this.url = url; this.readyState = 0; this.listeners = {}; this.sent = []; Socket.all.push(this); }
  addEventListener(type, callback) { (this.listeners[type] ||= []).push(callback); }
  emit(type, event = {}) { for (const callback of this.listeners[type] || []) callback(event); }
  open() { this.readyState = 1; this.emit('open'); }
  send(raw) { this.sent.push(JSON.parse(raw)); }
  close() { this.readyState = 3; this.emit('close'); }
  receive(frame) { this.emit('message', {data: JSON.stringify(frame)}); }
}
function feed() {
  const buffered = []; const waits = []; let closed = false;
  return { push(value) { if (waits.length) waits.shift()({value, done: false}); else buffered.push(value); },
    end() { closed = true; for (const resolve of waits.splice(0)) resolve({done: true}); },
    next() { if (buffered.length) return Promise.resolve({value: buffered.shift(), done: false});
      return closed ? Promise.resolve({done: true}) : new Promise(resolve => waits.push(resolve)); },
    return() { this.end(); return Promise.resolve({done: true}); }, [Symbol.asyncIterator]() { return this; } };
}
function controller(mode = 'normal') {
  const calls = []; let index = 0; const sessions = new Map();
  const c = { calls, sessions,
    async resolveAgent(id) {return {agent: {id, ctx: {on() {return () => {};}}}};},
    async create() { const id = 'owned-' + (++index); calls.push(['create', id]); sessions.set(id, {seq: 10, feed: feed()}); return {sessionId: id}; },
    follow(input, signal) { const id = input.address.sessionId; const s = sessions.get(id); calls.push(['follow', id]);
      s.feed.push({type: 'snapshot', header: {id}, cursor: s.seq});
      signal.addEventListener('abort', () => s.feed.end(), {once: true}); return s.feed; },
    async prompt(input) { calls.push(['prompt', input]); const s = sessions.get(input.sessionId); s.input = input;
      c.event(s, 'turn/start', {turn: 1});
      c.event(s, 'user/message', {source: {kind: 'user', rpcId: input.requestId}});
      if (mode === 'normal') c.finish(input.sessionId);
      if (mode === 'broken') s.feed.end();
      return {accepted: true}; },
    event(s, type, data) { s.feed.push({type: 'event', event: {seq: ++s.seq, type, data}}); },
    finish(id, kind = 'completed') { const s = sessions.get(id);
      c.event(s, 'assistant/attempt', {turn: 1, stream: [{type: 'reasoning-delta', text: 'HIDDEN'}]});
      c.event(s, 'assistant/message', {turn: 1, message: {content: [{type: 'text', text: 'TOOL'}, {type: 'tool-call', name: 'shell'}]}});
      c.event(s, 'assistant/message', {turn: 1, message: {content: [{type: 'reasoning', text: 'PRIVATE'}, {type: 'text', text: '[happy][gesture:raise_hand] 你好。[gesture:invalid]'}]}});
      c.event(s, 'turn/end', {turn: 1, reason: {kind}}); },
    async cancel(input) { calls.push(['cancel', input.sessionId]);
      if (mode !== 'unconfirmed') { const s = sessions.get(input.sessionId); c.event(s, 'turn/end', {turn: 1, reason: {kind: 'aborted'}}); }
      return {accepted: true}; },
  }; return c;
}
async function ready(mode = 'normal', initial = undefined) {
  Socket.all = []; let stored = initial; const saved = [];
  const c = controller(mode); const relay = new MobileRelayConnector(c,
    {read: async () => stored, save: async value => { stored = value; saved.push(value); }},
    {relay_url: 'https://relay.example', verification_code: 'ABCD-1234'},
    {WebSocket: Socket, retryDelays: [], cancelWaitMs: 5});
  await relay.start(); const socket = Socket.all[0]; socket.open();
  socket.receive({type: 'paired', binding_id: 'binding-1', connector_token: token});
  await relay.messageChain;
  return {relay, socket, c, saved};
}
const request = (id = 'request-1', extra = {}) => ({type: 'request', request_id: id, session_id: 'phone-session-1',
  text: '你好', reply_language: 'zh', ...extra});

test('relay URL permits only secure base address and never query credentials', () => {
  assert.deepEqual(relayAddress('https://relay.example/mobile-call/v1/'), {origin: 'https://relay.example', socket: 'wss://relay.example/mobile-call/v1/connector'});
  for (const value of ['http://relay.example', 'https://key@relay.example', 'https://relay.example?token=x', 'https://relay.example/other']) {
    assert.throws(() => relayAddress(value), /invalid_relay_url/);
  }
});
test('validated upstream language note is preserved once without a second language instruction', () => {
  const note = '[本轮回复语言：ja] 本轮直接用日语回答。';
  const prompt = replyPrompt(note + '\n普通正文', 'ja');
  assert.equal(prompt.split(note).length - 1, 1);
  assert.equal(prompt.includes('请用日语回复。'), false);
  assert.ok(prompt.endsWith(note + '\n普通正文'));
});
test('first authentication message carries code, private store receives token, never URL', async () => {
  const {relay, socket, saved} = await ready();
  assert.deepEqual(socket.sent[0], {type: 'hello', mode: 'deepseek_harness', verification_code: 'ABCD-1234'});
  assert.equal(socket.url, 'wss://relay.example/mobile-call/v1/connector');
  assert.equal(saved[0].connector_token, token); assert.equal(relay.code, ''); await relay.dispose();
});
test('same relay stored token replaces transient pairing code on reconnection', async () => {
  const {relay, socket} = await ready('normal', {relay_url: 'https://relay.example', binding_id: 'binding-1', connector_token: token});
  assert.deepEqual(socket.sent[0], {type: 'hello', mode: 'deepseek_harness', connector_token: token}); await relay.dispose();
});
test('different relay cannot inherit stored token or unrelated sessions', async () => {
  const {relay, socket, c} = await ready('normal', {relay_url: 'https://other.example', binding_id: 'foreign', connector_token: token});
  assert.equal(socket.sent[0].verification_code, 'ABCD-1234');
  socket.receive(request('req', {session_id: 'someone-elses-host-session'})); await wait(() => socket.sent.some(x => x.type === 'done'));
  assert.equal(c.calls.find(x => x[0] === 'prompt')[1].sessionId, 'owned-1'); await relay.dispose();
});
test('only committed final body is transferred with original request identity and language', async () => {
  const {relay, socket, c} = await ready(); socket.receive(request('req-ja', {reply_language: 'ja'}));
  await wait(() => socket.sent.some(x => x.type === 'done'));
  assert.deepEqual(socket.sent.find(x => x.event?.type === 'text'), {type: 'event', request_id: 'req-ja', event: {type: 'text', text: '[happy][gesture:raise-hand]你好。'}});
  assert.deepEqual(socket.sent.at(-1), {type: 'done', request_id: 'req-ja', success: true});
  assert.equal(/HIDDEN|PRIVATE|TOOL/.test(JSON.stringify(socket.sent)), false);
  assert.match(c.calls.find(x => x[0] === 'prompt')[1].content[0].text, /请用日语回复/); await relay.dispose();
});
test('duplicate request cannot replay or terminate an in-flight original', async () => {
  const {relay, socket, c} = await ready('waiting'); socket.receive(request()); socket.receive(request());
  await wait(() => c.calls.some(x => x[0] === 'prompt'));
  assert.equal(c.calls.filter(x => x[0] === 'prompt').length, 1); assert.equal(socket.sent.some(x => x.type === 'done'), false);
  c.finish('owned-1'); await wait(() => socket.sent.some(x => x.type === 'done'));
  assert.equal(socket.sent.filter(x => x.type === 'done').length, 1); await relay.dispose();
});
test('parallel request on same phone session never unlocks another active turn', async () => {
  const {relay, socket, c} = await ready('waiting'); socket.receive(request('a')); socket.receive(request('b'));
  await wait(() => socket.sent.some(x => x.type === 'done' && x.request_id === 'b'));
  assert.equal(c.calls.filter(x => x[0] === 'create').length, 1); assert.equal(c.calls.filter(x => x[0] === 'prompt').length, 1);
  c.finish('owned-1'); await wait(() => socket.sent.some(x => x.type === 'done' && x.request_id === 'a'));
  assert.equal(socket.sent.find(x => x.type === 'done' && x.request_id === 'a').success, true); await relay.dispose();
});
test('cancel confirmation requires actual official turn end', async () => {
  const {relay, socket, c} = await ready('waiting'); socket.receive(request()); await wait(() => c.calls.some(x => x[0] === 'prompt'));
  socket.receive({type: 'cancel', request_id: 'request-1'});
  await wait(() => socket.sent.some(x => x.type === 'cancelled'));
  assert.equal(socket.sent.find(x => x.type === 'cancelled').confirmed, true);
  assert.equal(socket.sent.some(x => x.event?.type === 'text'), false); await relay.dispose();
});
test('accepted cancel without turn end is never promoted to confirmed', async () => {
  const {relay, socket, c} = await ready('unconfirmed'); socket.receive(request()); await wait(() => c.calls.some(x => x[0] === 'prompt'));
  socket.receive({type: 'cancel', request_id: 'request-1'});
  await new Promise(resolve => setTimeout(resolve, 15));
  assert.equal(socket.sent.find(x => x.type === 'cancelled')?.confirmed, false); await relay.dispose();
});
test('disconnect cancels only owned sessions and never automatically replays prompt', async () => {
  const {relay, socket, c} = await ready('waiting'); socket.receive(request()); await wait(() => c.calls.some(x => x[0] === 'prompt'));
  socket.close(); await tick(); assert.equal(c.calls.filter(x => x[0] === 'prompt').length, 1);
  assert.ok(c.calls.some(x => x[0] === 'cancel' && x[1] === 'owned-1')); await relay.dispose();
});
test('follow truncation cannot report successful final output', async () => {
  const {relay, socket} = await ready('broken'); socket.receive(request()); await wait(() => socket.sent.some(x => x.type === 'done'));
  assert.equal(socket.sent.at(-1).success, false); assert.equal(socket.sent.some(x => x.event?.type === 'text'), false); await relay.dispose();
});
test('unpaired or invalid language cannot create a host session', async () => {
  const {relay, c} = await ready(); relay.paired = false;
  await assert.rejects(relay.accept(JSON.stringify(request())), /pairing_required/);
  relay.paired = true; await assert.rejects(relay.accept(JSON.stringify(request('bad', {reply_language: 'xx'}))), /invalid_request/);
  assert.equal(c.calls.length, 0); assert.throws(() => replyPrompt('text', 'xx')); await relay.dispose();
});
test('old queued connection frames cannot reach a new connection', async () => {
  const {relay, socket, c} = await ready(); socket.close();
  await relay.accept(JSON.stringify(request()), socket);
  assert.equal(c.calls.length, 0); await relay.dispose();
});
test('host integration uses only its private credential record and preserves HTTP routes', async () => {
  Socket.all = []; const records = []; const hooks = []; const routes = []; const c = controller();
  const scoped = {sessionController: c, connection: {fetch: {register(route) { routes.push(route); return () => {}; }}},
    effect(fn) { return fn(); }, on(event, fn) { hooks.push(fn); },
    credentials: {async readRecord(key) { records.push(['read', key]); return undefined; },
      async modifyRecord(key, callback) { records.push(['write', key, await callback()]); }},
    inject(keys, fn) { assert.deepEqual(keys, ['credentials']); fn(scoped); }};
  apply({inject(keys, fn) { assert.deepEqual(keys, ['connection', 'sessionController']); fn(scoped); }},
    {relay_url: 'https://relay.example', verification_code: 'ABCD-1234'},
    {credentialKey: (scope, id) => scope + '/' + id, WebSocket: Socket});
  await wait(() => Socket.all.length === 1); const socket = Socket.all[0]; socket.open();
  socket.receive({type: 'paired', binding_id: 'binding-1', connector_token: token});
  await wait(() => records.some(x => x[0] === 'write'));
  assert.equal(routes.length, 4); assert.equal(records.length, 2);
  assert.ok(records.every(x => x[1] === 'dsh-mobile-call-adapter/mobile-relay'));
  assert.equal(records[1][2].kind, 'grant'); assert.equal(records[1][2].payload.connector_token, token);
  await Promise.all(hooks.map(fn => fn()));
});
test('confirmed cancelled session is replaced only for a new request, not replayed', async () => {
  const {relay, socket, c} = await ready('waiting'); socket.receive(request()); await wait(() => c.calls.some(x => x[0] === 'prompt'));
  socket.receive({type: 'cancel', request_id: 'request-1'}); await wait(() => socket.sent.some(x => x.type === 'cancelled'));
  socket.receive(request('new-request')); await wait(() => c.calls.filter(x => x[0] === 'prompt').length === 2);
  assert.equal(c.calls.filter(x => x[0] === 'create').length, 2);
  assert.equal(c.calls.filter(x => x[0] === 'prompt')[1][1].sessionId, 'owned-2');
  c.finish('owned-2'); await wait(() => socket.sent.some(x => x.type === 'done' && x.request_id === 'new-request'));
  await relay.dispose();
});
