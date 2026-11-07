# 插件兼容与实测

[English](PLUGIN_COMPATIBILITY.en.md) · [下载](DOWNLOADS.md) · [开发检查](PLUGIN_CHECKS.md)

| 插件 | 源码 | 已发布 ZIP | 宿主与实测 |
| --- | --- | --- | --- |
| [DSH Go 本机](../integrations/dsh-go/README.md) | 0.2.14 | [0.2.14](https://github.com/Franka-official/DSH-L2D-Public/releases/download/v0.0.1/dsh-go-live2d-0.2.14-agpl-source.zip) | Go 1.0.10、DSH rc.4。已在 Android 真机验证本机连接、主动开口和输入打断。0.2.5 曾验证本地识别和已授权设备的离线恢复。 |
| [DeepSeek Harness](../integrations/dsh-harness/README.md) | 1.1.3 | [1.1.3](https://github.com/Franka-official/DSH-L2D-Public/releases/download/v0.0.1/dsh-mobile-call-adapter-1.1.3-agpl-source.zip) | 使用 DSH rc.4 会话接口。新宿主与 DSHA 图形安装待实测。 |
| [AstrBot](../integrations/astrbot/README.md) | 1.0.1 | [1.0.1](https://github.com/Franka-official/DSH-L2D-Public/releases/download/v0.0.1/astrbot_plugin_live2d_call-1.0.1-agpl-source.zip) | 宿主范围 `>=4.29.3,<4.30`。选择手机 WebChat 配置，使用独立插件 ZIP。新宿主通话待实测。 |

每次通话选择一个连接来源。Go 本机桥接传输文字和兼容的本地转录。当前源码的 Go 桥接 0.2.15 通过应用启动与本机配对连接，并在音色管理显示本机语音识别的下载进度、就绪和重试。已发布的旧 APK 与插件请配套使用。

插件独立打包，模型、角色和用户配置在各自的应用或宿主设置中管理。
