# 从源码构建 Android

[English](BUILDING.en.md) · [安装](INSTALL.md) · [语音配置](TTS.md)

本页覆盖 0.0.1 的 Android 源码、完整版内置的 MOSS CPU 模型和可选 Audio8 模型包。

## 环境

使用 Git、Python 3、Node.js 24、JDK 17、Android SDK platform 34、build-tools 34.0.0、NDK 26.3.11579264 和 CMake 3.22.1（含 Ninja）。配置 `JAVA_HOME` 和 `ANDROID_HOME`，Gradle 使用仓库内的 wrapper。

## 准备 Audio8 与 ONNX Runtime

在仓库根目录运行准备工具。第一条使用显式联网下载；第二条复用已有缓存。默认准备位置为 `apps/android/.native-deps/audio8`。

```sh
git clone https://github.com/Franka-official/DSH-L2D-Public.git
cd DSH-L2D-Public
python3 apps/android/tools/bootstrap_audio8.py --prepare --download
```

```sh
python3 apps/android/tools/bootstrap_audio8.py --prepare \
  --model-cache /path/to/audio8-model \
  --ort-cache /path/to/onnxruntime-cache
```

如已有兼容的派生增量图，可用 `--incremental-graph FILE` 指定。普通模型 ZIP 由 `bootstrap_audio8.py --pack ZIP` 生成，布局见[语音配置](TTS.md)。私用包加 `--include-local-reference`，并用 `--reference-dir DIR` 提供自己有权使用的 `reference.json` 和 `reference.codes.i64.bin`。

独立参考音 ZIP 只需提供参考文本及其对应声码。下面以鲸鱼娘为例，另可选 `ellen` 或通用 `default`。

```sh
python3 apps/android/tools/bootstrap_audio8.py --pack-reference /path/to/whale-reference.zip \
  --reference-dir /path/to/inputs --reference-voice whale
```

完成后在语音设置选择「导入 Audio8 模型或参考音」。私用参考音应按素材授权使用。

## 准备 MOSS-TTS-Nano

- 将 `DSH_MOSS_SENTENCEPIECE_SOURCE_DIR` 指向 [SentencePiece 固定源码](https://github.com/google/sentencepiece/tree/31646a467d2051eb904e0b45de3a73e91fe1c1e3) 的目录。
- 启用手机 WAV 人声导入时，将 `moss_audio_tokenizer_encode.onnx` 和 `moss_audio_tokenizer_encode.data` 放入 `apps/android/app/src/main/assets/moss_encoder/`。文件来自 [OpenMOSS 官方编解码器](https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/tree/ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae)，该目录在本地准备。
- 构建本地 TTS 完整版时，`DSH_MOSS_MODEL_DIR` 指向准备好的模型根目录，含 `MOSS-TTS-Nano-100M-ONNX/`、`MOSS-Audio-Tokenizer-Nano-ONNX/` 及 `voices/whale.json` 和 `voices/ellen.json`。固定模型版本见 [MOSS 模型与音色](MOSS-NANO.md)，音色记录见[素材清单](../ASSET_SOURCES.md)。

## 准备渲染和角色资源

下载官方 Cubism Native SDK 5-r.4 并保留其许可文件。`DSH_ANDROID_ASSETS_DIR` 指向自备的运行素材根目录，素材路径与 APK 内的 `assets/` 对应。动作脚本使用仓库内的固定源和适配器。[Cubism SDK ZIP](https://cubism.live2d.com/sdk-native/bin/CubismSdkForNative-5-r.4.zip) · [素材清单](../ASSET_SOURCES.md)

```sh
cd apps/android
python3 tools/bootstrap_cubism.py --archive /path/to/CubismSdkForNative-5-r.4.zip
cd motion
npm ci
npm run build
cd ..
export DSH_ANDROID_ASSETS_DIR=/path/to/runtime-assets
```

## 构建

| Gradle 属性 | APK 内容 | 初始语音方式 |
|---|---|---|
| `-PdshTtsEdition=local` | MOSS 模型、两套角色音色和 WAV 编码器 | 手机 MOSS CPU 合成 |
| `-PdshTtsEdition=external` | 外接语音接口及手机语音运行组件 | 外接语音 API |

```sh
DSH_MOSS_MODEL_DIR=/path/to/moss-model bash ./gradlew --no-daemon -PdshTtsEdition=local assembleRelease
# 归档这一版 APK 后再构建下一版。
bash ./gradlew --no-daemon -PdshTtsEdition=external assembleRelease
```

两种构建均可保留 MOSS WAV 编码器和可选 Audio8 CPU 运行组件。外接版省略 MOSS 模型和预设音色。需要调试内置 Audio8 的开发版时，使用 `-PdshTtsEdition=local -PdshLocalTtsEngine=audio8`。[Android 原生构建与签名](../apps/android/native/BUILDING.md)

| 变量 | 内容 |
|---|---|
| `DSH_MOSS_MODEL_DIR` | 完整版模型根目录，含两套预设音色 |
| `DSH_MOSS_SENTENCEPIECE_SOURCE_DIR` | MOSS 运行组件使用的 SentencePiece 源码 |
| `DSH_ANDROID_ASSETS_DIR` | 运行素材根目录，可从此处提供 `moss_encoder/` |
| `DSH_AUDIO8_DEPS` | 可选 Audio8 后端使用的 ONNX Runtime 等依赖 |

## 签名与安装

配置发行签名后，APK 可在手机文件管理器中打开安装。覆盖安装要求兼容证书。开发验收可加 `-PdeploymentTestApplicationId=com.prts.client.deploytest` 使用独立应用 ID。设备有线调试与自建签名见[签名说明](../apps/android/native/BUILDING.md#signing)。
