# MOSS-TTS-Nano 手机部署 / Mobile deployment

[项目主页](../README.md) · [English home](../README.en.md)

## CPU 模型包 / CPU model package

完整版内置 MOSS-TTS-Nano INT8 CPU 合成模型、鲸鱼娘 Bright 和艾莲两套音色，以及 MOSS WAV 音色编码器。首次启动从 APK 准备模型，之后可在手机上离线合成语音。语音码生成器为 INT8，OpenMOSS 音频解码器为 FP32。

Local TTS Full bundles the MOSS-TTS-Nano INT8 CPU synthesis model, Whale Bright and Ellen voices, and the MOSS WAV voice encoder. It prepares the model from the APK at first launch for offline speech synthesis. The speech-token generator uses INT8 and the OpenMOSS audio decoder uses FP32.

[INT8 generator files](https://huggingface.co/REALBITS/MOSS-TTS-Nano-100M-ONNX-int8/tree/23fd19793be20a4f3f6aacd4e3a2130c8e3d2519) · [Official decoder files](https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/tree/ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae)

## 音色 / Voices

MOSS 将每个角色的参考 WAV 编码成独立的音色提示，合成时共用生成器和解码器。完整版的两个预设是鲸鱼娘 Bright 和艾莲。迁移其他引擎的音色时，用参考 WAV 重新编码。

MOSS encodes each character's reference WAV into a separate voice prompt. The voices share one generator and decoder. Local TTS Full supplies Whale Bright and Ellen presets. To move a voice from a different engine, encode the reference WAV again with MOSS.

[Upstream voice cloning](https://github.com/OpenMOSS/MOSS-TTS-Nano#cli-command-moss-tts-nano-generate)

「音色管理」可按角色切换音色或导入 0.5–8 秒 WAV。导入在手机 CPU 上编码，完成后自动选中。「通话管理」可设置主动开口等待时间，默认 30 秒。

Voice settings select a voice per character and import 0.5–8 second WAV clips. The phone encodes the reference on CPU and selects it after import. Call settings control the proactive-speech delay, with a default of 30 seconds.
