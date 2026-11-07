import {LocalConnector} from './carrier.mjs';
import {challengeId, freshChallenge, LOCAL_ORIGIN, LOCAL_PREFIX, proveLocal} from './identity.mjs';
import {localHttpFetch} from './local-http.mjs';

const labels = {disconnected: '未连接', authorizing: '等待 DSH L2D 授权连接', connecting: '正在连接本机应用',
  paired: '已连接本机 DSH L2D', identity_error: '本机服务身份未通过验证', connection_error: '本机连接未完成',
  relay_error: '本机授权已失效', connection_stopped: '连接已停止', protocol_error: '本机通信异常',
  reconnecting: '连接中断，正在重连', reconnect_failed: '自动重连未成功', launch_required: '请先在本机打开 DSH L2D', pairing_busy: '手机已有待确认的连接'};
const RECONNECT_DELAYS_MS = [1000, 2000, 4000];
const RECONNECT_ATTEMPT_MS = 2500;
const PAIRING_WINDOW_MS = 90000;
const PROOF_FAILURES = new Set(['CHALLENGE_INVALID', 'ENVELOPE_INVALID', 'PROTOCOL_INVALID', 'APP_INVALID', 'NONCE_MISMATCH',
  'PROOF_FORMAT_INVALID', 'HMAC_MISMATCH', 'PROOF_ABORTED', 'PROOF_TIMEOUT', 'FETCH_TYPE_ERROR', 'FETCH_NETWORK_ERROR', 'JSON_INVALID', 'PROOF_HTTP_REJECTED']);
const validGrant = grant => grant?.relay_url === LOCAL_ORIGIN && /^[a-f0-9]{64}$/.test(grant.bootstrap_challenge || '') &&
  typeof grant.connector_token === 'string' && grant.connector_token.length >= 16 && typeof grant.binding_id === 'string';
const sleep = (ms, signal) => new Promise((resolve, reject) => {
  const done = () => {clearTimeout(timer); signal?.removeEventListener('abort', abort);};
  const abort = () => {done(); reject(Error('connection_cancelled'));};
  const timer = setTimeout(() => {done(); resolve();}, ms);
  signal?.addEventListener('abort', abort, {once: true}); if (signal?.aborted) abort();
});

export class LocalManager {
  constructor(controller, store, options = {}) {
    this.controller = controller; this.store = store; this.fetch = options.fetch || localHttpFetch;
    this.Connector = options.Connector || LocalConnector;
    this.inspectModel = options.inspectModel; this.callDiagnostics = options.callDiagnostics === true;
    this.getSpeechController = options.getSpeechController || (() => undefined);
    this.getProactiveRoute = options.getProactiveRoute || (() => undefined);
    this.status = 'disconnected'; this.pending = null; this.preparing = null;
    this.connector = null; this.pairingFingerprint = null; this.closed = false;
    this.diagnostics = []; this.lastHeartbeatAtMs = null;
    this.restoring = null; this.recovery = null;
  }
  diagnostic(stage, code, httpStatus) {
    // Only fixed call-site identifiers and a numeric HTTP status are accepted; no error body or secret.
    if (!/^[a-z_]{1,40}$/.test(stage || '') || !/^[A-Z_]{1,50}$/.test(code || '')) return;
    this.diagnostics.push({stage, code, ...(Number.isInteger(httpStatus) && httpStatus >= 100 && httpStatus <= 599 ? {http_status: httpStatus} : {})});
    if (this.diagnostics.length > 16) this.diagnostics.shift();
  }
  snapshot() {
    const shownDiagnostic = this.diagnostics.findLast(row => row.http_status >= 400 || PROOF_FAILURES.has(row.code) || row.code.startsWith('CAUSE_')) || this.diagnostics.at(-1);
    return {status: this.status, statusLabel: labels[this.status] || labels.connection_error, ready: this.status === 'paired',
      lastHeartbeatAtMs: this.lastHeartbeatAtMs,
      ...(this.status === 'authorizing' && this.pairingFingerprint ? {pairing_fingerprint: this.pairingFingerprint} : {}),
      diagnostic: this.diagnostics.at(-1) || null, diagnostics: this.diagnostics.slice(),
      detail: (this.status === 'authorizing'
        ? '请回到 DSH L2D，核对两边显示的标识并点允许。无需输入标识。'
        : '只通过本机授权连接；不启用手机操控，不使用公网通话中转。') +
        (['identity_error', 'connection_error', 'relay_error', 'connection_stopped', 'protocol_error', 'reconnect_failed'].includes(this.status) && shownDiagnostic ? ' [诊断 ' + shownDiagnostic.stage + ': ' + shownDiagnostic.code +
          (shownDiagnostic.http_status ? ' HTTP ' + shownDiagnostic.http_status : '') + ']' : '')};
  }
  setStatus(status) {
    if (this.closed) return;
    this.status = Object.hasOwn(labels, status) ? status : 'connection_error';
    this.lastHeartbeatAtMs = this.status === 'paired' ? Date.now() : null;
    if (this.status !== 'authorizing') this.pairingFingerprint = null;
  }
  refreshSpeech() {
    if (!this.closed) void this.connector?.speech?.refreshController()?.catch(() => {});
  }
  prepare() {
    if (this.preparing) return this.preparing;
    const task = (async () => {
      const grant = await this.store.read().catch(() => undefined);
      return {...this.snapshot(), has_saved_authorization: validGrant(grant)};
    })().finally(() => {if (this.preparing === task) this.preparing = null;});
    this.preparing = task; return task;
  }
  restore() {
    if (this.restoring) return this.restoring;
    const task = this.restoreGranted().finally(() => {if (this.restoring === task) this.restoring = null;});
    this.restoring = task; return task;
  }
  async restoreGranted() {
    const grant = await this.store.read().catch(() => undefined);
    if (!validGrant(grant) || this.closed || this.pending) return;
    // Plugin startup only restores an already-authorized running service; never opens an app.
    try {
      this.diagnostic('restore', 'PROOF_BEGIN');
      const signal = AbortSignal.timeout(8000);
      await proveLocal(this.fetch, grant.bootstrap_challenge, signal, {diagnostic: (code, status) => this.diagnostic('restore_proof', code, status)});
      this.diagnostic('restore', 'PROOF_OK');
      if (this.closed || this.pending) return;
      const current = await this.claim(grant.bootstrap_challenge, signal);
      if (this.closed || this.pending) return;
      if (current) await this.attach(current);
    } catch {this.diagnostic('restore', 'RESTORE_FAILED'); this.setStatus('disconnected');}
  }
  async connectLocal() {
    if (this.closed || this.recovery || this.pending || this.status === 'paired') return this.snapshot();
    const abort = this.abort = new AbortController();
    const signal = AbortSignal.any([abort.signal, AbortSignal.timeout(RECONNECT_ATTEMPT_MS)]);
    this.setStatus('connecting');
    let publishPairing;
    const pairingStarted = new Promise(resolve => {publishPairing = resolve;});
    const task = (async () => {
      const saved = await this.store.read().catch(() => undefined);
      signal.throwIfAborted();
      if (!validGrant(saved)) {await this.pairLocal(abort, signal, publishPairing); return;}
      const challenge = saved.bootstrap_challenge;
      await proveLocal(this.fetch, challenge, signal,
        {diagnostic: (code, status) => this.diagnostic('resume_proof', code, status)});
      const grant = await this.claim(challenge, signal);
      if (!grant) {if (!signal.aborted) this.setStatus('launch_required'); return;}
      await this.attach(grant, signal);
    })().catch(error => {
      if (abort.signal.aborted) return;
      if (error?.message === 'local_identity_not_verified') this.setStatus('identity_error');
      else this.setStatus(this.status === 'authorizing' ? 'connection_error' : 'launch_required');
    }).finally(() => {if (this.pending === task) this.pending = null;});
    this.pending = task;
    await Promise.race([task, pairingStarted]);
    return this.snapshot();
  }
  connect() {return this.connectLocal();}
  async pairLocal(abort, beginSignal, publishPairing) {
    const challenge = freshChallenge();
    const response = await this.fetch(LOCAL_ORIGIN + LOCAL_PREFIX + '/pair/begin', {method: 'POST',
      signal: beginSignal, redirect: 'error', headers: {'content-type': 'application/json'}, body: JSON.stringify({challenge})});
    beginSignal.throwIfAborted();
    this.diagnostic('pair_begin', 'HTTP_RESULT', response.status);
    if (response.status === 403) {this.setStatus('launch_required'); return;}
    if (response.status === 409) {this.setStatus('pairing_busy'); return;}
    if (response.status !== 202 || (await response.json())?.status !== 'pending') throw Error('local_pair_begin_invalid');
    beginSignal.throwIfAborted();
    const signal = AbortSignal.any([abort.signal, AbortSignal.timeout(PAIRING_WINDOW_MS)]);
    this.pairingFingerprint = challengeId(challenge).slice(0, 8).toUpperCase();
    this.setStatus('authorizing');
    publishPairing();
    while (!signal.aborted) {
      await proveLocal(this.fetch, challenge, signal,
        {diagnostic: (code, status) => this.diagnostic('claim_proof', code, status)});
      const grant = await this.claim(challenge, signal);
      if (grant) {await this.attach(grant, signal); return;}
      await sleep(700, signal);
    }
    signal.throwIfAborted();
  }
  async claim(challenge, signal) {
      const response = await this.fetch(LOCAL_ORIGIN + LOCAL_PREFIX + '/pair/claim', {method: 'POST', signal,
        redirect: 'error', headers: {'content-type': 'application/json'}, body: JSON.stringify({challenge})});
      this.diagnostic('claim', response.ok ? 'HTTP_OK' : response.status === 202 ? 'APPROVAL_PENDING' : 'HTTP_REJECTED', response.status);
      if (response.status === 202) return null;
      if (!response.ok) throw Error('local_claim_failed');
      const data = await response.json();
      const grant = {relay_url: LOCAL_ORIGIN, bootstrap_challenge: challenge, binding_id: data.binding_id,
        connector_token: data.connector_token, expires_at_ms: data.expires_at_ms};
      if (!validGrant(grant) || !Number.isFinite(grant.expires_at_ms) || grant.expires_at_ms <= Date.now()) throw Error('local_claim_invalid');
      if (signal.aborted) throw Error('connection_cancelled');
      await this.store.save(grant); return grant;
  }
  reconnect(challenge) {
    if (this.closed || this.recovery) return;
    const abort = this.abort = new AbortController();
    this.setStatus('reconnecting');
    const task = (async () => {
      for (const delay of RECONNECT_DELAYS_MS) {
        await sleep(delay, abort.signal);
        const signal = AbortSignal.any([abort.signal, AbortSignal.timeout(RECONNECT_ATTEMPT_MS)]);
        try {
          this.diagnostic('reconnect', 'ATTEMPT');
          await proveLocal(this.fetch, challenge, signal,
            {diagnostic: (code, status) => this.diagnostic('reconnect_proof', code, status)});
          const grant = await this.claim(challenge, signal);
          if (!grant) break; // The visible authorization flow belongs to the user.
          await this.attach(grant, signal);
          if (this.status === 'paired') return;
        } catch (error) {
          if (abort.signal.aborted) return;
          if (error?.message === 'local_identity_not_verified') break;
          this.diagnostic('reconnect', 'ATTEMPT_FAILED');
        }
      }
      if (!abort.signal.aborted) this.setStatus('reconnect_failed');
    })().catch(() => {
      if (!abort.signal.aborted) this.setStatus('reconnect_failed');
    }).finally(() => {if (this.recovery === task) this.recovery = null;});
    this.recovery = task;
  }
  async attach(grant, signal) {
    signal?.throwIfAborted();
    const attempt = this.abort;
    if (this.closed || attempt?.signal.aborted) return;
    const old = this.connector; this.connector = null; if (old) await old.dispose();
    if (this.closed || this.abort !== attempt || attempt?.signal.aborted) return;
    signal?.throwIfAborted();
    const store = {read: async () => grant, save: payload => this.store.save({...grant, ...payload})};
    let connector;
    let paired = false;
    let resolveReady;
    const ready = signal ? new Promise(resolve => {resolveReady = resolve;}) : null;
    const abortAttach = () => resolveReady(false);
    this.diagnostic('attach', 'CONSTRUCT_BEGIN');
    connector = new this.Connector(this.controller, store, grant, {fetch: this.fetch, getSpeechController: this.getSpeechController, inspectModel: this.inspectModel, callDiagnostics: this.callDiagnostics,
      getProactiveRoute: this.getProactiveRoute,
      onDiagnostic: (stage, code, status) => {if (this.connector === connector) this.diagnostic(stage, code, status);},
      onHeartbeat: () => {if (this.connector === connector && this.status === 'paired') this.lastHeartbeatAtMs = Date.now();},
      onStatus: status => {
        if (this.connector !== connector) return;
        if (status === 'paired') {
          paired = true;
          this.setStatus(status);
          resolveReady?.(true);
        } else if (status === 'connection_stopped' && paired && !this.recovery) {
          this.reconnect(grant.bootstrap_challenge);
        } else {
          if (status === 'connection_stopped') resolveReady?.(false);
          if (this.recovery) this.setStatus('reconnecting');
          else this.setStatus(status);
        }
      }});
    this.connector = connector;
    if (!this.recovery) this.setStatus('connecting');
    signal?.addEventListener('abort', abortAttach, {once: true});
    try {
      signal?.throwIfAborted();
      this.diagnostic('attach', 'START_BEGIN');
      await connector.start();
      if (ready && !await ready) throw Error('local_reconnect_attach_failed');
      this.diagnostic('attach', 'START_RETURNED');
    } catch (error) {
      if (this.connector === connector) {
        this.connector = null;
        await connector.dispose();
      }
      throw error;
    } finally {
      signal?.removeEventListener('abort', abortAttach);
    }
  }
  async disconnect() {
    this.abort?.abort(); const old = this.connector; this.connector = null;
    this.setStatus('disconnected');
    if (old) await old.dispose();
    return this.snapshot();
  }
  async dispose() {await this.disconnect(); this.closed = true;}
}
