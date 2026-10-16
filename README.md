# Simprel Notes

A small, offline Android notes app with Markdown and a Material 3 style interface. Fast to open, with configurable editor actions, gestures, note layouts, local reminders, and voice notes. Android 8.0+ (API 26).

AI-made / fully vibecoded.

Download apk [here](https://github.com/rywby-dot/simprel-notes/releases)

https://github.com/user-attachments/assets/dc20ec21-9430-48db-b4a3-ad1d3d3a9b4f

<img width="288" height="640" alt="photo_2026-10-12_19-50-05" src="https://github.com/user-attachments/assets/cff070a4-dc8a-46e9-a297-789deca0296a" /> <img width="288" height="640" alt="photo_2026-10-12_19-50-09" src="https://github.com/user-attachments/assets/5d095bae-7b43-4f88-a8c5-d7c51105b1f2" /> <img width="288" height="640" alt="photo_2026-10-12_19-50-13" src="https://github.com/user-attachments/assets/ddc3ad0c-02f0-498e-8eb4-913758e7588f" />

## Reminders (2.0)

Tap the alarm button before the microphone in the editor toolbar to choose a day, hour, and minute. Hours and minutes wrap around; dates extend up to one year ahead. Dates other than Today and Tomorrow include a localized short weekday, for example `17 Sat` or `2 Nov Mon`. Cancel leaves the reminder unchanged, Set keeps the current screen, and Set and exit saves and closes the app. Write some text before setting a reminder.

A reminder badge appears at the bottom right of a card (beside the cross in compact mode). Tap to change its time; hold to remove it, with optional confirmation in Settings. Removal shows an Undo panel by default; its own switch appears under deletion undo settings and uses the shared panel duration. Undo restores the original reminder time without changing the note text or order. When due, a local notification shows the raw note text and opens that note when tapped. A muted orange badge marks a due reminder until you view the note; its badge then disappears, and the note counts as having no reminder. Future reminders remain when editing a note. Reminders survive relaunch, reboot, updates, and deletion undo. Discard restores the reminder from when the editor was opened.

The filter button between quick deletion and the layout switch cycles through all notes, notes with reminders, and notes without reminders. Settings can reverse the order of the last two modes. The filter resets to all notes when you return to the app; opening the editor or settings and rotating the screen keep the current filter. Reordering a filtered list moves only its visible notes, preserving hidden notes in their canonical positions.

Android 13+ requires notification permission, and Android 12+ requires access to alarms and reminders for precise delivery. The picker directs you to the relevant Android settings when necessary; return and tap Set again. If exact alarm access is later revoked, pending reminders use Android's approximate delivery until access is restored. Notification channel settings and Android's force-stop restrictions still apply. No network or background service is used.

## Voice notes

Voice recordings are attachments to ordinary notes. In the editor, tap the microphone between Reminder and Delete. The toolbar microphone always starts a fresh recording, replacing an existing attachment only after Save or Save and exit; Cancel preserves the previous audio. Recording starts immediately after microphone access is allowed; the keyboard stays hidden. Pause switches the live spectrum to the complete waveform. The microphone above Play resumes recording and appends audio; Play/Pause below previews the recording. Tap or drag inside the waveform to seek, and drag its two edge handles to trim. Accessible seek and trim actions are available without separate sliders.

Cancel leaves the saved attachment unchanged. Save attaches the recording to the current note and returns to its editor; Save and exit also closes the app. Audio-only notes are valid. The attachment occupies a non-editable row above the text; tap it to reopen the audio editor. Editing or appending uses a temporary copy until saved. Discarding editor changes restores the entry snapshot, including its original recording. Backgrounding pauses recording and preview; rotation preserves the paused audio and trim selection. A recording pauses automatically after one hour.

Cards display the microphone and waveform above text in grid mode, or in the first third of a compact row. Tap the audio area for a mini player, or the text area for the note editor. The mini player has Close, a seek bar and Play/Pause, and closes automatically at the end. Notes with audio share text notes' canonical insertion, ordering, filtering, reminders, deletion and Undo rules. Reminder notifications show a microphone, a short waveform and the note text. Copy actions copy the raw text. Recordings stay in local mono WAV files; saved trims physically remove the excluded samples.

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

After a successful local build, run the device suites in a separate installation with its own notes and settings:

```sh
python3 scripts/build-copy-tests.py
adb install -r app/build/local/copy-device-tests/copy-tests.apk
adb shell am instrument -w com.simprel.notes.copytests/com.simprel.notes.CopyTests
adb shell am instrument -w com.simprel.notes.copytests/com.simprel.notes.SmokeTests
adb shell pm grant com.simprel.notes.copytests android.permission.POST_NOTIFICATIONS
adb shell appops set com.simprel.notes.copytests SCHEDULE_EXACT_ALARM allow
adb shell am instrument -w com.simprel.notes.copytests/com.simprel.notes.ReminderTests
adb shell pm grant com.simprel.notes.copytests android.permission.RECORD_AUDIO
adb shell am instrument -w com.simprel.notes.copytests/com.simprel.notes.VoiceTests
adb uninstall com.simprel.notes.copytests
```

Keep the device unlocked. Use the same signing environment for both Python scripts. `CopyTests` checks all copy button/swipe actions, clipboard contents, deletion, confirmation, undo, and crosses in both layouts/directions. It refuses to run against the production application ID. These tests replace the clipboard contents. `ReminderTests` additionally checks picker controls, saved times, badges in both layouts/directions, removal confirmation, filter cycling/order and lifecycle reset, filtered reordering, actual alarm delivery, notification text/navigation, completed reminder badges, and Set and exit. `VoiceTests` checks audio attachments with and without text, canonical insertion, preview/seek/append/trim, replacement and discard, card touch routing, mini player completion, custom reminder content, deletion undo, rotation/background behavior, and localized themes. Notification permission applies to Android 13+; the alarm app-op applies to Android 12+. If the phone blocks shell permission grants, enable notifications and alarms for **Simprel Copy Tests** through Android settings instead. `VoiceTests` handles the standard foreground microphone permission dialog. If the phone blocks this automation, open a note in the test app, tap the microphone, allow access while using the app, and cancel the recording before running it.

For changes to the UI, also check keyboard/scroll behavior, themes, English/Russian, and large text. See [AGENTS.md](AGENTS.md) for the code map and contribution rules.
