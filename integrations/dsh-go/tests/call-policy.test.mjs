import test from 'node:test';
import assert from 'node:assert/strict';
import {CallReasoningPolicy} from '../package/call-policy.mjs';

test('rc.4 incrementing revisions retain same-attempt stream timings without collecting contents', async () => {
  const handlers = new Map();
  const agent = {id: 'test-session', ctx: {on(kind, callback) {
    handlers.set(kind, callback);
    return () => handlers.delete(kind);
  }}};
  const frames = [];
  const active = {requestId: 'test-request', emit: (kind, data) => frames.push({kind, data})};
  const session = {id: agent.id, active};
  const policy = new CallReasoningPolicy({resolveAgent: async () => ({agent})},
    {isOwned: candidate => candidate === session, diagnostics: true});
  const realNow = Date.now;
  let time = 1000;
  Date.now = () => time;
  try {
    await policy.bind(session);
    await handlers.get('agent/pre-step')({agent, turn: 7}, async () => ({
      kind: 'enter', messages: [{source: {kind: 'user', rpcId: active.requestId}}],
    }));
    await handlers.get('agent/request')({agent, turn: 7, step: 1}, async () => ({provider: 'mock', model: 'mock'}));
    const stream = frame => handlers.get('agent/assistant-stream')({agent, frame});
    const chunk = (attemptId, revision, index, type, text) =>
      stream({type: 'chunk', attemptId, revision, index, chunk: {type, index: 0, text}});
    stream({type: 'start', attemptId: 'foreign', turn: 9, revision: 5});
    chunk('foreign', 6, 0, 'reasoning-delta', 'FOREIGN');
    stream({type: 'start', attemptId: 'test-session:1', turn: 7, revision: 7});
    time += 18;
    chunk('test-session:1', 8, 0, 'reasoning-delta', 'PRIVATE');
    chunk('test-session:1', 8, 1, 'reasoning-delta', 'DUP_REVISION');
    chunk('test-session:1', 9, 0, 'reasoning-delta', 'DUP_INDEX');
    time += 23;
    chunk('test-session:1', 10, 1, 'text-delta', 'VISIBLE');
    stream({type: 'end', attemptId: 'test-session:1', revision: 11, index: 2,
      outcome: {kind: 'committed'}});
    chunk('test-session:1', 12, 2, 'reasoning-delta', 'POST_END');
    stream({type: 'start', attemptId: 'test-session:2', turn: 7, revision: 13});
    chunk('test-session:2', 14, 0, 'text-delta', 'NEXT_STEP');
    stream({type: 'end', attemptId: 'test-session:2', revision: 15, index: 1,
      outcome: {kind: 'abandoned'}});
    time += 9;
    policy.finish(session, active);
    assert.deepEqual(frames.at(-1), {kind: 'call_metrics', data: {
      thinking_chars: 7, first_thinking_ms: 18, first_text_ms: 41,
      elapsed_ms: 50, attempts: 2, source: 'agent_assistant_stream',
    }});
    assert.equal(active.callPolicyMetrics.streams.size, 0);
    assert.doesNotMatch(JSON.stringify(frames), /PRIVATE|VISIBLE|NEXT_STEP|FOREIGN|DUP_|POST_END/);
  } finally {
    Date.now = realNow;
    policy.dispose();
  }
});
