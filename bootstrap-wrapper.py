#!/usr/bin/env python3
"""Restore the upstream Gradle wrapper JAR, verifying its original Git blob hash."""
import hashlib
from pathlib import Path
import urllib.request

commit = '15948a12185e3ac8a27671a8c3512a094f734dd7'
expected_blob = '8bdaf60c75ab801e22807dde59e12a8735a34077'
target = Path(__file__).parent / 'gradle/wrapper/gradle-wrapper.jar'
with urllib.request.urlopen(f'https://raw.githubusercontent.com/SiRan-Dev/PhotoTimeFixer/{commit}/gradle/wrapper/gradle-wrapper.jar', timeout=45) as response:
    data = response.read()
actual_blob = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
if actual_blob != expected_blob:
    raise SystemExit('Gradle wrapper checksum mismatch; nothing written')
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(data)
print('Verified and restored gradle-wrapper.jar')
