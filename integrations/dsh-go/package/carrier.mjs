import {MobileRelayConnector} from './relay.mjs';
import {LOCAL_ORIGIN, LOCAL_PREFIX, proveLocal} from './identity.mjs';
import {ASR_PREPARATION_PROTOCOL, LocalSpeechChannel, MAX_ASR_FRAME_CHARS} from './speech.mjs';
import {localHttpFetch} from './local-http.mjs';
import {LocalProactiveText, PROACTIVE_PROTOCOL} from './proactive.mjs';
import packageMetadata from './package.json' with {type: 'json'};

const HEARTBEAT_INTERVAL_MS = 1000;
const HEARTBEAT_TIMEOUT_MS = 850;
const ATTACH_ROUTE = '/connector/status?attach=1';
const HEARTBEAT_ROUTE = '/connector/status?heartbeat=1';

/** WebSocket-shaped carrier; existing session ownership/body/cancel logic remains unchanged. */
export function localCarrier(grant, fetch, diagnostic = () => {}, onHeartbeat = () => {}) {
  return class HttpCarrier {
    constructor() {
      this.readyState = 0; this.listeners = {}; this.abort = new AbortController(); this.queue = Promise.resolve();
      this.heartbeatTimer = null; this.checkingHeartbeat = false;
      queueMicrotask(() => { if (this.readyState !== 0) return; this.readyState = 1; this.emit('open'); });
    }
    addEventListener(name, listener) { (this.listeners[name] ||= []).push(listener); }
    emit(name, event = {}) { for (const listener of this.listeners[name] || []) listener(event); }
    async request(path, options = {}, timeout = 35000) {
      const stage = path === HEARTBEAT_ROUTE ? 'heartbeat' :
        path === ATTACH_ROUTE ? 'carrier_status' : path === '/connector/poll' ? 'carrier_poll' : 'carrier_event';
      const signal = AbortSignal.any([this.abort.signal, AbortSignal.timeout(timeout)]);
      const response = await fetch(LOCAL_ORIGIN + LOCAL_PREFIX + path, {...options, signal, redirect: 'error',
        headers: {...options.headers, authorization: 'Bearer ' + grant.connector_token}});
      if (!response.ok) {diagnostic(stage, 'HTTP_REJECTED', response.status); throw Error('local_carrier_rejected');}
      if (path !== '/connector/poll' && path !== HEARTBEAT_ROUTE) diagnostic(stage, 'HTTP_OK', response.status);
      return response.json();
    }
    send(raw) {
      if (this.readyState !== 1) throw Error('local_carrier_closed');
      let frame; try { frame = JSON.parse(raw); } catch { throw Error('invalid_local_frame'); }
      if (frame.type === 'hello') {
        if (this.hello || frame.connector_token !== grant.connector_token) throw Error('invalid_local_hello');
        this.hello = true;
        this.queue = this.queue.then(async () => {
          diagnostic('carrier_proof', 'BEGIN');
          const discovery = await proveLocal(fetch, grant.bootstrap_challenge, this.abort.signal,
            {diagnostic: (code, status) => diagnostic('carrier_proof', code, status)});
          diagnostic('carrier_proof', 'OK');
          this.callDiagnostics = discovery.call_diagnostics === 'live2d.call-options.v1';
          this.presentation = discovery.presentation === 'live2d.presentation.v1';
          this.earlySpeech = this.presentation && discovery.early_speech === 'live2d.early-speech.v1';
          const status = await this.request(ATTACH_ROUTE, {}, 8000);
          if (status.connected !== true || status.binding_id !== grant.binding_id) {diagnostic('carrier_status', 'BINDING_MISMATCH'); throw Error('local_binding_mismatch');}
          if (this.readyState !== 1) return;
          this.emit('message', {data: JSON.stringify({type: 'paired', binding_id: grant.binding_id, connector_token: grant.connector_token,
            ...(discovery.asr_protocol === 'live2d.asr.v1' ? {asr_protocol: discovery.asr_protocol} : {}),
            ...(discovery.asr_preparation_protocol === ASR_PREPARATION_PROTOCOL ? {asr_preparation_protocol: ASR_PREPARATION_PROTOCOL} : {}),
            ...(discovery.proactive_protocol === PROACTIVE_PROTOCOL ? {proactive_protocol: PROACTIVE_PROTOCOL} : {})})});
          diagnostic('carrier', 'PAIRED_DISPATCHED');
          if (this.readyState !== 1) return;
          this.heartbeatTimer = setInterval(() => void this.heartbeat(), HEARTBEAT_INTERVAL_MS);
          this.heartbeatTimer.unref?.();
          void this.poll();
        }).catch(() => {diagnostic('carrier', 'HANDSHAKE_FAILED'); this.fail();});
      } else {
        if (!this.hello) throw Error('local_hello_required');
        // The old phone protocol rejects unknown events. Speech is always compatible.
        if (frame.type === 'event' && frame.event?.type === 'emotion' && !this.presentation) return;
        if (frame.type === 'event' && ['early_text', 'early_text_cancel'].includes(frame.event?.type) && !this.earlySpeech) return;
        if (frame.type === 'event' && ['call_options', 'call_metrics'].includes(frame.event?.type)) {
          // Optional private telemetry never queues or fails normal text/cancel frames. Old clients receive none.
          if (this.callDiagnostics) void fetch(LOCAL_ORIGIN + LOCAL_PREFIX + '/connector/event', {method: 'POST',
            redirect: 'error', signal: AbortSignal.any([this.abort.signal, AbortSignal.timeout(1500)]),
            headers: {'content-type': 'application/json', authorization: 'Bearer ' + grant.connector_token},
            body: JSON.stringify(frame)}).catch(() => {});
          return;
        }
        if (frame.type === 'capabilities') {
          frame.plugin_version = packageMetadata.version;
          diagnostic('speech_capability', 'SEND_BEGIN');
        }
        this.queue = this.queue.then(() => this.request('/connector/event', {method: 'POST',
          headers: {'content-type': 'application/json'}, body: JSON.stringify(frame)}, 10000)).catch(() => {diagnostic('carrier', 'EVENT_FAILED'); this.fail();});
      }
    }
    async heartbeat() {
      if (this.readyState !== 1 || this.checkingHeartbeat) return;
      this.checkingHeartbeat = true;
      try {
        const status = await this.request(HEARTBEAT_ROUTE, {}, HEARTBEAT_TIMEOUT_MS);
        if (this.readyState !== 1) return;
        if (status.connected !== true || status.binding_id !== grant.binding_id) {
          diagnostic('heartbeat', 'BINDING_MISMATCH');
          this.fail();
          return;
        }
        onHeartbeat();
      } catch {
        if (this.readyState === 1) {diagnostic('heartbeat', 'STATUS_UNAVAILABLE'); this.fail();}
      } finally {this.checkingHeartbeat = false;}
    }
    async poll() {
      try {
        while (this.readyState === 1) {
          const data = await this.request('/connector/poll');
          if (!Array.isArray(data.frames) || data.frames.length > 32) throw Error('invalid_local_frames');
          if (this.readyState === 1) this.emit('poll');
          for (const frame of data.frames) {
            if (this.readyState !== 1) return;
            this.emit('message', {data: JSON.stringify(frame)});
          }
        }
      } catch { if (this.readyState === 1) {diagnostic('carrier', 'POLL_FAILED'); this.fail();} }
    }
    fail() { if (this.readyState >= 2) return; this.emit('error'); this.close(); }
    close() {
      if (this.readyState >= 2) return;
      this.readyState = 3; clearInterval(this.heartbeatTimer); this.heartbeatTimer = null;
      this.abort.abort(); this.emit('close');
    }
  };
}

export class LocalConnector extends MobileRelayConnector {
  constructor(controller, store, grant, options = {}) {
    super(controller, store, {relay_url: 'https://local.invalid', verification_code: ''},
      {...options, retryDelays: options.retryDelays ?? [],
        WebSocket: localCarrier(grant, options.fetch || localHttpFetch, options.onDiagnostic, options.onHeartbeat)});
    // Base validates all model-carrier frames. This instance has no arbitrary destination.
    this.address = {origin: LOCAL_ORIGIN, socket: LOCAL_ORIGIN + LOCAL_PREFIX + '/connector/poll'};
    this.speech = new LocalSpeechChannel(options.getSpeechController, (frame, socket) => {
      if (socket === this.socket && this.paired) this.send(frame);
    }, {seen: this.seen, cancelWaitMs: options.cancelWaitMs});
    this.diagnostic = options.onDiagnostic || (() => {});
    this.proactive = new LocalProactiveText(options.getProactiveRoute || (() => undefined), (frame, socket) => {
      if (socket === this.socket && this.paired) this.send(frame);
    }, {seen: this.seen, cancelWaitMs: options.cancelWaitMs});
    this.proactiveProtocol = false;
  }
  connect() {
    super.connect();
    const socket = this.socket;
    socket?.addEventListener('close', () => {
      this.speech.detach(socket);
      this.proactive.interrupt(socket);
      if (!this.socket) this.proactiveProtocol = false;
    });
    socket?.addEventListener('poll', () => {if (this.socket === socket) this.refreshProactive();});
  }
  refreshProactive() {
    if (this.socket && this.paired && this.proactiveProtocol) this.proactive.announce(this.socket);
  }
  async accept(raw, socket = this.socket) {
    if (this.closed || socket !== this.socket) return;
    if (typeof raw !== 'string' || raw.length > MAX_ASR_FRAME_CHARS) throw Error('invalid_local_frame');
    let frame; try {frame = JSON.parse(raw);} catch {throw Error('invalid_local_frame');}
    if (frame?.type === 'proactive-request') {
      if (!this.paired || !this.proactiveProtocol) throw Error('proactive_protocol_required');
      this.proactive.accept(frame, socket); return;
    }
    if (frame?.type === 'cancel' && this.proactive.has(frame.request_id)) {
      void this.proactive.cancel(frame.request_id, socket); return;
    }
    if (this.paired && (frame?.type === 'request' || frame?.type === 'transcribe')) this.proactive.interrupt(socket);
    if (frame?.type === 'prepare-asr') {
      if (this.paired && this.asrProtocol) await this.speech.prepareSelected(socket);
      return;
    }
    if (frame?.type === 'transcribe') {
      if (!this.paired || !this.asrProtocol) throw Error('asr_protocol_required');
      this.speech.accept(frame, socket); return;
    }
    if (frame?.type === 'cancel' && this.speech.has(frame.request_id)) {
      void this.speech.cancel(frame.request_id, true, socket); return;
    }
    await super.accept(raw, socket);
    if (frame?.type === 'paired' && this.paired) {
      this.diagnostic('connector', 'PAIRED_ACCEPTED');
      this.proactiveProtocol = frame.proactive_protocol === PROACTIVE_PROTOCOL;
      this.refreshProactive();
      this.asrProtocol = frame.asr_protocol === 'live2d.asr.v1';
      this.diagnostic('speech', this.asrProtocol ? 'PROTOCOL_PRESENT' : 'PROTOCOL_ABSENT');
      if (this.asrProtocol) {await this.speech.announce(socket, frame.asr_preparation_protocol === ASR_PREPARATION_PROTOCOL); this.diagnostic('speech', 'ANNOUNCE_RETURNED');}
    }
  }
  async dispose() {this.proactive.interrupt(this.socket); await this.speech.dispose(); await super.dispose();}
}
