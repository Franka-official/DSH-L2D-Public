# Installation and connection

[简体中文](INSTALL.md) · [Downloads](DOWNLOADS.md) · [Speech setup](TTS.md)

The app supports ARM64 devices running Android 8.0 or later. [Local TTS Full](DOWNLOADS.md#local) prepares the bundled MOSS model and two character voices on first launch. Use Voice settings to select an external speech API. In-place updates require a compatible signature.

## Connect a conversation service

| Source | Setup |
| --- | --- |
| [Same-phone DSH Go](../integrations/dsh-go/README.en.md) | Install the plugin on the same phone. Open DSH L2D Bridge (`DSH L2D桥接`) in Go settings, connect, and approve the connection. In L2D, select Local (`本机`) under Connection settings. |
| [DeepSeek Harness](../integrations/dsh-harness/README.en.md) | Install the adapter in the existing host. Pair with the call service URL and a phone code. |
| [AstrBot](../integrations/astrbot/README.en.md) | Upload the standalone plugin ZIP, select a mobile WebChat profile, and pair. |
| Model API | Under Connection settings, enter the model API address and key, then the call relay address and its optional key. The relay must support `/client-ws`. Configure an external speech API separately under Voice settings. |

For the first connection, follow the Android prompt to open DSH L2D and approve the bridge. The bridge uses loopback on the phone. Model access follows the provider configuration. [Versions and tests](PLUGIN_COMPATIBILITY.en.md)

## Set up speech

- **Audio8** is optional. Import model and voice-reference ZIPs in Voice settings for on-device CPU synthesis. Character references take priority over a shared reference.
- **MOSS** is the default in Local TTS Full. Use Voice settings to select a voice per character or import a WAV. [Model and voices](MOSS-NANO.md)
- **External speech API**: Enter the speech service URL and any required key. Choose the model and voice under Advanced options. [API format](TTS.md)

[Source build](BUILDING.en.md)
