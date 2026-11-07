// Existing host peer APIs, not bundled replacement services or a WebSocket library.
import z from '@deepseek-ai/schemastery';
import { credentialKey } from '@deepseek-ai/dsh-credentials';
import { apply as install, name } from './index.js';
export { name };
export const Config = z.object({
  relay_url: z.string().default('').description('中继地址'),
  verification_code: z.string().default('').description('配对码'),
});
export function apply(ctx, config = {}) {
  return install(ctx, config, {credentialKey,
    onStatus: status => console.info('[dsh-mobile-call-adapter]', status)});
}
