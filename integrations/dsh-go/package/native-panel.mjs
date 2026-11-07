const busy = new Set(['authorizing', 'connecting', 'reconnecting']);
const failed = new Set(['identity_error', 'connection_error', 'relay_error', 'connection_stopped', 'protocol_error', 'reconnect_failed']);
const labels = {paired: '已连接', connecting: '连接中', reconnecting: '重连中', authorizing: '等待授权'};
const view = {v: 2, type: 'stack', children: {$: 'data.blocks'}};

export function registerNativePanel(dshGo, manager) {
  dshGo.panel('l2d-bridge', {
    view,
    data: (_scope, emit) => manager.observeStatus(() => {
      const {status, statusLabel, ready, pairing_fingerprint: fingerprint} = manager.snapshot();
      const pending = busy.has(status);
      const error = failed.has(status);
      const blocks = [{
        type: 'progress', shape: 'ring', value: ready || error ? 1 : 0, max: 1,
        tone: ready ? 'good' : error ? 'bad' : 'info', caption: 'L2D', label: labels[status] || statusLabel
      }];
      if (fingerprint) blocks.push({
        type: 'callout', tone: 'info', text: '回到 DSH L2D，核对标识并允许配对：' + fingerprint
      });
      blocks.push({type: 'actions', items: [ready ? {label: '断开连接', action: 'disconnect'}
        : pending ? {label: '取消', action: 'disconnect'}
          : {label: '一键连接', action: 'connect', style: 'primary'}]});
      emit({blocks});
    }),
    actions: {
      connect: () => {void manager.connectLocal().catch(() => manager.setStatus('connection_error'));},
      disconnect: () => {void manager.disconnect().catch(() => manager.setStatus('connection_error'));}
    }
  });
}
