import { EMOTIONS, GESTURES, MobileCallAdapter, PREFIX } from './adapter.mjs';

const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$/;
const validId = value => typeof value === 'string' && ID.test(value);
const validToken = value => typeof value === 'string' && value.length >= 16 && value.length <= 4096 && !/\s/.test(value);
export const RELAY_CAPABILITIES = Object.freeze({protocol: 'live2d.call.v1', provider: 'deepseek_harness',
  text_delivery: 'committed_final', supports_images: false, supports_native_approvals: false,
  supports_reply_language: true, cancellation_confirmation: 'observed_turn_end'});

export function relayAddress(value) {
  let url;
  try { url = new URL(value); } catch { throw new Error('invalid_relay_url'); }
  if (!['https:', 'wss:'].includes(url.protocol) || url.username || url.password || url.search || url.hash ||
      !['', '/', '/mobile-call/v1', '/mobile-call/v1/'].includes(url.pathname)) throw new Error('invalid_relay_url');
  return {origin: `https://${url.host}`, socket: `wss://${url.host}/mobile-call/v1/connector`};
}
const SHORT_SPEECH = '通话时用自然短句，每句只说一件事。普通闲聊先说重点，通常一到三句。把过长的句子用句号或问号拆开，少用一串逗号。用户要求朗诵、引用或按原文读时，保留原文。';

const SPOKEN_AFFECT = "用措辞和句子的节奏表达感情。高兴时轻快一点，安慰时放柔，意外时先短短回应。用自然停顿、问句或适量感叹号体现语气，不加“轻声地”“笑着说”等旁白。";

export function replyPrompt(text, language) {
  if (!['zh', 'ja', 'en'].includes(language)) throw new Error('invalid_reply_language');
  const shortSpeech = (text.includes(SHORT_SPEECH) ? '' : '\n\n' + SHORT_SPEECH) +
    (text.includes(SPOKEN_AFFECT) ? '' : '\n' + SPOKEN_AFFECT);
  if (['ja', 'en'].includes(language) && text.startsWith(`[手机双语字幕:v1;target=${language}]\n`)) {
    return text + shortSpeech;
  }
  const note = language === 'ja' ? '[本轮回复语言：ja] 本轮直接用日语回答。' : language === 'en' ? '[本轮回复语言：en] 本轮直接用英语回答。' : '[本轮回复语言：zh] 本轮直接用中文回答。';
  const alreadySpecified = text === note || text.startsWith(note + '\n');
  return `[手机通话：已经在与用户通话，直接回复1至3句可朗读正文，不附路由标识或思考/工具过程；${
    alreadySpecified ? '' : language === 'ja' ? '请用日语回复。' : language === 'en' ? '请用英语回复。' : '请用中文回复。'}]${shortSpeech}\n${text}`;
}
const post = (suffix, data) => new Request('http://adapter.invalid' + PREFIX + '/' + suffix,
  {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify(data)});

/** Outbound carrier only. All host operations use a separate in-memory adapter,
 * never an unauthenticated listener or another user's pre-existing session. */
export class MobileRelayConnector {
  constructor(controller, store, config, options = {}) {
    this.address = relayAddress(config.relay_url);
    this.code = (config.verification_code || '').trim().toUpperCase();
    if (this.code && !/^[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(this.code)) throw new Error('invalid_verification_code');
    this.callDiagnostics = options.callDiagnostics === true;
    this.store = store; this.adapter = new MobileCallAdapter(controller, {inspectModel: options.inspectModel, diagnostics: this.callDiagnostics});
    this.WebSocket = options.WebSocket || globalThis.WebSocket;
    if (typeof this.WebSocket !== 'function') throw new Error('websocket_unavailable');
    this.onStatus = options.onStatus || (() => {});
    this.cancelWaitMs = options.cancelWaitMs ?? 10000;
    this.retryDelays = options.retryDelays || [1000, 2000, 4000];
    this.socket = null; this.closed = false; this.paired = false; this.retry = 0; this.retryTimer = null;
    this.jobs = new Map(); this.sessions = new Map(); this.seen = new Set(); this.binding = null;
    this.messageChain = Promise.resolve();
  }
  status(value) { try { this.onStatus(value); } catch {} } // Enumerated status only; no secrets/output.
  async start() {
    if (this.closed || this.socket) return;
    const stored = await this.store.read();
    this.token = stored?.relay_url === this.address.origin && validToken(stored.connector_token) ? stored.connector_token : null;
    this.binding = this.token && validId(stored.binding_id) ? stored.binding_id : null;
    if (!this.token && !this.code) { this.status('pairing_required'); return; }
    this.connect();
  }
  connect() {
    if (this.closed || this.socket) return;
    this.paired = false;
    const socket = new this.WebSocket(this.address.socket);
    this.socket = socket;
    socket.addEventListener('open', () => {
      if (this.socket !== socket || this.closed) return;
      socket.send(JSON.stringify({type: 'hello', mode: 'deepseek_harness',
        ...(this.token ? {connector_token: this.token} : {verification_code: this.code})}));
    });
    socket.addEventListener('message', event => {
      if (this.socket !== socket || this.closed) return;
      this.messageChain = this.messageChain.then(() => this.accept(event.data, socket)).catch(() => {
        this.status('protocol_error'); socket.close(1008, 'protocol_error');
      });
    });
    socket.addEventListener('error', () => this.status('connection_error'));
    socket.addEventListener('close', () => {
      if (this.socket !== socket) return;
      this.socket = null; this.paired = false;
      for (const job of this.jobs.values()) this.cancelJob(job, false).catch(() => {});
      if (!this.closed && this.retry < this.retryDelays.length) {
        const delay = this.retryDelays[this.retry++];
        this.retryTimer = setTimeout(() => { this.retryTimer = null; this.connect(); }, delay);
        this.retryTimer.unref?.();
      } else if (!this.closed) this.status('connection_stopped');
    });
  }
  send(value) {
    if (this.closed || !this.socket || this.socket.readyState !== 1) return false;
    try { this.socket.send(JSON.stringify(value)); return true; } catch { return false; }
  }
  async accept(raw, socket = this.socket) {
    if (this.closed || socket !== this.socket) return;
    if (typeof raw !== 'string' || raw.length > 40000) throw new Error('invalid_frame');
    let frame;
    try { frame = JSON.parse(raw); } catch { throw new Error('invalid_frame'); }
    if (!frame || typeof frame !== 'object' || Array.isArray(frame)) throw new Error('invalid_frame');
    if (frame.type === 'paired') {
      if (!validId(frame.binding_id) || !validToken(frame.connector_token)) throw new Error('invalid_pairing');
      if (this.binding && this.binding !== frame.binding_id) throw new Error('binding_changed_repair_required');
      await this.store.save({relay_url: this.address.origin, binding_id: frame.binding_id, connector_token: frame.connector_token});
      if (this.closed || socket !== this.socket) return;
      this.binding = frame.binding_id; this.token = frame.connector_token; this.code = ''; this.paired = true;
      this.status('paired'); return;
    }
    if (frame.type === 'error') { this.status('relay_error'); return; }
    if (!this.paired) throw new Error('pairing_required');
    if (frame.type === 'request') {
      if (!validId(frame.request_id) || !validId(frame.session_id) || typeof frame.text !== 'string' ||
          !frame.text.trim() || frame.text.length > 8000 || !['zh', 'ja', 'en'].includes(frame.reply_language)) throw new Error('invalid_request');
      // Replayed/duplicate IDs never terminate or resubmit the original job.
      if (this.seen.has(frame.request_id)) return;
      if (this.seen.size >= 4096 || this.jobs.size >= 32) {
        this.send({type: 'done', request_id: frame.request_id, success: false}); return;
      }
      this.seen.add(frame.request_id);
      const job = {id: frame.request_id, phoneSession: frame.session_id, cancelled: false,
        active: null, session: null, task: null, success: false};
      this.jobs.set(job.id, job);
      // Do not serialize the model lifetime behind the WebSocket control queue.
      job.task = this.run(job, frame).finally(() => { if (this.jobs.get(job.id) === job) this.jobs.delete(job.id); });
      return;
    }
    if (frame.type === 'cancel' && validId(frame.request_id)) {
      const job = this.jobs.get(frame.request_id);
      if (job) void this.cancelJob(job, true);
      else this.send({type: 'cancelled', request_id: frame.request_id, confirmed: false});
      return;
    }
    throw new Error('unsupported_frame');
  }
  ownedSession(phoneSession) {
    let slot = this.sessions.get(phoneSession);
    if (slot?.retired && !slot.busy) { this.sessions.delete(phoneSession); slot = null; }
    if (slot) return slot;
    // Reserve before create awaits; simultaneous requests share one owned slot.
    slot = {id: null, busy: false, retired: false, promise: null};
    this.sessions.set(phoneSession, slot);
    slot.promise = (async () => {
      const response = await this.adapter.create(post('session', {}));
      if (!response.ok) throw new Error('session_create_failed');
      const created = await response.json();
      slot.id = created.session_id; return slot;
    })().catch(error => { if (this.sessions.get(phoneSession) === slot) this.sessions.delete(phoneSession); throw error; });
    return slot;
  }
  async run(job, input) {
    let slot;
    try {
      slot = this.ownedSession(job.phoneSession);
      if (slot.busy || slot.retired || job.cancelled || this.closed) throw new Error('session_unavailable');
      slot.busy = true; job.session = slot;
      await slot.promise;
      if (slot.retired || job.cancelled || this.closed) throw new Error('session_unavailable');
      const response = await this.adapter.prompt(post('prompt', {session_id: slot.id, request_id: job.id,
        text: replyPrompt(input.text, input.reply_language)}));
      if (!response.ok) throw new Error('prompt_not_accepted');
      job.active = this.adapter.session(slot.id).active;
      let buffer = ''; let presentation = ''; let textSeen = false; let completed = false; let done = false; let errorSeen = false;
      const reader = response.body.getReader(); const decoder = new TextDecoder();
      while (true) {
        const next = await reader.read();
        buffer += decoder.decode(next.value || new Uint8Array(), {stream: !next.done});
        if (buffer.length > 100000) throw new Error('response_too_large');
        let end;
        while ((end = buffer.indexOf('\n\n')) >= 0) {
          const packet = buffer.slice(0, end); buffer = buffer.slice(end + 2);
          if (packet.startsWith(':')) continue;
          const kind = /^event: (\w+)$/m.exec(packet)?.[1];
          const dataLine = /^data: (.*)$/m.exec(packet)?.[1];
          if (!kind || !dataLine) throw new Error('invalid_adapter_event');
          const data = JSON.parse(dataLine);
          if (this.callDiagnostics && ['call_options', 'call_metrics'].includes(kind) && !job.cancelled) {
            this.send({type: 'event', request_id: job.id, event: {type: kind, ...data}});
          }
          if (kind === 'error') {
            errorSeen = true;
            // Current local clients accept status + done, not a raw adapter error frame.
            // Report failure without forwarding provider errors, configuration, or stacks.
            if (!job.cancelled) this.send({type: 'event', request_id: job.id, event: {type: 'status', status: 'error'}});
          }
          if (kind === 'status') {
            completed ||= data.status === 'completed';
            if (!job.cancelled) this.send({type: 'event', request_id: job.id, event: {type: 'status', status: data.status}});
          }
          if (kind === 'emotion' && !job.cancelled) {
            // The relay carries presentation controls on committed text only.
            presentation = EMOTIONS.includes(data.emotion) && data.emotion !== 'neutral' ? `[${data.emotion}]` : '';
            if (GESTURES.includes(data.gesture)) presentation += `[gesture:${data.gesture}]`;
          }
          if (kind === 'text' && !job.cancelled) {
            textSeen = true;
            this.send({type: 'event', request_id: job.id, event: {type: 'text', text: presentation + data.text}});
            presentation = '';
          }
          if (kind === 'done') done = true;
        }
        if (next.done) break;
      }
      job.success = done && completed && textSeen && !errorSeen && !job.cancelled;
    } catch {
      if (slot && slot.id && job.session === slot) {
        slot.retired = true;
        try { await this.adapter.requestCancel(this.adapter.session(slot.id)); } catch {}
      }
    } finally {
      if (slot && job.session === slot) slot.busy = false;
      this.send({type: 'done', request_id: job.id, success: job.success});
    }
  }
  async cancelJob(job, acknowledge) {
    job.cancelled = true;
    if (!job.session || !job.session.id) {
      if (job.session) job.session.retired = true;
      if (acknowledge) this.send({type: 'cancelled', request_id: job.id, confirmed: true});
      return; // run() checks cancelled after create, before prompt.
    }
    job.session.retired = true;
    const active = job.active || this.adapter.session(job.session.id).active;
    try { await this.adapter.requestCancel(this.adapter.session(job.session.id)); } catch {}
    let timer;
    if (active?.done) await Promise.race([active.done.catch(() => {}), new Promise(resolve => {
      timer = setTimeout(resolve, this.cancelWaitMs);
    })]);
    clearTimeout(timer);
    const confirmed = Boolean(active?.settled && (!active.submitted || active.terminalObserved));
    if (acknowledge) this.send({type: 'cancelled', request_id: job.id, confirmed});
  }
  async dispose() {
    this.closed = true; clearTimeout(this.retryTimer); this.retryTimer = null;
    const socket = this.socket; this.socket = null; this.paired = false;
    try { socket?.close(1000, 'plugin_disposed'); } catch {}
    for (const job of this.jobs.values()) { job.cancelled = true; if (job.session) job.session.retired = true; }
    await this.adapter.dispose();
  }
}
