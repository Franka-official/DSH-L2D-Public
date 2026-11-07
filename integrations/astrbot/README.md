# AstrBot 手机通话插件

[English](README.en.md) · [下载](../../docs/DOWNLOADS.md) · [兼容与实测](../../docs/PLUGIN_COMPATIBILITY.md)

让 L2D 使用 AstrBot 的手机 WebChat 对话。插件在指定配置与身份下补入口语和表情提示，使用宿主所选人格、模型和工具。

## 安装与配对

1. 在 AstrBot 插件管理上传独立 ZIP。包根包含 `main.py` 和 `metadata.yaml`。宿主范围为 `>=4.29.3,<4.30`，运行需要 `aiohttp` 与 `httpx`。
2. 创建或选择手机专用 WebChat 配置，把实际 ID 填入 `allowed_profile_ids`。默认留空，使用不同于 `default` 的手机专用配置 ID。
3. 填写通话 relay 地址与手机一次性连接码。插件为该路线创建仅有 chat 权限的内部身份。自管外部 API 身份可用 `allowed_api_principals` 配置身份标识。
4. 配对后清除连接码。凭据保存在插件私有目录。

[连接器配置](CONNECTOR.md) · [支持范围](SUPPORTED.md) · [开发检查](../../docs/PLUGIN_CHECKS.md)

卸载时结束通话，从手机 WebChat 配置移除插件，再在宿主管理器卸载。[AGPL-3.0-only](COPYING) · [第三方许可](THIRD_PARTY_NOTICES.md)
