import test from 'node:test';
import assert from 'node:assert/strict';
import {createHmac} from 'node:crypto';
import {challengeId, discoveryUrl, verifyDiscovery, proveLocal} from '../package/identity.mjs';
test('discovery never puts the bootstrap secret into the unauthenticated URL', () => {
  const challenge = 'a'.repeat(64), nonce = 'b'.repeat(32);
  assert.equal(discoveryUrl(challenge, nonce).includes(challenge), false);
  assert.ok(discoveryUrl(challenge, nonce).includes(challengeId(challenge)));
});
test('only the app receiving the authorized App Link can prove the fresh nonce', () => {
  const challenge = 'a'.repeat(64), nonce = 'b'.repeat(32);
  const data = {protocol: 'live2d.local.v1', app: 'com.prts.client', nonce,
    bootstrap_proof: createHmac('sha256', challenge).update(`live2d.local.v1|discover|${challengeId(challenge)}|${nonce}`).digest('hex')};
  assert.equal(verifyDiscovery(data, challenge, nonce), data);
  assert.throws(() => verifyDiscovery({...data, bootstrap_proof: '0'.repeat(64)}, challenge, nonce));
  assert.throws(() => verifyDiscovery(data, challenge, 'c'.repeat(32)));
});
test('unverified port cannot cause bootstrap disclosure', async () => {
  const challenge = 'd'.repeat(64), urls = [];
  await assert.rejects(proveLocal(async url => {urls.push(url); return Response.json({protocol: 'wrong'});}, challenge), /identity/);
  assert.equal(urls.length, 1); assert.equal(urls[0].includes(challenge), false);
});
