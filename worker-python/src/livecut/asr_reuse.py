"""Explicit read-only reuse of an already published ASR transcript.

The public stage manifest pins the transcript. Raw/checkpoint files are only
used to prove that today's mapper produces exactly that pinned transcript;
their current hashes are audit evidence, not historical integrity guarantees.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import tempfile
from pathlib import Path, PurePosixPath
from uuid import UUID

from .asr import ASRError, _base_url, map_transcript
from .worker import StageContext, WorkerError, _native_path, _write, sha256


def _fail(code: str = "ASR_REUSE_INVALID_SOURCE") -> None:
    raise WorkerError(code)


def _uuid(value: object) -> str:
    if not isinstance(value, str):
        _fail()
    try:
        if str(UUID(value)) != value:
            _fail()
    except (ValueError, AttributeError):
        _fail()
    return value


def _hash(value: object) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{64}", value):
        _fail()
    return value


def _canonical(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True,
                      separators=(",", ":")).encode("utf-8")


def _pairs(items: list[tuple[str, object]]) -> dict:
    result: dict = {}
    for key, value in items:
        if key in result:
            _fail("ASR_REUSE_INVALID_JSON")
        result[key] = value
    return result


def _json(path: Path, limit: int) -> tuple[object, bytes]:
    with _native_path(path).open("rb") as handle:
        data = handle.read(limit + 1)
    if len(data) > limit:
        _fail("ASR_REUSE_INVALID_JSON")
    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=_pairs,
                           parse_constant=lambda _: _fail("ASR_REUSE_INVALID_JSON"))
    except (ValueError, UnicodeError, RecursionError):
        _fail("ASR_REUSE_INVALID_JSON")
    return value, data


def _confined(root: Path, relative: str, *, file: bool = True) -> Path:
    """Reject links/reparse points before resolving, including parent links."""
    if (not isinstance(relative, str) or not relative or "\\" in relative or ":" in relative
            or "\x00" in relative or relative.startswith("/")
            or any(part in {"", ".", ".."} for part in relative.split("/"))):
        _fail("ASR_REUSE_UNSAFE_PATH")
    target = root / PurePosixPath(relative)
    cursor = root
    for part in (None, *PurePosixPath(relative).parts):
        if part is not None:
            cursor = cursor / part
        info = _native_path(cursor).lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            _fail("ASR_REUSE_UNSAFE_PATH")
    if not target.resolve().is_relative_to(root.resolve()):
        _fail("ASR_REUSE_UNSAFE_PATH")
    if file and not _native_path(target).is_file():
        _fail("ASR_REUSE_UNSAFE_PATH")
    return target


def _published(context: StageContext, source: dict, stage: dict) -> tuple[Path, dict, dict]:
    root = context.storage_root.resolve()
    prefix = f"jobs/{source['id']}/stages/{_uuid(stage.get('id'))}/attempts/{stage['attempt']}"
    attempts = _confined(root, prefix, file=False)
    # A public snapshot intentionally omits its execution token. Only a unique
    # manifest with its published digest can identify the producing directory.
    matches: list[tuple[Path, bytes, dict]] = []
    for child in _native_path(attempts).iterdir():
        token = child.name
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", token):
            continue
        directory = _confined(root, prefix + "/" + token, file=False)
        candidate = directory / "manifest.json"
        if not _native_path(candidate).exists():
            continue
        candidate = _confined(directory, "manifest.json")
        manifest, data = _json(candidate, 1024 * 1024)
        if hashlib.sha256(data).hexdigest() == stage["manifest_hash"]:
            matches.append((directory, data, manifest))
    if len(matches) != 1:
        _fail("ASR_REUSE_MANIFEST_MISMATCH")
    directory, _, manifest = matches[0]
    expected = {"schema_version": 1, "job_id": source["id"], "video_id": source["video_id"],
                "analysis_generation": source["generation"], "stage_id": stage["id"],
                "stage_type": stage["stage_type"], "attempt": stage["attempt"],
                "run_token": directory.name, "input_sha256": context.lease.source_hash,
                "data_kind": "model_result", "review_status": "DRAFT"}
    if (not isinstance(manifest, dict)
            or any(_canonical(manifest.get(key)) != _canonical(value) for key, value in expected.items())):
        _fail("ASR_REUSE_MANIFEST_MISMATCH")
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list) or not 1 <= len(artifacts) <= 64:
        _fail("ASR_REUSE_MANIFEST_MISMATCH")
    files, keys = {}, set()
    attempt_key = directory.relative_to(root).as_posix() + "/"
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            _fail("ASR_REUSE_MANIFEST_MISMATCH")
        kind, key = artifact.get("kind"), artifact.get("key")
        if (not isinstance(kind, str) or not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", kind)
                or kind in files or not isinstance(key, str) or key in keys
                or not key.startswith(attempt_key)):
            _fail("ASR_REUSE_MANIFEST_MISMATCH")
        path = _confined(root, key)
        # The lexical prefix alone is not enough when a directory is a junction.
        if not path.resolve().is_relative_to(directory.resolve()):
            _fail("ASR_REUSE_UNSAFE_PATH")
        if sha256(path) != _hash(artifact.get("sha256")):
            _fail("ASR_REUSE_ARTIFACT_MISMATCH")
        files[kind] = {"path": path, "sha256": artifact["sha256"]}
        keys.add(key)
    required = {"media_info", "audio"} if stage["stage_type"] == "MEDIA" else {"transcript"}
    if not required.issubset(files):
        _fail("ASR_REUSE_MANIFEST_MISMATCH")
    return directory, files, manifest


def _copy_bytes(path: Path, data: bytes) -> None:
    target = _native_path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile("wb", dir=target.parent, delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, target)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def reuse_asr(context: StageContext, source: dict, audio: Path) -> list[dict]:
    """Validate everything before producing current-attempt artifacts; no HTTP."""
    try:
        return _reuse_asr(context, source, audio)
    except (OSError, ValueError, TypeError, KeyError, ASRError, RecursionError):
        _fail()


def _reuse_asr(context: StageContext, source: dict, audio: Path) -> list[dict]:
    context.guard.check()
    if not isinstance(source, dict) or context.lease.stage_type != "ASR":
        _fail()
    _uuid(source.get("id"))
    if (source["id"] == context.lease.job_id or source.get("video_id") != context.lease.video_id
            or type(source.get("generation")) is not int or source["generation"] <= 0):
        _fail("ASR_REUSE_INPUT_MISMATCH")
    config = source.get("config")
    model = context.lease.config.get("asr_model", "paraformer-v2")
    if not isinstance(config, dict) or config.get("asr_model") != model or not isinstance(model, str):
        _fail("ASR_REUSE_MODEL_MISMATCH")
    stages = source.get("stages")
    if not isinstance(stages, list) or any(not isinstance(item, dict) for item in stages):
        _fail()
    selected = {}
    for kind in ("MEDIA", "ASR"):
        matches = [item for item in stages if item.get("stage_type") == kind]
        if (len(matches) != 1 or matches[0].get("status") != "SUCCEEDED"
                or type(matches[0].get("attempt")) is not int or matches[0]["attempt"] <= 0):
            _fail("ASR_REUSE_SOURCE_NOT_SUCCEEDED")
        _hash(matches[0].get("manifest_hash"))
        selected[kind] = matches[0]
    _, media_files, _ = _published(context, source, selected["MEDIA"])
    asr_dir, asr_files, _ = _published(context, source, selected["ASR"])
    audio_hash = sha256(audio)
    if audio_hash != media_files["audio"]["sha256"]:
        _fail("ASR_REUSE_AUDIO_MISMATCH")
    checkpoint, checkpoint_bytes = _json(_confined(asr_dir, "asr/checkpoint.json"), 4 * 1024 * 1024)
    fingerprint = {"audio_sha256": audio_hash, "model": model,
                   "base_url": _base_url(os.environ.get("LIVECUT_ASR_BASE_URL")),
                   "source_chunk_id": "c_001", "chunk_start_ms": 0, "adapter_version": 1}
    if (not isinstance(checkpoint, dict) or type(checkpoint.get("version")) is not int
            or checkpoint["version"] != 1 or checkpoint.get("state") != "SUCCEEDED"
            or _canonical(checkpoint.get("input")) != _canonical(fingerprint)
            or not isinstance(checkpoint.get("provider_task_id"), str)
            or not re.fullmatch(r"[A-Za-z0-9_-]{1,256}", checkpoint["provider_task_id"])):
        _fail("ASR_REUSE_CHECKPOINT_MISMATCH")
    raw, raw_bytes = _json(_confined(asr_dir, "asr/raw-result.json"), 16 * 1024 * 1024)
    transcript, transcript_bytes = _json(asr_files["transcript"]["path"], 8 * 1024 * 1024)
    if hashlib.sha256(transcript_bytes).hexdigest() != asr_files["transcript"]["sha256"]:
        _fail("ASR_REUSE_ARTIFACT_MISMATCH")
    if _canonical(map_transcript(raw)) != _canonical(transcript):
        _fail("ASR_REUSE_TRANSCRIPT_MISMATCH")
    context.guard.check()
    current = _confined(context.storage_root.resolve(), context.lease.attempt_key, file=False)
    if current.resolve() != context.directory.resolve():
        _fail("ASR_REUSE_UNSAFE_PATH")
    if _native_path(current / "asr").exists():
        _confined(current, "asr", file=False)
    for name in ("transcript.json", "cache-provenance.json"):
        if _native_path(current / "asr" / name).exists():
            _confined(current, "asr/" + name)
    target = context.directory / "asr" / "transcript.json"
    _copy_bytes(target, transcript_bytes)
    provenance = context.directory / "asr" / "cache-provenance.json"
    _write(provenance, {
        "schema_version": 1, "reuse_mode": "published_transcript_equivalence",
        "source_job_id": source["id"], "source_video_id": source["video_id"],
        "source_generation": source["generation"], "source_media_stage_id": selected["MEDIA"]["id"],
        "source_asr_stage_id": selected["ASR"]["id"],
        "source_media_manifest_sha256": selected["MEDIA"]["manifest_hash"],
        "source_asr_manifest_sha256": selected["ASR"]["manifest_hash"],
        "source_transcript_sha256": asr_files["transcript"]["sha256"],
        "audio_sha256": audio_hash, "asr_model": model, "adapter_version": 1,
        "verified_raw_sha256": hashlib.sha256(raw_bytes).hexdigest(),
        "verified_checkpoint_sha256": hashlib.sha256(checkpoint_bytes).hexdigest(),
        "provider_submissions": 0,
    })
    return [context.artifact("transcript", target), context.artifact("cache_provenance", provenance)]
