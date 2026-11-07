"""Install the pinned open font used by caption MP4 rendering (no model calls)."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

PROFILE_ID = "noto-sans-cjk-sc-2.004"
UPSTREAM_COMMIT = "523d033d6cb47f4a80c58a35753646f5c3608a78"
BASE_URL = f"https://raw.githubusercontent.com/notofonts/noto-cjk/{UPSTREAM_COMMIT}"
FONT_NAME = "NotoSansCJKsc-Regular.otf"
FONT_SIZE = 16437364
FONT_SHA256 = "2c76254f6fc379fddfce0a7e84fb5385bb135d3e399294f6eeb6680d0365b74b"
LICENSE_SIZE = 4301
LICENSE_SHA256 = "6a73f9541c2de74158c0e7cf6b0a58ef774f5a780bf191f2d7ec9cc53efe2bf2"
DEFAULT_DIRECTORY = Path(__file__).resolve().parents[1] / "data/fonts" / PROFILE_ID


class FontInstallError(Exception):
    pass


def file_matches(path: Path, size: int, digest: str) -> bool:
    if path.is_symlink() or not path.is_file() or path.stat().st_size != size:
        return False
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            value.update(chunk)
    return value.hexdigest() == digest


def install_asset(directory: Path, name: str, url: str, size: int, digest: str,
                  *, verify_only: bool = False, opener=urllib.request.urlopen) -> Path:
    """A wrong existing file is preserved; unverified downloads are never published."""
    if Path(name).name != name or not name or name in {".", ".."}:
        raise FontInstallError("INVALID_FONT_ASSET_NAME")
    target = directory / name
    if target.is_symlink():
        raise FontInstallError("UNSAFE_FONT_ASSET_TARGET")
    if target.exists():
        if file_matches(target, size, digest):
            return target
        raise FontInstallError("EXISTING_FONT_ASSET_HASH_MISMATCH")
    if verify_only:
        raise FontInstallError("CAPTION_FONT_NOT_INSTALLED")
    directory.mkdir(parents=True, exist_ok=True)
    for attempt in range(2):
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(prefix=".caption-font-", suffix=".part",
                                             dir=directory, delete=False) as stream:
                temporary = Path(stream.name)
                received, hashed = 0, hashlib.sha256()
                with opener(url, timeout=25) as response:
                    while True:
                        chunk = response.read(min(65536, size + 1 - received))
                        if not chunk:
                            break
                        received += len(chunk)
                        if received > size:
                            raise FontInstallError("FONT_ASSET_TOO_LARGE")
                        stream.write(chunk)
                        hashed.update(chunk)
                if received != size or hashed.hexdigest() != digest:
                    raise FontInstallError("FONT_ASSET_HASH_MISMATCH")
                stream.flush()
                os.fsync(stream.fileno())
            if target.is_symlink() or (target.exists() and not file_matches(target, size, digest)):
                raise FontInstallError("EXISTING_FONT_ASSET_HASH_MISMATCH")
            os.replace(temporary, target)
            return target
        except urllib.error.HTTPError as error:
            if attempt or error.code not in {429, 500, 502, 503, 504}:
                raise FontInstallError("FONT_ASSET_DOWNLOAD_FAILED") from None
        except (urllib.error.URLError, TimeoutError):
            if attempt:
                raise FontInstallError("FONT_ASSET_DOWNLOAD_FAILED") from None
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
        time.sleep(1)
    raise FontInstallError("FONT_ASSET_DOWNLOAD_FAILED")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_DIRECTORY)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args(argv)
    try:
        directory = args.output_dir.resolve()
        font = install_asset(directory, FONT_NAME,
            f"{BASE_URL}/Sans/OTF/SimplifiedChinese/{FONT_NAME}", FONT_SIZE, FONT_SHA256,
            verify_only=args.verify_only)
        install_asset(directory, "LICENSE.font.txt", f"{BASE_URL}/LICENSE",
                      LICENSE_SIZE, LICENSE_SHA256, verify_only=args.verify_only)
        print(json.dumps({"status": "ready", "profile_id": PROFILE_ID, "path": str(font),
                          "size_bytes": FONT_SIZE, "sha256": FONT_SHA256,
                          "license_sha256": LICENSE_SHA256}, ensure_ascii=False))
        return 0
    except FontInstallError as error:
        print(json.dumps({"status": "error", "error_code": str(error)}))
        return 1
    except OSError:
        print(json.dumps({"status": "error", "error_code": "FONT_ASSET_STORAGE_UNAVAILABLE"}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
