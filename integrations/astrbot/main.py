"""AstrBot 4.29.3 mobile-only speech style and semantic expression hints."""
import logging
from astrbot.api import AstrBotConfig
from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.provider import ProviderRequest
from astrbot.api.star import Context, Star, register

from .policy import MARKER, build_presentation, scope_matches


@register("astrbot_plugin_live2d_call", "local", "手机通话口语与语义表情提示；不修改QQ", "1.0.0")
class Live2DCall(Star):
    def __init__(self, context: Context, config: AstrBotConfig):
        super().__init__(context)
        self.context = context
        self.config = config
        self._connector = None

    async def initialize(self):
        if self.config.get('enabled', True) is not True or not self.config.get('relay_url', '').strip():
            return  # Existing presentation-only installations remain unchanged.
        try:
            from astrbot.core.star.star_tools import StarTools
            from .connector import OutboundConnector
            directory = StarTools.get_data_dir('astrbot_plugin_live2d_call') / 'private'
            self._connector = await OutboundConnector.prepare(self.context, self.config, directory)
            self._connector.start()
        except Exception:
            # In particular never logger.exception(): an HTTP/config traceback
            # can retain the pairing code, generated Key, or relay hello frame.
            logging.getLogger('astrbot').warning('Live2DCall: outbound connector preparation failed; presentation hook remains available')

    @filter.on_llm_request(priority=-1000)
    async def prepare_call(self, event: AstrMessageEvent, request: ProviderRequest):
        try:
            # Public v4.29.3 ACM API resolves the actual routed profile, rather
            # than trusting user text, arbitrary request extras or persona names.
            profile = self.context.astrbot_config_mgr.get_conf_info(event.unified_msg_origin)
            profile_id = profile.get("id", "") if isinstance(profile, dict) else ""
            scoped_config = self.config
            if self._connector is not None and event.get_sender_id() == self._connector.principal:
                # Only this plugin's privately saved chat Key is added, in memory.
                # The original webchat + selected mobile profile gate stays mandatory.
                scoped_config = dict(self.config)
                scoped_config['allowed_api_principals'] = list(self.config.get('allowed_api_principals', [])) + [self._connector.principal]
            matches = scope_matches(event.get_platform_name(), event.get_sender_id(), profile_id, scoped_config)
        except (AttributeError, TypeError, ValueError, RuntimeError, KeyError):
            return  # Changed/missing context is outside this plugin's scope.
        if not matches:
            return
        original = request.system_prompt or ""
        if not isinstance(original, str) or MARKER in original:
            return
        request.system_prompt = original + "\n\n" + build_presentation(self.config)
        event.set_extra("live2d_call_presentation_applied", True)
        logging.getLogger("astrbot").info("Live2DCall: mobile presentation applied")

    async def terminate(self):
        if self._connector is not None:
            await self._connector.close()
            self._connector = None
