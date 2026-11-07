# Installation and connection

[简体中文](INSTALL.md) · [Downloads](DOWNLOADS.md) · [Speech setup](TTS.md)

The app supports ARM64 devices running Android 8.0 or later. [Local TTS Full](DOWNLOADS.md#local) prepares the bundled MOSS model and two character voices on first launch. Use Voice settings to select an external speech API. In-place updates require a compatible signature.

## First launch

Use the setup guide to grant microphone and camera access as needed. To keep the same-phone bridge running in the background, choose Open battery settings (打开电池设置) and select Unrestricted or the equivalent option in Android's app battery settings.

Choose Connect DSH Go (连接 DSH Go) to open the same-phone connection page. To continue later, choose Later (稍后) and reopen the guide from Settings → Setup guide (设置 → 新手引导).

## Connect a conversation service

| Source | Setup |
| --- | --- |
| [Same-phone DSH Go](../integrations/dsh-go/README.en.md) | Install Go and the plugin on the same phone. In L2D Connection settings, select Local DSH Go and start pairing. Open DSH L2D Bridge (`DSH L2D桥接`) under Go’s All Features and connect. Return to L2D, compare the displayed codes and approve. |
| [DeepSeek Harness](../integrations/dsh-harness/README.en.md) | Install the adapter in the existing host. Pair with the call service URL and a phone code. |
| [AstrBot](../integrations/astrbot/README.en.md) | Upload the standalone plugin ZIP, select a mobile WebChat profile, and pair. |
| Model API | Under Connection settings, enter the model API address and key, then the call relay address and its optional key. The relay must support `/client-ws`. Configure an external speech API separately under Voice settings. |

L2D's local connection page includes the plugin download and installation steps. The pairing button opens Go directly when it is installed. The bridge uses loopback on the phone. Model inference follows Go's provider configuration. [Versions and tests](PLUGIN_COMPATIBILITY.en.md)

## Speech recognition

After connecting DSH Go, enable its Voice Input plugin and select its local recognizer. In L2D, open Voice settings → Local speech recognition (音色管理 → 本机语音识别（STT）) and choose Download speech recognition model (下载语音识别模型). The panel shows progress for the current file and Ready (已就绪) when the model is available. After an interruption, choose Retry download and preparation (重试下载与准备). Completed files are reused and the interrupted file downloads again. With an older bridge, prepare the model in Go's Voice Input settings.

## Set up speech

- **Audio8** is optional. Import model and voice-reference ZIPs in Voice settings for on-device CPU synthesis. Character references take priority over a shared reference.
- **MOSS** is the default in Local TTS Full. Use Voice settings to select a voice per character or import a WAV. [Model and voices](MOSS-NANO.md)
- **External speech API**: Enter the speech service URL and any required key. Choose the model and voice under Advanced options. [API format](TTS.md)

[Source build](BUILDING.en.md)
