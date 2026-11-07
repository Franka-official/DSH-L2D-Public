# 安装与连接

[English](INSTALL.en.md) · [下载](DOWNLOADS.md) · [语音设置](TTS.md)

支持 ARM64 Android 8.0 及以上设备。默认安装[本地 TTS 完整版](DOWNLOADS.md#local)，首次启动准备内置的 MOSS 模型和两套角色音色。已有语音服务时，在「音色管理」选择外接语音 API。覆盖安装要求签名兼容。

## 连接对话服务

| 来源 | 设置步骤 |
| --- | --- |
| [DSH Go 本机](../integrations/dsh-go/README.md) | 在同一部手机安装插件。在 Go 的「设置」打开「DSH L2D桥接」并连接，按系统提示完成授权。在 L2D 的「连接管理」选择「本机」。 |
| [DeepSeek Harness](../integrations/dsh-harness/README.md) | 在现有宿主安装适配器，使用通话服务地址和手机连接码配对。 |
| [AstrBot](../integrations/astrbot/README.md) | 上传独立插件 ZIP，选择手机 WebChat 配置，完成配对。 |
| 模型 API | 在「连接管理」填写模型 API 地址和 Key，以及通话中继地址和可选的中继 Key。中继需支持 `/client-ws`。外接语音 API 在「音色管理」单独配置。 |

DSH Go 首次连接按系统提示选择 DSH L2D 并完成授权。本机桥接使用回环连接，模型供应商仍按所选配置访问。[版本与实测](PLUGIN_COMPATIBILITY.md)

## 设置语音

- **Audio8**可选。在「音色管理」分别导入模型 ZIP 与参考音 ZIP，使用手机 CPU 合成。角色参考音优先，未配置角色使用通用参考音。
- **MOSS**是完整版默认后端。可按角色切换音色或导入 WAV。[模型与音色](MOSS-NANO.md)
- **外接语音 API**：填写语音服务地址和服务要求的 Key，在高级选项中指定模型和音色。[接口格式](TTS.md)

[源码构建](BUILDING.md)
