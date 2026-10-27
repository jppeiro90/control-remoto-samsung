"""Descarga herramientas portátiles oficiales, sin cambiar PATH ni instalar servicios."""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import subprocess
import shutil
import sys
import urllib.request
import zipfile
import requests

ROOT = Path(__file__).resolve().parent
CACHE = Path(os.environ['LOCALAPPDATA']) / 'ControlSamsungBuild'
CACHE.mkdir(exist_ok=True)

def get_json(url):
    r = requests.get(url, timeout=60)
    r.raise_for_status()
    return r.json()

def setup(name, url, sha=None):
    destination = CACHE / name
    if destination.exists():
        return destination
    archive = CACHE / (name + '.zip')
    print('Descargando ' + name, flush=True)
    if not archive.exists():
        with requests.get(url, timeout=120, stream=True) as source, archive.open('wb') as out:
            source.raise_for_status()
            for chunk in source.iter_content(1024 * 1024):
                out.write(chunk)
    if sha and hashlib.sha256(archive.read_bytes()).hexdigest().lower() != sha.lower():
        raise RuntimeError('Checksum incorrecto: ' + name)
    destination.mkdir()
    with zipfile.ZipFile(archive) as z:
        z.extractall(destination)
    print('Preparado ' + name, flush=True)
    return destination

asset = get_json('https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows')[0]['binary']['package']
sha_response = requests.get('https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256', timeout=60)
sha_response.raise_for_status()
gradle_sha = sha_response.text.strip()
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    jobs = [pool.submit(setup, 'jdk17', asset['link'], asset['checksum']),
            pool.submit(setup, 'gradle', 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip', gradle_sha),
            pool.submit(setup, 'cmdline', 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip')]
    jdk_root, gradle_root, cmdline_root = [j.result() for j in jobs]

jdk = next(jdk_root.glob('jdk-*'))
gradle = gradle_root / 'gradle-8.11.1' / 'bin' / 'gradle.bat'
sdk = CACHE / 'sdk'
sdk.mkdir(exist_ok=True)
output = CACHE / 'outputs' / 'app'
env = dict(os.environ, JAVA_HOME=str(jdk), ANDROID_HOME=str(sdk), GRADLE_USER_HOME=str(CACHE / 'gradle-home'), CONTROL_SAMSUNG_BUILD_DIR=str(output))
sdkmanager = cmdline_root / 'cmdline-tools' / 'bin' / 'sdkmanager.bat'
print('Preparando SDK Android 35 y build-tools 35...', flush=True)
subprocess.run([str(sdkmanager), '--sdk_root=' + str(sdk), 'platforms;android-35', 'build-tools;35.0.0'],
               input='y\n' * 100, text=True, env=env, check=True)
(ROOT / 'local.properties').write_text('sdk.dir=' + str(sdk).replace('\\', '/') + '\n', encoding='utf-8')
print('Compilando APK y ejecutando pruebas...', flush=True)
tasks = sys.argv[1:] or ['testDebugUnitTest', 'assembleDebug', 'lintDebug']
subprocess.run([str(gradle), '--no-daemon', '--console=plain', '--project-cache-dir', str(CACHE / 'project-cache'),
                *tasks], cwd=ROOT, env=env, check=True)
target = ROOT.parent / 'Control-Samsung-Android.apk'
shutil.copy2(output / 'outputs/apk/debug/app-debug.apk', target)
print('APK: ' + str(target), flush=True)
