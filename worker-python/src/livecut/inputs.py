"""Small, catalog-free dataset manifests and split-leakage validation."""

from pathlib import Path
from typing import Any
from urllib.parse import urlparse


class DatasetValidationError(ValueError):
    pass


def validate_dataset_manifest(
    data: dict[str, Any], *, base_dir: Path, check_files: bool = True
) -> None:
    errors: list[str] = []
    if not isinstance(data, dict) or data.get("schema_version") != "1.0":
        raise DatasetValidationError("manifest schema_version must be 1.0")
    if data.get("data_kind") not in {"synthetic_fixture", "real_video_manifest"}:
        errors.append("data_kind must distinguish a fixture from real videos")
    entries = data.get("videos")
    if not isinstance(entries, list) or not entries:
        raise DatasetValidationError("videos must be a non-empty list")
    seen: set[str] = set()
    splits: dict[str, str] = {}
    for i, entry in enumerate(entries):
        prefix = f"videos[{i}]"
        if not isinstance(entry, dict):
            errors.append(f"{prefix} must be an object")
            continue
        for field in ("video_id", "original_video_id", "local_path", "source_type", "split"):
            if not isinstance(entry.get(field), str) or not entry[field].strip():
                errors.append(f"{prefix}.{field} must be a non-empty string")
        vid = entry.get("video_id")
        if isinstance(vid, str):
            if vid in seen:
                errors.append(f"{prefix}: duplicate video_id {vid}")
            seen.add(vid)
        split = entry.get("split")
        if split not in {"dev", "test"}:
            errors.append(f"{prefix}.split must be dev or test")
        original = entry.get("original_video_id")
        if isinstance(original, str) and isinstance(split, str):
            if original in splits and splits[original] != split:
                errors.append(f"{prefix}: original video occurs in both dev and test")
            splits[original] = split
        source = entry.get("source_type")
        if source not in {"public_video", "local_authorized", "synthetic_fixture"}:
            errors.append(f"{prefix}.source_type is unsupported")
        if data.get("data_kind") == "real_video_manifest" and source == "synthetic_fixture":
            errors.append(f"{prefix}: fixture cannot be represented as a real video")
        if source == "public_video":
            url = entry.get("source_url")
            parsed = urlparse(url if isinstance(url, str) else "")
            if parsed.scheme not in {"http", "https"} or not parsed.netloc:
                errors.append(f"{prefix}.source_url must be HTTP(S)")
        offset = entry.get("original_offset_ms", 0)
        if type(offset) is not int or offset < 0:
            errors.append(f"{prefix}.original_offset_ms must be a non-negative integer")
        if check_files and isinstance(entry.get("local_path"), str):
            video_path = Path(entry["local_path"])
            if not video_path.is_absolute():
                video_path = base_dir / video_path
            if not video_path.is_file():
                errors.append(f"{prefix}: local video does not exist")
    if errors:
        raise DatasetValidationError("; ".join(errors))
