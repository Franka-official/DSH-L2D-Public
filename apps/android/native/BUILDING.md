# Android native source build

The CPU build uses the Audio8 JNI and the prepared ARM64 ONNX Runtime distribution. Cubism uses the official matched Framework and Core. Follow the source build guide for the SDK, runtime assets, Audio8 model, and Gradle commands. [Build guide](../../../docs/BUILDING.en.md) · [中文构建](../../../docs/BUILDING.md)

The GPU development build also takes matching MNN source and a static ARM64 archive. The optional Audio8 development configuration is described in the [build guide](../../../docs/BUILDING.en.md).

## Signing

An unset signing configuration produces an unsigned release APK. No release
task falls back to the debug keystore. To use the long-lived project release
identity, set external paths `DSH_ANDROID_KEYSTORE`,
`DSH_ANDROID_STORE_PASSWORD_FILE`, and optionally `DSH_ANDROID_KEY_ALIAS`
(default `dsh-l2d-release`) / `DSH_ANDROID_KEY_PASSWORD_FILE`.
Never commit a key, password, local.properties or signing configuration file.

### Sign your own build

The project's private signing key is not distributed with the source. Create
your own key once and keep it for later updates. These Bash commands prompt for
passwords without putting their values in command arguments or terminal output;
the key and password file stay outside the checkout:

```sh
set +x
umask 077
signing_dir="$HOME/.local/share/dsh-l2d-local-signing"
mkdir -p "$signing_dir"
keytool -genkeypair -storetype PKCS12 \
  -keystore "$signing_dir/my-release.p12" -alias dsh-l2d-local \
  -keyalg RSA -keysize 4096 -validity 10000
read -r -s -p 'Re-enter your keystore password: ' signing_password
printf '\n'
printf '%s\n' "$signing_password" > "$signing_dir/store.pass"
unset signing_password
export DSH_ANDROID_KEYSTORE="$signing_dir/my-release.p12"
export DSH_ANDROID_STORE_PASSWORD_FILE="$signing_dir/store.pass"
export DSH_ANDROID_KEY_ALIAS=dsh-l2d-local
bash ./gradlew --no-daemon -PdeploymentTestApplicationId=com.prts.client.deploytest assembleRelease
```

The signed file is `app/build/outputs/apk/release/app-release.apk`. The separate
application ID lets you try it without clearing the production app's data.
Omit that property only when intentionally building your own `com.prts.client`
distribution; a different key cannot overwrite an existing official install.
Never use the project's private key or commit your own signing files.

Copy the signed APK to the phone and open it from the file manager, allowing
installation from that source when prompted. This ordinary install does not
need developer options. Alternatively, for an authorized wired development
device, check `adb devices` and use `adb -s <device-serial> install -r` followed
by the APK path. Your own certificate/application ID does not automatically
verify AppLinks for the official domain.

The maintainers' legacy debug-key migration uses a separately reviewed SDK33
rotation/lineage signing step. The self-signing instructions above do not grant
that identity or provide that migration path.

Only ARM64/API26+ is currently declared. New phones, first-install domain
verification, full model provisioning, asset licensing and device compatibility
require their own evidence; compiling alone does not establish these results.
