"""Pinned font installation rejects corrupt/oversized bytes without replacing user files."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/install-caption-font.py"
spec = importlib.util.spec_from_file_location("caption_font_installer", SCRIPT)
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


def test_pinned_profile_matches_shared_contract():
    schema = json.loads((SCRIPT.parents[1] / "contracts/caption-render-spec.schema.json").read_text(encoding="utf-8"))
    font = schema["properties"]["font"]["properties"]
    assert installer.PROFILE_ID == font["profile_id"]["const"]
    assert installer.FONT_SHA256 == font["sha256"]["const"]
    assert installer.FONT_SIZE == font["size_bytes"]["const"]


def test_verified_install_reuses_bytes_without_network(tmp_path):
    data = b"OTTOsynthetic-font"
    digest = hashlib.sha256(data).hexdigest()
    calls = []

    def fetch(url, timeout):
        calls.append((url, timeout))
        return io.BytesIO(data)

    path = installer.install_asset(tmp_path, "font.otf", "https://example.invalid/font", len(data), digest, opener=fetch)
    assert path.read_bytes() == data
    assert installer.install_asset(tmp_path, "font.otf", "https://example.invalid/font", len(data), digest,
                                   opener=lambda *a, **k: pytest.fail("verified file must not redownload")) == path
    assert len(calls) == 1
    assert not list(tmp_path.glob("*.part"))


@pytest.mark.parametrize("download", [b"WRONG", b"longer-than-expected"])
def test_bad_download_is_never_published_and_owned_temp_is_cleaned(tmp_path, download):
    data = b"VALID"
    with pytest.raises(installer.FontInstallError):
        installer.install_asset(tmp_path, "font.otf", "https://example.invalid/font", len(data),
            hashlib.sha256(data).hexdigest(), opener=lambda *a, **k: io.BytesIO(download))
    assert list(tmp_path.iterdir()) == []


def test_wrong_existing_file_is_preserved_without_network(tmp_path):
    target = tmp_path / "font.otf"
    target.write_bytes(b"original")
    with pytest.raises(installer.FontInstallError, match="EXISTING_FONT_ASSET_HASH_MISMATCH"):
        installer.install_asset(tmp_path, "font.otf", "https://example.invalid/font", 5,
            hashlib.sha256(b"VALID").hexdigest(), opener=lambda *a, **k: pytest.fail("must not fetch"))
    assert target.read_bytes() == b"original"


def test_verify_only_missing_never_downloads_or_creates_directory(tmp_path):
    directory = tmp_path / "missing"
    with pytest.raises(installer.FontInstallError, match="CAPTION_FONT_NOT_INSTALLED"):
        installer.install_asset(directory, "font.otf", "https://example.invalid/font", 5,
            hashlib.sha256(b"VALID").hexdigest(), verify_only=True,
            opener=lambda *a, **k: pytest.fail("verify only must not fetch"))
    assert not directory.exists()
