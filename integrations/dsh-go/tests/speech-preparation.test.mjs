import assert from 'node:assert/strict';
import {EventEmitter, on} from 'node:events';
import test from 'node:test';
import {LocalSpeechChannel} from '../package/speech.mjs';

const flush = () => new Promise(resolve => setImmediate(resolve));
const state = preparation => ({providers: [{id: 'sensevoice-local', location: 'host-local', preparation}],
  selection: {providerId: 'sensevoice-local'}});

test('only an explicit paired action restarts the selected local provider; progress and readiness follow the host', async () => {
  const changes = new EventEmitter();
  let snapshot = state({phase: 'failed', message: '/private/host/path'});
  let requests = 0;
  const controller = {
    catalog: () => snapshot,
    transcribe: () => {},
    async *follow(signal) {for await (const [value] of on(changes, 'state', {signal})) yield value;},
    prepare(id) {
      assert.equal(id, 'sensevoice-local');
      requests++;
      snapshot = state({phase: 'downloading', completedBytes: 123, totalBytes: 1024});
      changes.emit('state', snapshot);
    },
  };
  const frames = [], channel = new LocalSpeechChannel(() => controller, frame => frames.push(frame));
  const socket = {};
  await channel.announce(socket, true);
  assert.deepEqual(frames[0].asr_preparation, {phase: 'failed', can_prepare: true});
  await channel.prepareSelected({});
  assert.equal(requests, 0);
  await channel.prepareSelected(socket);
  await flush();
  assert.equal(requests, 1);
  assert.ok(frames.some(row => row.asr_preparation?.completed_bytes === 123 && row.asr_preparation?.total_bytes === 1024));
  await channel.prepareSelected(socket);
  assert.equal(requests, 1);
  changes.emit('state', state({phase: 'ready'}));
  await flush();
  assert.deepEqual(frames.at(-1), {type: 'capabilities', asr: true, asr_preparation: {phase: 'ready', can_prepare: false}});
  await channel.dispose();
});

test('older phones and nonlocal provider selections cannot trigger preparation', async () => {
  let snapshot = state({phase: 'unprepared'});
  const controller = {catalog: () => snapshot, transcribe: () => {}, async *follow() {},
    prepare() {assert.fail('unexpected preparation');}};
  const frames = [], channel = new LocalSpeechChannel(() => controller, frame => frames.push(frame));
  const socket = {};
  await channel.announce(socket);
  assert.equal(frames[0].asr_preparation, undefined);
  await channel.prepareSelected(socket);
  snapshot.providers[0].location = 'cloud';
  await channel.announce(socket, true);
  assert.deepEqual(frames.at(-1).asr_preparation, {phase: 'unavailable', can_prepare: false});
  await channel.prepareSelected(socket);
  assert.deepEqual(frames.at(-1), {type: 'asr-preparation-result', accepted: false});
  await channel.dispose();
});
