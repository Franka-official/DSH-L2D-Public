import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {LocalManager} from '../package/manager.mjs';
import {registerNativePanel} from '../package/native-panel.mjs';

test('manifest exposes the bridge only in Go native Features', () => {
  const manifest = JSON.parse(readFileSync(new URL('../package/package.json', import.meta.url)));
  assert.deepEqual(manifest.dshGo.contributes.panels.map(({id, placement}) => ({id, placement})), [
    {id: 'l2d-bridge', placement: ['features']}
  ]);
  assert.equal(manifest.dsh.client, undefined);
  assert.equal(manifest.exports['./client'], undefined);
});

test('native panel follows the real connector and returns promptly from one-click actions', async () => {
  const manager = new LocalManager({}, {});
  let panel;
  registerNativePanel({panel: (id, spec) => {assert.equal(id, 'l2d-bridge'); panel = spec;}}, manager);
  assert.equal(panel.view.v, 2);
  assert.equal(panel.view.children[0].shape, 'ring');
  const states = [];
  const unsubscribe = panel.data({}, data => states.push(data));
  const state = () => states.at(-1);
  assert.deepEqual([state().label, state().fill, state().tone, state().actions[0].action], ['未连接', 0, 'info', 'connect']);
  let clicks = 0;
  let finish;
  const waiting = new Promise(resolve => {finish = resolve;});
  manager.connectLocal = async () => {clicks++; manager.setStatus('connecting'); await waiting;};
  assert.equal(panel.actions.connect(), undefined);
  assert.equal(clicks, 1);
  assert.deepEqual([state().label, state().fill, state().actions[0].label], ['连接中', 0, '取消']);
  manager.pairingFingerprint = 'ABCD1234';
  manager.setStatus('authorizing');
  assert.match(state().authorization.text, /ABCD1234/);
  finish();
  manager.setStatus('paired');
  assert.deepEqual([state().label, state().fill, state().tone, state().actions[0].action], ['已连接', 1, 'good', 'disconnect']);
  assert.equal(state().authorization, null);
  assert.equal(panel.actions.disconnect(), undefined);
  assert.equal(state().label, '未连接');
  manager.setStatus('reconnect_failed');
  assert.deepEqual([state().fill, state().tone], [1, 'bad']);
  unsubscribe();
  const count = states.length;
  manager.setStatus('paired');
  assert.equal(states.length, count);
  await manager.dispose();
});
