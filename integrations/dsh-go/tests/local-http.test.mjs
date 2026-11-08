import test, {before, after, afterEach} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {createHmac} from 'node:crypto';
import {localHttpFetch} from '../package/local-http.mjs';
import {proveLocal, challengeId} from '../package/identity.mjs';
import {LocalManager} from '../package/manager.mjs';
import packageMetadata from '../package/package.json' with {type: 'json'};

const origin = 'http://127.0.0.1:47651', prefix = '/local-dsh/v1', challenge = 'd'.repeat(64);
const current = {binding_id: 'current-binding', connector_token: 'test_current_role_token', expires_at_ms: Date.now() + 1800000};
const records = [], posts = [], sockets = new Set(), pending = new Set();
let mode = 'normal', emitted = false;
function wav() {
  const out = Buffer.alloc(32044); out.write('RIFF', 0); out.writeUInt32LE(out.length - 8, 4); out.write('WAVEfmt ', 8);
  out.writeUInt32LE(16, 16); out.writeUInt16LE(1, 20); out.writeUInt16LE(1, 22); out.writeUInt32LE(16000, 24);
  out.writeUInt32LE(32000, 28); out.writeUInt16LE(2, 32); out.writeUInt16LE(16, 34); out.write('data', 36); out.writeUInt32LE(32000, 40); return out;
}
const frame = {type: 'transcribe', request_id: 'tcp-asr-1', asr_request_id: 'tcp-asr-1', session_id: 'phone-session-1',
  audioBase64: wav().toString('base64'), sample_rate: 16000, audio_seconds: 1, language: 'auto', reply_language: 'zh'};
const server = createServer((request, response) => {
  sockets.add(request.socket); const chunks = [];
  request.on('data', data => chunks.push(data));
  request.on('end', () => {
    const url = new URL(request.url, origin), body = Buffer.concat(chunks);
    records.push({path: url.pathname, method: request.method, headers: request.headers, bytes: body.length});
    const json = (value, status = 200) => {response.writeHead(status, {'content-type': 'application/json'}); response.end(JSON.stringify(value));};
    if (url.pathname === prefix + '/discover') {
      if (mode === 'redirect') {response.writeHead(302, {location: origin + prefix + '/discover'}); response.end(); return;}
      const nonce = url.searchParams.get('nonce');
      json({protocol: 'live2d.local.v1', app: 'com.prts.client', nonce, asr_protocol: 'live2d.asr.v1',
        bootstrap_proof: createHmac('sha256', challenge).update(`live2d.local.v1|discover|${challengeId(challenge)}|${nonce}`).digest('hex')}); return;
    }
    if (url.pathname === prefix + '/pair/claim') {
      assert.equal(JSON.parse(body.toString('utf8')).challenge, challenge); json(current); return;
    }
    if (request.headers.authorization !== 'Bearer ' + current.connector_token) {json({ok: false}, 401); return;}
    if (url.pathname === prefix + '/connector/status') {json({...current, connected: true}); return;}
    if (url.pathname === prefix + '/connector/event') {posts.push(JSON.parse(body.toString('utf8'))); json({ok: true}); return;}
    if (url.pathname === prefix + '/connector/poll') {
      if (mode === 'oversized') {response.writeHead(200); response.end(Buffer.alloc(4 * 1024 * 1024 + 1)); return;}
      if (mode === 'asr' && !emitted) {emitted = true; json({frames: [frame]}); return;}
      if (mode === 'stall' || mode === 'asr') {pending.add(response); response.once('close', () => pending.delete(response)); return;}
      json({frames: [frame]}); return;
    }
    json({ok: false}, 404);
  });
});
before(async () => {await new Promise((resolve, reject) => {server.once('error', reject); server.listen(47651, '127.0.0.1', resolve);});});
afterEach(() => {mode = 'normal';});
after(async () => {for (const response of pending) response.destroy(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve));});
const tick = () => new Promise(resolve => setTimeout(resolve, 5));
async function wait(check) {for (let i = 0; i < 200; i++) {if (check()) return; await tick();} throw Error('condition_not_met');}

test('real TCP proof, byte-correct claim and >40K WAV poll ignore proxy env and never reuse connections', async () => {
  const previous = process.env.HTTP_PROXY; process.env.HTTP_PROXY = 'http://127.0.0.1:1';
  try {
    const before = records.length, socketCount = sockets.size;
    const proof = await proveLocal(localHttpFetch, challenge, AbortSignal.timeout(1000)); assert.equal(proof.asr_protocol, 'live2d.asr.v1');
    const claim = await localHttpFetch(origin + prefix + '/pair/claim', {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify({challenge})});
    assert.equal((await claim.json()).connector_token, current.connector_token);
    const poll = await localHttpFetch(origin + prefix + '/connector/poll', {headers: {authorization: 'Bearer ' + current.connector_token}});
    const value = await poll.json(); assert.ok(JSON.stringify(value).length > 40000); assert.equal(value.frames[0].audioBase64, frame.audioBase64);
    const rows = records.slice(before); assert.equal(rows.length, 3); assert.equal(sockets.size - socketCount, 3);
    assert.ok(rows.every(row => row.headers.connection === 'close' && row.headers['accept-encoding'] === 'identity'));
    assert.equal(Number(rows[1].headers['content-length']), rows[1].bytes);
  } finally {if (previous === undefined) delete process.env.HTTP_PROXY; else process.env.HTTP_PROXY = previous;}
});

test('real TCP abort and total deadline close a stalled poll', async () => {
  mode = 'stall'; const abort = new AbortController(), started = Date.now();
  const request = localHttpFetch(origin + prefix + '/connector/poll', {headers: {authorization: 'Bearer ' + current.connector_token}, signal: abort.signal});
  setTimeout(() => abort.abort(), 15); await assert.rejects(request); assert.ok(Date.now() - started < 1000);
  await assert.rejects(localHttpFetch(origin + prefix + '/connector/poll', {headers: {authorization: 'Bearer ' + current.connector_token}, timeoutMs: 15}));
});

test('real TCP response size is bounded and redirects are returned without following', async () => {
  mode = 'oversized'; await assert.rejects(localHttpFetch(origin + prefix + '/connector/poll', {headers: {authorization: 'Bearer ' + current.connector_token}}), error => error.code === 'LOCAL_RESPONSE_TOO_LARGE');
  mode = 'redirect'; const before = records.length;
  const response = await localHttpFetch(origin + prefix + '/discover'); assert.equal(response.status, 302); assert.equal(records.length - before, 1);
  await assert.rejects(localHttpFetch('http://127.0.0.1:1/local-dsh/v1/discover'), error => error.code === 'LOCAL_TARGET_REJECTED');
});

test('real TCP manager renews approved stale role through claim and returns ASR without submitting model work', async () => {
  mode = 'asr'; emitted = false; const before = posts.length, recordBefore = records.length; let promptCount = 0;
  let stored = {relay_url: origin, bootstrap_challenge: challenge, binding_id: 'old-binding', connector_token: 'test_old_role_token', expires_at_ms: Date.now() + 100000};
  const store = {read: async () => stored, save: async value => {stored = value;}};
  const speech = {catalog: () => ({providers: [{id: 'sensevoice', location: 'host-local', preparation: {phase: 'ready'}}], selection: {providerId: 'sensevoice'},
    maxAudioBytes: 4194304, maxDurationSeconds: 120}),
    async transcribe(request) {assert.equal(request.providerId, 'sensevoice'); assert.equal(Buffer.from(request.audioBase64, 'base64').length, 32044); return {text: '本机语音连接测试', audioSeconds: 1, inferenceSeconds: 0.05};}};
  const manager = new LocalManager({prompt() {promptCount++;}}, store, {getSpeechController: () => speech});
  try {
    manager.connect(); await manager.pending; await wait(() => posts.slice(before).some(row => row.type === 'transcription'));
    assert.equal(manager.snapshot().ready, true); assert.equal(stored.connector_token, current.connector_token);
    const capabilities = posts.slice(before).find(row => row.type === 'capabilities');
    assert.equal(capabilities.asr, true);
    assert.equal(capabilities.plugin_version, packageMetadata.version);
    assert.equal(posts.slice(before).find(row => row.type === 'transcription').text, '本机语音连接测试'); assert.equal(promptCount, 0);
    const rows = records.slice(recordBefore); assert.ok(rows.some(row => row.path.endsWith('/pair/claim')));
    assert.ok(rows.filter(row => row.path.endsWith('/connector/status')).every(row => row.headers.authorization === 'Bearer ' + current.connector_token));
  } finally {await manager.dispose();}
});
