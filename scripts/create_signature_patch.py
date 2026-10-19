#!/usr/bin/env python3
"""Transport public signing/layout bytes while keeping APK contents unchanged."""
import base64
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile


def create(source, signed, destination, certificate_sha256):
    original, result = Path(source).read_bytes(), Path(signed).read_bytes()
    commands, cursor = [], 0
    def data_offset(data, entry):
        name, extra = struct.unpack_from('<HH', data, entry.header_offset + 26)
        return entry.header_offset + 30 + name + extra
    def literal(data):
        if data:
            commands.append({'data_base64': base64.b64encode(data).decode()})
    with zipfile.ZipFile(source) as a, zipfile.ZipFile(signed) as b:
        if a.namelist() != b.namelist():
            raise ValueError('Signing changed APK entries')
        for entry in b.infolist():
            before = a.getinfo(entry.filename)
            x, y = data_offset(original, before), data_offset(result, entry)
            n = entry.compress_size
            if before.compress_size != n or original[x:x+n] != result[y:y+n]:
                raise ValueError('Signing changed APK payload: ' + entry.filename)
            literal(result[cursor:y])
            if n:
                commands.append({'offset': x, 'length': n})
            cursor = y + n
    literal(result[cursor:])
    patch = dict(unsigned_sha256=hashlib.sha256(original).hexdigest(),
                 signed_sha256=hashlib.sha256(result).hexdigest(),
                 signed_bytes=len(result), commands=commands,
                 certificate_sha256=certificate_sha256)
    Path(destination).write_text(json.dumps(patch, indent=2) + '\n')


if __name__ == '__main__':
    if len(sys.argv) != 5:
        raise SystemExit('Usage: create_signature_patch.py unsigned.apk signed.apk output.json certificate_sha256')
    create(*sys.argv[1:])
