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
  assert.deepEqual(panel.view.children, {$: 'data.blocks'});
  const states = [];
  const unsubscribe = panel.data({}, data => states.push(data));
  const blocks = () => states.at(-1).blocks;
  const ring = () => blocks()[0];
  const action = () => blocks().at(-1).items[0];
  const types = () => blocks().map(block => block.type);
  assert.deepEqual(types(), ['progress', 'actions']);
  assert.deepEqual([ring().shape, ring().label, ring().value, ring().tone, action().action], ['ring', '未连接', 0, 'info', 'connect']);
  let clicks = 0;
  let finish;
  const waiting = new Promise(resolve => {finish = resolve;});
  manager.connectLocal = async () => {clicks++; manager.setStatus('connecting'); await waiting;};
  assert.equal(panel.actions.connect(), undefined);
  assert.equal(clicks, 1);
  assert.deepEqual([ring().label, ring().value, action().label], ['连接中', 0, '取消']);
  manager.pairingFingerprint = 'ABCD1234';
  manager.setStatus('authorizing');
  assert.deepEqual(types(), ['progress', 'callout', 'actions']);
  assert.match(blocks()[1].text, /ABCD1234/);
  finish();
  manager.setStatus('paired');
  assert.deepEqual([ring().label, ring().value, ring().tone, action().action], ['已连接', 1, 'good', 'disconnect']);
  assert.deepEqual(types(), ['progress', 'actions']);
  assert.equal(panel.actions.disconnect(), undefined);
  assert.equal(ring().label, '未连接');
  manager.setStatus('reconnect_failed');
  assert.deepEqual([ring().value, ring().tone], [1, 'bad']);
  unsubscribe();
  const count = states.length;
  manager.setStatus('paired');
  assert.equal(states.length, count);
  await manager.dispose();
});
