#!/usr/bin/env python3
"""Build an isolated device-test APK after scripts/build-local.py.
This uses a separate application ID and cannot access production notes.
"""
import os
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'app/build/local'
TEST = OUT / 'copy-device-tests'
TEST.mkdir(parents=True, exist_ok=True)
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
CACHE = Path(os.environ.get('GRADLE_ARTIFACT_CACHE', str(Path.home() / '.gradle/caches/modules-2/files-2.1')))
tools = SDK / 'build-tools/36.0.0'
android = SDK / 'platforms/android-37.0/android.jar'


def run(*args, **kwargs):
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True, **kwargs)


def artifact(group, name, version):
    matches = list((CACHE / group / name / version).glob('*/*.jar'))
    if len(matches) != 1:
        raise SystemExit(f'Missing or ambiguous cached artifact: {group}:{name}:{version}')
    return matches[0]


manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
manifest = manifest.replace('<manifest ', '<manifest package="com.simprel.notes.copytests" ', 1)
manifest = manifest.replace('android:name=".MainActivity"', 'android:name="com.simprel.notes.MainActivity"')
manifest = manifest.replace('android:label="Simprel Notes"', 'android:label="Simprel Copy Tests"')
manifest = manifest.replace('</manifest>', '\n'.join(
    f'<instrumentation android:name="com.simprel.notes.{suite}" android:targetPackage="com.simprel.notes.copytests"/>'
    for suite in ['CopyTests', 'SmokeTests', 'ReminderTests']) + '\n</manifest>')
(TEST / 'AndroidManifest.xml').write_text(manifest)
run(tools / 'aapt2', 'link', '-I', android, '--manifest', TEST / 'AndroidManifest.xml',
    '--min-sdk-version', '26', '--target-sdk-version', '37', '-o', TEST / 'unsigned.apk',
    '--java', TEST / 'generated', '--extra-packages', 'com.simprel.notes:io.noties.markwon',
    OUT / 'resources.zip', OUT / 'markwon-res.zip')
# Cloned resources must match the identifiers compiled into the original app.
if (TEST / 'generated/com/simprel/notes/R.java').read_text() != (OUT / 'generated/com/simprel/notes/R.java').read_text():
    raise SystemExit('Cloned resource identifiers differ: rebuild with scripts/build-local.py first.')
run(tools / 'd8', '--release', '--min-api', '26', '--lib', android, '--output', TEST,
    OUT / 'app.jar', OUT / 'tests.jar', OUT / 'resources.jar',
    artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.10'), OUT / 'markwon.jar',
    artifact('com.atlassian.commonmark', 'commonmark', '0.13.0'),
    artifact('androidx.annotation', 'annotation-jvm', '1.9.1'), OUT / 'strikethrough.jar',
    artifact('com.atlassian.commonmark', 'commonmark-ext-gfm-strikethrough', '0.13.0'))
with zipfile.ZipFile(TEST / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
    for dex in TEST.glob('classes*.dex'):
        archive.write(dex, dex.name)
run(tools / 'zipalign', '-f', '4', TEST / 'unsigned.apk', TEST / 'aligned.apk')
key = Path(os.environ.get('SIMPREL_KEYSTORE', str(ROOT / '.signing/simprel-notes.keystore'))).expanduser()
custom_key = 'SIMPREL_KEYSTORE' in os.environ
if custom_key and not os.environ.get('SIMPREL_STORE_PASSWORD'):
    raise SystemExit('Set SIMPREL_STORE_PASSWORD for the custom signing key.')
env = os.environ.copy()
env.setdefault('SIMPREL_STORE_PASSWORD', 'android')
env.setdefault('SIMPREL_KEY_PASSWORD', env['SIMPREL_STORE_PASSWORD'])
alias = env.get('SIMPREL_KEY_ALIAS', 'simprel-release' if custom_key else 'androiddebugkey')
run(tools / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', alias,
    '--ks-pass', 'env:SIMPREL_STORE_PASSWORD', '--key-pass', 'env:SIMPREL_KEY_PASSWORD',
    '--out', TEST / 'copy-tests.apk', TEST / 'aligned.apk', env=env)
print(f'Test APK: {TEST / "copy-tests.apk"}')
