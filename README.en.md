<div align="center">

# DSH L2D

[0.0.1 · Initial release](https://github.com/Franka-official/DSH-L2D-Public/releases/tag/v0.0.1)

**Connect your conversation service to a Live2D character on Android.**

**[Download 0.0.1 Local TTS Full](https://github.com/Franka-official/DSH-L2D-Public/releases/download/v0.0.1/DSH-L2D-0.0.1-local-tts.apk)**

[Installation](docs/INSTALL.en.md) · [Speech setup](docs/TTS.md) · [简体中文](README.md)

</div>

DSH L2D is an Android calling client for Live2D characters. DSH Go, DeepSeek Harness, AstrBot or a compatible model API supplies the replies. The phone handles subtitles, speech playback and character animation. Speech can be synthesized on the phone or by a separately configured TTS service.

## Install and make a call

1. Start with Local TTS Full. It bundles the MOSS-TTS-Nano INT8 CPU model and the Whale Bright and Ellen voices. The APK supports ARM64 devices running Android 8.0 or later.
2. Follow the [installation guide](docs/INSTALL.en.md) to connect DSH Go, DeepSeek Harness, AstrBot or a compatible conversation API.
3. Choose a character and start a call. Use Voice settings to switch speech backends and Call settings to adjust the proactive speech delay.

## Speech backends

| Backend | Use |
| --- | --- |
| MOSS-TTS-Nano | Local TTS Full's default on-device CPU synthesis, with WAV voice import |
| Audio8 | Optional on-device CPU synthesis with imported models and voice references |
| External TTS API | An independent speech service and its voices |

[Downloads and plugins](docs/DOWNLOADS.md) · [Speech setup](docs/TTS.md) · [Plugin compatibility](docs/PLUGIN_COMPATIBILITY.en.md)

## Community

Join QQ group **455092497** to discuss DSH L2D, character adaptation and plugins.

## Repository layout

| Directory | Contents |
| --- | --- |
| [apps/android](apps/android/) | Android calls, audio playback, Live2D and settings |
| [integrations](integrations/) | Connections for DSH Go, DeepSeek Harness and AstrBot |
| [docs](docs/) | Installation, source builds and releases |

The [source build guide](docs/BUILDING.en.md) covers SDKs, models and assets. See [CONTRIBUTING](CONTRIBUTING.md) to improve characters, speech or connections. For device issues, include the Android version, device, app version and steps to reproduce.

Project-owned source is licensed under [AGPL-3.0-only](LICENSE). The [third-party notices](THIRD_PARTY_LICENSES.md) cover Cubism combination and distribution terms and other dependencies. Character and voice sources are listed in [ASSET_SOURCES](ASSET_SOURCES.md).
