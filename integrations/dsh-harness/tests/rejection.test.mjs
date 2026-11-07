import test from 'node:test';
import assert from 'node:assert/strict';
import {MobileCallAdapter, PREFIX, TurnCollector} from '../adapter.mjs';
import {MobileRelayConnector} from '../relay.mjs';

const tick = () => new Promise(resolve => setImmediate(resolve));
async function until(check) {for (let n = 0; n < 100; n++) {if (check()) return; await tick();} throw Error('condition_not_met');}
function feed() {
  const values = [], pending = []; let ended = false;
  return {push(value) {if (pending.length) pending.shift()({value, done: false}); else values.push(value);},
    end() {ended = true; for (const resolve of pending.splice(0)) resolve({done: true});},
    next() {return values.length ? Promise.resolve({value: values.shift(), done: false}) : ended ? Promise.resolve({done: true}) : new Promise(resolve => pending.push(resolve));},
    return() {this.end(); return Promise.resolve({done: true});}, [Symbol.asyncIterator]() {return this;}};
}
const event = (seq, type, data) => ({type: 'event', event: {seq, type, data}});
function controller(mode) {
  const hooks = new Map(), q = feed(), calls = [];
  const agent = {id: 'owned-1', ctx: {on(name, fn) {const set = hooks.get(name) || new Set(); hooks.set(name, set); set.add(fn); return () => set.delete(fn);}}};
  let adapter, seq = 10;
  const c = {calls, agent, hooks, setAdapter(value) {adapter = value;},
    async resolveAgent() {return {agent};}, async create() {calls.push('create'); return {sessionId: agent.id};},
    follow(_input, signal) {q.push({type: 'snapshot', header: {id: agent.id}, cursor: seq}); signal.addEventListener('abort', () => q.end(), {once: true}); return q;},
    async prompt(input) {
      calls.push('prompt');
      // Equivalent to the public pre-step hook matching the exact queued RPC.
      adapter.session(agent.id).active.callPolicyTurn = 7;
      q.push(event(++seq, 'turn/start', {turn: 7}));
      if (mode === 'turn-rejected') q.push(event(++seq, 'turn/end', {turn: 7, reason: {kind: 'error', error: {code: 'UNSUPPORTED_REASONING_EFFORT', message: 'SECRET provider details'}}}));
      if (mode === 'agent-rejected') queueMicrotask(() => c.fail());
      return {accepted: true};
    },
    fail(who = agent) {for (const fn of hooks.get('agent/error') || []) fn({agent: who, error: Error('SECRET provider details')});},
    async cancel() {calls.push('cancel'); return {accepted: true};},
  }; return c;
}
const req = (suffix, payload) => new Request('http://localhost' + PREFIX + '/' + suffix, {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify(payload)});
async function setup(mode) {
  const c = controller(mode), a = new MobileCallAdapter(c); c.setAdapter(a);
  const route = suffix => a.routes().find(row => row.path.endsWith('/' + suffix)).fetch;
  await route('session')(req('session', {}));
  return {c, a, response: await route('prompt')(req('prompt', {session_id: 'owned-1', request_id: 'request-1', text: 'One call'}))};
}
const packets = raw => raw.trim().split('\n\n').filter(s => s.startsWith('event:')).map(s => ({type: /^event: (\w+)/.exec(s)[1], data: JSON.parse(/^data: (.*)$/m.exec(s)[1])}));

test('pre-entry request-config rejection closes only the exact RPC turn', () => {
  const c = new TurnCollector('request-1', 10);
  c.accept(event(11, 'turn/start', {turn: 6}));
  assert.equal(c.accept(event(12, 'turn/end', {turn: 6, reason: {kind: 'error'}}), 7), null);
  c.accept(event(13, 'turn/start', {turn: 7}));
  assert.deepEqual(c.accept(event(14, 'turn/end', {turn: 7, reason: {kind: 'error'}}), 7), {kind: 'ended', reason: 'error', text: ''});
});

for (const mode of ['turn-rejected', 'agent-rejected']) test(`${mode} returns one safe failure and done without replay`, async () => {
  const {c, a, response} = await setup(mode);
  const raw = await response.text(), rows = packets(raw);
  assert.equal(rows.filter(row => row.type === 'error').length, 1);
  assert.equal(rows.filter(row => row.type === 'done').length, 1);
  assert.equal(rows.some(row => row.type === 'text'), false);
  assert.equal(raw.includes('SECRET'), false);
  assert.equal(c.calls.filter(value => value === 'prompt').length, 1);
  assert.equal(a.session('owned-1').active, null);
  assert.equal(c.hooks.get('agent/error')?.size, 0);
  await a.dispose();
});

test('foreign agent failure cannot end a genuine pending request', async () => {
  const {c, a, response} = await setup('pending'); let finished = false;
  const text = response.text().then(raw => {finished = true; return raw;});
  for (let n = 0; n < 10; n++) {c.fail({id: 'foreign'}); await tick();}
  assert.equal(finished, false);
  assert.equal(c.calls.filter(value => value === 'prompt').length, 1);
  c.fail(); assert.ok(packets(await text).some(row => row.type === 'done'));
  await a.dispose();
});

class Socket {
  static all = [];
  constructor() {this.readyState = 0; this.listeners = {}; this.sent = []; Socket.all.push(this);}
  addEventListener(type, fn) {(this.listeners[type] ||= []).push(fn);}
  emit(type, value = {}) {for (const fn of this.listeners[type] || []) fn(value);}
  send(raw) {this.sent.push(JSON.parse(raw));}
  close() {this.readyState = 3; this.emit('close');}
  receive(value) {this.emit('message', {data: JSON.stringify(value)});}
}
for (const mode of ['turn-rejected', 'agent-rejected']) test(`relay ${mode} clears phone processing using relay-compatible error status and done`, async () => {
  Socket.all = []; const c = controller(mode);
  const r = new MobileRelayConnector(c, {read: async () => undefined, save: async () => {}},
    {relay_url: 'https://relay.example', verification_code: 'ABCD-1234'}, {WebSocket: Socket, retryDelays: []});
  c.setAdapter(r.adapter); await r.start();
  const s = Socket.all[0]; s.readyState = 1; s.emit('open');
  s.receive({type: 'paired', binding_id: 'binding-1', connector_token: 'test_not_a_live_connector_token'}); await r.messageChain;
  s.receive({type: 'request', request_id: 'request-1', session_id: 'phone-1', text: 'One call', reply_language: 'zh'});
  await until(() => s.sent.some(frame => frame.type === 'done'));
  assert.deepEqual(s.sent.filter(frame => frame.type === 'done'), [{type: 'done', request_id: 'request-1', success: false}]);
  assert.equal(s.sent.filter(frame => frame.event?.type === 'status' && frame.event.status === 'error').length, 1);
  assert.equal(s.sent.some(frame => frame.event?.type === 'text' || frame.event?.type === 'error'), false);
  assert.equal(JSON.stringify(s.sent).includes('SECRET'), false);
  assert.equal(c.calls.filter(value => value === 'prompt').length, 1);
  await r.dispose();
});
