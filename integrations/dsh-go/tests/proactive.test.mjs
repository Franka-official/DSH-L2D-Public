import assert from 'node:assert/strict';
import test from 'node:test';
import {LocalConnector} from '../package/carrier.mjs';
import {defaultTextModel, LocalProactiveText, PROACTIVE_PROTOCOL} from '../package/proactive.mjs';

const turn = {type: 'proactive-request', request_id: 'req-1', session_id: 'session-1',
  text: '现在暂时安静。对方可能在笑。', reply_language: 'zh'};
const stop = {type: 'finish', reason: {kind: 'stop'}};
const pending = () => Promise.withResolvers();

function fixture(prepareCall, wait = 30) {
  const frames = [], socket = {};
  const channel = new LocalProactiveText(() => ({provider: 'test', model: 'text', llm: {prepareCall}}),
    frame => frames.push(frame), {seen: new Set(), cancelWaitMs: wait});
  return {channel, frames, socket};
}

test('capability requires a model route, removes tools and rejects camera bytes', async () => {
  assert.equal(defaultTextModel({get: () => undefined}), undefined);
  const {channel, frames, socket} = fixture(async (selection, preparedSignal) => {
    assert.equal(selection.model, 'text');
    return {config: {tools: ['danger'], toolChoice: 'auto', tool_choice: 'auto'}, async *stream(options) {
      assert.equal(options.signal, preparedSignal);
      assert.equal(options.messages[0].content[0].text, turn.text);
      for (const key of ['tools', 'toolChoice', 'tool_choice']) assert.equal(Object.hasOwn(options, key), false);
      yield {type: 'text-delta', text: '要不要聊聊刚才那个话题？'};
      yield stop;
    }};
  });
  channel.announce(socket);
  assert.deepEqual(frames[0], {type: 'proactive-capability', protocol: PROACTIVE_PROTOCOL, available: true});
  assert.throws(() => channel.accept({...turn, image: 'base64'}, socket), /invalid_proactive_request/);
  channel.accept(turn, socket);
  await channel.active.task;
  assert.equal(frames[1].event.type, 'text');
  assert.deepEqual(frames[2], {type: 'done', request_id: 'req-1', success: true});
  channel.accept(turn, socket);
  assert.equal(frames.length, 3);
});

test('tool output is rejected instead of being executed or spoken', async () => {
  const {channel, frames, socket} = fixture(async () => ({config: {}, async *stream() {
    yield {type: 'text-delta', text: 'Partial'};
    yield {type: 'tool-call', toolName: 'danger'};
    yield stop;
  }}));
  channel.accept(turn, socket);
  await channel.active.task;
  assert.deepEqual(frames, [{type: 'done', request_id: 'req-1', success: false}]);
});

test('cancellation reaches provider; confirm only on observed settlement', async () => {
  const release = pending(), aborted = pending(), started = pending();
  const {channel, frames, socket} = fixture(async (_options, signal) => {
    signal.addEventListener('abort', () => aborted.resolve());
    return {config: {}, async *stream() {
      started.resolve();
      await release.promise;
      yield {type: 'text-delta', text: 'Late text'};
      yield stop;
    }};
  }, 2000);
  channel.accept(turn, socket);
  await started.promise;
  const cancellation = channel.cancel(turn.request_id, socket);
  await aborted.promise;
  assert.equal(frames.length, 0);
  release.resolve();
  await cancellation;
  assert.deepEqual(frames, [{type: 'cancelled', request_id: 'req-1', confirmed: true}]);
});

test('provider that has not stopped yields an unconfirmed cancel and never speaks late text', async () => {
  const release = pending(), started = pending();
  const {channel, frames, socket} = fixture(async () => ({config: {}, async *stream() {
    started.resolve();
    await release.promise;
    yield stop;
  }}), 1);
  channel.accept(turn, socket);
  await started.promise;
  const task = channel.active.task;
  await channel.cancel(turn.request_id, socket);
  assert.deepEqual(frames, [{type: 'cancelled', request_id: 'req-1', confirmed: false}]);
  release.resolve();
  await task;
  assert.equal(frames.length, 1);
});

test('legacy connector pairing never advertises or routes a proactive turn', async () => {
  const sent = [], socket = {};
  const connector = new LocalConnector({}, {save: async () => {}}, {}, {getProactiveRoute: () => {
    throw Error('legacy must not use proactive');
  }});
  connector.socket = socket;
  connector.send = frame => sent.push(frame);
  await connector.accept(JSON.stringify({type: 'paired', binding_id: 'legacy', connector_token: 'fake-scope-only-test-token'}), socket);
  assert.deepEqual(sent, []);
  await assert.rejects(connector.accept(JSON.stringify(turn), socket), /proactive_protocol_required/);
  assert.equal(connector.jobs.size, 0);
});

test('negotiated proactive uses only the direct model; human ASR interrupts it', async () => {
  const sent = [], socket = {}, release = pending(), started = pending();
  let providerSignal, asr = false;
  const connector = new LocalConnector({prompt() {throw Error('never submit an agent prompt');}},
    {save: async () => {}}, {}, {getProactiveRoute: () => ({provider: 'test', model: 'text', llm: {
      async prepareCall(_, signal) {
        providerSignal = signal;
        return {config: {}, async *stream() {
          started.resolve();
          await release.promise;
          yield {type: 'text-delta', text: 'Late text'};
          yield stop;
        }};
      },
    }})});
  connector.socket = socket;
  connector.send = frame => sent.push(frame);
  await connector.accept(JSON.stringify({type: 'paired', binding_id: 'current',
    connector_token: 'fake-scope-only-test-token', proactive_protocol: PROACTIVE_PROTOCOL}), socket);
  assert.deepEqual(sent.map(x => x.type), ['proactive-capability']);
  connector.refreshProactive();
  assert.equal(sent.length, 1);
  await connector.accept(JSON.stringify(turn), socket);
  const task = connector.proactive.active.task;
  await started.promise;
  assert.equal(connector.jobs.size, 0);
  connector.asrProtocol = true;
  connector.speech.accept = () => {asr = true;};
  await connector.accept(JSON.stringify({type: 'transcribe'}), socket);
  assert.equal(asr, true);
  assert.equal(providerSignal.aborted, true);
  release.resolve();
  await task;
  assert.equal(sent.length, 1);
});
