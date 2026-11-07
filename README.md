<div align="center">

# DSH L2D

[0.0.1 · 首次发布](https://github.com/Franka-official/DSH-L2D-Public/releases/tag/v0.0.1)

**把你的对话服务接到 Android Live2D 角色。**

**[下载 0.0.1 本地 TTS 完整版](https://github.com/Franka-official/DSH-L2D-Public/releases/download/v0.0.1/DSH-L2D-0.0.1-local-tts.apk)**

[安装指南](docs/INSTALL.md) · [语音设置](docs/TTS.md) · [English](README.en.md)

</div>

DSH L2D 是一个 Android Live2D 通话客户端。DSH Go、DeepSeek Harness、AstrBot 或兼容的模型 API 提供对话回复，手机负责字幕、语音播放和角色表现。语音合成可以在手机运行，也可以交给单独配置的 TTS 服务。

## 安装与通话

1. 默认下载本地 TTS 完整版，内置 MOSS-TTS-Nano INT8 CPU 模型、鲸鱼娘 Bright 和艾莲音色。支持 Android 8.0 及以上的 ARM64 设备。
2. 按[安装指南](docs/INSTALL.md)连接 DSH Go、DeepSeek Harness、AstrBot 或兼容的对话 API。
3. 选一个角色开始通话。在「音色管理」切换语音方式，在「通话管理」设置主动开口等待时间。

## 语音后端

| 方案 | 用途 |
| --- | --- |
| MOSS-TTS-Nano | 完整版默认使用的手机 CPU 合成，支持 WAV 音色导入 |
| Audio8 | 可选的手机 CPU 合成，另行导入模型和参考音 |
| 外接 TTS API | 使用独立语音服务及其音色 |

[下载与插件](docs/DOWNLOADS.md) · [语音设置](docs/TTS.md) · [插件兼容](docs/PLUGIN_COMPATIBILITY.md)

## 社区

QQ 交流群：**455092497**。欢迎交流使用体验、角色适配和插件开发。

## 工程目录

| 目录 | 内容 |
| --- | --- |
| [apps/android](apps/android/) | Android 通话、语音播放、Live2D 和设置 |
| [integrations](integrations/) | DSH Go、DeepSeek Harness、AstrBot 的连接实现 |
| [docs](docs/) | 安装、构建和发布 |

按 [源码构建](docs/BUILDING.md) 准备 SDK、模型和素材。[贡献指南](CONTRIBUTING.md) 说明如何提交角色适配、语音和连接方面的改进。报告设备问题时，请附 Android 版本、设备、应用版本和复现步骤。

项目自有源码采用 [AGPL-3.0-only](LICENSE)。Cubism 组合分发许可和第三方依赖见 [第三方许可](THIRD_PARTY_LICENSES.md)，角色和声音来源见 [素材清单](ASSET_SOURCES.md)。
