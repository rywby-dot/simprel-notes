# Contributor guide for agents

## Scope and product

These instructions apply to the whole repository. Simprel Notes is a small, fast, offline Android notes app with a Material 3 style interface and Markdown. Keep changes focused on the requested behavior and consistent with existing interactions. Do not add cloud services, networking, analytics, UI frameworks, or dependencies without a concrete requirement.

The maintainer currently uses the same historical local signing key for development builds made by the Python builder and public APKs. Preserve that choice; do not generate or replace the maintainer's key as routine release preparation.

## Repository map

Production Kotlin is under `app/src/main/java/com/simprel/notes/`:

| File | Responsibility |
| --- | --- |
| `MainActivity.kt` | Activity lifecycle, editor/list/settings, preferences, localized context, colors, toolbar icons, navigation animations, gesture dispatch, confirmation and undo |
| `Note.kt` | Stable UUID identity and mutable raw text |
| `Store.kt` | Atomic JSON persistence and `settings` SharedPreferences |
| `EditorDraft.kt` | Immutable editor-entry snapshot for discarding autosaved changes |
| `InteractionRules.kt` | Platform-independent gestures, note ordering/coordinates, scroll anchoring, undo timing, copy action mapping, language fallback |
| `AnimatedNotesGrid.kt` | Stable card instances, grid/compact presentation, drag reorder, animations, enlarged delete-badge touch routing |
| `NotePreview.kt` | Card text rendering and clipping around the delete badge |
| `NoteSurfaceDrawable.kt` | Rounded card and border drawing |
| `MarkdownStyler.kt` | Markdown spans in the editable text without changing its raw contents |
| `MarkdownAlignment.kt` | Source/rendered alignment and whitespace helpers |
| `SettingToggle.kt`, `MaterialRadio.kt`, `RoundingSlider.kt` | Custom settings controls |
| `ScrollRail.kt` | Editor scrolling rail |

Other relevant paths:

- `app/src/main/res/values/strings.xml`: English strings; `values-ru/strings.xml`: Russian strings.
- `app/src/main/res/`: theme resources, colors, launcher/splash drawables. Screens are built programmatically, not through layout XML.
- `app/src/main/AndroidManifest.xml`: production manifest and activity, debugging disabled.
- `app/build.gradle.kts`: Android settings, dependencies, signing, version, release export task.
- `tests/InteractionRulesTest.kt`, `tests/MarkdownRulesTest.kt`: standalone Kotlin programs using `check`, run on the JVM by the local builder.
- `app/src/androidTest/java/com/simprel/notes/SmokeTests.kt`: framework Instrumentation suite for storage, rendering, layout, Markdown.
- `app/src/androidTest/java/com/simprel/notes/CopyTests.kt`: isolated device suite for copy actions, swipe, badge presses, confirmation and undo.
- `scripts/build-local.py`: offline compilation, JVM tests, device-test compilation, APK packaging/signing/verification.
- `scripts/build-copy-tests.py`: separate device-test APK containing the app and test classes.
- `dist/Simprel-Notes.apk`: ignored, signed output for distribution.

## Coding conventions

Use Kotlin, native Android Views, and the existing small helpers. Match surrounding formatting: four-space indentation, compact functions and view builders, descriptive local names. Avoid broad reformatting or unrelated refactors. Python helpers use the standard library.

Use existing `icon`, `tool`, `shape`, `dp`, colors, and settings control patterns. Toolbar glyphs are drawn in `Glyph` at the end of `MainActivity.kt`, with 48dp toolbar touch targets. Preserve the enlarged effective touch targets of smaller note delete badges.

Put visible text in both language resource files. Use `str(...)` in the activity so changing the selected language updates existing UI bindings. Settings have appearance bindings for live theme/language updates; new controls must participate. Include accessibility descriptions and selected/checked state where appropriate. Respect text scaling, theme colors, screen insets, and keyboard resizing.

Prefer pure functions in `InteractionRules.kt` or another small helper for rules that benefit from JVM regression coverage. Do not rewrite the app into Compose, introduce a large architecture layer, or replace the build system as incidental cleanup.

## Data and interaction invariants

- Notes are identified by ID, never by a visible index or displayed text. Reordering and reverse presentation must not change which note an action targets.
- `Store.notes` is the canonical order. Reverse layout changes presentation; dragging commits a new canonical order. Test both grid and compact modes in both directions.
- `Store.save()` writes raw `{id, text}` entries to `filesDir/notes.json` through `AtomicFile`, omitting blank notes. Preserve atomic writes, Unicode, line breaks, whitespace, and Markdown syntax.
- Editor text changes autosave. A new nonblank note is inserted into the store. Discard means restoring the immutable editor-entry snapshot, not merely skipping a final save. Keep `EditorDraft`, previous-last-note tracking, and failure rollback consistent.
- Reuse the existing deletion path for confirmation, persistence, rollback, and undo. Do not duplicate deletion logic for a new trigger.
- Undo preserves the deleted note's ID, text, and canonical index. Only the latest deletion is retained. Exiting after deletion keeps undo available on the next entry; navigation must not accidentally restart its timer.
- Copy the full raw note text, not a formatted preview or the selection. Copy-and-delete happens after deletion confirmation; cancellation must leave both the note and clipboard unchanged. A failed copy must not silently proceed to deletion.
- `copy_action` defaults to `copy_delete_exit`. Supported copy values are `copy_delete_exit`, `copy_stay`, `copy_list`, `copy_delete_list`, `copy_exit`; button and left swipe share `copyAction(...)`.
- `left_swipe_action` still defaults to `save_exit`; existing discard options remain supported. Right swipe returns to the list. Short/reversed/cancelled gestures must not commit an action. Preserve vertical scrolling, selection, long press, and multi-touch cancellation.
- A short cross press deletes; a long press copies and deletes. The badge event forwarding in `AnimatedNotesGrid` must still suppress the short click after a long press and avoid starting a card drag.
- Lifecycle code handles background/foreground entry, animation interruption, keyboard focus, previous-screen selection, and configuration changes. Read this code before changing navigation or gesture completion.
- New preferences need a stable key, fallback, localized labels, and inclusion in `resetDefaults()` when appropriate. Existing user preferences and notes must survive upgrades.

## Build environment and commands

Run commands from the repository root. Use JDK 21, Android SDK Platform 37; minimum Android API is 26. Gradle wrapper and Android plugin versions are declared in the repository; do not upgrade them merely to make a local environment convenient.

Fresh-checkout development build:

```sh
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Gradle discovers the SDK through `local.properties` / `ANDROID_HOME` and downloads dependencies when needed. Debug signing is Gradle's local Android debug identity, not necessarily the historical Python-builder identity.

Signed Gradle build:

```sh
./gradlew :app:exportReleaseApk
```

Preferred workflow in the maintainer's prepared local environment:

```sh
python3 scripts/build-local.py
```

This runs both standalone JVM suites, compiles production and instrumentation Kotlin, builds DEX/resources, aligns, signs, and verifies `dist/Simprel-Notes.apk`. It does not execute instrumentation tests. The manifest disables debugging regardless of the chosen signing identity.

The Python builders require:

- Python 3, `java`, `javac`, and `jar` on PATH.
- SDK Platform jar at `$ANDROID_HOME/platforms/android-37.0/android.jar`, Build-Tools 36.0.0; SDK defaults to `~/Android/Sdk`.
- Existing cached artifacts at `~/.gradle/caches/modules-2/files-2.1`, overridable with `GRADLE_ARTIFACT_CACHE`. Consult `artifact(...)` calls for exact versions, including Kotlin compiler 2.2.10, Markwon 4.6.2, CommonMark 0.13.0, AndroidX annotation 1.9.1, and auxiliary compiler jars.
- An existing signing key or the explicit custom signing environment below.

The local scripts do not read `sdk.dir` and do not download missing dependencies. A successful Gradle build does not guarantee that all exact Kotlin compiler artifacts needed by the scripts are cached. On a fresh machine, use Gradle for development; report missing prerequisites accurately instead of claiming an offline build was verified.

## Signing and release outputs

Both release builders default to `.signing/simprel-notes.keystore`, alias `androiddebugkey`, using the historical local credentials configured in the scripts. The private keystore is absent from public checkouts.

Custom signing uses `SIMPREL_KEYSTORE`, `SIMPREL_KEY_ALIAS`, `SIMPREL_STORE_PASSWORD`, and optionally `SIMPREL_KEY_PASSWORD` (defaults to the store password). A custom path requires an explicit store password; its default alias is `simprel-release`. See README for a one-time key creation example. Reuse a contributor's existing key when available. Clear all four variables or use a subshell when switching back to the default identity.

Do not print secrets, commit keystores, or replace an existing key. Backups and signing identity are the maintainer's responsibility. Ordinary updates require the same signing identity; do not uninstall a user's production app to bypass a signature mismatch. A differently named APK with the same application ID still shares the same installation/data.

Release output is always `dist/Simprel-Notes.apk` and is overwritten. When a version bump is requested, update `versionCode` and `versionName` in **both** `app/build.gradle.kts` and the `aapt2 link` invocation in `scripts/build-local.py`. SDK/min/target changes likewise need both build paths updated. Verify signing and output location before reporting a release artifact as ready. Building an APK does not authorize publishing a GitHub release.

## Test workflow

Use checks appropriate to the changes. For documentation-only edits, validate commands/paths against the code and run `git diff --check`; no APK rebuild is necessary. For Kotlin/resources changes in the prepared environment, use `python3 scripts/build-local.py`. Gradle build/test tasks do not execute the standalone programs in `tests/`.

Existing device smoke suite through Gradle:

```sh
./gradlew :app:connectedDebugAndroidTest
```

It targets the debug app and uses isolated test storage/preferences for storage checks. Its runner is `SmokeTests`; it does not run `CopyTests`.

For the complete device suites, first successfully run the local builder, then use the **same signing environment**:

```sh
python3 scripts/build-copy-tests.py
adb install -r app/build/local/copy-device-tests/copy-tests.apk
adb shell am instrument -w com.simprel.notes.copytests/com.simprel.notes.CopyTests
adb shell am instrument -w com.simprel.notes.copytests/com.simprel.notes.SmokeTests
adb uninstall com.simprel.notes.copytests
```

The clone uses application ID `com.simprel.notes.copytests`, its own storage/preferences, and the original app classes and resource identifiers. The clone builder checks that resource IDs match the compiled app. Do not manually change only the APK filename or remove this check. `CopyTests` refuses to run unless the target package ends in `.copytests`; it clears that target's settings/notes during testing. Never point it at production or weaken its isolation guard.

The phone must remain unlocked; UI tests open/close screens and replace clipboard contents. Use synthetic notes, not user notes. Device installation/testing must be within the user's authorized scope. Remove only the test installation and temporary artifacts you created after completion; preserve production data/settings.

Read instrumentation output, not just the ADB exit status: the shell can exit successfully while the test process crashes. Look for the suite's explicit PASS/completion message and inspect the crash log on failure. Do not report merely compiled device tests as executed tests.

Regression coverage should protect behavior rather than mirror implementation. Extend the standalone suites for pure rules; extend device suites for Android storage, spans, layout, clipboard, gesture routing, and lifecycle behavior. If a standalone test needs another production file, add it to the local builder's explicit JVM compiler inputs. Device suites are compiled from all `.kt` files under `androidTest`.

For layout tests, call `requestLayout()` before remeasuring after changing fields such as `reverse`; unchanged measurement specs/bounds can otherwise leave old coordinates in place.

For UI changes, check the relevant combinations:

- Grid/compact, normal/reverse order, reordered IDs, empty and nonempty lists.
- English/Russian, light/dark/AMOLED, small and large text, supported rounding/border settings.
- Keyboard open/closed, long-note scrolling, selection, cancelled and committed gestures.
- Confirmation cancel/accept, undo on the current screen and after app exit, relaunch/persistence.
- Exact copied text including Markdown, Unicode, line breaks, and trailing spaces.

## Finishing a contribution

Inspect the existing diff before editing and preserve unrelated work. Keep README accurate when commands, dependencies, output paths, or test behavior change. Avoid adding generated files, SDK paths, signing material, or local credentials to version control; build directories, `dist/`, and `.signing/` are ignored.

Run `git diff --check`. Report the implemented behavior, actual checks and results, and any unverified environment-dependent part. When an APK was requested, complete the requested build and give its path. Do not install, publish, commit, or push beyond the user's authorized scope.
