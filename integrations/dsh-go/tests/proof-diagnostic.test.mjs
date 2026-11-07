import test from 'node:test';
import assert from 'node:assert/strict';
import {createHmac} from 'node:crypto';
import {proveLocal, challengeId} from '../package/identity.mjs';
const challenge = 'c'.repeat(64);
const options = rows => ({diagnostic: (code, status) => rows.push({code, ...(status ? {status} : {})})});
function valid(raw) {
  const nonce = new URL(raw).searchParams.get('nonce');
  return {protocol: 'live2d.local.v1', app: 'com.prts.client', nonce,
    bootstrap_proof: createHmac('sha256', challenge).update(`live2d.local.v1|discover|${challengeId(challenge)}|${nonce}`).digest('hex'),
    asr: true, asr_protocol: 'live2d.asr.v1'};
}
test('proof diagnostic reports challenge shape without value or network access', async () => {
  const rows = []; await assert.rejects(proveLocal(() => assert.fail(), undefined, undefined, options(rows)));
  assert.deepEqual(rows, [{code: 'CHALLENGE_INVALID'}]);
});
test('proof diagnostic preserves HTTP rejection status', async () => {
  const rows = []; await assert.rejects(proveLocal(async () => new Response('', {status: 401}), challenge, undefined, options(rows)));
  assert.deepEqual(rows.at(-1), {code: 'PROOF_HTTP_REJECTED', status: 401});
});
test('proof diagnostic classifies fetch TypeError and only approved cause codes', async () => {
  for (const [cause, expected] of [['ECONNREFUSED', true], ['PRIVATE_SECRET_CAUSE', false]]) {
    const rows = []; await assert.rejects(proveLocal(async () => {throw new TypeError('PRIVATE_SECRET_MESSAGE', {cause: {code: cause}});}, challenge, undefined, options(rows)));
    assert.ok(rows.some(row => row.code === 'FETCH_TYPE_ERROR'));
    assert.equal(rows.some(row => row.code === 'CAUSE_ECONNREFUSED'), expected);
    assert.equal(JSON.stringify(rows).includes('PRIVATE_SECRET'), false);
  }
});
test('proof has an independent deadline distinct from caller cancellation', async () => {
  const rows = [], hold = setTimeout(() => {}, 200);
  try {
    await assert.rejects(proveLocal((_url, request) => new Promise((_resolve, reject) => request.signal.addEventListener('abort', () => reject(request.signal.reason), {once: true})),
      challenge, undefined, {...options(rows), timeoutMs: 10}));
    assert.ok(rows.some(row => row.code === 'PROOF_TIMEOUT'));
  } finally {clearTimeout(hold);}
  const cancelled = [], abort = new AbortController();
  await assert.rejects(proveLocal(async (_url, request) => {abort.abort(); request.signal.throwIfAborted();}, challenge, abort.signal, options(cancelled)));
  assert.ok(cancelled.some(row => row.code === 'PROOF_ABORTED'));
});
test('proof distinguishes JSON, nonce, format, and HMAC errors while ignoring compatible new fields', async () => {
  const cases = [
    ['JSON_INVALID', async () => new Response('not JSON')],
    ['NONCE_MISMATCH', async raw => Response.json({...valid(raw), nonce: 'wrong'})],
    ['PROOF_FORMAT_INVALID', async raw => Response.json({...valid(raw), bootstrap_proof: 'wrong'})],
    ['HMAC_MISMATCH', async raw => Response.json({...valid(raw), bootstrap_proof: '0'.repeat(64)})],
  ];
  for (const [code, fetch] of cases) {const rows = []; await assert.rejects(proveLocal(fetch, challenge, undefined, options(rows))); assert.ok(rows.some(row => row.code === code)); assert.equal(JSON.stringify(rows).includes(challenge), false);}
  const rows = [], data = await proveLocal(async raw => Response.json(valid(raw)), challenge, undefined, options(rows));
  assert.equal(data.asr, true); assert.equal(rows.at(-1).code, 'VERIFIED');
});
