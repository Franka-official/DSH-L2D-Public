import {request as httpRequest} from 'node:http';
import {Buffer} from 'node:buffer';

const ORIGIN = 'http://127.0.0.1:47651';
const PREFIX = '/local-dsh/v1';
const ROUTES = new Map([['/discover', 'GET'], ['/pair/begin', 'POST'], ['/pair/claim', 'POST'], ['/connector/status', 'GET'],
  ['/connector/poll', 'GET'], ['/connector/event', 'POST']]);
const LIMIT = 4 * 1024 * 1024;
const failure = code => Object.assign(new Error(code), {code});

/** Dedicated fixed-loopback HTTP. It never consults proxy env, global fetch, DNS, or a shared agent. */
export async function localHttpFetch(raw, options = {}) {
  const url = new URL(raw);
  const path = url.pathname.slice(PREFIX.length), method = (options.method || 'GET').toUpperCase();
  if (url.origin !== ORIGIN || url.username || url.password || url.hash || !url.pathname.startsWith(PREFIX) || ROUTES.get(path) !== method) throw failure('LOCAL_TARGET_REJECTED');
  const body = options.body === undefined ? null : typeof options.body === 'string' ? Buffer.from(options.body, 'utf8') :
    Buffer.isBuffer(options.body) || options.body instanceof Uint8Array ? Buffer.from(options.body) : null;
  if ((options.body !== undefined && body === null) || (body && (method !== 'POST' || body.length > LIMIT))) throw failure('LOCAL_BODY_REJECTED');
  const headers = {'connection': 'close', 'accept-encoding': 'identity'};
  for (const [key, value] of new Headers(options.headers || {})) {
    if (!['authorization', 'content-type', 'accept', 'cache-control'].includes(key)) throw failure('LOCAL_HEADER_REJECTED');
    headers[key] = value;
  }
  if (method === 'POST') headers['content-length'] = String(body?.length || 0);
  const deadline = AbortSignal.timeout(options.timeoutMs ?? 35000);
  const signal = options.signal ? AbortSignal.any([options.signal, deadline]) : deadline;
  signal.throwIfAborted();
  return new Promise((resolve, reject) => {
    let settled = false;
    const fail = error => {if (!settled) {settled = true; reject(error);}};
    const outgoing = httpRequest({protocol: 'http:', hostname: '127.0.0.1', port: 47651,
      path: url.pathname + url.search, method, headers, agent: false, signal}, incoming => {
      const encoding = incoming.headers['content-encoding'];
      if (encoding && encoding !== 'identity') {const error = failure('LOCAL_ENCODING_REJECTED'); fail(error); incoming.destroy(error); outgoing.destroy(error); return;}
      let size = 0; const parts = [];
      incoming.on('data', part => {
        size += part.length;
        if (size > LIMIT) {const error = failure('LOCAL_RESPONSE_TOO_LARGE'); fail(error); incoming.destroy(error); outgoing.destroy(error); return;}
        parts.push(part);
      });
      incoming.once('error', fail);
      incoming.once('aborted', () => fail(failure('LOCAL_RESPONSE_ABORTED')));
      incoming.once('end', () => {
        if (settled) return;
        const status = incoming.statusCode;
        if (!Number.isInteger(status) || status < 200 || status > 599) {fail(failure('LOCAL_STATUS_REJECTED')); return;}
        try {
          const result = new Response([204, 205, 304].includes(status) ? null : Buffer.concat(parts, size), {status,
            headers: incoming.headers});
          settled = true; resolve(result);
        } catch {fail(failure('LOCAL_RESPONSE_INVALID'));}
      });
    });
    outgoing.once('error', fail);
    outgoing.end(body || undefined);
  });
}
