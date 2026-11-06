"""Copy frozen, published MEDIA/ASR artifacts without recomputation or HTTP.

This claim-only protocol trusts Java's frozen source configuration and published
manifest digests. It does not re-map provider raw results or require checkpoints;
an immediately preceding source may itself be a preparation-copy job.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import stat
import tempfile
from pathlib import Path, PurePosixPath
from uuid import UUID

from .segmentation import SegmentationError, _inputs
from .worker import StageContext, WorkerError, _json_bytes, _native_path


def _fail(reason: str = "INVALID_SOURCE") -> None:
    raise WorkerError("PREPARATION_REUSE_" + reason)


def _uuid(value: object) -> str:
    if not isinstance(value, str):
        _fail()
    try:
        if str(UUID(value)) != value:
            _fail()
    except ValueError:
        _fail()
    return value


def _hash(value: object) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{64}", value):
        _fail()
    return value


def _confined(root: Path, key: str, *, directory: bool = False) -> Path:
    """Check lexical components before resolution, including Windows junctions."""
    if (not isinstance(key, str) or not key or "\\" in key or ":" in key
            or "\x00" in key or key.startswith("/")
            or any(part in {"", ".", ".."} for part in key.split("/"))):
        _fail("UNSAFE_PATH")
    target = root / PurePosixPath(key)
    cursor = root
    for part in (None, *PurePosixPath(key).parts):
        if part is not None:
            cursor /= part
        info = _native_path(cursor).lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            _fail("UNSAFE_PATH")
        if stat.S_ISREG(info.st_mode) and info.st_nlink != 1:
            _fail("UNSAFE_PATH")
    if (not target.resolve().is_relative_to(root.resolve())
            or (directory and not _native_path(target).is_dir())
            or (not directory and not _native_path(target).is_file())):
        _fail("UNSAFE_PATH")
    return target


def _signature(info: os.stat_result) -> tuple:
    # Windows path stat and fstat may report different ctime semantics. The
    # pinned digest below proves bytes; these fields detect replacement/write.
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns


def _stream(context: StageContext, path: Path, *, sink=None, limit: int | None = None) -> tuple[str, bytes]:
    """Hash while copying, check cancellation per MiB and detect source mutation."""
    root = context.storage_root.resolve()
    key = path.relative_to(root).as_posix()
    path = _confined(root, key)
    before = _native_path(path).stat()
    digest, collected, size = hashlib.sha256(), bytearray(), 0
    with _native_path(path).open("rb") as handle:
        if _signature(os.fstat(handle.fileno())) != _signature(before):
            _fail("ARTIFACT_MISMATCH")
        while True:
            context.guard.check()
            block = handle.read(1024 * 1024)
            if not block:
                break
            size += len(block)
            if limit is not None:
                if size > limit:
                    _fail("INVALID_JSON")
                collected.extend(block)
            digest.update(block)
            if sink is not None:
                sink.write(block)
        if _signature(os.fstat(handle.fileno())) != _signature(before):
            _fail("ARTIFACT_MISMATCH")
    _confined(root, key)
    if _signature(_native_path(path).stat()) != _signature(before):
        _fail("ARTIFACT_MISMATCH")
    return digest.hexdigest(), bytes(collected)


def _pairs(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            _fail("INVALID_JSON")
        result[key] = value
    return result


def _json(context: StageContext, path: Path, digest: str, limit: int) -> object:
    actual, data = _stream(context, path, limit=limit)
    if actual != digest:
        _fail("ARTIFACT_MISMATCH")
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=_pairs,
                          parse_constant=lambda _: _fail("INVALID_JSON"))
    except (ValueError, UnicodeError, RecursionError):
        _fail("INVALID_JSON")


def _spec(context: StageContext, value: object) -> dict:
    fields = {"schema_version", "source_job_id", "video_id", "source_generation",
              "source_hash", "asr_model", "media", "asr"}
    if (not isinstance(value, dict) or set(value) != fields
            or type(value["schema_version"]) is not int or value["schema_version"] != 1
            or type(value["source_generation"]) is not int or value["source_generation"] <= 0):
        _fail()
    _uuid(value["source_job_id"])
    _uuid(value["video_id"])
    if (value["source_job_id"] == context.lease.job_id
            or value["video_id"] != context.lease.video_id
            or _hash(value["source_hash"]) != context.lease.source_hash):
        _fail("INPUT_MISMATCH")
    model = value["asr_model"]
    if (not isinstance(model, str) or not model.strip()
            or model != context.lease.config.get("asr_model", "paraformer-v2")):
        _fail("MODEL_MISMATCH")
    for kind in ("media", "asr"):
        stage = value[kind]
        if (not isinstance(stage, dict)
                or set(stage) != {"id", "attempt", "manifest_key", "manifest_hash"}
                or type(stage["attempt"]) is not int or stage["attempt"] <= 0):
            _fail()
        _uuid(stage["id"])
        _hash(stage["manifest_hash"])
    if value["media"]["id"] == value["asr"]["id"]:
        _fail()
    return value


def _published(context: StageContext, *, job_id: str, generation: int, stage_type: str,
               reference: dict) -> dict:
    """The frozen key identifies exactly one original fenced attempt."""
    root = context.storage_root.resolve()
    prefix = (f"jobs/{job_id}/stages/{reference['id']}/attempts/"
              f"{reference['attempt']}/")
    key = reference.get("manifest_key")
    if not isinstance(key, str) or not key.startswith(prefix):
        _fail("MANIFEST_MISMATCH")
    tail = key[len(prefix):].split("/")
    if len(tail) != 2 or tail[1] != "manifest.json" or not re.fullmatch(r"[A-Za-z0-9_-]{16,64}", tail[0]):
        _fail("MANIFEST_MISMATCH")
    path = _confined(root, key)
    manifest = _json(context, path, _hash(reference.get("manifest_hash")), 1024 * 1024)
    expected = {"schema_version": 1, "job_id": job_id, "video_id": context.lease.video_id,
                "analysis_generation": generation, "stage_id": reference["id"],
                "stage_type": stage_type, "attempt": reference["attempt"], "run_token": tail[0],
                "input_sha256": context.lease.source_hash, "data_kind": "model_result",
                "review_status": "DRAFT"}
    # JSON representation equality keeps bool distinct from integer identities.
    if (not isinstance(manifest, dict) or any(
            _json_bytes(manifest.get(field)) != _json_bytes(value) for field, value in expected.items())):
        _fail("MANIFEST_MISMATCH")
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list) or not 1 <= len(artifacts) <= 64:
        _fail("MANIFEST_MISMATCH")
    files, keys = {}, set()
    for item in artifacts:
        if not isinstance(item, dict) or set(item) != {"kind", "key", "sha256"}:
            _fail("MANIFEST_MISMATCH")
        kind, artifact_key = item["kind"], item["key"]
        if (not isinstance(kind, str) or not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", kind)
                or kind in files or not isinstance(artifact_key, str) or artifact_key in keys
                or not artifact_key.startswith(path.parent.relative_to(root).as_posix() + "/")):
            _fail("MANIFEST_MISMATCH")
        artifact_path = _confined(root, artifact_key)
        digest = _hash(item["sha256"])
        if _stream(context, artifact_path)[0] != digest:
            _fail("ARTIFACT_MISMATCH")
        files[kind] = {"path": artifact_path, "sha256": digest}
        keys.add(artifact_key)
    required = {"audio", "media_info"} if stage_type == "MEDIA" else {"transcript"}
    if not required.issubset(files):
        _fail("MANIFEST_MISMATCH")
    return files


def _publish(context: StageContext, files: dict, provenance: dict) -> list[dict]:
    """Publish one complete directory; an existing publication is immutable."""
    root = context.storage_root.resolve()
    current = _confined(root, context.lease.attempt_key, directory=True)
    if current.resolve() != context.directory.resolve():
        _fail("UNSAFE_PATH")
    names = {"audio": "audio.wav", "media_info": "media-info.json", "transcript": "transcript.json"}
    provenance_bytes = _json_bytes(provenance)
    expected = {names[kind]: item["sha256"] for kind, item in files.items()}
    expected["cache-provenance.json"] = hashlib.sha256(provenance_bytes).hexdigest()
    target = current / "preparation"
    temporary = Path(tempfile.mkdtemp(prefix=".preparation-", dir=_native_path(current)))
    try:
        for kind, item in files.items():
            with (temporary / names[kind]).open("xb") as handle:
                digest, _ = _stream(context, item["path"], sink=handle)
                handle.flush()
                os.fsync(handle.fileno())
            if digest != item["sha256"]:
                _fail("ARTIFACT_MISMATCH")
        with (temporary / "cache-provenance.json").open("xb") as handle:
            handle.write(provenance_bytes)
            handle.flush()
            os.fsync(handle.fileno())
        context.guard.check()
        _confined(root, context.lease.attempt_key, directory=True)
        if not _native_path(target).exists():
            try:
                os.rename(temporary, _native_path(target))
            except FileExistsError:
                pass
        _confined(root, context.lease.attempt_key + "/preparation", directory=True)
        if set(child.name for child in _native_path(target).iterdir()) != set(expected):
            _fail("OUTPUT_MISMATCH")
        for name, digest in expected.items():
            if _stream(context, target / name)[0] != digest:
                _fail("OUTPUT_MISMATCH")
        context.guard.check()
    finally:
        # Only our random staging directory is removed, never source or output.
        if temporary.exists():
            shutil.rmtree(temporary)
    # All paths and bytes were checked above with guard-aware streaming. Avoid
    # another uninterruptible hash pass over a potentially hours-long WAV.
    prefix = target.relative_to(root).as_posix() + "/"
    artifacts = [{"kind": kind, "key": prefix + names[kind], "sha256": item["sha256"]}
                 for kind, item in files.items()]
    artifacts.append({"kind": "cache_provenance", "key": prefix + "cache-provenance.json",
                      "sha256": expected["cache-provenance.json"]})
    return artifacts


def reuse_preparation(context: StageContext, value: object) -> list[dict]:
    try:
        return _reuse_preparation(context, value)
    except FileNotFoundError:
        _fail("MISSING_SOURCE")
    except OSError:
        _fail("STORAGE_ERROR")
    except (ValueError, TypeError, KeyError, RecursionError):
        _fail()


def _reuse_preparation(context: StageContext, value: object) -> list[dict]:
    context.guard.check()
    if context.lease.stage_type not in {"MEDIA", "ASR"}:
        _fail()
    source = _spec(context, value)
    source_video = _confined(context.storage_root.resolve(), context.lease.source_key)
    if _stream(context, source_video)[0] != source["source_hash"]:
        _fail("INPUT_MISMATCH")
    media = _published(context, job_id=source["source_job_id"], generation=source["source_generation"],
                       stage_type="MEDIA", reference=source["media"])
    info = _json(context, media["media_info"]["path"], media["media_info"]["sha256"], 1024 * 1024)
    if not isinstance(info, dict) or type(info.get("duration_ms")) is not int or info["duration_ms"] <= 0:
        _fail("INVALID_MEDIA")
    asr = _published(context, job_id=source["source_job_id"], generation=source["source_generation"],
                     stage_type="ASR", reference=source["asr"])
    transcript = _json(context, asr["transcript"]["path"], asr["transcript"]["sha256"], 8 * 1024 * 1024)
    try:
        _inputs(transcript, info["duration_ms"])
    except SegmentationError:
        _fail("TRANSCRIPT_MISMATCH")
    stage_type = context.lease.stage_type
    provenance = {"schema_version": 1, "reuse_mode": "published_preparation_copy",
                  "stage_type": stage_type, "source_job_id": source["source_job_id"],
                  "source_video_id": source["video_id"], "source_generation": source["source_generation"],
                  "source_hash": source["source_hash"], "asr_model": source["asr_model"],
                  "source_media_stage_id": source["media"]["id"],
                  "source_asr_stage_id": source["asr"]["id"],
                  "source_media_manifest_sha256": source["media"]["manifest_hash"],
                  "source_asr_manifest_sha256": source["asr"]["manifest_hash"],
                  "audio_sha256": media["audio"]["sha256"],
                  "source_transcript_sha256": asr["transcript"]["sha256"], "provider_submissions": 0}
    if stage_type == "ASR":
        matches = [item for item in context.lease.artifacts if item.get("stage_type") == "MEDIA"]
        if len(matches) != 1:
            _fail("MISSING_MEDIA")
        reference = matches[0]
        # The current MEDIA manifest is verified as rigorously as the source.
        manifest_key = reference.get("manifest_key", "")
        parts = manifest_key.split("/") if isinstance(manifest_key, str) else []
        if len(parts) != 8 or not parts[5].isdigit():
            _fail("MANIFEST_MISMATCH")
        current_media = _published(context, job_id=context.lease.job_id,
            generation=context.lease.generation, stage_type="MEDIA",
            reference={"id": reference["stage_id"], "attempt": int(parts[5]),
                       "manifest_key": manifest_key, "manifest_hash": reference["manifest_hash"]})
        if (current_media["audio"]["sha256"] != media["audio"]["sha256"]
                or current_media["media_info"]["sha256"] != media["media_info"]["sha256"]):
            _fail("AUDIO_MISMATCH")
        if "cache_provenance" not in current_media:
            _fail("MISSING_MEDIA")
        current_provenance = current_media["cache_provenance"]
        expected_provenance = dict(provenance, stage_type="MEDIA")
        actual_provenance = _json(context, current_provenance["path"],
                                  current_provenance["sha256"], 1024 * 1024)
        if _json_bytes(actual_provenance) != _json_bytes(expected_provenance):
            _fail("MEDIA_MISMATCH")
        context.provider_input_hash = media["audio"]["sha256"]
    files = ({kind: media[kind] for kind in ("audio", "media_info")} if stage_type == "MEDIA"
             else {"transcript": asr["transcript"]})
    return _publish(context, files, provenance)
