#!/usr/bin/env python3
"""Verify package identity, version and stable release certificate before upload."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--version', required=True)
parser.add_argument('--code', required=True)
parser.add_argument('--output', default='dist/release-assets')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '36.0.0'
expected_cert = (root / 'docs/release-certificate.sha256').read_text().strip()
output = Path(args.output)
output.mkdir(parents=True, exist_ok=True)
checksums = []
for device in ('mobile', 'tv'):
    apk = root / f'app-{device}/build/outputs/apk/release/app-{device}-release.apk'
    signature = subprocess.check_output([str(sdk / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)', signature)
    if certs != [expected_cert]:
        raise SystemExit('APK release certificate differs from the committed public digest')
    badging = subprocess.check_output([str(sdk / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    package = 'app.umuo.player' + ('.tv' if device == 'tv' else '')
    fields = dict(re.findall(r"\b(name|versionCode|versionName)='([^']*)'", badging.splitlines()[0]))
    if fields != {'name': package, 'versionCode': args.code, 'versionName': args.version}:
        raise SystemExit('APK package/version does not match release metadata')
    if 'application-debuggable' in badging:
        raise SystemExit('Release APK must not be debuggable')
    filename = f'UmuoPlayer-{args.version}-{device}-release.apk'
    shutil.copyfile(apk, output / filename)
    checksums.append(hashlib.sha256(apk.read_bytes()).hexdigest() + '  ' + filename)
    print(f'Verified {filename}: {package}, versionCode {args.code}, stable certificate')
(output / 'SHA256SUMS').write_text('\n'.join(checksums) + '\n')
