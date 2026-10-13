#!/usr/bin/env python3
"""Offline verification build using an existing Android SDK and Gradle artifact cache.
Normal builds should use ./gradlew :app:exportReleaseApk instead.
"""
import os
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get('GRADLE_ARTIFACT_CACHE', str(Path.home() / '.gradle/caches/modules-2/files-2.1')))
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
OUT = ROOT / 'app/build/local'
OUT.mkdir(parents=True, exist_ok=True)


def artifact(group, name, version, suffix='jar'):
    matches = list((CACHE / group / name / version).glob(f'*/*.{suffix}'))
    if len(matches) != 1:
        raise SystemExit(f'Missing or ambiguous cached artifact: {group}:{name}:{version}')
    return matches[0]


def run(*args):
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)


android = SDK / 'platforms/android-37.0/android.jar'
tools = SDK / 'build-tools/36.0.0'
stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.10')
compiler_jars = [artifact('org.jetbrains.kotlin', name, '2.2.10') for name in
                 ['kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime', 'kotlin-daemon-embeddable']]
compiler_jars += list((CACHE / 'org.jetbrains.kotlin/kotlin-reflect').glob('*/*/*.jar'))[:1]
compiler_jars += list((CACHE / 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm').glob('*/*/*.jar'))[:1]
compiler_jars += list((CACHE / 'org.jetbrains/annotations').glob('*/*/*.jar'))[:1]
markwon = OUT / 'markwon.jar'
with zipfile.ZipFile(artifact('io.noties.markwon', 'core', '4.6.2', 'aar')) as z:
    markwon.write_bytes(z.read('classes.jar'))
    library_res = OUT / 'markwon-res/values'
    library_res.mkdir(parents=True, exist_ok=True)
    (library_res / 'values.xml').write_bytes(z.read('res/values/values.xml'))
strike = OUT / 'strikethrough.jar'
with zipfile.ZipFile(artifact('io.noties.markwon', 'ext-strikethrough', '4.6.2', 'aar')) as z:
    strike.write_bytes(z.read('classes.jar'))
strike_parser = artifact('com.atlassian.commonmark', 'commonmark-ext-gfm-strikethrough', '0.13.0')
commonmark = artifact('com.atlassian.commonmark', 'commonmark', '0.13.0')
annotations = artifact('androidx.annotation', 'annotation-jvm', '1.9.1')
run(tools / 'aapt2', 'compile', '--dir', ROOT / 'app/src/main/res', '-o', OUT / 'resources.zip')
run(tools / 'aapt2', 'compile', '--dir', OUT / 'markwon-res', '-o', OUT / 'markwon-res.zip')
# aapt2 standalone needs a manifest package, which AGP normally supplies.
manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text().replace('<manifest ', '<manifest package="com.simprel.notes" ', 1)
(OUT / 'AndroidManifest.xml').write_text(manifest)
run(tools / 'aapt2', 'link', '-I', android, '--manifest', OUT / 'AndroidManifest.xml',
    '--min-sdk-version', '26', '--target-sdk-version', '37', '--version-code', '22', '--version-name', '2.0',
    '-o', OUT / 'unsigned.apk', '--java', OUT / 'generated', '--extra-packages', 'io.noties.markwon',
    OUT / 'resources.zip', OUT / 'markwon-res.zip')
(OUT / 'resource-classes').mkdir(exist_ok=True)
run('javac', '--release', '17', '-d', OUT / 'resource-classes', *sorted((OUT / 'generated').rglob('*.java')))
run('jar', 'cf', OUT / 'resources.jar', '-C', OUT / 'resource-classes', '.')
classpath = os.pathsep.join(str(p) for p in [android, stdlib, markwon, commonmark, annotations, strike, strike_parser, OUT / 'resources.jar'])
run('java', '-cp', os.pathsep.join(map(str, compiler_jars)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath,
    '-d', OUT / 'app.jar', *sorted((ROOT / 'app/src/main/java').rglob('*.kt')))
run('java', '-cp', os.pathsep.join(map(str, compiler_jars)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath + os.pathsep + str(OUT / 'app.jar'),
    '-d', OUT / 'tests.jar', *sorted((ROOT / 'app/src/androidTest/java').rglob('*.kt')))
# Execute the platform-independent regression tests on the JVM.
run('java', '-cp', os.pathsep.join(map(str, compiler_jars)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', stdlib,
    '-d', OUT / 'rules-tests.jar', ROOT / 'app/src/main/java/com/simprel/notes/InteractionRules.kt',
    ROOT / 'app/src/main/java/com/simprel/notes/MarkdownAlignment.kt', ROOT / 'app/src/main/java/com/simprel/notes/Note.kt', ROOT / 'app/src/main/java/com/simprel/notes/EditorDraft.kt', ROOT / 'app/src/main/java/com/simprel/notes/ReminderTime.kt', ROOT / 'tests/InteractionRulesTest.kt')
run('java', '-cp', os.pathsep.join(map(str, [OUT / 'rules-tests.jar', stdlib])), 'com.simprel.notes.InteractionRulesTestKt')
markdown_test_classpath = os.pathsep.join(map(str, [stdlib, commonmark, strike_parser]))
run('java', '-cp', os.pathsep.join(map(str, compiler_jars)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', markdown_test_classpath,
    '-d', OUT / 'markdown-tests.jar', ROOT / 'tests/MarkdownRulesTest.kt')
run('java', '-cp', str(OUT / 'markdown-tests.jar') + os.pathsep + markdown_test_classpath, 'com.simprel.notes.MarkdownRulesTestKt')
run(tools / 'd8', '--release', '--min-api', '26', '--lib', android, '--output', OUT,
    OUT / 'app.jar', OUT / 'resources.jar', stdlib, markwon, commonmark, annotations, strike, strike_parser)
with zipfile.ZipFile(OUT / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    for dex in OUT.glob('classes*.dex'):
        z.write(dex, dex.name)
run(tools / 'zipalign', '-f', '4', OUT / 'unsigned.apk', OUT / 'aligned.apk')
# Keep the existing certificate so the installed 1.0 can be updated without data loss.
# The key lives outside build/, so Gradle clean cannot remove it.
key = Path(os.environ.get('SIMPREL_KEYSTORE', str(ROOT / '.signing/simprel-notes.keystore'))).expanduser()
if not key.exists():
    raise SystemExit('Signing key missing: restore .signing/simprel-notes.keystore before building an update.')
apk = ROOT / 'dist/Simprel-Notes.apk'
apk.parent.mkdir(parents=True, exist_ok=True)
custom_key = 'SIMPREL_KEYSTORE' in os.environ
if custom_key and not os.environ.get('SIMPREL_STORE_PASSWORD'):
    raise SystemExit('Set SIMPREL_STORE_PASSWORD for the custom signing key.')
os.environ.setdefault('SIMPREL_STORE_PASSWORD', 'android')
os.environ.setdefault('SIMPREL_KEY_PASSWORD', os.environ['SIMPREL_STORE_PASSWORD'])
alias = os.environ.get('SIMPREL_KEY_ALIAS', 'simprel-release' if custom_key else 'androiddebugkey')
run(tools / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', alias,
    '--ks-pass', 'env:SIMPREL_STORE_PASSWORD', '--key-pass', 'env:SIMPREL_KEY_PASSWORD',
    '--out', apk, OUT / 'aligned.apk')
run(tools / 'apksigner', 'verify', '--verbose', apk)
print(f'APK: {apk}')
