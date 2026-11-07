# 手机配对连接器

配置菜单只需填写 `relay_url` 和手机显示的一次性 `verification_code`。
网址接受 HTTPS/WSS 主站或固定 `/mobile-call/v1/connector` 路径。
留空时保留原有口语提示插件，不启动连接任务或新增入站端口。

连接器沿用 `allowed_profile_ids` 中唯一的非 default 手机配置；不会静默选择 QQ 默认配置。
它通过 AstrBot 正式 `/api/v1/chat` 接口调用原 WebChat 完整流程，保留所选人格、会话记忆和兼容插件。
`reply_language` 只接受 zh/ja，作为本轮通话语言偏好，不更换人格或模型供应商；通话后端已有同一语言附注时不重复添加。

插件使用官方 `ApiKeyService(context._db).create_api_key(payload, created_by=...)` 自动生成仅 `chat` 权限的内部身份。
内部 Key、配对令牌及请求去重记录保存在插件数据目录的 `private/connector-private.json`，Linux 目录/文件权限为 0700/0600。
Key 和令牌不会发给手机，也不放进配置菜单、URL或日志。配对成功会清除一次性验证码配置。
原有 prompt scope 函数保持不变；新增身份只在本插件运行时、webchat 平台与允许的手机配置同时匹配时加入范围，不影响 QQ。

当前通道支持正文、稳定会话和确认取消，不声明图片或原生审批能力。
只传递完成后的助手正文；不会把模型思考、工具过程或不完整的流误作最终回答。
重复 request_id 持久记录并拒绝重放。断线重连只恢复连接，不重发提示。
取消必须由同一聊天 Key 对所属会话执行停止，并观察 `active_runs=[]` 与 `is_running=false` 后才确认成功。
若清理结果不明，连接器拒绝新的生成任务，保留私有记录供定向处理。

本次公共源码检查包含 36 项范围与连接器离线测试，使用真实模块和 HTTP MockTransport；未建立真实 WSS、未创建生产 Key、未调用主模型、未发送 QQ 消息。
网络库延迟至实际 WSS 任务启动才导入。正式环境需预检宿主已有 aiohttp/httpx；开发单元测试只声明 httpx，不自动安装生产依赖。
源码版本见 [插件兼容表](../../docs/PLUGIN_COMPATIBILITY.md)。

原始契约来源（AstrBot 官方 v4.29.3）：

- https://github.com/AstrBotDevs/AstrBot/blob/v4.29.3/astrbot/dashboard/services/chat_service.py
- https://github.com/AstrBotDevs/AstrBot/blob/v4.29.3/astrbot/dashboard/services/api_key_service.py
- https://github.com/AstrBotDevs/AstrBot/blob/v4.29.3/astrbot/core/star/context.py
- https://github.com/AstrBotDevs/AstrBot/blob/v4.29.3/astrbot/core/star/star_tools.py

内部 ChatService 函数名在已安装快照与官方标签之间有差别；连接器依赖已实测的正式 HTTP 路由，不猜测或调用私有 ChatService 方法。
