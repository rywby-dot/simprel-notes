# Simprel Notes

A small, offline Android notes app with Markdown and a Material 3 style interface. Fast to open, with configurable editor actions, gestures, and note layouts. Android 8.0+ (API 26).

AI-made / fully vibecoded.

install [here.](https://github.com/rywby-dot/simprel-notes/releases/download/v1.20/Simprel-Notes.apk)

https://github.com/user-attachments/assets/dc20ec21-9430-48db-b4a3-ad1d3d3a9b4f

<img width="1152" height="2560" alt="photo_2026-10-12_19-50-05" src="https://github.com/user-attachments/assets/cff070a4-dc8a-46e9-a297-789deca0296a" />
<img width="1152" height="2560" alt="photo_2026-10-12_19-50-09" src="https://github.com/user-attachments/assets/5d095bae-7b43-4f88-a8c5-d7c51105b1f2" />
<img width="1152" height="2560" alt="photo_2026-10-12_19-50-13" src="https://github.com/user-attachments/assets/ddc3ad0c-02f0-498e-8eb4-913758e7588f" />

## Build

Use JDK 21 and Android SDK Platform 37. Set `ANDROID_HOME` or `sdk.dir` in `local.properties`; Gradle downloads its dependencies on the first build. Run commands from the repository root. On Windows, use `gradlew.bat`.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds use your local Android debug key. They cannot update a release signed with a different key.

To build a signed release:

```sh
./gradlew :app:exportReleaseApk
```

Output: `dist/Simprel-Notes.apk` (overwritten). By default, signing uses `.signing/simprel-notes.keystore`, which is private and absent from a fresh clone. Contributors can build debug APKs without it, or create their own key once:

```sh
keytool -genkeypair -v -keystore "$HOME/simprel-release.jks" \
  -storetype PKCS12 -alias simprel-release -keyalg RSA -keysize 3072 -validity 10000
```

Use that key for release builds (zsh):

```zsh
(
  export SIMPREL_KEYSTORE="$HOME/simprel-release.jks"
  export SIMPREL_KEY_ALIAS=simprel-release
  unset SIMPREL_KEY_PASSWORD
  read -rs 'SIMPREL_STORE_PASSWORD?Keystore password: '
  echo
  export SIMPREL_STORE_PASSWORD
  ./gradlew :app:exportReleaseApk
)
```

`SIMPREL_KEY_PASSWORD` is optional when the key and keystore passwords match. Keep the existing signing key for updates; never commit keys or passwords. Uninstalling to switch signatures deletes local notes.

## Test

The existing storage, layout, and Markdown device tests can run through Gradle:

```sh
./gradlew :app:connectedDebugAndroidTest
```

The alternative local builder also runs both JVM regression suites and compiles the device tests:

```sh
python3 scripts/build-local.py
```

It produces the same signed `dist/Simprel-Notes.apk`, with debugging disabled. It requires Python 3, Build-Tools 36.0.0, SDK at `$ANDROID_HOME/platforms/android-37.0` (default SDK: `~/Android/Sdk`), and the exact cached artifacts listed in the script, including Kotlin 2.2.10. It does not download dependencies or run device tests. `GRADLE_ARTIFACT_CACHE` overrides the default `~/.gradle/caches/modules-2/files-2.1`. Both builders accept the signing variables above; a regular Gradle build does not run the standalone suites in `tests/`.

After a successful local build, run both device suites in a separate installation with its own notes and settings:

```sh
python3 scripts/build-copy-tests.py
adb install -r app/build/local/copy-device-tests/copy-tests.apk
adb shell am instrument -w com.simprel.notes.copytests/com.simprel.notes.CopyTests
adb shell am instrument -w com.simprel.notes.copytests/com.simprel.notes.SmokeTests
adb uninstall com.simprel.notes.copytests
```

Keep the device unlocked. Use the same signing environment for both Python scripts. `CopyTests` checks all copy button/swipe actions, clipboard contents, deletion, confirmation, undo, and crosses in both layouts/directions. It refuses to run against the production application ID. These tests replace the clipboard contents.

For changes to the UI, also check keyboard/scroll behavior, themes, English/Russian, and large text. See [AGENTS.md](AGENTS.md) for the code map and contribution rules.
