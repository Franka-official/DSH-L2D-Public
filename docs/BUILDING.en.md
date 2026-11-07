# Build the Android app from source

[简体中文](BUILDING.md) · [Installation](INSTALL.en.md) · [Speech setup](TTS.md)

This guide covers Android 0.0.1, the MOSS CPU model bundled with Local TTS Full, and optional Audio8 packages.

## Environment

Use Git, Python 3, Node.js 24, JDK 17, Android SDK platform 34, build-tools 34.0.0, NDK 26.3.11579264 and CMake 3.22.1 with Ninja. Set `JAVA_HOME` and `ANDROID_HOME`, and use the repository's Gradle wrapper.

## Prepare Audio8 and ONNX Runtime

Run the preparation tool from the repository root. The first example enables downloads explicitly. The second uses existing caches. The default preparation directory is `apps/android/.native-deps/audio8`.

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

Use `--incremental-graph FILE` to supply an existing compatible derived graph. Run `bootstrap_audio8.py --pack ZIP` to create a standard model ZIP. A private-use pack can add `--include-local-reference` and point `--reference-dir DIR` at your authorized `reference.json` and `reference.codes.i64.bin`. [Pack layout](TTS.md)

A standalone reference ZIP uses the reference transcript and its corresponding voice codes. This example targets Whale. Use `ellen` for Ellen or `default` for a shared reference.

```sh
python3 apps/android/tools/bootstrap_audio8.py --pack-reference /path/to/whale-reference.zip \
  --reference-dir /path/to/inputs --reference-voice whale
```

Use “导入 Audio8 模型或参考音” in the speech settings to import the ZIP. Use references within their authorization.

## Prepare MOSS-TTS-Nano

- Set `DSH_MOSS_SENTENCEPIECE_SOURCE_DIR` to a checkout of the [pinned SentencePiece source](https://github.com/google/sentencepiece/tree/31646a467d2051eb904e0b45de3a73e91fe1c1e3).
- For on-device WAV voice import, copy `moss_audio_tokenizer_encode.onnx` and `moss_audio_tokenizer_encode.data` from the [official OpenMOSS codec revision](https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/tree/ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae) into `apps/android/app/src/main/assets/moss_encoder/` locally.
- For Local TTS Full, set `DSH_MOSS_MODEL_DIR` to a prepared model root containing `MOSS-TTS-Nano-100M-ONNX/`, `MOSS-Audio-Tokenizer-Nano-ONNX/` and `voices/whale.json` plus `voices/ellen.json`. See [MOSS setup and measurements](MOSS-NANO.md) for pinned models and [Asset sources](../ASSET_SOURCES.md) for voices.

## Prepare rendering and character resources

Get the official Cubism Native SDK 5-r.4 and retain its licenses. Point `DSH_ANDROID_ASSETS_DIR` at your runtime resource root using paths corresponding to the APK's `assets/` directory. The motion bundle builds from the pinned sources and adapter in the repository. [Cubism SDK ZIP](https://cubism.live2d.com/sdk-native/bin/CubismSdkForNative-5-r.4.zip) · [Asset sources](../ASSET_SOURCES.md)

```sh
cd apps/android
python3 tools/bootstrap_cubism.py --archive /path/to/CubismSdkForNative-5-r.4.zip
cd motion
npm ci
npm run build
cd ..
export DSH_ANDROID_ASSETS_DIR=/path/to/runtime-assets
```

## Build

| Gradle property | APK contents | Initial speech option |
|---|---|---|
| `-PdshTtsEdition=local` | MOSS model, two character voices and the WAV encoder | On-device MOSS CPU synthesis |
| `-PdshTtsEdition=external` | Speech API and on-device runtimes | Speech API |

```sh
DSH_MOSS_MODEL_DIR=/path/to/moss-model bash ./gradlew --no-daemon -PdshTtsEdition=local assembleRelease
# Archive this APK before building the next edition.
bash ./gradlew --no-daemon -PdshTtsEdition=external assembleRelease
```

Both releases retain the MOSS WAV encoder and the optional Audio8 CPU runtime. The external build leaves out the MOSS model and bundled voices. For development with a bundled Audio8 model, use `-PdshTtsEdition=local -PdshLocalTtsEngine=audio8`. [Native build and signing](../apps/android/native/BUILDING.md)

| Variable | Contents |
|---|---|
| `DSH_MOSS_MODEL_DIR` | Local model root, including both voice presets |
| `DSH_MOSS_SENTENCEPIECE_SOURCE_DIR` | SentencePiece sources for the MOSS runtime |
| `DSH_ANDROID_ASSETS_DIR` | Runtime asset root, including `moss_encoder/` when supplied there |
| `DSH_AUDIO8_DEPS` | ONNX Runtime and other dependencies for the optional Audio8 backend |

## Signing and installation

After signing, open the APK from the phone's file manager. An in-place update requires a compatible certificate. For a separate development installation, add `-PdeploymentTestApplicationId=com.prts.client.deploytest`. [Signing and wired development installation](../apps/android/native/BUILDING.md#signing)
