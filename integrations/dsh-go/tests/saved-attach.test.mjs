import test from 'node:test';
import assert from 'node:assert/strict';
import {createHmac} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';
import {challengeId, LOCAL_ORIGIN} from '../package/identity.mjs';
import {LocalManager} from '../package/manager.mjs';

const challenge = 'ab'.repeat(32);
const grant = {
  relay_url: LOCAL_ORIGIN,
  bootstrap_challenge: challenge,
  connector_token: 'cd'.repeat(32),
  binding_id: 'fixture-binding',
  expires_at_ms: Date.now() + 60_000,
};
const store = {async read() {return grant;}, async save() {}};
function discovery(url, valid = true) {
  const nonce = url.searchParams.get('nonce');
  assert.equal(url.searchParams.get('challenge_id'), challengeId(challenge));
  return Response.json({protocol: 'live2d.local.v1', app: 'com.prts.client', nonce,
    bootstrap_proof: valid ? createHmac('sha256', challenge)
      .update(`live2d.local.v1|discover|${challengeId(challenge)}|${nonce}`).digest('hex') : '00'.repeat(32)});
}

test('manual saved connection completes a real connector attach delayed beyond 2.5s', async () => {
  let attachCount = 0;
  const manager = new LocalManager({}, store, {
    async fetch(raw, options) {
      const url = new URL(raw);
      if (url.pathname.endsWith('/discover')) return discovery(url);
      if (url.pathname.endsWith('/pair/claim')) {
        assert.equal(JSON.parse(options.body).challenge, challenge);
        return Response.json(grant);
      }
      if (url.pathname.endsWith('/connector/status')) {
        assert.equal(options.headers.authorization, 'Bearer ' + grant.connector_token);
        if (url.searchParams.get('attach') === '1') {
          attachCount++;
          await delay(2650, undefined, {signal: options.signal});
        }
        return Response.json({connected: true, binding_id: grant.binding_id});
      }
      if (url.pathname.endsWith('/connector/poll')) {
        await delay(30_000, undefined, {signal: options.signal});
        return Response.json({frames: []});
      }
      assert.fail('Unexpected loopback route: ' + url.pathname);
    },
  });
  try {
    const state = await manager.connectLocal();
    assert.equal(state.status, 'paired');
    assert.equal(state.ready, true);
    assert.equal(attachCount, 1);
  } finally {
    await manager.dispose();
  }
});

test('manual saved connection rejects an invalid HMAC without retrying or claiming', async () => {
  let requests = 0;
  const manager = new LocalManager({}, store, {
    async fetch(raw) {
      requests++;
      const url = new URL(raw);
      assert.ok(url.pathname.endsWith('/discover'));
      return discovery(url, false);
    },
  });
  try {
    const state = await manager.connectLocal();
    assert.equal(state.status, 'identity_error');
    assert.equal(requests, 1);
  } finally {
    await manager.dispose();
  }
});
