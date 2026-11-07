"""Offline execution of actual outbound connector and formal HTTP transport.

No socket, model, QQ event or production credential is used. HTTP MockTransport
implements the reviewed installed API responses, not a second connector.
"""
import asyncio
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest

import httpx

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'outbound_connector_test'
package = types.ModuleType(PACKAGE)
package.__path__ = [str(ROOT)]
sys.modules[PACKAGE] = package


def load(name):
    spec = importlib.util.spec_from_file_location(PACKAGE + '.' + name, ROOT / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


chat_module = load('connector_chat')
connector_module = load('connector')


class BilingualLanguageTests(unittest.TestCase):
    def test_en_and_bilingual_instruction_is_not_overridden_by_pure_language_note(self):
        self.assertEqual(chat_module.language_message('hello', 'en'), chat_module.LANGUAGE_NOTES['en'] + '\nhello')
        for language in ('ja', 'en'):
            text = f'[手机双语字幕:v1;target={language}]\n同一回复两份字幕。\n你好'
            self.assertEqual(chat_module.language_message(text, language), text)


class WS:
    closed = False
    def __init__(self):
        self.frames = []
    async def send_str(self, value):
        self.frames.append(json.loads(value))
    async def close(self):
        self.closed = True


class Chunks(httpx.AsyncByteStream):
    def __init__(self, events, *, entered=None, release=None):
        self.value = b': heartbeat\n\n' + b''.join(('data: ' + json.dumps(event, ensure_ascii=False) + '\n\n').encode('utf-8') for event in events)
        self.entered, self.release = entered, release
    async def __aiter__(self):
        if self.entered:
            self.entered.set()
        if self.release:
            await self.release.wait()
        for byte in self.value:
            yield bytes([byte])
            await asyncio.sleep(0)


class Keys:
    def __init__(self):
        self.calls = []
    async def create_api_key(self, payload, *, created_by):
        self.calls.append((payload, created_by))
        return {'key_id': 'fixture-key-id', 'api_key': 'abk_offline_fixture', 'scopes': ['chat']}
    async def revoke_api_key(self, key_id):
        self.calls.append(('revoke', key_id))


class ConnectorTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.store = connector_module.PrivateState(Path(self.directory.name) / 'private')
        self.calls = []
        self.events = [{'type': 'plain', 'chain_type': 'reasoning', 'data': 'PRIVATE_THINKING', 'streaming': True},
            {'type': 'complete'}, {'type': 'plain', 'data': '[happy]你好。', 'streaming': True},
            {'type': 'complete'}, {'type': 'plain', 'chain_type': 'tool_call_result', 'data': 'PRIVATE_TOOL'},
            {'type': 'plain', 'data': '听得到。', 'streaming': False}, {'type': 'end'}]
        self.state = {'active_runs': [], 'is_running': False}
        self.entered, self.release = None, None
        self.status = 200
        async def handler(request):
            self.calls.append(request)
            if request.url.path == '/api/v1/chat/configs':
                return httpx.Response(200, json={'status': 'ok', 'data': {'info_list': [{'id': 'phone-profile'}, {'id': 'default'}]}})
            if request.url.path.endswith('/stop'):
                if self.release:
                    self.release.set()
                return httpx.Response(200, json={'status': 'ok', 'data': {'stopped_count': 1}})
            if request.method == 'GET':
                return httpx.Response(200, json={'status': 'ok', 'data': self.state})
            return httpx.Response(self.status, headers={'content-type': 'text/event-stream'},
                stream=Chunks(self.events, entered=self.entered, release=self.release))
        self.client = httpx.AsyncClient(transport=httpx.MockTransport(handler), trust_env=False)
        self.chat = chat_module.OfficialChatClient('http://127.0.0.1:6185', 'abk_offline_fixture', 'api_key:fixture-key-id',
            'phone-profile', client=self.client)
        self.connector = connector_module.OutboundConnector('wss://relay.invalid/mobile-call/v1/connector',
            'ABCD-1234', self.store, self.chat)
        self.connector.websocket = WS()
        await self.connector.paired({'binding_id': 'binding-one', 'connector_token': 'offline_connector_token_not_real'})

    async def asyncTearDown(self):
        operation = self.connector.operation
        if operation and operation.task:
            operation.task.cancel()
            await asyncio.gather(operation.task, return_exceptions=True)
        await self.client.aclose()
        self.directory.cleanup()

    async def request(self, request_id='request-one', session='stable-phone', text='现在的话', language='zh'):
        await self.connector.accept({'type': 'request', 'request_id': request_id,
            'session_id': session, 'text': text, 'reply_language': language})
        operation = self.connector.operation
        if operation:
            await operation.task
        return self.connector.websocket.frames

    def posts(self):
        return [request for request in self.calls if request.method == 'POST' and request.url.path == '/api/v1/chat']

    async def test_pairing_token_private_and_never_copied_to_outbound_result(self):
        reloaded = connector_module.PrivateState(self.store.directory)
        self.assertEqual(reloaded.data['connector_token'], 'offline_connector_token_not_real')
        frames = await self.request()
        self.assertNotIn('offline_connector_token_not_real', json.dumps(frames))
        self.assertNotIn('abk_offline_fixture', json.dumps(frames))

    async def test_first_pair_uses_code_reconnect_uses_private_token(self):
        self.assertEqual(self.connector.hello(), {'type': 'hello', 'mode': 'astrbot', 'connector_token': 'offline_connector_token_not_real'})
        self.connector.verification_code = 'EFGH-5678'
        self.assertEqual(self.connector.hello(), {'type': 'hello', 'mode': 'astrbot', 'verification_code': 'EFGH-5678'})

    async def test_formal_pipeline_exact_mobile_profile_principal_and_final_text(self):
        frames = await self.request()
        body = json.loads(self.posts()[0].content)
        self.assertEqual(body['config_id'], 'phone-profile')
        self.assertEqual(body['username'], 'api_key:fixture-key-id')
        self.assertEqual(body['flags'], {'enable_reasoning': False})
        self.assertNotIn('selected_provider', body)
        self.assertEqual([x['event']['text'] for x in frames if x.get('event', {}).get('type') == 'text'], ['[happy]你好。听得到。'])
        self.assertTrue(frames[-1]['success'])
        self.assertNotIn('PRIVATE_', json.dumps(frames))
        self.assertEqual(len(self.posts()), 1)

    async def test_stable_phone_session_keeps_context_but_other_binding_isolated(self):
        await self.request()
        await self.request('request-two')
        self.assertEqual(json.loads(self.posts()[0].content)['session_id'], json.loads(self.posts()[1].content)['session_id'])
        self.assertNotEqual(chat_module.upstream_session('binding-one', 'stable-phone'), chat_module.upstream_session('binding-two', 'stable-phone'))

    async def test_language_is_bounded_display_preference_not_arbitrary_system_persona(self):
        await self.request(language='ja')
        body = json.loads(self.posts()[0].content)
        self.assertNotIn('locale', body)  # Avoid an unverified optional formal API schema field.
        self.assertIn('日语', body['message'])
        self.assertNotIn('system_prompt', body)

    async def test_backend_language_note_is_present_exactly_once(self):
        text = chat_module.LANGUAGE_NOTES['ja'] + '\n这条来自通话后端。'
        await self.request(language='ja', text=text)
        body = json.loads(self.posts()[0].content)
        self.assertEqual(body['message'], text)

    async def test_duplicate_id_persisted_across_reload_is_not_replayed(self):
        await self.request()
        self.connector.store = connector_module.PrivateState(self.store.directory)
        await self.request()
        self.assertEqual(len(self.posts()), 1)
        self.assertFalse(self.connector.websocket.frames[-1]['success'])

    async def test_unended_stream_is_failure_not_partial_success(self):
        self.events = [{'type': 'plain', 'data': '不完整正文。'}]
        frames = await self.request()
        self.assertFalse(frames[-1]['success'])
        self.assertFalse(any(x.get('event', {}).get('type') == 'text' for x in frames))
        self.assertEqual(len(self.posts()), 1)
        self.assertTrue(any(x.url.path.endswith('/stop') for x in self.calls))

    async def test_unknown_cancel_is_not_falsely_confirmed(self):
        await self.connector.cancel('unknown-id')
        self.assertEqual(self.connector.websocket.frames[-1], {'type': 'cancelled', 'request_id': 'unknown-id', 'confirmed': False})
        self.assertEqual(self.calls, [])

    async def test_cancel_waits_for_real_owned_idle_confirmation(self):
        self.entered, self.release = asyncio.Event(), asyncio.Event()
        await self.connector.accept({'type': 'request', 'request_id': 'cancel-me', 'session_id': 'stable-phone', 'text': '等待这次', 'reply_language': 'zh'})
        await self.entered.wait()
        await self.connector.cancel('cancel-me')
        frames = self.connector.websocket.frames
        self.assertEqual(frames[-1], {'type': 'cancelled', 'request_id': 'cancel-me', 'confirmed': True})
        self.assertFalse(any(x.get('type') == 'done' and x.get('success') is True for x in frames))
        self.assertEqual(len(self.posts()), 1)
        self.assertEqual(self.store.ledger()['cancel-me']['state'], 'cancelled')

    async def test_successful_stop_response_without_idle_evidence_is_not_confirmation(self):
        operation = chat_module.ChatOperation('r', 'owned-session', dispatched=True)
        self.state = {'is_running': False}  # Missing active_runs is not proof of cleanup.
        self.assertFalse(await self.chat.stop_confirmed(operation))

    async def test_unknown_server_500_does_not_count_as_proven_request_rejection(self):
        async def accepted_unknown():
            return httpx.Response(500)
        operation = chat_module.ChatOperation('r500', 'owned-session', dispatched=True,
            opening=asyncio.create_task(accepted_unknown()))
        self.state = {'is_running': False}
        self.assertFalse(await self.chat.stop_confirmed(operation))
        self.assertTrue(any(request.url.path.endswith('/stop') for request in self.calls))

    async def test_queued_or_busy_second_request_does_not_enter_model_slot(self):
        self.entered, self.release = asyncio.Event(), asyncio.Event()
        await self.connector.accept({'type': 'request', 'request_id': 'first', 'session_id': 'stable-phone', 'text': '第一条'})
        await self.entered.wait()
        await self.connector.accept({'type': 'request', 'request_id': 'second', 'session_id': 'other-phone', 'text': '第二条'})
        self.assertEqual(len(self.posts()), 1)
        self.assertFalse(self.connector.websocket.frames[-1]['success'])
        self.release.set()
        await self.connector.operation.task

    async def test_invalid_request_or_language_does_not_dispatch(self):
        await self.connector.accept({'type': 'request', 'request_id': 'r', 'session_id': '../bad', 'text': '文本'})
        await self.connector.accept({'type': 'request', 'request_id': 'r2', 'session_id': 'safe', 'text': '文本', 'reply_language': 'arbitrary-prompt'})
        self.assertEqual(self.posts(), [])

    async def test_create_only_chat_key_and_reuse_private_credential(self):
        keys = Keys()
        first = await connector_module.ensure_chat_identity(object(), self.store, key_service=keys)
        second = await connector_module.ensure_chat_identity(object(), self.store, key_service=keys)
        self.assertEqual(first, second)
        self.assertEqual(len(keys.calls), 1)
        self.assertEqual(keys.calls[0][0]['scopes'], ['chat'])

    async def test_new_key_is_revoked_if_private_save_fails(self):
        keys = Keys()
        def failed_save():
            raise OSError('offline fixture')
        self.store.save = failed_save
        with self.assertRaises(connector_module.ConnectorError):
            await connector_module.ensure_chat_identity(object(), self.store, key_service=keys)
        self.assertEqual(keys.calls[-1], ('revoke', 'fixture-key-id'))

    async def test_unexpected_broad_key_scope_is_revoked_not_accepted(self):
        keys = Keys()
        async def changed_contract(payload, *, created_by):
            return {'key_id': 'fixture-key-id', 'api_key': 'abk_offline_fixture', 'scopes': ['chat', 'system']}
        keys.create_api_key = changed_contract
        with self.assertRaises(connector_module.ConnectorError):
            await connector_module.ensure_chat_identity(object(), self.store, key_service=keys)
        self.assertEqual(keys.calls[-1], ('revoke', 'fixture-key-id'))
        self.assertNotIn('chat_identity', self.store.data)

    async def test_prepare_uses_real_info_list_and_never_starts_inference(self):
        keys = Keys()
        context = types.SimpleNamespace(_config={'dashboard': {'port': 6185}})
        config = {'relay_url': 'https://relay.invalid', 'verification_code': 'ABCD-1234', 'allowed_profile_ids': ['phone-profile']}
        value = await connector_module.OutboundConnector.prepare(context, config, self.store.directory,
            key_service=keys, http_client=self.client)
        self.assertEqual(value.principal, 'api_key:fixture-key-id')
        self.assertEqual(self.posts(), [])
        self.assertTrue(any(x.url.path == '/api/v1/chat/configs' for x in self.calls))

    async def test_multiple_or_default_profile_cannot_silently_choose_qq(self):
        for profiles in (['default'], ['phone-profile', 'qq-profile']):
            with self.assertRaises(connector_module.ConnectorError):
                await connector_module.OutboundConnector.prepare(object(), {'relay_url': 'https://relay.invalid',
                    'verification_code': 'ABCD-1234', 'allowed_profile_ids': profiles}, self.store.directory)
        self.assertEqual(self.calls, [])

    async def test_relay_url_cannot_embed_secrets_or_insecure_transport(self):
        for value in ('ws://relay.invalid', 'https://user:password@relay.invalid', 'https://relay.invalid?key=secret', 'https://relay.invalid/arbitrary'):
            with self.assertRaises(connector_module.ConnectorError):
                connector_module.relay_endpoint(value)


class ConnectorHookScopeTests(unittest.TestCase):
    def apply(self, platform='webchat', profile='phone-profile', sender='api_key:paired-fixture'):
        import test_presentation as hook
        context = types.SimpleNamespace(astrbot_config_mgr=types.SimpleNamespace(get_conf_info=lambda _: {'id': profile}))
        config = {**hook.DEFAULT, 'allowed_profile_ids': ['phone-profile']}
        plugin = hook.module.Live2DCall(context, config)
        plugin._connector = types.SimpleNamespace(principal='api_key:paired-fixture')
        request = types.SimpleNamespace(system_prompt='SAME_PERSONA', func_tool=object())
        event = hook.Event(platform=platform, sender=sender)
        asyncio.run(plugin.prepare_call(event, request))
        return request.system_prompt, config

    def test_new_private_principal_only_changes_its_webchat_mobile_profile(self):
        prompt, config = self.apply()
        self.assertTrue(prompt.startswith('SAME_PERSONA'))
        self.assertIn('Live2D mobile call presentation', prompt)
        self.assertNotIn('api_key:paired-fixture', config['allowed_api_principals'])

    def test_new_private_principal_never_expands_to_qq_or_default_profile(self):
        self.assertEqual(self.apply(platform='aiocqhttp')[0], 'SAME_PERSONA')
        self.assertEqual(self.apply(profile='default')[0], 'SAME_PERSONA')
        self.assertEqual(self.apply(profile='other-phone-profile')[0], 'SAME_PERSONA')

    def test_unrelated_api_key_still_cannot_match(self):
        self.assertEqual(self.apply(sender='api_key:someone-else')[0], 'SAME_PERSONA')


if __name__ == '__main__':
    unittest.main(verbosity=2)
