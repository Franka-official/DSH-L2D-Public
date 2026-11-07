import test from 'node:test';
import assert from 'node:assert/strict';
import {apply} from '../package/plugin.js';

test('resume acknowledges while the bridge checks its credentials, and status stays readable', async () => {
  const routes = new Map();
  let releaseRead;
  const pendingRead = new Promise(resolve => {releaseRead = resolve;});
  let reads = 0;
  let attempts = 0;
  const host = {
    sessionController: {},
    credentials: {readRecord() {return ++reads === 1 ? undefined : pendingRead;}},
    connection: {fetch: {register(route) {routes.set(route.path, route);}}},
    inject() {},
    on() {},
    effect(register) {register();},
  };
  apply({inject(_services, install) {install(host);}}, {}, {
    async fetch(url) {
      assert.equal(new URL(url).pathname, '/local-dsh/v1/pair/begin');
      attempts++;
      return new Response(null, {status: 403});
    },
  });
  const request = action => routes.get('/api/dsh-go-live2d/v1/' + action).fetch();
  const nextTurn = () => new Promise(resolve => setImmediate(resolve));
  try {
    const response = await Promise.race([request('resume'), nextTurn()]);
    assert.ok(response instanceof Response);
    assert.equal((await response.json()).status, 'connecting');
    assert.equal((await (await request('status')).json()).status, 'connecting');
    assert.equal((await (await request('resume')).json()).status, 'connecting');
    assert.equal(reads, 2);
    assert.equal(attempts, 0);
  } finally {
    releaseRead(undefined);
  }
  await nextTurn();
  assert.equal(attempts, 1);
  assert.equal((await (await request('status')).json()).status, 'launch_required');
});
