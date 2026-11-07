# Plugin compatibility and device tests

[简体中文](PLUGIN_COMPATIBILITY.md) · [Downloads](DOWNLOADS.md) · [Development checks](PLUGIN_CHECKS.en.md)

| Plugin | Source | Published ZIP | Host and test evidence |
| --- | --- | --- | --- |
| [Same-phone DSH Go](../integrations/dsh-go/README.en.md) | 0.2.14 | [0.2.14](https://github.com/Franka-official/DSH-L2D-Public/releases/download/v0.0.1/dsh-go-live2d-0.2.14-agpl-source.zip) | Go 1.0.10 and DSH rc.4. Android device tests passed local connection, proactive speech and typing interruption with 0.2.14. Tests of 0.2.5 covered local ASR and offline recovery of an approved phone. |
| [DeepSeek Harness](../integrations/dsh-harness/README.en.md) | 1.1.3 | [1.1.3](https://github.com/Franka-official/DSH-L2D-Public/releases/download/v0.0.1/dsh-mobile-call-adapter-1.1.3-agpl-source.zip) | Uses the DSH rc.4 session interface. Fresh-host and DSHA UI installation tests are pending. |
| [AstrBot](../integrations/astrbot/README.en.md) | 1.0.1 | [1.0.1](https://github.com/Franka-official/DSH-L2D-Public/releases/download/v0.0.1/astrbot_plugin_live2d_call-1.0.1-agpl-source.zip) | Host range `>=4.29.3,<4.30`. Select a mobile WebChat profile and use the standalone plugin ZIP. Calls on a fresh host are pending. |

Choose one connection source per call. The local Go bridge carries text and compatible on-device transcripts. The 0.2.15 bridge in the current source uses app launch and local pairing. Voice settings show local speech recognition download progress, readiness, and retry. Use matching versions of older released APKs and plugins.

The plugins ship separately. Configure models, characters and user settings in the app or host that uses them.
