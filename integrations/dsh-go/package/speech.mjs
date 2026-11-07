import {Buffer} from 'node:buffer';

const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$/;
const MAX_AUDIO_BYTES = 4 * 1024 * 1024;
export const MAX_ASR_FRAME_CHARS = Math.ceil(MAX_AUDIO_BYTES / 3) * 4 + 4096;
export const ASR_PREPARATION_PROTOCOL = 'live2d.asr-preparation.v1';
const RESTARTABLE = new Set(['unprepared', 'failed', 'cancelled']);
const PREPARATION_PHASES = new Set(['ready', 'standby', 'waking', 'checking', 'downloading', 'loading', 'cancelling', ...RESTARTABLE]);
const USABLE = new Set(['ready', 'standby', 'waking']);
const safeUnavailable = '本机语音识别尚未就绪，请检查 DSH Go 语音设置。';
const safeFailed = '本次语音识别没有完成，请重新说一次。';

function localSelection(catalog) {
  const provider = catalog?.providers?.find(row => row.id === catalog.selection?.providerId);
  return provider?.location === 'host-local' ? provider : null;
}

/** Only the user's already-selected host-local provider can receive recordings. */
export function selectedLocalProvider(catalog) {
  const provider = localSelection(catalog);
  return provider && USABLE.has(provider.preparation?.phase ?? 'ready') ? provider : null;
}

function preparationView(controller, catalog) {
  const provider = localSelection(catalog);
  const state = provider?.preparation;
  const phase = state?.phase ?? (provider ? 'ready' : 'unavailable');
  const view = {phase: PREPARATION_PHASES.has(phase) ? phase : 'unavailable',
    can_prepare: RESTARTABLE.has(phase) && typeof controller?.prepare === 'function' && typeof controller.follow === 'function'};
  if (phase === 'downloading' && Number.isSafeInteger(state.completedBytes) && state.completedBytes >= 0) {
    view.completed_bytes = state.completedBytes;
    if (Number.isSafeInteger(state.totalBytes) && state.totalBytes > 0 && state.totalBytes >= state.completedBytes)
      view.total_bytes = state.totalBytes;
  }
  return view;
}

/** Recording recognition is independent of all model sessions and prompts. */
export class LocalSpeechChannel {
  constructor(getController, send, options = {}) {
    this.getController = getController || (() => undefined);
    this.send = send;
    this.jobs = new Map(); this.seen = options.seen || new Set();
    this.cancelWaitMs = options.cancelWaitMs ?? 10000;
    this.closed = false; this.currentSocket = null;
    this.observation = null; this.lastCapability = null;
  }
  async availability() {
    try {
      const controller = this.getController();
      if (!controller || typeof controller.catalog !== 'function' || typeof controller.transcribe !== 'function') return null;
      const catalog = await controller.catalog(), provider = selectedLocalProvider(catalog);
      return provider ? {controller, catalog, provider} : null;
    } catch {return null;}
  }
  emit(frame, socket) {if (!this.closed && this.currentSocket === socket) this.send(frame, socket);}
  async announce(socket, preparationProtocol = false) {
    this.stopObservation();
    this.currentSocket = socket;
    if (this.closed) return;
    const observation = {socket, abort: new AbortController(), task: null, preparationProtocol, starting: false};
    this.observation = observation;
    let controller;
    try {controller = this.getController();} catch {}
    if (!controller || typeof controller.catalog !== 'function' || typeof controller.transcribe !== 'function') {
      this.publishCapability(null, null, observation); return;
    }
    try {this.publishCapability(controller, await controller.catalog(), observation);}
    catch {this.publishCapability(null, null, observation);}
    if (this.observation !== observation || observation.abort.signal.aborted || typeof controller.follow !== 'function') return;
    // Official readiness stream: no model preparation, download, preference write or polling.
    observation.task = (async () => {
      try {
        for await (const catalog of controller.follow(observation.abort.signal)) {
          if (observation.abort.signal.aborted || this.observation !== observation) break;
          this.publishCapability(controller, catalog, observation);
        }
      } catch {if (!observation.abort.signal.aborted) this.publishCapability(null, null, observation);}
    })();
  }
  publishCapability(controller, catalog, observation) {
    if (this.closed || observation.abort.signal.aborted || this.observation !== observation || this.currentSocket !== observation.socket) return;
    const asr = Boolean(selectedLocalProvider(catalog));
    const preparation = observation.preparationProtocol ? preparationView(controller, catalog) : null;
    // Keep the shared carrier queue small. Transmit exact byte counts when a percent or MiB boundary changes.
    const progress = preparation?.total_bytes ? Math.floor(preparation.completed_bytes / preparation.total_bytes * 100)
      : Math.floor((preparation?.completed_bytes ?? 0) / 1048576);
    const key = JSON.stringify([asr, preparation?.phase, preparation?.can_prepare, preparation?.total_bytes, progress]);
    if (this.lastCapability?.socket === observation.socket && this.lastCapability.key === key) return;
    this.lastCapability = {socket: observation.socket, key};
    this.emit({type: 'capabilities', asr, ...asr ? {} : {asr_message: safeUnavailable},
      ...preparation ? {asr_preparation: preparation} : {}}, observation.socket);
  }
  async prepareSelected(socket) {
    const observation = this.observation;
    if (!observation?.preparationProtocol || observation.socket !== socket || observation.starting || this.closed) return;
    observation.starting = true;
    let accepted = false;
    try {
      const controller = this.getController();
      const catalog = await controller.catalog();
      if (this.observation !== observation || observation.abort.signal.aborted) return;
      if (preparationView(controller, catalog).can_prepare) {
        // Host-owned work starts only after a user action on the paired phone.
        await controller.prepare(localSelection(catalog).id);
        accepted = true;
      }
    } catch {} finally {
      observation.starting = false;
      if (this.observation === observation) this.emit({type: 'asr-preparation-result', accepted}, socket);
    }
  }
  stopObservation() {
    const old = this.observation; this.observation = null; old?.abort.abort();
  }
  refreshController() {
    if (!this.closed && this.currentSocket) return this.announce(this.currentSocket, this.observation?.preparationProtocol ?? false);
  }
  has(id) {return this.jobs.has(id);}
  accept(frame, socket) {
    if (this.closed || this.currentSocket !== socket) return;
    if (!ID.test(frame.request_id || '') || frame.asr_request_id !== frame.request_id || !ID.test(frame.session_id || '') ||
        frame.sample_rate !== 16000 || !Number.isFinite(frame.audio_seconds) || frame.audio_seconds <= 0 || frame.audio_seconds > 60 ||
        !['zh', 'ja', 'en'].includes(frame.reply_language) || typeof frame.audioBase64 !== 'string' ||
        !['auto', 'zh', 'ja', 'en'].includes(frame.language ?? 'auto')) throw Error('invalid_transcription_frame');
    if (this.seen.has(frame.request_id)) return;
    if (this.seen.size >= 4096 || this.jobs.size) {
      this.emit({type: 'transcription_error', request_id: frame.request_id, code: 'ASR_FAILED', message: safeFailed}, socket); return;
    }
    this.seen.add(frame.request_id);
    const job = {id: frame.request_id, abort: new AbortController(), cancelled: false, settled: false, socket, task: null};
    this.jobs.set(job.id, job);
    job.task = this.run(job, frame).finally(() => {job.settled = true; if (this.jobs.get(job.id) === job) this.jobs.delete(job.id);});
  }
  async run(job, frame) {
    try {
      const ready = await this.availability();
      if (job.cancelled || this.closed) return;
      if (!ready) {this.emit({type: 'transcription_error', request_id: job.id, code: 'ASR_UNAVAILABLE', message: safeUnavailable}, job.socket); return;}
      const cap = Math.min(MAX_AUDIO_BYTES, ready.catalog.maxAudioBytes ?? MAX_AUDIO_BYTES);
      if (frame.audioBase64.length > Math.ceil(cap / 3) * 4 || frame.audio_seconds > Math.min(60, ready.catalog.maxDurationSeconds ?? 60)) throw Error('invalid_audio_size');
      const audio = Buffer.from(frame.audioBase64, 'base64');
      if (audio.length > cap || audio.length < 46 || audio.toString('base64') !== frame.audioBase64) throw Error('invalid_audio_encoding');
      // The official controller performs authoritative WAV format/duration validation.
      const request = {audioBase64: frame.audioBase64, providerId: ready.provider.id,
        ...(frame.language && frame.language !== 'auto' ? {language: frame.language} : {})};
      const result = await ready.controller.transcribe(request, job.abort.signal);
      if (job.cancelled || this.closed || this.currentSocket !== job.socket) return;
      if (typeof result?.text !== 'string' || !result.text.trim() || result.text.length > 8000 ||
          !Number.isFinite(result.audioSeconds) || result.audioSeconds <= 0 || !Number.isFinite(result.inferenceSeconds) || result.inferenceSeconds < 0) throw Error('invalid_transcript');
      this.emit({type: 'transcription', request_id: job.id, text: result.text,
        audio_seconds: result.audioSeconds, inference_seconds: result.inferenceSeconds}, job.socket);
    } catch {
      if (!job.cancelled && !this.closed) this.emit({type: 'transcription_error', request_id: job.id, code: 'ASR_FAILED', message: safeFailed}, job.socket);
    }
  }
  async cancel(id, acknowledge, socket) {
    const job = this.jobs.get(id); if (!job) return false;
    job.cancelled = true; job.abort.abort();
    let timer;
    await Promise.race([job.task.catch(() => {}), new Promise(resolve => {timer = setTimeout(resolve, this.cancelWaitMs);})]);
    clearTimeout(timer);
    if (acknowledge) this.emit({type: 'cancelled', request_id: id, confirmed: job.settled}, socket);
    return true;
  }
  detach(socket) {
    if (this.currentSocket === socket) {this.currentSocket = null; this.stopObservation(); this.lastCapability = null;}
    for (const job of this.jobs.values()) if (job.socket === socket) {job.cancelled = true; job.abort.abort();}
  }
  async dispose() {
    this.closed = true; this.currentSocket = null; this.stopObservation(); this.lastCapability = null;
    await Promise.all([...this.jobs.keys()].map(id => this.cancel(id, false, null)));
  }
}
