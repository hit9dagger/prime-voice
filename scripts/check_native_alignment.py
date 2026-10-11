#!/usr/bin/env python3
"""Verify ARM64 native ELF loading requirements for Android 16 KiB pages."""
import struct
import sys
import zipfile
PAGE = 16384

def check_library(name, data):
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"{name}: expected little-endian ELF64")
    if struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError(f"{name}: expected ARM64 machine code")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    if size < 56 or count == 0 or offset + size * count > len(data):
        raise ValueError(f"{name}: invalid ELF program header")
    loads = 0
    for index in range(count):
        header = offset + size * index
        if struct.unpack_from("<I", data, header)[0] != 1:
            continue
        loads += 1
        file_offset, address = struct.unpack_from("<QQ", data, header + 8)
        alignment = struct.unpack_from("<Q", data, header + 48)[0]
        if alignment < PAGE or alignment & (alignment - 1):
            raise ValueError(f"{name}: invalid PT_LOAD alignment {alignment}; need >= 16 KiB")
        if address % PAGE != file_offset % PAGE:
            raise ValueError(f"{name}: PT_LOAD address and offset are not 16 KiB congruent")
    if loads == 0:
        raise ValueError(f"{name}: no loadable segments")
    print(f"16 KiB ELF OK: {name} ({loads} loadable segments)")

def main():
    with zipfile.ZipFile(sys.argv[1]) as archive:
        libraries = [n for n in archive.namelist() if n.startswith("lib/") and n.endswith(".so")]
        if not libraries:
            raise ValueError("APK has no native libraries; expected Vosk and JNA")
        for name in libraries:
            if not name.startswith("lib/arm64-v8a/"):
                raise ValueError(f"Unexpected architecture: {name}")
            check_library(name, archive.read(name))
        for required in ("libvosk.so", "libjnidispatch.so"):
            if not any(n.endswith("/" + required) for n in libraries):
                raise ValueError(f"APK is missing {required}")
if __name__ == "__main__":
    main()
