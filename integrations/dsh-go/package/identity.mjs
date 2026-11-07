import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

export const LOCAL_ORIGIN = 'http://127.0.0.1:47651';
export const LOCAL_PREFIX = '/local-dsh/v1';
export const freshChallenge = () => randomBytes(32).toString('hex');
const FETCH_CAUSES = new Set(['ECONNREFUSED', 'ECONNRESET', 'ETIMEDOUT', 'ENETUNREACH', 'EHOSTUNREACH', 'ENOTFOUND', 'EAI_AGAIN',
  'UND_ERR_CONNECT_TIMEOUT', 'UND_ERR_HEADERS_TIMEOUT', 'UND_ERR_BODY_TIMEOUT', 'UND_ERR_SOCKET', 'ABORT_ERR']);
const challengeValid = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const rejected = (code, diagnostic, identity = false, httpStatus) => {
  diagnostic(code, httpStatus); const error = Error(identity ? 'local_identity_not_verified' : 'local_service_unavailable'); error.proofCode = code; return error;
};
export const challengeId = challenge => createHash('sha256').update(challenge, 'utf8').digest('hex');
export function discoveryUrl(challenge, nonce) {
  return `${LOCAL_ORIGIN}${LOCAL_PREFIX}/discover?challenge_id=${challengeId(challenge)}&nonce=${nonce}`;
}
export function verifyDiscovery(data, challenge, nonce, diagnostic = () => {}) {
  if (!challengeValid(challenge)) throw rejected('CHALLENGE_INVALID', diagnostic, true);
  if (!data || typeof data !== 'object' || Array.isArray(data)) throw rejected('ENVELOPE_INVALID', diagnostic, true);
  if (data.protocol !== 'live2d.local.v1') throw rejected('PROTOCOL_INVALID', diagnostic, true);
  if (data.app !== 'com.prts.client') throw rejected('APP_INVALID', diagnostic, true);
  if (data.nonce !== nonce) throw rejected('NONCE_MISMATCH', diagnostic, true);
  if (typeof data.bootstrap_proof !== 'string' || !/^[a-f0-9]{64}$/.test(data.bootstrap_proof)) throw rejected('PROOF_FORMAT_INVALID', diagnostic, true);
  const expected = createHmac('sha256', Buffer.from(challenge, 'utf8'))
    .update(`live2d.local.v1|discover|${challengeId(challenge)}|${nonce}`, 'utf8').digest();
  if (!timingSafeEqual(expected, Buffer.from(data.bootstrap_proof, 'hex'))) throw rejected('HMAC_MISMATCH', diagnostic, true);
  diagnostic('VERIFIED');
  return data;
}
export async function proveLocal(fetch, challenge, signal, options = {}) {
  const diagnostic = options.diagnostic || (() => {});
  if (!challengeValid(challenge)) throw rejected('CHALLENGE_INVALID', diagnostic, true);
  diagnostic('CHALLENGE_VALID');
  const deadline = AbortSignal.timeout(options.timeoutMs ?? 8000);
  const combined = signal ? AbortSignal.any([signal, deadline]) : deadline;
  const nonce = randomBytes(16).toString('hex');
  const failed = (error, fallback) => {
    const code = signal?.aborted ? 'PROOF_ABORTED' : deadline.aborted ? 'PROOF_TIMEOUT' : fallback;
    const failure = rejected(code, diagnostic);
    const cause = error?.cause?.code ?? error?.code;
    if (FETCH_CAUSES.has(cause)) diagnostic('CAUSE_' + cause);
    return failure;
  };
  let response;
  diagnostic('FETCH_BEGIN');
  try {response = await fetch(discoveryUrl(challenge, nonce), {signal: combined, redirect: 'error'});}
  catch (error) {throw failed(error, error?.name === 'TypeError' ? 'FETCH_TYPE_ERROR' : 'FETCH_NETWORK_ERROR');}
  diagnostic(response.ok ? 'FETCH_OK' : 'HTTP_REJECTED', response.status);
  if (!response.ok) throw rejected('PROOF_HTTP_REJECTED', diagnostic, false, response.status);
  let data;
  try {data = await response.json();}
  catch (error) {throw failed(error, 'JSON_INVALID');}
  diagnostic('JSON_OK');
  return verifyDiscovery(data, challenge, nonce, diagnostic);
}
