import {LocalManager} from './manager.mjs';
import {registerNativePanel} from './native-panel.mjs';
import {defaultTextModel} from './proactive.mjs';
export const name = 'dsh-go-live2d-call';
const PREFIX = '/api/dsh-go-live2d/v1/';
export function apply(ctx, _config = {}, options = {}) {
  ctx.inject(['sessionController', 'credentials', 'connection', 'llm'], scoped => {
    const key = name + '/local-phone';
    const store = {async read() {const record = await scoped.credentials.readRecord(key); return record?.kind === 'grant' ? record.payload : undefined;},
      async save(payload) {await scoped.credentials.modifyRecord(key, () => ({kind: 'grant', payload}));}};
    let speechController;
    const manager = new LocalManager(scoped.sessionController, store, {...options,
      callDiagnostics: true, inspectModel: (provider, model, signal) => scoped.llm.resolveModelInfo(provider, model, signal),
      getSpeechController: options.getSpeechController || (() => speechController),
      getProactiveRoute: options.getProactiveRoute || (() => defaultTextModel(scoped))});
    scoped.on('dispose', () => manager.dispose());
    scoped.inject(['dshGo'], native => registerNativePanel(native.dshGo, manager));
    // Optional injection must never keep the text connector pending while speech starts.
    scoped.inject(['speechController'], speechScoped => {
      const current = speechScoped.speechController; speechController = current; manager.refreshSpeech();
      speechScoped.on('dispose', () => {
        if (speechController === current) {speechController = undefined; manager.refreshSpeech();}
      });
    });
    for (const [action, method] of [['status', 'GET'], ['prepare', 'GET'], ['connect', 'POST'], ['resume', 'POST'], ['disconnect', 'POST']]) {
      scoped.effect(() => scoped.connection.fetch.register({path: PREFIX + action, methods: [method], requestBody: 'buffered',
        fetch: async () => {
          if (action === 'resume') void manager.connectLocal().catch(() => manager.setStatus('connection_error'));
          return Response.json(action === 'status' || action === 'resume' ? manager.snapshot()
            : action === 'prepare' ? await manager.prepare()
              : action === 'connect' ? await manager.connectLocal() : await manager.disconnect(),
          {headers: {'cache-control': 'no-store'}});
        }}));
    }
    void manager.restore();
  });
}
