"""Mobile connector transport through AstrBot's normal chat-scoped OpenAPI.

Contracts reviewed against AstrBot 4.29.3 dashboard ChatService, ApiKeyService,
and the installed /api/v1/chat SSE and owned session stop/state contract.
No provider is called directly and no local persona/history is reconstructed.
"""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import json
import re
import time
import uuid

import httpx


class ChatContractError(RuntimeError):
    """A constant safe error code, never a provider body, credential, or URL."""


LANGUAGE_NOTES = {'zh': '[本轮回复语言：zh] 本轮直接用中文回答。',
                  'ja': '[本轮回复语言：ja] 本轮直接用日语回答。',
                  'en': '[本轮回复语言：en] 本轮直接用英语回答。'}


def language_message(text, language):
    if language not in LANGUAGE_NOTES:
        raise ChatContractError('unsupported_reply_language')
    if language in {'ja', 'en'} and text.startswith(f'[手机双语字幕:v1;target={language}]\n'):
        return text
    note = LANGUAGE_NOTES[language]
    for previous in LANGUAGE_NOTES.values():
        if text == previous:
            return note
        if text.startswith(previous + '\n'):
            text = text[len(previous) + 1:]
            break
    return note + '\n' + text


@dataclass
class ChatOperation:
    request_id: str
    session_id: str
    cancelled: bool = False
    dispatched: bool = False
    opening: asyncio.Task | None = None
    task: asyncio.Task | None = None
    finished: asyncio.Event = field(default_factory=asyncio.Event)


def upstream_session(binding_id: str, phone_session: str) -> str:
    # Stable across connector restarts; ownership is still enforced by chat Key.
    return str(uuid.uuid5(uuid.NAMESPACE_URL, 'live2d-call:' + binding_id + ':' + phone_session))


def _data(response):
    if response.status_code != 200:
        raise ChatContractError('local_chat_http_' + str(response.status_code))
    try:
        value = response.json()
    except ValueError:
        raise ChatContractError('local_chat_invalid_json') from None
    if not isinstance(value, dict) or value.get('status') == 'error':
        raise ChatContractError('local_chat_rejected')
    value = value.get('data', value)
    if not isinstance(value, dict):
        raise ChatContractError('local_chat_invalid_data')
    return value


async def sse_objects(response):
    """Bounded UTF-8 SSE frames; comments are keepalives, not dialogue."""
    buffer = b''
    fields = []
    async for chunk in response.aiter_bytes():
        buffer += chunk
        if len(buffer) > 131072:
            raise ChatContractError('local_chat_frame_too_large')
        while b'\n' in buffer:
            raw, buffer = buffer.split(b'\n', 1)
            line = raw.rstrip(b'\r')
            if line.startswith(b':'):
                continue
            if not line:
                if fields:
                    try:
                        value = json.loads(b'\n'.join(fields).decode('utf-8'))
                    except (ValueError, UnicodeError):
                        raise ChatContractError('local_chat_invalid_sse') from None
                    if not isinstance(value, dict) or not isinstance(value.get('type'), str):
                        raise ChatContractError('local_chat_invalid_event')
                    yield value
                    fields = []
            elif line.startswith(b'data:'):
                fields.append(line[5:].lstrip(b' '))
                if sum(map(len, fields)) > 131072:
                    raise ChatContractError('local_chat_frame_too_large')
            elif line not in (b'event: message',):
                raise ChatContractError('local_chat_unsupported_sse_field')
    if buffer.strip() or fields:
        raise ChatContractError('local_chat_truncated_sse')


class OfficialChatClient:
    def __init__(self, origin, key, principal, profile_id, *, client=None):
        self.origin, self.principal, self.profile_id = origin, principal, profile_id
        self.headers = {'Authorization': 'Bearer ' + key, 'Accept': 'text/event-stream'}
        self.client = client or httpx.AsyncClient(trust_env=False, follow_redirects=False,
            timeout=httpx.Timeout(connect=10, read=3600, write=20, pool=10))
        self.owns_client = client is None

    async def ready(self):
        # Read-only preparation verifies chat scope and the exact selected mobile profile.
        data = _data(await self.client.get(self.origin + '/api/v1/chat/configs', headers=self.headers, timeout=15))
        items = data.get('info_list')
        if not isinstance(items, list) or not any(isinstance(x, dict) and x.get('id') == self.profile_id for x in items):
            raise ChatContractError('selected_mobile_profile_unavailable')

    async def generate(self, operation: ChatOperation, text: str, reply_language: str) -> str:
        payload = {'username': self.principal, 'session_id': operation.session_id,
            'config_id': self.profile_id, 'enable_streaming': True,
            'flags': {'enable_reasoning': False},
            'message': language_message(text, reply_language)}
        response = None
        pieces, ended, chars = [], False, 0
        request = self.client.build_request('POST', self.origin + '/api/v1/chat', headers=self.headers, json=payload)
        operation.dispatched = True
        operation.opening = asyncio.create_task(self.client.send(request, stream=True))
        try:
            response = await asyncio.shield(operation.opening)
            if response.status_code != 200:
                raise ChatContractError('local_chat_http_' + str(response.status_code))
            if response.headers.get('content-type', '').split(';', 1)[0].strip() != 'text/event-stream':
                raise ChatContractError('local_chat_expected_sse')
            async for event in sse_objects(response):
                kind = event['type']
                if kind == 'error':
                    raise ChatContractError('local_chat_run_failed')
                if kind == 'session_id' and event.get('session_id') != operation.session_id:
                    raise ChatContractError('local_chat_session_changed')
                if kind == 'plain' and event.get('chain_type') not in {'reasoning', 'tool_call', 'tool_result', 'tool_call_result'}:
                    if ended:
                        raise ChatContractError('local_chat_text_after_end')
                    value = event.get('data')
                    if not isinstance(value, str):
                        raise ChatContractError('local_chat_nontext_reply')
                    chars += len(value)
                    if chars > 64000:
                        raise ChatContractError('local_chat_reply_too_large')
                    pieces.append(value)
                elif kind == 'end':
                    ended = True
            if not ended:
                raise ChatContractError('local_chat_missing_end')
            result = re.sub(r'<think\b[^>]*>.*?(?:</think\s*>|\Z)', '', ''.join(pieces), flags=re.I | re.S).strip()
            if not result:
                raise ChatContractError('local_chat_no_final_text')
            return result
        except httpx.HTTPError:
            raise ChatContractError('local_chat_connection_failed') from None
        finally:
            if response is not None:
                await response.aclose()
            operation.finished.set()

    async def stop_confirmed(self, operation: ChatOperation) -> bool:
        operation.cancelled = True
        if not operation.dispatched:
            return True
        # Await the *same* POST's acceptance, never retry an unknown prompt.
        if operation.opening is not None:
            try:
                response = await asyncio.wait_for(asyncio.shield(operation.opening), 10)
                if response.status_code in {400, 401, 403, 404, 409, 422, 429}:
                    return True  # Explicit request rejection, not an unknown server/transport error.
            except (asyncio.TimeoutError, httpx.HTTPError):
                return False
        deadline = time.monotonic() + 15
        for _ in range(16):
            try:
                _data(await self.client.post(self.origin + '/api/v1/chat/sessions/' + operation.session_id + '/stop',
                    headers=self.headers, timeout=5))
                state = _data(await self.client.get(self.origin + '/api/v1/chat/sessions/' + operation.session_id,
                    params={'page': 1, 'page_size': 1}, headers=self.headers, timeout=5))
                runs, running = state.get('active_runs'), state.get('is_running')
                if not isinstance(runs, list) or type(running) is not bool:
                    return False
                if not runs and not running:
                    return True
            except (httpx.HTTPError, ChatContractError):
                return False
            if time.monotonic() >= deadline:
                break
            await asyncio.sleep(.25)
        return False

    async def close(self):
        if self.owns_client:
            await self.client.aclose()
