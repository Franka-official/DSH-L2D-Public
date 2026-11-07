# 素材来源 / Asset sources

项目自有源代码采用 [AGPL-3.0-only](LICENSE)。运行资源按各自来源和许可管理，构建时从仓库外提供。角色资源和声码提示随 0.0.1 完整版 APK 分发。

Project-owned source is licensed under [AGPL-3.0-only](LICENSE). Runtime assets have separate sources and terms and are supplied outside the source tree. Character resources and voice prompts ship in the 0.0.1 full APK.

| 组件 / Component | 来源 / Source |
| --- | --- |
| MOSS-TTS-Nano INT8 | [REALBITS INT8 生成器](https://huggingface.co/REALBITS/MOSS-TTS-Nano-100M-ONNX-int8/tree/23fd19793be20a4f3f6aacd4e3a2130c8e3d2519) |
| MOSS 音频编解码器 / Audio codec | [OpenMOSS Audio Tokenizer](https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/tree/ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae) |
| 可选 Audio8 / Optional Audio8 | [Audio8 0.1B ONNX INT8](https://huggingface.co/Edge0/audio8-TTS-0.1B-ONNX-INT8/tree/317c12d4e0da83847b594fcf8bd74bf2c76615ec) |
| 鲸鱼娘 Bright、艾莲 / Whale Bright, Ellen | 角色资源与声码提示打入完整版 APK。 / Character resources and voice prompts ship in the full APK. |
| 背景 / Backgrounds | 完整版保留带 CC0 来源记录的背景图。 / The APK retains backgrounds with recorded CC0 sources. |

[构建与资源配置](docs/BUILDING.md) · [Build and assets](docs/BUILDING.en.md) · [Third-party notices](THIRD_PARTY_LICENSES.md)
