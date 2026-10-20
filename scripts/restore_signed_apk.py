#!/usr/bin/env python3
"""Restore the locally signed release from its public APK signing-block bytes.

The key and password never enter GitHub. Both complete APK hashes are mandatory.
"""
import base64
import hashlib
import json
from pathlib import Path
import sys


def restore(source, patch_file, destination):
    original = Path(source).read_bytes()
    patch = json.loads(Path(patch_file).read_text())
    if hashlib.sha256(original).hexdigest() != patch['unsigned_sha256']:
        raise ValueError('Unsigned APK checksum mismatch')
    signed = bytearray()
    for command in patch['commands']:
        if 'data_base64' in command:
            signed.extend(base64.b64decode(command['data_base64'], validate=True))
        else:
            offset, length = command['offset'], command['length']
            if not (0 <= offset <= len(original) and 0 <= length <= len(original) - offset):
                raise ValueError('Invalid APK content boundaries')
            signed.extend(original[offset:offset + length])
    if len(signed) != patch['signed_bytes']:
        raise ValueError('Signed APK size mismatch')
    if hashlib.sha256(signed).hexdigest() != patch['signed_sha256']:
        raise ValueError('Signed APK checksum mismatch')
    Path(destination).write_bytes(signed)


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('Usage: restore_signed_apk.py unsigned.apk signature.json signed.apk')
    restore(*sys.argv[1:])
