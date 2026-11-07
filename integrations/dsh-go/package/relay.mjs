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
export function normalizeVerificationCode(value) {
  const code = (value || '').trim().toUpperCase();
  if (code && !/^[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(code)) throw new Error('invalid_verification_code');
  return code;
}
const SHORT_SPEECH = '通话时用自然短句，每句只说一件事。普通闲聊先说重点，通常一到三句。把过长的句子用句号或问号拆开，少用一串逗号。用户要求朗诵、引用或按原文读时，保留原文。';

const FACE_SCORES = ['confidence', 'smile_probability', 'left_eye_open_probability', 'right_eye_open_probability', 'mouth_open'];
const FACE_ANGLES = ['head_yaw_deg', 'head_pitch_deg', 'head_roll_deg'];
const FACE_FIELDS = new Set(['version', 'age_ms', 'face_count', ...FACE_SCORES, ...FACE_ANGLES]);
const FACE_MARKER = '[手机本轮间接表情提示:v1]';

function facePrompt(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(key => !FACE_FIELDS.has(key)) ||
      value.version !== 1 || value.face_count !== 1 || !Number.isInteger(value.age_ms) || value.age_ms < 0 || value.age_ms > 3000) return '';
  const cues = [];
  const scores = {smile_probability: '嘴角形状偏向微笑', left_eye_open_probability: '左眼',
    right_eye_open_probability: '右眼', mouth_open: '嘴部'};
  for (const key of FACE_SCORES) {
    const number = value[key];
    if (number == null) continue;
    if (!Number.isFinite(number) || number < 0 || number > 1) return '';
    if (key === 'smile_probability') { if (number >= 0.75) cues.push(scores[key]); }
    else if (key !== 'confidence' && (number >= 0.8 || number <= 0.2)) cues.push(scores[key] + (number >= 0.8 ? '似乎张开' : '似乎闭合'));
  }
  let pose = false;
  for (const key of FACE_ANGLES) {
    const number = value[key];
    if (number == null) continue;
    if (!Number.isFinite(number) || number < -90 || number > 90) return '';
    if (Math.abs(number) >= 12) pose = true;
  }
  if (pose) cues.push('头部似乎有转动或倾斜');
  if (cues.length === 0) return '';
  return `${FACE_MARKER} 手机在这条消息发出时粗略提示：${cues.join('、')}。可能不准，只是间接线索，不是画面或实时摄像头。` +
    '用户没问就只作语气参考；问时说“手机提示像是……，我不能确定”。不要说“我看到你”或断定身份、心情、想法、动机。不要记入个人记忆。';
}

const SPOKEN_AFFECT = "用措辞和句子的节奏表达感情。高兴时轻快一点，安慰时放柔，意外时先短短回应。用自然停顿、问句或适量感叹号体现语气，不加“轻声地”“笑着说”等旁白。";
const CALL_PRESENTATION = "按回复内容，可在台词开头放最多一个表情标记：[neutral]、[happy]、[sad]、[angry]、[surprised]、[thinking]、[shy]；另可选一个动作：[gesture:nod]（赞同）、[gesture:shake]（否定）、[gesture:bow]（感谢或道歉）、[gesture:sway]（俏皮）、[gesture:raise-hand]（招呼）或[gesture:lower-hand]（放下手）。没有合适动作就省略。标记仅给界面，不在台词里描述、重复或朗读。只输出控制标记和台词。";

// Opt-in from a compatible local phone is still restricted to short ordinary Chinese turns.
const TOOL_OR_COMMAND = /(?:查(?:一下|找|询)|搜索|联网|网页|新闻|天气|几点|执行|运行|调用|打开|切换|下载|上传|安装|启动|停止|重启|读取|删除|发送|截图|拍照|识图|代码|命令|插件|工具|文件|日志|密码|https?:|\b(?:ssh|adb|curl|sudo|python)\b)/iu;
function canPreviewText(text) {
  return text.length <= 160 && /\p{Script=Han}/u.test(text) &&
    !/[\n\r`<>\[\]{}\\/]/u.test(text) && !TOOL_OR_COMMAND.test(text);
}
const EARLY_SPEECH_STYLE = '直接用一句完整的中文口语短句开头，用句号、问号或感叹号结束；可选表情动作标记放在台词之前。';

export function replyPrompt(text, language, face = null, earlySpeech = false) {
  if (!['zh', 'ja', 'en'].includes(language)) throw new Error('invalid_reply_language');
  const shortSpeech = (text.includes(SHORT_SPEECH) ? '' : '\n\n' + SHORT_SPEECH) +
    (text.includes(SPOKEN_AFFECT) ? '' : '\n' + SPOKEN_AFFECT);
  const faceNote = text.includes(FACE_MARKER) ? '' : facePrompt(face);
  const withFace = prompt => {
    if (!faceNote) return prompt;
    const augmented = prompt + '\n\n' + faceNote;
    return augmented.length <= 8000 && new TextEncoder().encode(JSON.stringify(augmented)).byteLength <= 19000 ? augmented : prompt;
  };
  if (['ja', 'en'].includes(language) && text.startsWith(`[手机双语字幕:v1;target=${language}]\n`)) {
    return withFace(text + shortSpeech);
  }
  const note = language === 'ja' ? '[本轮回复语言：ja] 本轮直接用日语回答。' : language === 'en' ? '[本轮回复语言：en] 本轮直接用英语回答。' : '[本轮回复语言：zh] 本轮直接用中文回答。';
  const alreadySpecified = text === note || text.startsWith(note + '\n');
  return withFace(`[手机通话：已经在与用户通话，直接回复1至3句可朗读正文，不附路由标识或思考/工具过程；${
    alreadySpecified ? '' : language === 'ja' ? '请用日语回复。' : language === 'en' ? '请用英语回复。' : '请用中文回复。'}]${shortSpeech}\n${CALL_PRESENTATION}${earlySpeech ? '\n' + EARLY_SPEECH_STYLE : ''}\n${text}`);
}
const post = (suffix, data) => new Request('http://adapter.invalid' + PREFIX + '/' + suffix,
  {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify(data)});

/** Outbound carrier only. All host operations use a separate in-memory adapter,
 * never an unauthenticated listener or another user's pre-existing session. */
export class MobileRelayConnector {
  constructor(controller, store, config, options = {}) {
    this.address = relayAddress(config.relay_url);
    this.code = normalizeVerificationCode(config.verification_code);
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
      const earlySpeech = socket?.earlySpeech === true && frame.early_speech === true &&
        frame.reply_language === 'zh' && canPreviewText(frame.text);
      const job = {id: frame.request_id, phoneSession: frame.session_id, cancelled: false,
        active: null, session: null, task: null, success: false, earlySpeech};
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
        text: replyPrompt(input.text, input.reply_language, input.face_observation, job.earlySpeech)}),
        {earlySpeech: job.earlySpeech});
      if (!response.ok) throw new Error('prompt_not_accepted');
      job.active = this.adapter.session(slot.id).active;
      let buffer = ''; let textSeen = false; let completed = false; let done = false; let errorSeen = false;
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
            if (!job.cancelled) this.send({type: 'event', request_id: job.id, event: {type: 'status', status: 'failed'}});
          }
          if (kind === 'status') {
            completed ||= data.status === 'completed';
            if (!job.cancelled) this.send({type: 'event', request_id: job.id, event: {type: 'status', status: data.status}});
          }
          if (kind === 'emotion' && !job.cancelled && EMOTIONS.includes(data.emotion)) {
            this.send({type: 'event', request_id: job.id, event: {type: 'emotion', emotion: data.emotion,
              ...(GESTURES.includes(data.gesture) ? {gesture: data.gesture} : {})}});
          }
          if (job.earlySpeech && !job.cancelled && kind === 'early_text' &&
              typeof data.text === 'string' && data.text.length > 0 && data.text.length <= 256) {
            this.send({type: 'event', request_id: job.id, event: {type: 'early_text', text: data.text}});
          }
          if (job.earlySpeech && !job.cancelled && kind === 'early_text_cancel') {
            this.send({type: 'event', request_id: job.id, event: {type: 'early_text_cancel'}});
          }
          if (kind === 'text' && !job.cancelled) {
            textSeen = true; this.send({type: 'event', request_id: job.id, event: {type: 'text', text: data.text}});
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
