# 语音配置 / Speech setup

[安装 / Install](INSTALL.md) · [下载 / Downloads](DOWNLOADS.md)

## 中文

对话服务生成回复，语音后端把回复转换成声音。聊天与语音分别保存地址和 Key。

### 手机 MOSS

完整版内置 MOSS-TTS-Nano INT8 CPU 合成模型、鲸鱼娘 Bright 和艾莲两套音色，以及 MOSS WAV 音色编码器。首次启动从 APK 准备模型，之后可在手机上离线合成语音。[模型与音色](MOSS-NANO.md)

### 手机 Audio8

Audio8 为可选后端。在「导入 Audio8 模型或参考音」导入模型 ZIP 和独立参考音 ZIP，使用 CPU 合成。

参见[源码构建](BUILDING.md)准备 Audio8 模型包。

模型来自 Audio8 0.1B ONNX INT8 的固定版本 `317c12d4e0da83847b594fcf8bd74bf2c76615ec`。预测使用 INT8 图，增量解码图使用 FP16 编解码器权重，产生 44.1 kHz PCM。播放时优雅档为 0.65×，快速档为 1×，两档保持音高。[上游模型卡](https://huggingface.co/Edge0/audio8-TTS-0.1B-ONNX-INT8)

模型包根目录包含 `model-manifest.json`（`dsh-audio8-model-pack-v1`）和 `model/`。普通分发包提供模型。私用包可另含 `inputs/reference.json` 和 `inputs/reference.codes.i64.bin`。独立参考音 ZIP 可指定 `ellen`、`whale` 或通用 `default`。应用优先使用当前角色的参考音，缺省时使用通用参考音。已准备的本机 Audio8 `inputs/` 也可复用。[素材来源](../ASSET_SOURCES.md) · [构建与资源准备](BUILDING.md)

### 外接语音 API

API 模式无需加载手机语音权重。填写有效地址后才能开始，Key 可留空；本地角色参考音频不会自动上传。

| 字段 | 规则 |
|---|---|
| 语音地址 | 根地址补 /v1/audio/speech；/v1 后补 /audio/speech；完整 /audio/speech 地址直接使用。无 query、fragment 或 userinfo |
| API Key | 非空时发送 Authorization: Bearer。Key 不回显；同地址留空保留，换地址留空清除旧 Key |
| model | 默认 tts-1，高级选项可改 |
| voice | 默认 alloy，高级选项可改 |

请求为 POST JSON，包含 input、model、voice、response_format: "wav"、speed: 1.0。每个句段接收完整 WAV 再交给队列，播放当前句时可以生成下一句；这一路不属于字节级语音流式。播放器沿用 0.65× / 1× 并保持音高。失败时显示错误，不自动重试或切换后端。

## English

The conversation service generates replies and the speech backend synthesizes them. Chat and speech have separate URLs and keys.

### On-device MOSS

Local TTS Full bundles the MOSS-TTS-Nano INT8 CPU synthesis model, Whale Bright and Ellen voices, and the MOSS WAV voice encoder. It prepares the model from the APK at first launch for offline speech synthesis. [Model and voices](MOSS-NANO.md)

### On-device Audio8

Audio8 is optional. Use “导入 Audio8 模型或参考音” to import model and voice-reference ZIPs for CPU synthesis.

See the [source build guide](BUILDING.en.md) to prepare the Audio8 model pack.

The model comes from Audio8 0.1B ONNX INT8 revision `317c12d4e0da83847b594fcf8bd74bf2c76615ec`. Prediction uses INT8 graphs, and the incremental decoder uses FP16 codec weights to produce 44.1 kHz PCM. Elegant plays at 0.65× and Fast at 1×, both with pitch preserved. [Upstream model card](https://huggingface.co/Edge0/audio8-TTS-0.1B-ONNX-INT8)

The model ZIP has `model-manifest.json` (`dsh-audio8-model-pack-v1`) and `model/` at its root. A private-use pack may additionally include `inputs/reference.json` and `inputs/reference.codes.i64.bin`. A standalone reference ZIP can target `ellen`, `whale`, or the shared `default`. The app uses the current character’s reference first and falls back to the shared reference. Existing on-device Audio8 `inputs/` remain usable. [Asset sources](../ASSET_SOURCES.md) · [Build and asset setup](BUILDING.en.md)

### External speech API

API mode does not load the on-phone speech weights. A valid URL is required; the key is optional. Character reference audio is not automatically uploaded.

| Field | Rule |
|---|---|
| Speech URL | Root gains /v1/audio/speech; /v1 gains /audio/speech; a complete /audio/speech endpoint is used directly. No query, fragment or userinfo |
| API key | Nonempty keys use Authorization: Bearer. Saved keys are hidden; empty retains the key for the same URL and clears it for a changed URL |
| model | Defaults to tts-1; editable under advanced options |
| voice | Defaults to alloy; editable under advanced options |

POST JSON contains input, model, voice, response_format: "wav" and speed: 1.0. Each sentence is received as a complete WAV before playback; the next can be generated while the current one plays. This is not byte-level audio streaming. Playback retains the 0.65× / 1× pitch-preserving choices. Failures are displayed without automatic retry or backend switching.
