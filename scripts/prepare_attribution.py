#!/usr/bin/env python3
"""Copy an unmodified Google Translate badge from Google's official attribution archive."""
import base64
import hashlib
import io
from pathlib import Path
import urllib.request
import zipfile

destination = Path("app/src/main/res/drawable/google_translate_attribution.png")
if destination.exists():
    print("Using checked-in Google Translate attribution asset.")
else:
    url = "https://docs.cloud.google.com/static/translate/images/google-translate-attribution.zip"
    with urllib.request.urlopen(url, timeout=30) as response:
        payload = response.read(4 * 1024 * 1024 + 1)
    if len(payload) > 4 * 1024 * 1024:
        raise ValueError("Attribution archive exceeds limit")
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        candidates = [info for info in archive.infolist()
                      if info.filename.lower().endswith(".png") and not info.filename.startswith("__MACOSX/")
                      and info.file_size < 512 * 1024]
        print("Official badge candidates:", [info.filename for info in candidates])
        if not candidates:
            raise ValueError("No official PNG attribution asset in archive")
        candidates.sort(key=lambda info: ("white" not in info.filename.lower(), info.filename))
        info = candidates[0]
        image = archive.read(info)
        if not image.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError("Expected PNG")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(image)
        print("Selected original asset:", info.filename)
        print("Attribution SHA256:", hashlib.sha256(image).hexdigest())
        # The original public logo is logged once so it can be pinned in source without modification.
        print("ATTRIBUTION_PNG_BASE64=" + base64.b64encode(image).decode("ascii"))
