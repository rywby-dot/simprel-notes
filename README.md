AI Made
Fully vibecode

Simprel-Notes is simple note app for android. Without internet, without cloud, without slop, fast and minimal. With markdown. Opens fast, recommend to use with phone's action button (I use nothing essential key)


https://github.com/user-attachments/assets/7d7d8a39-85d2-40f7-8ef1-f9e08953652d


<img width="228" height="640" alt="photo_2026-10-12_11-17-56" src="https://github.com/user-attachments/assets/6d22b0a0-6b10-4e2d-aa2d-1e776bb89d6b" /> <img width="228" height="640" alt="photo_2026-10-12_11-18-04" src="https://github.com/user-attachments/assets/3410fee9-7d21-4437-bee7-828db50e15a0" /> <img width="228" height="640" alt="photo_2026-10-12_11-18-01" src="https://github.com/user-attachments/assets/7ed4ba24-c570-41cd-ac9b-b45afc0a2f63" />

## Build

Requirements: JDK 21 and Android SDK Platform 37. Configure the SDK location through Android Studio, `ANDROID_HOME`, or `sdk.dir` in your local `local.properties`. The Gradle wrapper downloads Gradle and dependencies on the first build.

Development APK:

```sh
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Development APKs use a local debug key and may not update the published app.

Signed release:

```sh
./gradlew :app:exportReleaseApk
```

Output: `dist/Simprel-Notes.apk`. Set `SIMPREL_KEYSTORE` to your private keystore path, `SIMPREL_KEY_ALIAS` to its key alias, and `SIMPREL_STORE_PASSWORD` securely in your environment. Optionally set `SIMPREL_KEY_PASSWORD` if the key password differs. These settings work with Gradle and `scripts/build-local.py`. Without a custom path, the historical local key `.signing/simprel-notes.keystore` is used for compatibility with development installations. Never commit signing keys or passwords. Keep a secure key backup and use the same signing identity for updates.

On Windows, use `gradlew.bat` instead of `./gradlew`.

`scripts/build-local.py` is the alternative offline builder used during development. It requires an existing Gradle artifact cache, SDK Platform 37 at `platforms/android-37.0`, Build-Tools 36.0.0, and the local signing key. It compiles the app, runs JVM regression tests, compiles Android instrumentation tests, creates DEX with D8, aligns the APK, and signs it with apksigner. It is not a clean-machine setup script. Running Android instrumentation tests requires a device or emulator.

### Create a release key (Linux / zsh)

Before the first public release, create a dedicated key without overwriting the old one:

```sh
mkdir -p .signing
keytool -genkeypair -v -keystore .signing/simprel-release.jks \
  -storetype PKCS12 -alias simprel-release -keyalg RSA -keysize 3072 -validity 10000
chmod 600 .signing/simprel-release.jks
```

Enter a strong password at the prompt and keep the key and password backed up privately. Certificate identity fields are public. Then configure this shell session:

```sh
export SIMPREL_KEYSTORE="$PWD/.signing/simprel-release.jks"
export SIMPREL_KEY_ALIAS=simprel-release
read -rs 'SIMPREL_STORE_PASSWORD?Keystore password: '
echo
export SIMPREL_STORE_PASSWORD
./gradlew :app:exportReleaseApk
unset SIMPREL_STORE_PASSWORD
```

The `read` syntax above is for zsh; passwords are not placed in command history. For the offline builder, replace the Gradle command with `python3 scripts/build-local.py` (requires the cached tools/dependencies described above). Signing with a new key does not allow an ordinary update over an APK signed with the old key. Preserve existing notes before uninstalling; the app currently has no export feature.
