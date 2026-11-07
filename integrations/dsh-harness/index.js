import { MobileCallAdapter } from './adapter.mjs';
import { MobileRelayConnector } from './relay.mjs';

export const name = 'dsh-mobile-call-adapter';

// The connection service owns admission, cookies, Host/Origin validation and
// owner-only exact fetch routes. This plugin does not manufacture a Peer.
export function apply(ctx, config = {}, host = {}) {
  ctx.inject(['connection', 'sessionController'], scoped => {
    const adapter = new MobileCallAdapter(scoped.sessionController);
    for (const route of adapter.routes()) {
      scoped.effect(() => scoped.connection.fetch.register(route), name + route.path);
    }
    scoped.on('dispose', () => adapter.dispose());
    if (config.relay_url) {
      if (typeof host.credentialKey !== 'function') throw new Error('credential_host_entry_required');
      scoped.inject(['credentials'], relayScope => {
        const key = host.credentialKey(name, 'mobile-relay');
        const store = {
          async read() {
            const record = await relayScope.credentials.readRecord(key);
            return record?.kind === 'grant' ? record.payload : undefined;
          },
          async save(payload) {
            await relayScope.credentials.modifyRecord(key, async () => ({kind: 'grant', payload}));
          },
        };
        const connector = new MobileRelayConnector(relayScope.sessionController, store, config,
          {onStatus: host.onStatus, WebSocket: host.WebSocket});
        relayScope.on('dispose', () => connector.dispose());
        void connector.start().catch(() => host.onStatus?.('relay_start_failed'));
      });
    }
  });
}
