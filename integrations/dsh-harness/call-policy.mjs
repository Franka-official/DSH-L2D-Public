// DSH rc.4 public Agent-scoped hooks. Never changes deployment defaults.
const safeId = value => typeof value === 'string' && value.length <= 160 && !/[\u0000-\u001f\u007f]/.test(value) ? value : null;
const milliseconds = value => Math.max(0, Math.min(Number.MAX_SAFE_INTEGER, Math.round(value)));

export class CallReasoningPolicy {
  constructor(controller, {isOwned, inspectModel, diagnostics = false} = {}) {
    this.controller = controller; this.isOwned = isOwned; this.inspectModel = inspectModel;
    this.diagnostics = diagnostics; this.bindings = new Map(); this.closed = false;
  }
  current(session, agent) {
    return !this.closed && this.isOwned(session) && this.bindings.get(session.id)?.agent === agent ? session.active : null;
  }
  async bind(session) {
    if (this.closed || !this.isOwned(session)) throw Error('call_session_not_owned');
    if (typeof this.controller.resolveAgent !== 'function') throw Error('call_agent_api_unavailable');
    const found = await this.controller.resolveAgent(session.id);
    const agent = found?.agent;
    if (!agent || agent.id !== session.id || typeof agent.ctx?.on !== 'function') throw Error('call_agent_unavailable');
    if (this.closed || !this.isOwned(session)) throw Error('call_session_not_owned');
    if (this.bindings.get(session.id)?.agent === agent) return;
    this.unbind(session.id);
    const binding = {agent, disposers: []}; this.bindings.set(session.id, binding);
    try {
      // Match the plugin's exact queued RPC, including tool continuations in that turn.
      binding.disposers.push(agent.ctx.on('agent/pre-step', async (payload, next) => {
        const decision = await next(); const active = this.current(session, agent);
        if (payload.agent === agent && active && decision?.kind === 'enter' &&
            decision.messages?.some(message => message?.source?.kind === 'user' && message.source.rpcId === active.requestId)) {
          active.callPolicyTurn = payload.turn;
        }
        return decision;
      }));
      // Outermost after-next override: the host's model-selection listener cannot restore its old effort.
      binding.disposers.push(agent.ctx.on('agent/request', async (payload, next) => {
        const config = await next(); const active = this.current(session, agent);
        if (payload.agent !== agent || !active || active.callPolicyTurn !== payload.turn) return config;
        active.callPolicyMetrics ||= {started: Date.now(), thinkingChars: 0, firstThinking: null, firstText: null, attempts: 0, lastRevision: -1, streams: new Map()};
        let info;
        try { info = await this.inspectModel?.(config.provider, config.model, payload.signal); } catch {}
        // Metadata may settle after cancellation, replacement or disposal.
        if (this.current(session, agent) !== active || payload.signal?.aborted) return config;
        const efforts = Array.isArray(info?.reasoning?.efforts) ? info.reasoning.efforts : [];
        const supportedOff = efforts.some(effort => effort?.id === 'off');
        const reason = supportedOff ? 'advertised_off' : info ? 'off_not_advertised' : 'metadata_unavailable';
        if (this.diagnostics) {
          active.emit?.('call_options', {provider: safeId(config.provider), model: safeId(config.model),
            requested_effort: safeId(config.reasoningEffort),
            effective_effort: supportedOff ? 'off' : safeId(config.reasoningEffort),
            override_applied: supportedOff, override_reason: reason,
            effort_ids: efforts.map(effort => safeId(effort?.id)).filter(Boolean).slice(0, 32),
            default_effort: safeId(info?.reasoning?.defaultEffort), metadata_status: info ? 'known' : 'unknown',
            turn: payload.turn, step: payload.step});
        }
        // An unadvertised off can be rejected before the user message commits.
        // Keep the exact original request usable; this does not claim thinking is disabled.
        return supportedOff ? {...config, reasoningEffort: 'off'} : config;
      }, {prepend: true}));
      binding.disposers.push(agent.ctx.on('agent/assistant-stream', payload => {
        const active = this.current(session, agent); const metrics = active?.callPolicyMetrics; const frame = payload.frame;
        if (!this.diagnostics || payload.agent !== agent || !metrics || !frame) return;
        // The stream revision advances per frame; the attempt ID stays fixed.
        const attempt = safeId(frame.attemptId);
        const revision = frame.revision;
        if (!attempt || !Number.isSafeInteger(revision) || revision <= metrics.lastRevision) return;
        if (frame.type === 'start') {
          if (frame.turn !== active.callPolicyTurn || metrics.streams.has(attempt)) return;
          metrics.lastRevision = revision;
          metrics.streams.set(attempt, -1); metrics.attempts++;
        } else if (frame.type === 'chunk' || frame.type === 'end') {
          const previous = metrics.streams.get(attempt);
          if (previous === undefined || !Number.isSafeInteger(frame.index) || frame.index <= previous) return;
          metrics.lastRevision = revision;
          if (frame.type === 'end') {
            metrics.streams.delete(attempt);
            return;
          }
          metrics.streams.set(attempt, frame.index);
          const chunk = frame.chunk;
          if (chunk?.type === 'reasoning-delta' && typeof chunk.text === 'string') {
            metrics.thinkingChars += chunk.text.length;
            if (chunk.text && metrics.firstThinking === null) metrics.firstThinking = milliseconds(Date.now() - metrics.started);
          } else if (chunk?.type === 'text-delta' && typeof chunk.text === 'string' && chunk.text && metrics.firstText === null) {
            metrics.firstText = milliseconds(Date.now() - metrics.started);
          }
        }
      }));
    } catch (error) { this.unbind(session.id); throw error; }
  }
  finish(session, active) {
    const metrics = active.callPolicyMetrics;
    if (!this.diagnostics || !metrics || !this.isOwned(session)) return;
    active.emit?.('call_metrics', {thinking_chars: metrics.thinkingChars, first_thinking_ms: metrics.firstThinking,
      first_text_ms: metrics.firstText, elapsed_ms: milliseconds(Date.now() - metrics.started), attempts: metrics.attempts,
      source: 'agent_assistant_stream'});
  }
  unbind(id) {
    const binding = this.bindings.get(id); this.bindings.delete(id);
    for (const dispose of binding?.disposers || []) { try { dispose(); } catch {} }
  }
  dispose() { this.closed = true; for (const id of this.bindings.keys()) this.unbind(id); }
}
