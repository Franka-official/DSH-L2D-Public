"""Outbound paired mobile-call connector; no inbound port or QQ dispatch.

Only the relay address/code are user settings. The relay token and generated
chat-only credential live in this plugin's private data directory, never logs.
"""
from __future__ import annotations

import asyncio
from collections import OrderedDict
import json
import logging
import os
from pathlib import Path
import re
import stat
import tempfile
from urllib.parse import urlsplit, urlunsplit

from .connector_chat import ChatContractError, ChatOperation, OfficialChatClient, upstream_session

LOGGER = logging.getLogger('astrbot')
IDENTIFIER = re.compile(r'[A-Za-z0-9._:-]{1,160}\Z')
CODE = re.compile(r'[A-Za-z0-9]{4}-[A-Za-z0-9]{4}\Z')
CHAT_KEY = re.compile(r'abk_[A-Za-z0-9_-]{12,252}\Z')


class ConnectorError(RuntimeError):
    """Constant safe diagnostic, never include settings, incoming frames or key values."""


def relay_endpoint(value):
    if not isinstance(value, str):
        raise ConnectorError('invalid_relay_address')
    parsed = urlsplit(value.strip())
    if (parsed.scheme not in {'https', 'wss'} or not parsed.netloc or parsed.username or parsed.password
            or parsed.query or parsed.fragment or parsed.path not in {'', '/', '/mobile-call/v1/connector'}):
        raise ConnectorError('invalid_relay_address')
    return urlunsplit(('wss', parsed.netloc, '/mobile-call/v1/connector', '', ''))


class PrivateState:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        if os.name != 'nt':
            self.directory.chmod(0o700)
        self.path = self.directory / 'connector-private.json'
        self.data = {}
        if self.path.exists():
            if self.path.is_symlink() or self.path.stat().st_size > 262144:
                raise ConnectorError('invalid_private_state')
            if os.name != 'nt' and stat.S_IMODE(self.path.stat().st_mode) & 0o077:
                raise ConnectorError('private_state_permissions')
            try:
                self.data = json.loads(self.path.read_text(encoding='utf-8'))
            except (ValueError, OSError):
                raise ConnectorError('invalid_private_state') from None
            if not isinstance(self.data, dict):
                raise ConnectorError('invalid_private_state')

    def save(self):
        descriptor, temporary = tempfile.mkstemp(prefix='.connector-', suffix='.tmp', dir=self.directory)
        try:
            if os.name != 'nt':
                os.fchmod(descriptor, 0o600)
            with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
                json.dump(self.data, stream, ensure_ascii=False, allow_nan=False)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def ledger(self):
        values = self.data.get('requests', [])
        if not isinstance(values, list):
            raise ConnectorError('invalid_request_ledger')
        return OrderedDict((x['id'], x) for x in values if isinstance(x, dict) and isinstance(x.get('id'), str))

    def record(self, request_id, session_id, state):
        ledger = self.ledger()
        ledger[request_id] = {'id': request_id, 'session_id': session_id, 'state': state}
        while len(ledger) > 128:
            first = next(iter(ledger))
            if ledger[first].get('state') not in {'completed', 'cancelled', 'failed_stopped'}:
                raise ConnectorError('unresolved_request_ledger_full')
            ledger.pop(first)
        self.data['requests'] = list(ledger.values())
        self.save()


async def ensure_chat_identity(context, store, *, key_service=None):
    identity = store.data.get('chat_identity')
    if isinstance(identity, dict):
        key, key_id = identity.get('api_key'), identity.get('key_id')
        if isinstance(key, str) and CHAT_KEY.fullmatch(key) and isinstance(key_id, str) and IDENTIFIER.fullmatch(key_id):
            if identity.get('scopes') == ['chat']:
                return identity
        raise ConnectorError('invalid_saved_chat_identity')
    if key_service is None:
        # Actual v4.29.3 API: ApiKeyService(db), not an invented APIKeyManager.
        from astrbot.dashboard.services.api_key_service import ApiKeyService
        key_service = ApiKeyService(context._db)
    created = await key_service.create_api_key(
        {'name': 'Live2D paired mobile connector', 'scopes': ['chat']},
        created_by='plugin:astrbot_plugin_live2d_call')
    if (not isinstance(created, dict) or created.get('scopes') != ['chat'] or
            not isinstance(created.get('key_id'), str) or not IDENTIFIER.fullmatch(created['key_id']) or
            not isinstance(created.get('api_key'), str) or not CHAT_KEY.fullmatch(created['api_key'])):
        if isinstance(created, dict) and isinstance(created.get('key_id'), str) and IDENTIFIER.fullmatch(created['key_id']):
            await key_service.revoke_api_key(created['key_id'])
        raise ConnectorError('chat_identity_creation_contract')
    identity = {key: created[key] for key in ('key_id', 'api_key', 'scopes')}
    store.data['chat_identity'] = identity
    try:
        store.save()
    except Exception:
        await key_service.revoke_api_key(identity['key_id'])
        raise ConnectorError('chat_identity_private_save_failed') from None
    return identity


class OutboundConnector:
    def __init__(self, endpoint, verification_code, store, chat, *, on_paired=None):
        self.endpoint, self.verification_code, self.store, self.chat = endpoint, verification_code, store, chat
        self.on_paired = on_paired
        self.principal = chat.principal
        self.binding_id = None
        self.closed = False
        self.worker = None
        self.websocket = None
        self.operation = None
        self.quarantined = any(x.get('state') not in {'completed', 'cancelled', 'failed_stopped'} for x in store.ledger().values())
        self._send_lock = asyncio.Lock()

    @classmethod
    async def prepare(cls, context, config, directory, *, key_service=None, http_client=None):
        endpoint = relay_endpoint(config.get('relay_url', ''))
        profiles = config.get('allowed_profile_ids', [])
        if (not isinstance(profiles, list) or len(profiles) != 1 or profiles[0] == 'default'
                or not isinstance(profiles[0], str) or not profiles[0]):
            raise ConnectorError('one_dedicated_mobile_profile_required')
        store = PrivateState(directory)
        code = config.get('verification_code', '').strip()
        saved_endpoint = store.data.get('relay_endpoint')
        if saved_endpoint and saved_endpoint != endpoint and not CODE.fullmatch(code):
            # An existing binding's token is never sent to a different relay.
            raise ConnectorError('relay_changed_requires_explicit_unpair')
        token = store.data.get('connector_token')
        if not token and not CODE.fullmatch(code):
            raise ConnectorError('verification_code_required')
        identity = await ensure_chat_identity(context, store, key_service=key_service)
        dashboard = getattr(context, '_config', {}).get('dashboard', {})
        port = dashboard.get('port', 6185) if isinstance(dashboard, dict) else 6185
        if type(port) is not int or not 1 <= port <= 65535:
            raise ConnectorError('local_dashboard_port_invalid')
        chat = OfficialChatClient('http://127.0.0.1:' + str(port), identity['api_key'],
            'api_key:' + identity['key_id'], profiles[0], client=http_client)
        try:
            await chat.ready()
        except Exception:
            await chat.close()
            raise ConnectorError('local_mobile_chat_preparation_failed') from None
        async def clear_code():
            if config.get('verification_code') == code and code:
                config['verification_code'] = ''
                save = getattr(config, 'save_config_async', None)
                if callable(save):
                    await save()
                else:
                    save = getattr(config, 'save_config', None)
                    if callable(save):
                        save()
        return cls(endpoint, code, store, chat, on_paired=clear_code)

    def start(self):
        if self.worker is None:
            self.worker = asyncio.create_task(self.run(), name='live2d-mobile-outbound-connector')

    async def send(self, frame):
        websocket = self.websocket
        if websocket is None or websocket.closed:
            return
        encoded = json.dumps(frame, ensure_ascii=False, allow_nan=False)
        if len(encoded.encode('utf-8')) > 262144:
            raise ConnectorError('outbound_frame_too_large')
        async with self._send_lock:
            await websocket.send_str(encoded)

    def hello(self):
        frame = {'type': 'hello', 'mode': 'astrbot'}
        if CODE.fullmatch(self.verification_code):
            frame['verification_code'] = self.verification_code
        else:
            token = self.store.data.get('connector_token')
            if not isinstance(token, str) or not token:
                raise ConnectorError('connector_pairing_credential_missing')
            frame['connector_token'] = token
        return frame

    async def error_done(self, request_id, code):
        await self.send({'type': 'event', 'request_id': request_id,
                         'event': {'type': 'error', 'code': code, 'message': '这次手机通话请求没有完成，请检查连接器状态。'}})
        await self.send({'type': 'done', 'request_id': request_id, 'success': False})

    async def paired(self, frame):
        binding, token = frame.get('binding_id'), frame.get('connector_token')
        if (not isinstance(binding, str) or not IDENTIFIER.fullmatch(binding) or
                not isinstance(token, str) or not 16 <= len(token) <= 2048 or any(x.isspace() for x in token)):
            raise ConnectorError('relay_pairing_contract')
        previous = self.store.data.get('binding_id')
        if previous and previous != binding and not CODE.fullmatch(self.verification_code):
            raise ConnectorError('relay_binding_changed')
        self.store.data.update(relay_endpoint=self.endpoint, binding_id=binding, connector_token=token)
        self.store.save()
        self.binding_id = binding
        self.verification_code = ''
        if self.on_paired:
            await self.on_paired()
        LOGGER.info('Live2DCall: outbound connector paired')

    async def accept(self, frame):
        if not isinstance(frame, dict):
            raise ConnectorError('relay_invalid_frame')
        kind = frame.get('type')
        if kind == 'paired':
            await self.paired(frame)
            return
        if kind == 'ping':
            await self.send({'type': 'pong'})
            return
        if kind == 'cancel':
            request_id = frame.get('request_id')
            if isinstance(request_id, str) and IDENTIFIER.fullmatch(request_id):
                await self.cancel(request_id)
            return
        if kind != 'request' or self.binding_id is None:
            raise ConnectorError('relay_request_before_pairing')
        request_id, session_id, text = frame.get('request_id'), frame.get('session_id'), frame.get('text')
        language = frame.get('reply_language', 'zh')
        if not isinstance(request_id, str) or not IDENTIFIER.fullmatch(request_id):
            raise ConnectorError('relay_invalid_request_id')
        if (not isinstance(session_id, str) or not IDENTIFIER.fullmatch(session_id) or
                not isinstance(text, str) or not text.strip() or len(text) > 8000 or
                len(text.encode('utf-8')) > 32000 or not isinstance(language, str) or language not in {'zh', 'ja', 'en'}):
            await self.error_done(request_id, 'invalid_mobile_request')
            return
        if request_id in self.store.ledger():
            await self.error_done(request_id, 'duplicate_request_not_replayed')
            return
        if self.operation is not None or self.quarantined:
            await self.error_done(request_id, 'connector_busy_or_cleanup_unconfirmed')
            return
        operation = ChatOperation(request_id, upstream_session(self.binding_id, session_id))
        # Latch before the only chat POST. Process/socket restarts must not replay it.
        self.store.record(request_id, operation.session_id, 'dispatched')
        self.operation = operation
        operation.task = asyncio.create_task(self.serve(operation, text, language), name='live2d-mobile-request')

    async def serve(self, operation, text, language):
        try:
            await self.send({'type': 'event', 'request_id': operation.request_id, 'event': {'type': 'status', 'status': 'running'}})
            if operation.cancelled:
                return
            result = await self.chat.generate(operation, text, language)
            if operation.cancelled:
                return
            self.store.record(operation.request_id, operation.session_id, 'completed')
            await self.send({'type': 'event', 'request_id': operation.request_id, 'event': {'type': 'text', 'text': result}})
            await self.send({'type': 'done', 'request_id': operation.request_id, 'success': True})
        except asyncio.CancelledError:
            raise
        except Exception:
            stopped = await self.chat.stop_confirmed(operation)
            self.quarantined |= not stopped
            self.store.record(operation.request_id, operation.session_id, 'failed_stopped' if stopped else 'unknown_cleanup')
            if not self.closed:
                await self.error_done(operation.request_id, 'local_chat_failed' if stopped else 'local_chat_stop_unconfirmed')
            LOGGER.warning('Live2DCall: connector request failed; owned cleanup confirmed=%s', stopped)
        finally:
            if self.operation is operation:
                self.operation = None

    async def cancel(self, request_id):
        operation = self.operation
        if operation is None or operation.request_id != request_id:
            # No current owned host operation: never falsely confirm an unknown request.
            await self.send({'type': 'cancelled', 'request_id': request_id, 'confirmed': False})
            return
        confirmed = await self.chat.stop_confirmed(operation)
        self.quarantined |= not confirmed
        self.store.record(request_id, operation.session_id, 'cancelled' if confirmed else 'unknown_cleanup')
        if confirmed and operation.task is not None:
            operation.task.cancel()
            await asyncio.gather(operation.task, return_exceptions=True)
        await self.send({'type': 'cancelled', 'request_id': request_id, 'confirmed': confirmed})

    async def run(self):
        import aiohttp  # Already an AstrBot runtime dependency; not needed by offline policy/hooks.
        timeout = aiohttp.ClientTimeout(total=None, connect=15, sock_read=None)
        async with aiohttp.ClientSession(timeout=timeout, trust_env=False) as client:
            delay = 2
            while not self.closed:
                try:
                    async with client.ws_connect(self.endpoint, heartbeat=25, max_msg_size=65536) as websocket:
                        self.websocket = websocket
                        self.binding_id = None
                        await self.send(self.hello())
                        delay = 2
                        async for message in websocket:
                            if message.type == aiohttp.WSMsgType.TEXT:
                                try:
                                    frame = json.loads(message.data)
                                except ValueError:
                                    raise ConnectorError('relay_invalid_json') from None
                                await self.accept(frame)
                            elif message.type in {aiohttp.WSMsgType.ERROR, aiohttp.WSMsgType.CLOSED}:
                                break
                except asyncio.CancelledError:
                    raise
                except Exception:
                    LOGGER.warning('Live2DCall: outbound connector disconnected; no prompt is replayed')
                finally:
                    self.websocket = None
                    if self.operation is not None:
                        await self.cancel(self.operation.request_id)
                if not self.closed:
                    await asyncio.sleep(delay)
                    delay = min(delay * 2, 30)

    async def close(self):
        self.closed = True
        if self.operation is not None:
            await self.cancel(self.operation.request_id)
        if self.websocket is not None:
            await self.websocket.close()
        if self.worker is not None:
            self.worker.cancel()
            await asyncio.gather(self.worker, return_exceptions=True)
        await self.chat.close()
