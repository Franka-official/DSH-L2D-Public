# live2d.call.v1 / DeepSeek Harness

## Official authentication, before plugin APIs

The configured URL is the actual DSH root origin. The key is the current DSH App Key/launch token, **not** an OpenAI Bearer token.

1. Send `GET /connect?token=<URL-encoded key>&interactions=none` to that root.
2. Disable HTTP redirects. A valid official response is `302` with `Set-Cookie` and `Location: ./api/remote.mux`.
3. Retain the cookie privately, using an HTTP cookie jar with its original authority/path/security rules. Do not follow the redirect as an ordinary GET to a WebSocket endpoint.
4. Send the cookie on all following plugin HTTP/SSE calls to the same authority. Do not send an Authorization Bearer header instead.
5. An expired cookie/runtime generation can yield401. The client may renew authentication but must never automatically replay an unknown prompt result. A different authority needs its own valid credential.

The host's exact fetch route implementation admits authenticated owner principals only. The plugin has no owner bypass. `interactions=none` deliberately leaves native approvals/questions in DSHA's normal interface. Keep a normal DSHA client open when interactive tools are needed.

## Preparation: no inference

`GET /api/mobile-call/v1/capabilities`

```json
{"protocol":"live2d.call.v1","provider":"deepseek_harness","runtime":"dsh-v0.2.1-rc.4","supports_native_approvals":false,"supports_resume":false,"text_delivery":"committed_final","max_text_chars":8000,"emotions":["neutral","happy","sad","angry","surprised","thinking","shy"]}
```

`POST /api/mobile-call/v1/session`, `Content-Type: application/json`, body `{}`.

```json
{"session_id":"actual-created-session-id"}
```

Both calls are non-inference. Session creation inherits the host's current default configuration; arbitrary cwd, model, preset, permissions, or history are not accepted. Only sessions returned by this instance of the plugin can be used.

## Prompt and SSE

`POST /api/mobile-call/v1/prompt`, JSON:

```json
{"session_id":"actual-created-session-id","request_id":"client-generated-stable-uuid","text":"Hello"}
```

`session_id` and `request_id` are1–160 ASCII characters, beginning alphanumeric and then alphanumeric/`.`/`_`/`:`/`-`. Text must be nonblank, <=8000 UTF-16 characters; total encoded JSON <=20000 bytes. Unknown fields are rejected. Never retry a submission with unknown outcome or mint a fresh ID to conceal a retry.

Successful admission returns `Content-Type: text/event-stream; charset=utf-8`. Every event has one JSON data object:

```text
event: status
data: {"status":"running"}

event: status
data: {"status":"needs_interaction","message":"Open this session in DSHA to review the request."}

event: emotion
data: {"emotion":"happy"}

event: text
data: {"text":"Hello phone.","delta":false}

event: status
data: {"status":"completed"}

event: done
data: {"session_id":"actual-created-session-id","request_id":"client-generated-stable-uuid"}
```

`needs_interaction` may later return to `running`; do not auto-approve or synthesize a user response. It does not mean the API supports native approvals. Only the current turn's last committed assistant text is returned, after `turn/end.reason.kind=completed`. This release does not stream partial spoken text. Thinking, tool content and model attempts are not text events.

The only recognized optional leading text tags are `[neutral]`, `[happy]`, `[sad]`, `[angry]`, `[surprised]`, `[thinking]`, `[shy]`. That single leading tag is removed into `emotion`; absent means neutral. Models/presets are not rewritten to force tags. Emotion is semantic, not a model-specific expression index or filename.

SSE comments `: keepalive` may arrive every15 seconds; ignore them. No event data contains a credential. Do not log the connect token URL, cookie, or full HTTP exception objects that include credentials.

## Failure and cancellation

Pre-stream failures are JSON `{"error":{"code":"...","message":"..."}}` with HTTP4xx/5xx. Typical cases: unknown_session404, session_busy409, request_already_seen409, session_closed_create_new409, body_too_large413.

Errors after stream admission are:

```text
event: error
data: {"code":"submission_unknown_result","message":"No automatic retry. Open the session in DSHA to check its result."}

event: done
data: {"session_id":"...","request_id":"..."}
```

`done` ends transport handling; it does **not** imply success. Successful speech requires completed status and no error. Validate both IDs in done. Error codes include `follow_not_ready` (no prompt submitted), `submission_unknown_result`, `follow_failed_unknown_result`, `turn_max-tokens`, `turn_error`, `interaction_required`, `empty_final_response`, `cancelled`. Internal stack traces are not exposed.

`POST /api/mobile-call/v1/cancel`, JSON `{"session_id":"..."}` → `{"accepted":true}`. This acknowledges cancellation, not a fabricated stopped status. The plugin retains its active lock until the real turn/end. The cancelled session is closed for further plugin prompts; create a new one for the next call. Closing the SSE transport or aborting its HTTP request also requests cancellation of that owned turn.

The official cancel only affects an active turn, not pending inbox. Therefore if cancellation happens after prompt acceptance but before the actual turn begins, the plugin watches turn/start and asks cancel again. It never resubmits prompt. On host failure, no completion/cancellation can be guaranteed: inspect the original session in DSHA, do not retry automatically.

## Deliberate limitations

- No resume across plugin/runtime restarts, no existing session adoption or multi-user identity UI.
- One active input per plugin-created session; limits32 open sessions and256 requests per session. Cancel unused sessions to close them.
- No approval/answer endpoint; use DSHA's original interface.
- No TTS/STT/audio/image upload inside this package. The existing phone backend supplies speech and Live2D mapping.
- No OpenAI-compatible DSH endpoint, no new listening port, no tool privilege expansion.

# Outbound pairing carrier (1.1.0)

Only this carrier uses the public relay. No host HTTP listener is added. The
existing authenticated HTTP/SSE contract below is preserved independently.

Connect to `wss://<relay-origin>/mobile-call/v1/connector`, then first-message
authenticate (never query parameters):

```json
{"type":"hello","mode":"deepseek_harness","verification_code":"XXXX-XXXX"}
```

After pairing, persist the returned `{binding_id, connector_token}` through the
host credential record API, scoped to this plugin and relay origin. Subsequent
connections send `connector_token` instead of `verification_code`.

The relay sends `{type:"request",request_id,session_id,text,reply_language}`;
reply_language is `zh`, `ja` or `en`. An opt-in bilingual caption instruction is
carried in the same text turn and must not be replaced by a single-language wrapper.
The session_id is a phone-owned mapping key, not
an existing DSH session to adopt. The plugin adds a short spoken-body/language
context to this dedicated session's user prompt; it does not edit host persona,
model defaults or permissions. Text still requires official committed completion.

For paired relay calls, the final text may begin with canonical emotion and gesture tags. Gesture tags are `[nod]`, `[shake]`, `[bow]`, `[sway]`, `[raise-hand]` and `[lower-hand]`. L2D uses these tags for expression and motion.

Responses are `{type:"event",request_id,event:{type:"text",text}}` and
`{type:"event",request_id,event:{type:"status",status}}`, followed by
`{type:"done",request_id,success:true|false}`. No reasoning/tool output is sent.

On `{type:"cancel",request_id}`, the plugin reports
`{type:"cancelled",request_id,confirmed:true|false}`. `true` means either no
prompt was submitted or official `turn/end` was observed. An accepted cancel,
timeout, unknown request or broken follow never becomes a false confirmation.
The 10-second cancellation observation wait is not an agent task deadline.
Disconnect cancels owned work, but does not replay requests. Reconnection itself
is bounded to three attempts, with 1/2/4 second delays; requests are not retried.

