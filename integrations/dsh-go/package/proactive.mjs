export const PROACTIVE_PROTOCOL = 'live2d.proactive-text.v1';

const validId = value => typeof value === 'string' && /^[\w-]{1,80}$/.test(value);
const languages = {zh: '中文', ja: '日本語', en: 'English'};
const fields = new Set(['type', 'request_id', 'session_id', 'text', 'reply_language']);

export function defaultTextModel(ctx) {
  try {
    const selection = ctx.get('agentDefaultModel')?.currentSelection?.();
    const llm = ctx.get('llm');
    if (typeof selection?.provider === 'string' && selection.provider && typeof selection.model === 'string' &&
        selection.model && typeof llm?.prepareCall === 'function') return {...selection, llm};
  } catch { /* A missing optional service must not interrupt manual chat. */ }
}

export class LocalProactiveText {
  constructor(getRoute, emit, {seen, cancelWaitMs = 10000}) {
    this.getRoute = getRoute;
    this.emit = emit;
    this.seen = seen;
    this.cancelWaitMs = cancelWaitMs;
    this.active = null;
    this.lastCapability = null;
  }

  announce(socket) {
    const available = Boolean(this.getRoute());
    if (this.lastCapability?.socket === socket && this.lastCapability.available === available) return;
    this.lastCapability = {socket, available};
    this.emit({type: 'proactive-capability', protocol: PROACTIVE_PROTOCOL, available}, socket);
  }

  accept(frame, socket) {
    const id = frame.request_id;
    if (!validId(id) || !validId(frame.session_id) || !Object.hasOwn(languages, frame.reply_language) ||
        typeof frame.text !== 'string' || !frame.text.trim() || frame.text.length > 1600 ||
        Object.keys(frame).some(key => !fields.has(key))) throw Error('invalid_proactive_request');
    if (this.seen.has(id)) return;
    const route = this.getRoute();
    if (!route || this.active || this.seen.size >= 4096) {
      this.emit({type: 'done', request_id: id, success: false}, socket);
      return;
    }
    this.seen.add(id);
    const job = {id, socket, abort: new AbortController(), settled: false, task: null};
    this.active = job;
    job.task = this.run(job, route, frame).finally(() => {
      job.settled = true;
      if (this.active === job) this.active = null;
    });
  }

  async run(job, route, frame) {
    const signal = job.abort.signal;
    const event = value => { if (!signal.aborted) this.emit(value, job.socket); };
    try {
      const prepared = await route.llm.prepareCall({provider: route.provider, model: route.model, maxTokens: 192}, signal);
      signal.throwIfAborted();
      const config = {...prepared.config};
      for (const key of ['tools', 'toolChoice', 'tool_choice']) delete config[key];
      const system = `用${languages[frame.reply_language]}说一句自然、简短的通话开场白。只输出可朗读文字。` +
        '你未看到画面。对方表情如果有描述，也仅是不确定的本机观察，不要当成事实。';
      const messages = [{role: 'user', content: [{type: 'text', text: frame.text}]}];
      let text = '', finish;
      for await (const chunk of prepared.stream({...config, system, messages, signal})) {
        signal.throwIfAborted();
        if (chunk.type === 'text-delta') {
          if (typeof chunk.text !== 'string' || text.length + chunk.text.length > 1600) throw Error('invalid_proactive_output');
          text += chunk.text;
        } else if (chunk.type === 'finish') finish = chunk.reason?.kind;
        else if (chunk.type.startsWith('tool-')) throw Error('proactive_tool_output');
      }
      signal.throwIfAborted();
      if (!['stop', 'max-tokens'].includes(finish) || !text.trim()) throw Error('empty_proactive_reply');
      event({type: 'event', request_id: job.id, event: {type: 'text', text: text.trim()}});
      event({type: 'done', request_id: job.id, success: true});
    } catch {
      event({type: 'done', request_id: job.id, success: false});
    }
  }

  has(id) { return this.active?.id === id; }

  interrupt(socket) {
    if (this.active?.socket === socket) this.active.abort.abort();
  }

  async cancel(id, socket) {
    const job = this.active;
    if (!job || job.id !== id || job.socket !== socket) return;
    job.abort.abort();
    let timer;
    try {
      await Promise.race([job.task, new Promise(resolve => { timer = setTimeout(resolve, this.cancelWaitMs); })]);
    } finally { clearTimeout(timer); }
    this.emit({type: 'cancelled', request_id: id, confirmed: job.settled}, socket);
  }
}
