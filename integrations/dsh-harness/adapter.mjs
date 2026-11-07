import {CallReasoningPolicy} from './call-policy.mjs';
export const PREFIX = '/api/mobile-call/v1';
export const EMOTIONS = Object.freeze(['neutral', 'happy', 'sad', 'angry', 'surprised', 'thinking', 'shy']);
export const GESTURES = Object.freeze(['nod', 'shake', 'bow', 'sway', 'raise-hand', 'lower-hand']);
const CONTROL = /\[(neutral|happy|sad|angry|surprised|thinking|shy|gesture:[^\]\r\n]{0,64})\]/gi;
const encoder = new TextEncoder();
const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$/;
const MAX_BODY_BYTES = 20000;
const MAX_TEXT_CHARS = 8000;

class InputError extends Error {
  constructor(code, status = 400) { super(code); this.code = code; this.status = status; }
}
const json = (value, status = 200) => new Response(JSON.stringify(value), {
  status, headers: {'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store'},
});
const failure = error => json({error: {code: error instanceof InputError ? error.code : 'internal_error',
  message: error instanceof InputError ? error.code : 'The operation could not be completed.'}}, error instanceof InputError ? error.status : 500);
const validId = value => typeof value === 'string' && ID.test(value);

async function body(request, fields) {
  if (!(request.headers.get('content-type') || '').toLowerCase().startsWith('application/json')) {
    throw new InputError('json_required', 415);
  }
  const raw = new Uint8Array(await request.arrayBuffer());
  if (raw.byteLength > MAX_BODY_BYTES) throw new InputError('body_too_large', 413);
  let value;
  try { value = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(raw)); }
  catch { throw new InputError('invalid_json'); }
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(key => !fields.includes(key))) {
    throw new InputError('invalid_fields');
  }
  return value;
}

export function visibleReply(message) {
  const blocks = message?.content;
  if (!Array.isArray(blocks) || blocks.some(block => block?.type === 'tool-call')) return '';
  return blocks.filter(block => block?.type === 'text' && typeof block.text === 'string').map(block => block.text).join('\n').trim();
}

export function presentation(text) {
  let emotion, gesture;
  text = text.trimStart();
  while (true) {
    const control = text.match(/^\[(neutral|happy|sad|angry|surprised|thinking|shy|gesture:[^\]\r\n]{0,64})\]\s*/i);
    if (!control) break;
    const label = control[1].toLowerCase();
    if (label.startsWith('gesture:')) {
      const motion = label.slice(8).replaceAll('_', '-');
      if (GESTURES.includes(motion)) gesture ??= motion;
    } else emotion ??= label;
    text = text.slice(control[0].length);
  }
  // Reserved controls may never reach TTS, even if the model puts them after speech starts.
  return {emotion: emotion ?? 'neutral', text: text.replace(CONTROL, '').trim(), ...(gesture ? {gesture} : {})};
}

/** Official turn journal reducer, not a model stream/reasoning parser. */
export class TurnCollector {
  constructor(requestId, cursor) {
    this.requestId = requestId; this.cursor = cursor; this.openTurn = null;
    this.turn = null; this.finalText = ''; this.lastSeq = cursor; this.pendingInteractions = new Set();
  }
  accept(frame, expectedTurn) {
    if (frame?.type !== 'event') return null;
    const event = frame.event;
    if (!event || !Number.isSafeInteger(event.seq) || event.seq <= this.lastSeq) return null;
    this.lastSeq = event.seq;
    const data = event.data || {};
    if (event.type === 'turn/start') { this.openTurn = data.turn; return {kind: 'turn_started'}; }
    if (event.type === 'user/message' && data.source?.kind === 'user' && data.source.rpcId === this.requestId) {
      if (!Number.isSafeInteger(this.openTurn)) throw new Error('missing_turn_start');
      this.turn = this.openTurn;
      return {kind: 'matched'};
    }
    // A request-config failure can close the exact pre-step turn before user/message is committed.
    // The Agent-scoped policy records this hint only after matching this request's queued RPC.
    if (this.turn === null && event.type === 'turn/end' && Number.isSafeInteger(expectedTurn) &&
        data.turn === expectedTurn && data.reason?.kind !== 'completed') {
      return {kind: 'ended', reason: data.reason?.kind || 'unknown', text: ''};
    }
    if (this.turn === null) return null;
    if (event.type === 'approval/asked' && typeof data.id === 'string') {
      this.pendingInteractions.add('approval:' + data.id); return {kind: 'interaction'};
    }
    if (event.type === 'approval/decided') {
      if (this.pendingInteractions.delete('approval:' + data.id) && this.pendingInteractions.size === 0) return {kind: 'resumed'};
    }
    if (event.type === 'tool/call' && data.turn === this.turn && data.name === 'ask_user_question') {
      this.pendingInteractions.add('question:' + data.callId); return {kind: 'interaction'};
    }
    if (event.type === 'tool/result' && data.turn === this.turn) {
      if (this.pendingInteractions.delete('question:' + data.message?.toolCallId) && this.pendingInteractions.size === 0) return {kind: 'resumed'};
    }
    if (event.type === 'assistant/message' && data.turn === this.turn) {
      this.finalText = data.interrupted ? '' : visibleReply(data.message);
    }
    if (event.type === 'turn/end' && data.turn === this.turn) {
      return {kind: 'ended', reason: data.reason?.kind || 'unknown', text: this.finalText};
    }
    return null;
  }
}

export class MobileCallAdapter {
  constructor(sessionController, {maxSessions = 32, maxRequests = 256, inspectModel, diagnostics = false} = {}) {
    this.controller = sessionController;
    this.sessions = new Map(); this.maxSessions = maxSessions; this.maxRequests = maxRequests;
    this.disposed = false; this.creating = 0;
    this.callPolicy = new CallReasoningPolicy(sessionController, {inspectModel, diagnostics,
      isOwned: session => this.sessions.get(session.id) === session});
  }
  routes() {
    return [
      ['capabilities', 'GET', () => json({protocol: 'live2d.call.v1', provider: 'deepseek_harness',
        runtime: 'dsh-v0.2.1-rc.4', supports_native_approvals: false, supports_resume: false,
        text_delivery: 'committed_final', max_text_chars: MAX_TEXT_CHARS, emotions: EMOTIONS})],
      ['session', 'POST', request => this.create(request)],
      ['prompt', 'POST', request => this.prompt(request)],
      ['cancel', 'POST', request => this.cancel(request)],
    ].map(([suffix, method, handler]) => ({path: `${PREFIX}/${suffix}`, methods: [method], requestBody: 'buffered',
      fetch: async request => {
        try {
          if (this.disposed) throw new InputError('adapter_unavailable', 503);
          return await handler(request);
        } catch (error) { return failure(error); }
      }}));
  }
  session(id) {
    if (!validId(id)) throw new InputError('invalid_session_id');
    const session = this.sessions.get(id);
    if (!session) throw new InputError('unknown_session', 404);
    return session;
  }
  async create(request) {
    await body(request, []);
    for (const [id, session] of this.sessions) {
      if (session.retired && !session.active) {this.callPolicy.unbind(id); this.sessions.delete(id);}
    }
    if (this.sessions.size + this.creating >= this.maxSessions) throw new InputError('session_limit', 429);
    this.creating++;
    try {
      const result = await this.controller.create({});
      if (!validId(result?.sessionId) || this.sessions.has(result.sessionId)) throw new Error('invalid_created_session');
      const session = {id: result.sessionId, active: null, retired: false, seen: new Set()};
      this.sessions.set(session.id, session);
      try { await this.callPolicy.bind(session); }
      catch (error) {this.sessions.delete(session.id); this.callPolicy.unbind(session.id); throw error;}
      return json({session_id: result.sessionId});
    } finally { this.creating--; }
  }
  async prompt(request) {
    const input = await body(request, ['session_id', 'request_id', 'text']);
    const session = this.session(input.session_id);
    if (session.retired) throw new InputError('session_closed_create_new', 409);
    if (session.active) throw new InputError('session_busy', 409);
    if (!validId(input.request_id)) throw new InputError('invalid_request_id');
    if (typeof input.text !== 'string' || !input.text.trim() || input.text.length > MAX_TEXT_CHARS) throw new InputError('invalid_text');
    if (session.seen.has(input.request_id)) throw new InputError('request_already_seen', 409);
    if (session.seen.size >= this.maxRequests) throw new InputError('request_limit_create_new', 429);
    if (request.signal.aborted) throw new InputError('request_aborted', 409);
    const active = {requestId: input.request_id, abort: new AbortController(), submitted: false,
      cancelRequested: false, detached: false, emit: null, done: null,
      terminalObserved: false, settled: false};
    session.active = active;
    session.seen.add(input.request_id);
    const detach = () => {
      active.detached = true;
      this.requestCancel(session).catch(() => {});
    };
    const stream = new ReadableStream({
      start: sink => {
        active.emit = (event, data) => {
          if (active.detached) return;
          try { sink.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)); }
          catch { detach(); }
        };
        // SSE comment only: no model call and no application message.
        const heartbeat = setInterval(() => {
          if (!active.detached) { try { sink.enqueue(encoder.encode(': keepalive\n\n')); } catch { detach(); } }
        }, 15000);
        heartbeat.unref?.();
        active.done = this.run(session, active, input.text).finally(() => {
          clearInterval(heartbeat);
          request.signal.removeEventListener('abort', detach);
          try { sink.close(); } catch {}
        });
        request.signal.addEventListener('abort', detach, {once: true});
        if (request.signal.aborted) detach();
      },
      cancel: detach,
    });
    return new Response(stream, {headers: {'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-store', 'x-accel-buffering': 'no'}});
  }
  async run(session, active, text) {
    let iterator;
    let disposeAgentError;
    let acknowledged = false;
    const emit = (event, data) => active.emit(event, data);
    try {
      await this.callPolicy.bind(session);
      iterator = this.controller.follow({address: {kind: 'session', sessionId: session.id}, maxMessages: 20}, active.abort.signal)[Symbol.asyncIterator]();
      const opening = await iterator.next();
      const snapshot = opening.value;
      if (opening.done || snapshot?.type !== 'snapshot' || snapshot.header?.id !== session.id || !Number.isSafeInteger(snapshot.cursor)) {
        throw new Error('invalid_follow_snapshot');
      }
      // The public controller follow is already attached before accepting a prompt.
      const collector = new TurnCollector(active.requestId, snapshot.cursor);
      const found = await this.controller.resolveAgent(session.id);
      const agent = found?.agent;
      if (!agent || agent.id !== session.id || typeof agent.ctx?.on !== 'function') throw new Error('call_agent_unavailable');
      let rejectAgent;
      const agentFailure = new Promise((_, reject) => { rejectAgent = reject; });
      // Keep the event rejection observed even if a synchronous prompt admission error wins first.
      void agentFailure.catch(() => {});
      disposeAgentError = agent.ctx.on('agent/error', payload => {
        if (payload?.agent !== agent || session.active !== active || !active.submitted ||
            collector.turn !== null || !Number.isSafeInteger(active.callPolicyTurn)) return;
        rejectAgent(new InputError('agent_request_failed'));
      });
      if (active.cancelRequested || active.abort.signal.aborted) throw new InputError('cancelled');
      active.submitted = true;
      await Promise.race([agentFailure, this.controller.prompt({sessionId: session.id, requestId: active.requestId, mode: 'queue',
        content: [{type: 'text', text}]}, active.abort.signal)]);
      acknowledged = true;
      emit('status', {status: 'running'});
      if (active.cancelRequested) await this.controller.cancel({sessionId: session.id});
      while (true) {
        const next = await Promise.race([agentFailure, iterator.next()]);
        if (next.done) throw new Error('follow_ended');
        const update = collector.accept(next.value, active.callPolicyTurn);
        if (!update) continue;
        if (update.kind === 'turn_started' && active.cancelRequested) {
          await this.controller.cancel({sessionId: session.id});
        } else if (update.kind === 'interaction') {
          emit('status', {status: 'needs_interaction', message: 'Open this session in DSHA to review the request.'});
        } else if (update.kind === 'resumed') {
          emit('status', {status: 'running'});
        } else if (update.kind === 'ended') {
          active.terminalObserved = true;
          if (active.cancelRequested || update.reason === 'aborted') throw new InputError('cancelled');
          if (update.reason !== 'completed') {
            throw new InputError(update.reason === 'blocked' ? 'interaction_required' : `turn_${update.reason}`);
          }
          const reply = presentation(update.text);
          if (!reply.text) throw new InputError('empty_final_response');
          emit('emotion', {emotion: reply.emotion, ...(reply.gesture ? {gesture: reply.gesture} : {})});
          emit('text', {text: reply.text, delta: false});
          emit('status', {status: 'completed'});
          break;
        }
      }
    } catch (error) {
      if (active.cancelRequested) error = new InputError('cancelled');
      if (!(error instanceof InputError) || !['cancelled', 'empty_final_response'].includes(error.code)) session.retired = true;
      emit('error', {code: error instanceof InputError ? error.code : (!active.submitted ? 'follow_not_ready' :
        acknowledged ? 'follow_failed_unknown_result' : 'submission_unknown_result'),
        message: error instanceof InputError ? error.code : (!active.submitted ? 'No prompt was submitted.' :
          'No automatic retry. Open the session in DSHA to check its result.')});
      if (active.submitted && !acknowledged) session.retired = true;
      if (active.submitted && session.retired) {
        try { await this.controller.cancel({sessionId: session.id}); } catch {}
      }
    } finally {
      try { disposeAgentError?.(); } catch {}
      this.callPolicy.finish(session, active);
      emit('done', {session_id: session.id, request_id: active.requestId});
      active.abort.abort();
      try { await iterator?.return?.(); } catch {}
      if (session.active === active) session.active = null;
      active.settled = true;
    }
  }
  async requestCancel(session) {
    session.retired = true;
    const active = session.active;
    if (!active) return;
    active.cancelRequested = true;
    if (active.submitted) await this.controller.cancel({sessionId: session.id});
    else active.abort.abort();
    // Do not release the lock here: cancellation is acknowledged before the
    // official turn/end, and an accepted queued message may not have entered yet.
  }
  async cancel(request) {
    const input = await body(request, ['session_id']);
    const session = this.session(input.session_id);
    await this.requestCancel(session);
    return json({accepted: true});
  }
  async dispose() {
    this.disposed = true;
    await Promise.allSettled([...this.sessions.values()].map(async session => {
      await this.requestCancel(session);
      session.active?.abort.abort();
    }));
    this.callPolicy.dispose();
  }
}
