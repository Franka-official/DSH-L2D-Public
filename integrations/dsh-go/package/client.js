/* Built lazy-CJS module for the host's same-device bridge settings. */
globalThis.__ModuleLoader__.load({id: 'dsh-go-live2d-call', factory: require => {
  const React = require('react'), h = React.createElement;
  const endpoint = action => new URL('/api/dsh-go-live2d/v1/' + action, location.origin).href;
  const statuses = {
    loading: '检查连接',
    disconnected: '未连接',
    authorizing: '等待授权',
    connecting: '连接中',
    reconnecting: '重连中',
    reconnect_failed: '重连失败',
    launch_required: '开启／恢复本机桥接',
    pairing_busy: '授权处理中',
    identity_error: '身份验证失败',
    disconnecting: '断开中',
    paired: '已连接',
    offline: 'Go 引擎离线',
    dropped: '已断开',
  };
  const title = 'DSH L2D桥接';
  const ringCss = `
    .dsh-l2d-bridge-ring:focus-visible { outline: 2px solid currentColor; outline-offset: 6px; }
    .dsh-l2d-bridge-ring[data-ring-state="paired"] .dsh-l2d-glow { animation: dsh-l2d-bridge-breathe 2.5s ease-in-out infinite; }
    .dsh-l2d-bridge-ring[data-ring-state="reconnect_failed"] .dsh-l2d-stroke { animation: dsh-l2d-bridge-retry .54s ease-out 2; }
    @keyframes dsh-l2d-bridge-breathe {
      0%, 100% { stroke-opacity: .10; }
      50% { stroke-opacity: .38; }
    }
    @keyframes dsh-l2d-bridge-retry {
      0%, 68% { stroke: #e05a58; }
      100% { stroke: #8995a3; }
    }
    @media (prefers-reduced-motion: reduce) {
      .dsh-l2d-bridge-ring[data-ring-state] .dsh-l2d-glow,
      .dsh-l2d-bridge-ring[data-ring-state] .dsh-l2d-stroke { animation: none; }
    }`;
  async function request(action, method, lifetime) {
    const controller = new AbortController();
    const cancel = () => controller.abort();
    lifetime.addEventListener('abort', cancel, {once: true});
    if (lifetime.aborted) cancel();
    const timeout = setTimeout(cancel, method === 'GET' ? 1500 : 5000);
    try {
      const response = await fetch(endpoint(action), {method, signal: controller.signal, credentials: 'same-origin',
        cache: 'no-store', ...(method === 'POST' ? {headers: {'content-type': 'application/json'}, body: '{}'} : {})});
      if (!response.ok) throw Error('local_engine_unavailable');
      return await response.json();
    } finally {
      clearTimeout(timeout);
      lifetime.removeEventListener('abort', cancel);
    }
  }
  function Settings() {
    const [status, setStatus] = React.useState('loading');
    const [fingerprint, setFingerprint] = React.useState('');
    const controls = React.useRef(null);
    React.useEffect(() => {
      const session = new AbortController();
      const signal = session.signal;
      let checking = false, commanding = false, revision = 0;
      const offline = () => {setFingerprint(''); setStatus('offline');};
      const update = value => {
        const phase = value?.status;
        const code = value?.pairing_fingerprint;
        setFingerprint(phase === 'authorizing' && typeof code === 'string' && /^[A-F0-9]{8}$/.test(code) ? code : '');
        setStatus(phase === 'paired' ? (value.ready === true ? 'paired' : 'dropped')
          : Object.hasOwn(statuses, phase) ? phase : 'dropped');
      };
      const refresh = async () => {
        if (signal.aborted || document.hidden || checking || commanding) return;
        const startedAt = revision;
        checking = true;
        try {
          const value = await request('status', 'GET', signal);
          if (signal.aborted || revision !== startedAt) return;
          update(value);
        } catch {
          if (!signal.aborted && revision === startedAt) offline();
        } finally {
          checking = false;
        }
      };
      const command = async action => {
        if (signal.aborted || commanding) return;
        commanding = true;
        revision++;
        setFingerprint('');
        setStatus(action === 'disconnect' ? 'disconnecting' : 'connecting');
        try {
          const value = await request(action, 'POST', signal);
          if (!signal.aborted) update(value);
        } catch {
          if (!signal.aborted) offline();
        } finally {
          commanding = false;
        }
      };
      controls.current = command;
      void refresh();
      const timer = setInterval(refresh, 1000);
      document.addEventListener('visibilitychange', refresh);
      return () => {
        controls.current = null;
        session.abort();
        clearInterval(timer);
        document.removeEventListener('visibilitychange', refresh);
      };
    }, []);

    const label = fingerprint ? '核对标识' : statuses[status];
    const paired = status === 'paired';
    const busy = ['authorizing', 'connecting', 'reconnecting', 'disconnecting'].includes(status);
    const disabled = status === 'loading' || status === 'disconnecting';
    const ring = {className: 'dsh-l2d-bridge-ring', 'data-ring-state': status, style: {
      width: 88, height: 88, borderRadius: '50%', margin: '19px 0 15px',
      padding: 0, boxSizing: 'border-box', border: 0, appearance: 'none', display: 'inline-grid',
      placeItems: 'center', background: 'transparent', color: paired ? '#32a675' : '#8995a3',
      textDecoration: 'none', cursor: disabled ? 'default' : 'pointer', touchAction: 'manipulation',
    }};
    const icon = h('svg', {width: 88, height: 88, viewBox: '0 0 88 88', fill: 'none',
      stroke: 'currentColor', 'aria-hidden': 'true', style: {display: 'block', pointerEvents: 'none'}},
      paired && h('circle', {className: 'dsh-l2d-glow', cx: 44, cy: 44, r: 40, strokeWidth: 7, strokeOpacity: .18}),
      h('circle', {className: 'dsh-l2d-stroke', cx: 44, cy: 44, r: 39, strokeWidth: 3}),
      !paired && h('g', {transform: 'translate(29 29) scale(1.25)', strokeWidth: 1.6,
        strokeLinecap: 'round', strokeLinejoin: 'round'},
        h('path', {d: 'M10 13.5a4 4 0 0 0 5.7 0l2.8-2.8a4 4 0 0 0-5.7-5.7l-1.4 1.4'}),
        h('path', {d: 'M14 10.5a4 4 0 0 0-5.7 0l-2.8 2.8a4 4 0 0 0 5.7 5.7l1.4-1.4'})));
    const action = paired ? '断开连接' : busy ? '取消连接'
      : status === 'loading' ? '检查连接' : status === 'disconnected' ? '一键连接' : '重试连接';
    const button = h('button', {...ring, type: 'button', disabled, 'aria-label': action,
      onClick: () => void controls.current?.(paired || busy ? 'disconnect' : 'resume')}, icon);
    return h('section', {style: {fontFamily: 'inherit', textAlign: 'center', margin: '8px 0', padding: '18px 16px',
      border: '1px solid #879bb83d', borderRadius: 16, background: '#879bb80c'}},
    h('style', null, ringCss),
    h('div', {style: {fontWeight: 650, fontSize: 16}}, title),
    button,
    h('div', {role: 'status', 'aria-live': 'polite', 'aria-atomic': 'true', 'data-local-status': status},
      h('div', {style: {fontWeight: 600, fontSize: 14}}, label),
      fingerprint && h('div', {style: {font: '600 20px monospace', letterSpacing: '.16em', marginTop: 7}}, fingerprint)));
  }
  return {inject: ['slots', 'connection'], apply(ctx) {
    ctx.slots.inject('settings.section', () => ctx.slots.register({name: 'settings.section', id: 'dsh-go-live2d-local', order: 80, label: title}, Settings));
    ctx.slots.inject('plugins.bundle.config', () => ctx.slots.register({name: 'plugins.bundle.config', key: 'dsh-go-live2d-call'}, Settings));
  }};
}});
