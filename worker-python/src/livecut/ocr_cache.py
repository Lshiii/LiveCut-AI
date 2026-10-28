"""Verified, bounded local OCR bundles shared by different stage attempts.

The cache is file evidence, never business state. A global process lock makes
capacity accounting and publication conservative; it is not a distributed lock.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import shutil
import stat
import time
from contextlib import contextmanager
from dataclasses import dataclass
from importlib.metadata import PackageNotFoundError, distribution, version
from pathlib import Path
from typing import Any, Callable, Iterator

from .ocr import observe_video_ocr
from .ocr_sampling import sampling_timestamps
from .vision import _PROFILE, VisionError

_HASH = re.compile(r"[a-f0-9]{64}")
_IDENTITY = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
_FRAME = re.compile(r"ocr_[0-9]{6}\.jpg")
_MAX_JSON = 1024 * 1024
_MAX_FRAME = 4 * 1024 * 1024
_MAX_FILES = 128
_SCHEMA = 1
_OBSERVER = "rapidocr-prefix-observer-v1-score045-text2000"
_UNIFORM_OBSERVER = "rapidocr-uniform-observer-v1-score045-text2000"
_PREFIX_SAMPLING = "prefix-at-fixed-interval-v1"
_UNIFORM_SAMPLING = "uniform-bin-centers-v1"


@dataclass(frozen=True)
class OCRCacheResult:
    observations: list[dict]
    provenance: dict


def _error(code: str = "OCR_CACHE_CORRUPT") -> VisionError:
    messages = {
        "OCR_CACHE_CORRUPT": "OCR evidence cache failed integrity or structure validation",
        "OCR_CACHE_BUSY": "Another process still owns the OCR evidence cache",
        "OCR_CACHE_CAPACITY_EXCEEDED": "OCR cache has no capacity for a bounded new bundle",
        "OCR_CACHE_IDENTITY_REQUIRED": "Custom OCR and frame extractors require explicit stable identities",
        "OCR_CACHE_RUNTIME_NOT_READY": "Default OCR model files must be prepared before cache identity is available",
        "OCR_CACHE_UNSUPPORTED_RUNTIME": "Only the verified default ONNX OCR runtime can use shared caching",
        "OCR_CACHE_INPUT_CHANGED": "OCR input or runtime changed while evidence was being prepared",
        "OCR_CACHE_OUTPUT_CONFLICT": "The attempt already contains different OCR evidence",
        "OCR_CACHE_UNSAFE_PATH": "OCR evidence paths must be regular local paths without redirects",
        "OCR_CACHE_STORAGE_ERROR": "OCR evidence cache storage is unavailable",
    }
    failure = VisionError(code, messages[code])
    # Lock contention can outlive the bounded local wait. Let Java apply its
    # existing finite backoff, while keeping integrity/capacity failures final.
    failure.retryable = code == "OCR_CACHE_BUSY"
    return failure


def _canonical(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def _check(path: Path, *, existing: bool = True) -> Path:
    path = Path(os.path.abspath(path.expanduser()))
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current /= part
        try:
            attributes = current.lstat()
        except FileNotFoundError:
            if existing:
                raise _error("OCR_CACHE_UNSAFE_PATH") from None
            continue
        if stat.S_ISLNK(attributes.st_mode) or getattr(attributes, "st_file_attributes", 0) & 0x400:
            raise _error("OCR_CACHE_UNSAFE_PATH")
    return path


def _directory(path: Path) -> Path:
    path = _check(path, existing=False)
    path.mkdir(parents=True, exist_ok=True)
    if not _check(path).is_dir():
        raise _error("OCR_CACHE_UNSAFE_PATH")
    return path


def _sha(path: Path, guard: Callable[[], None], maximum: int | None = None) -> str:
    path = _check(path)
    if not path.is_file() or (maximum is not None and path.stat().st_size > maximum):
        raise _error()
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            guard()
            block = handle.read(1024 * 1024)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def _pairs(pairs: list[tuple[str, Any]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON member")
        result[key] = value
    return result


def _read(path: Path) -> Any:
    try:
        if not path.exists() and not path.is_symlink():
            raise _error()
        path = _check(path)
        if not path.is_file() or path.stat().st_size > _MAX_JSON:
            raise _error()
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_pairs,
                          parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))
    except (OSError, ValueError, UnicodeError):
        raise _error() from None


def _write(path: Path, value: Any, guard: Callable[[], None]) -> None:
    guard()
    data = _canonical(value) + b"\n"
    if len(data) > _MAX_JSON:
        raise _error()
    temporary = path.with_name(path.name + ".writing")
    if temporary.exists():
        _check(temporary).unlink()
    with temporary.open("xb") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())
    guard()
    os.replace(temporary, path)


def default_runtime_identity(*, guard: Callable[[], None] | None = None) -> dict:
    """Inspect real default assets without constructing RapidOCR or downloading."""
    check = guard or (lambda: None)
    try:
        package = _check(Path(distribution("rapidocr").locate_file("rapidocr")))
        rapidocr_version = version("rapidocr")
        import yaml
        config = _check(package / "config.yaml")
        settings = yaml.safe_load(config.read_text(encoding="utf-8"))
        if (not isinstance(settings, dict) or settings.get("Global", {}).get("model_root_dir") is not None
                or settings.get("Rec", {}).get("rec_keys_path") is not None
                or any(settings.get(task, {}).get("engine_type") != "onnxruntime"
                       or settings.get(task, {}).get("model_path") is not None
                       or settings.get(task, {}).get("model_dir") is not None for task in ("Det", "Cls", "Rec"))):
            raise _error("OCR_CACHE_UNSUPPORTED_RUNTIME")
        model_directory = package / "models"
        models = sorted(model_directory.glob("*.onnx")) if model_directory.is_dir() else []
        # The current default engine has separate detection, classification and
        # recognition assets. Extra local models conservatively invalidate hits.
        if (not models or not all(any(marker in model.name.lower() for model in models)
                                  for marker in ("det", "cls", "rec"))):
            raise _error("OCR_CACHE_RUNTIME_NOT_READY")
        if len(models) > 32:
            raise _error("OCR_CACHE_UNSUPPORTED_RUNTIME")
        assets = [config, package / "default_models.yaml", *models]
        fingerprints = [{"path": path.relative_to(package).as_posix(), "size_bytes": path.stat().st_size,
                         "sha256": _sha(path, check, 512 * 1024 * 1024)} for path in assets]
        runtimes = {}
        for name in ("onnxruntime", "onnxruntime-gpu"):
            try:
                runtimes[name] = version(name)
            except PackageNotFoundError:
                pass
        if not runtimes:
            raise _error("OCR_CACHE_RUNTIME_NOT_READY")
        return {"kind": "rapidocr-onnxruntime-default", "model_id": "rapidocr-" + rapidocr_version,
                "packages": {"rapidocr": rapidocr_version, **runtimes}, "assets": fingerprints}
    except PackageNotFoundError:
        raise VisionError("OCR_NOT_INSTALLED", "Install local OCR with: uv sync --extra ocr") from None
    except (OSError, ValueError, ImportError, AttributeError, TypeError):
        raise _error("OCR_CACHE_UNSUPPORTED_RUNTIME") from None


def _executable_identity(ffmpeg_path: str | Path, guard: Callable[[], None]) -> dict:
    found = shutil.which(str(ffmpeg_path))
    candidate = Path(found) if found else Path(ffmpeg_path)
    if not candidate.is_file():
        raise VisionError("TOOL_NOT_FOUND", "FFmpeg executable not found")
    # Installed tool launchers such as WinGet Links are intentional aliases.
    # Bind the resolved binary's bytes, while cache/evidence paths remain strict.
    path = _check(candidate.resolve(strict=True))
    return {"sha256": _sha(path, guard), "size_bytes": path.stat().st_size, "profile": _PROFILE}


@contextmanager
def _locked(root: Path, timeout: float, guard: Callable[[], None]) -> Iterator[None]:
    path = root / ".cache.lock"
    _check(path, existing=False)
    with path.open("a+b") as handle:
        if handle.tell() == 0:
            handle.write(b"\0")
            handle.flush()
        deadline = time.monotonic() + timeout
        while True:
            guard()
            handle.seek(0)
            try:
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise _error("OCR_CACHE_BUSY") from None
                time.sleep(min(.05, max(0, deadline - time.monotonic())))
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def _paths(directory: Path, *, lock_allowed: bool = False) -> set[str]:
    """Bounded exact payload enumeration; never follows a directory redirect."""
    result: set[str] = set()
    for parent, directories, files in os.walk(_check(directory), followlinks=False):
        for name in directories:
            child = _check(Path(parent) / name)
            if child.relative_to(directory).as_posix() not in {"evidence", "evidence/frames", "frames"}:
                raise _error()
        for name in files:
            child = _check(Path(parent) / name)
            relative = child.relative_to(directory).as_posix()
            if lock_allowed and relative == ".vision.lock":
                continue
            result.add(relative)
            if len(result) > _MAX_FILES:
                raise _error()
    return result


def _remove_build(path: Path) -> None:
    """Only owned bounded build trees, while the cache lock is held."""
    if not path.exists():
        return
    _paths(path, lock_allowed=True)
    for parent, directories, files in os.walk(path, topdown=False, followlinks=False):
        for name in files:
            _check(Path(parent) / name).unlink()
        for name in directories:
            _check(Path(parent) / name).rmdir()
    path.rmdir()


def _integer(value: Any, minimum: int = 0) -> bool:
    return type(value) is int and value >= minimum


def _validate_evidence(directory: Path, binding: dict, guard: Callable[[], None], *,
                       attempt_metadata: bool = False) -> list[dict]:
    state, frames, observations = (_read(directory / name) for name in ("ocr-state.json", "frames.json", "observations.json"))
    expected_input = {"source_sha256": binding["source_sha256"], "duration_ms": binding["duration_ms"],
                      "interval_ms": binding["interval_ms"], "max_frames": binding["max_frames"],
                      "model_id": binding["runtime"]["model_id"]}
    if binding["sampling"] not in {_PREFIX_SAMPLING, _UNIFORM_SAMPLING}:
        raise _error()
    policy = "uniform" if binding["sampling"] == _UNIFORM_SAMPLING else "prefix"
    if policy == "uniform":
        expected_input["sampling_policy"] = "uniform"
    requested = sampling_timestamps(binding["duration_ms"], binding["interval_ms"], binding["max_frames"], policy)
    truncated = policy == "prefix" and math.ceil(binding["duration_ms"] / binding["interval_ms"]) > binding["max_frames"]
    if (not isinstance(state, dict) or set(state) != {"input", "frames", "observations", "coverage_truncated"}
            or _canonical(state["input"]) != _canonical(expected_input) or type(state["coverage_truncated"]) is not bool
            or state["coverage_truncated"] != truncated or not isinstance(frames, list)
            or not isinstance(observations, list) or state["frames"] != frames or state["observations"] != observations
            or len(frames) != len(requested) or len(observations) != len(requested)):
        raise _error()
    expected_paths = {"ocr-state.json", "frames.json", "observations.json"}
    for index, (frame, observation, requested_ms) in enumerate(zip(frames, observations, requested, strict=True)):
        guard()
        frame_id = f"ocr_{index + 1:06d}"
        key = "frames/" + frame_id + ".jpg"
        expected_paths.add(key)
        if (not isinstance(frame, dict) or set(frame) != {"frame_id", "requested_timestamp_ms", "timestamp_ms", "source_pts", "source_time_base", "object_key", "sha256"}
                or frame["frame_id"] != frame_id or type(frame["requested_timestamp_ms"]) is not int
                or frame["requested_timestamp_ms"] != requested_ms or not _integer(frame["timestamp_ms"])
                or frame["timestamp_ms"] >= binding["duration_ms"] or type(frame["source_pts"]) is not int
                or not isinstance(frame["source_time_base"], str)
                or not re.fullmatch(r"[1-9][0-9]*/[1-9][0-9]*", frame["source_time_base"])
                or frame["object_key"] != key or not isinstance(frame["sha256"], str) or not _HASH.fullmatch(frame["sha256"])
                or not isinstance(observation, dict) or set(observation) != {"frame_id", "timestamp_ms", "object_key", "presented_category", "observed_text", "visibility"}
                or observation["frame_id"] != frame_id or type(observation["timestamp_ms"]) is not int
                or observation["timestamp_ms"] != frame["timestamp_ms"] or observation["object_key"] != key
                or observation["presented_category"] is not None or not isinstance(observation["observed_text"], str)
                or len(observation["observed_text"]) > 2000 or observation["visibility"] not in {"clear", "not_visible"}
                or (observation["visibility"] == "not_visible" and observation["observed_text"] != "")):
            raise _error()
        numerator, denominator = (int(part) for part in frame["source_time_base"].split("/"))
        # showinfo's pts_time text is rounded. Allow at most one millisecond,
        # while checking the underlying PTS/time-base relation independently.
        if (frame["source_pts"] < 0 or frame["timestamp_ms"] + 1 < requested_ms
                or abs(frame["timestamp_ms"] * denominator
                       - frame["source_pts"] * numerator * 1000) > denominator):
            raise _error()
        image = directory / key
        if not image.exists() and not image.is_symlink():
            raise _error()
        image = _check(image)
        if image.stat().st_size < 4 or _sha(image, guard, _MAX_FRAME) != frame["sha256"]:
            raise _error()
        with image.open("rb") as handle:
            start = handle.read(2)
            handle.seek(-2, os.SEEK_END)
            end = handle.read(2)
        if start != b"\xff\xd8" or end != b"\xff\xd9":
            raise _error()
    paths = _paths(directory, lock_allowed=True)
    if attempt_metadata and "cache-provenance.json" in paths:
        provenance = _read(directory / "cache-provenance.json")
        if (not isinstance(provenance, dict) or provenance.get("cache_key") != hashlib.sha256(_canonical(binding)).hexdigest()
                or provenance.get("observer_id") != binding["runtime"]["model_id"]
                or type(provenance.get("frame_count")) is not int or provenance["frame_count"] != len(observations)):
            raise _error("OCR_CACHE_OUTPUT_CONFLICT")
        if policy == "uniform" and (provenance.get("sampling_policy") != "uniform"
                                    or provenance.get("coverage_truncated") is not False):
            raise _error("OCR_CACHE_OUTPUT_CONFLICT")
        paths.remove("cache-provenance.json")
    if paths != expected_paths:
        raise _error()
    return observations


def _manifest(directory: Path, binding: dict, guard: Callable[[], None]) -> dict:
    _validate_evidence(directory / "evidence", binding, guard)
    files = []
    for relative in sorted(_paths(directory / "evidence", lock_allowed=True)):
        path = directory / "evidence" / relative
        files.append({"path": "evidence/" + relative, "size_bytes": path.stat().st_size,
                      "sha256": _sha(path, guard, _MAX_FRAME if relative.endswith(".jpg") else _MAX_JSON)})
    return {"schema_version": _SCHEMA, "binding": binding, "files": files}


def _verify_bundle(directory: Path, binding: dict, guard: Callable[[], None]) -> tuple[list[dict], dict]:
    manifest = _read(directory / "manifest.json")
    if (not isinstance(manifest, dict) or set(manifest) != {"schema_version", "binding", "files"}
            or type(manifest["schema_version"]) is not int or manifest["schema_version"] != _SCHEMA
            or _canonical(manifest["binding"]) != _canonical(binding) or not isinstance(manifest["files"], list)):
        raise _error()
    seen = set()
    for row in manifest["files"]:
        if (not isinstance(row, dict) or set(row) != {"path", "size_bytes", "sha256"}
                or not isinstance(row["path"], str) or row["path"] in seen
                or not _integer(row["size_bytes"], 1) or not isinstance(row["sha256"], str)
                or not _HASH.fullmatch(row["sha256"])):
            raise _error()
        seen.add(row["path"])
    expected = _manifest(directory, binding, guard)
    if manifest != expected or _paths(directory) != {"manifest.json", *(row["path"] for row in expected["files"])}:
        raise _error()
    return _read(directory / "evidence/observations.json"), manifest


def _capacity(root: Path, reservation: int, max_bytes: int, max_entries: int, guard: Callable[[], None]) -> None:
    entries, used = 0, 0
    for path in root.iterdir():
        guard()
        _check(path)
        if path.name == ".cache.lock":
            continue
        if not path.is_dir() or not (_HASH.fullmatch(path.name)
                                     or re.fullmatch(r"\.build-[a-f0-9]{64}|\.build-cold", path.name)):
            raise _error()
        entries += 1
        for relative in _paths(path):
            used += (path / relative).stat().st_size
            if used > max_bytes:
                raise _error("OCR_CACHE_CAPACITY_EXCEEDED")
    if entries >= max_entries or used + reservation > max_bytes:
        raise _error("OCR_CACHE_CAPACITY_EXCEEDED")


def _materialize(bundle: Path, output: Path, binding: dict, manifest: dict, guard: Callable[[], None]) -> None:
    if output.exists():
        _validate_evidence(output, binding, guard, attempt_metadata=True)
        for row in manifest["files"]:
            relative = row["path"].removeprefix("evidence/")
            if _sha(output / relative, guard) != row["sha256"]:
                raise _error("OCR_CACHE_OUTPUT_CONFLICT")
        return
    parent = _directory(output.parent)
    temporary = parent / ("." + output.name + ".ocr-copy")
    _remove_build(temporary)
    _directory(temporary / "frames")
    try:
        for row in manifest["files"]:
            guard()
            relative = row["path"].removeprefix("evidence/")
            source, target = bundle / row["path"], temporary / relative
            with _check(source).open("rb") as incoming, target.open("xb") as outgoing:
                while block := incoming.read(1024 * 1024):
                    guard()
                    outgoing.write(block)
                outgoing.flush()
                os.fsync(outgoing.fileno())
            if target.stat().st_size != row["size_bytes"] or _sha(target, guard) != row["sha256"]:
                raise _error()
        _validate_evidence(temporary, binding, guard)
        guard()
        os.replace(temporary, output)
    finally:
        _remove_build(temporary)


def cached_observe_video_ocr(
    source: str | Path, output_dir: str | Path, *, cache_root: str | Path,
    duration_ms: int, ffmpeg_path: str | Path = "ffmpeg", interval_ms: int = 45000,
    max_frames: int = 16, guard: Callable[[], None] | None = None,
    sampling_policy: str = "prefix",
    engine: Callable[[str], Any] | None = None, engine_identity: str | None = None,
    extractor: Callable[..., dict] | None = None, extractor_identity: str | None = None,
    max_cache_bytes: int = 1024 * 1024 * 1024, max_entries: int = 128,
    lock_timeout_seconds: float = 30.0,
) -> OCRCacheResult:
    """Reuse validated evidence, or generate one bounded complete bundle.

    Custom callables must carry caller-owned explicit identities. The normal
    default runtime instead binds package config, weights and runtime versions.
    """
    if (any(type(value) is not int or value <= 0 for value in (duration_ms, interval_ms, max_frames, max_cache_bytes, max_entries))
            or max_frames > 120 or max_entries > 4096 or max_cache_bytes > 16 * 1024**3
            or type(lock_timeout_seconds) not in {int, float} or not math.isfinite(lock_timeout_seconds)
            or not 0 <= lock_timeout_seconds <= 300):
        raise VisionError("INVALID_CONFIGURATION", "OCR cache configuration is outside its supported bounds")
    sampling_timestamps(duration_ms, interval_ms, max_frames, sampling_policy)
    for custom, identity in ((engine, engine_identity), (extractor, extractor_identity)):
        if custom is not None and not callable(custom):
            raise VisionError("INVALID_CONFIGURATION", "OCR adapters must be callable")
        if custom is not None and (not isinstance(identity, str) or not _IDENTITY.fullmatch(identity)):
            raise _error("OCR_CACHE_IDENTITY_REQUIRED")
        if custom is None and identity is not None:
            raise _error("OCR_CACHE_IDENTITY_REQUIRED")
    check = guard or (lambda: None)
    path, output = _check(Path(source)), _check(Path(output_dir), existing=False)
    root = _directory(Path(cache_root))
    if (not path.is_file() or path.stat().st_size == 0 or output == path or root == output
            or root.is_relative_to(output) or output.is_relative_to(root) or path.is_relative_to(root)):
        raise _error("OCR_CACHE_UNSAFE_PATH")
    source_hash = _sha(path, check)
    executable = _executable_identity(ffmpeg_path, check)
    binding = {"source_sha256": source_hash, "duration_ms": duration_ms, "interval_ms": interval_ms,
               "max_frames": max_frames,
               "sampling": _UNIFORM_SAMPLING if sampling_policy == "uniform" else _PREFIX_SAMPLING,
               "observer": _UNIFORM_OBSERVER if sampling_policy == "uniform" else _OBSERVER,
               "ffmpeg": executable, "extractor_identity": extractor_identity}
    try:
        with _locked(root, lock_timeout_seconds, check):
            cold = False
            if engine is not None:
                runtime = {"kind": "custom", "model_id": "custom-ocr-engine", "identity": engine_identity}
            else:
                try:
                    runtime = default_runtime_identity(guard=check)
                except VisionError as failure:
                    if failure.code != "OCR_CACHE_RUNTIME_NOT_READY":
                        raise
                    cold = True
                    runtime = None
            if runtime is not None:
                binding["runtime"] = runtime
            key = hashlib.sha256(_canonical(binding)).hexdigest()
            build = root / (".build-cold" if cold else ".build-" + key)
            _remove_build(build)
            bundle = root / key
            hit = not cold and bundle.exists()
            if not hit:
                reservation = max_frames * _MAX_FRAME + 3 * _MAX_JSON + _MAX_JSON
                _capacity(root, reservation, max_cache_bytes, max_entries, check)
                _directory(build / "evidence")
                try:
                    observe_video_ocr(path, build / "evidence", duration_ms=duration_ms,
                                      ffmpeg_path=str(ffmpeg_path), interval_ms=interval_ms, max_frames=max_frames,
                                      sampling_policy=sampling_policy, engine=engine, extractor=extractor, guard=check)
                    if engine is None:
                        actual = default_runtime_identity(guard=check)
                        if runtime is not None and actual != runtime:
                            raise _error("OCR_CACHE_INPUT_CHANGED")
                        binding["runtime"] = actual
                        key = hashlib.sha256(_canonical(binding)).hexdigest()
                        bundle = root / key
                    if _sha(path, check) != source_hash or _executable_identity(ffmpeg_path, check) != executable:
                        raise _error("OCR_CACHE_INPUT_CHANGED")
                    lock_file = build / "evidence/.vision.lock"
                    if lock_file.exists():
                        _check(lock_file).unlink()
                    manifest = _manifest(build, binding, check)
                    _write(build / "manifest.json", manifest, check)
                    _verify_bundle(build, binding, check)
                    check()
                    if bundle.exists():
                        # A cold bootstrap can discover a pre-existing key only
                        # after models have become available; never replace it.
                        _verify_bundle(bundle, binding, check)
                    else:
                        os.replace(build, bundle)
                finally:
                    _remove_build(build)
            observations, manifest = _verify_bundle(bundle, binding, check)
            _materialize(bundle, output, binding, manifest, check)
            if _sha(path, check) != source_hash:
                raise _error("OCR_CACHE_INPUT_CHANGED")
            if (_executable_identity(ffmpeg_path, check) != executable
                    or (engine is None and default_runtime_identity(guard=check) != binding["runtime"])):
                raise _error("OCR_CACHE_INPUT_CHANGED")
            check()
            provenance = {"schema_version": _SCHEMA, "cache_key": key, "cache_hit": hit,
                          "binding_sha256": hashlib.sha256(_canonical(binding)).hexdigest(),
                          "bundle_manifest_sha256": _sha(bundle / "manifest.json", check),
                          "frame_count": len(observations),
                          "coverage_truncated": sampling_policy == "prefix" and math.ceil(duration_ms / interval_ms) > max_frames,
                          "observer_id": binding["runtime"]["model_id"]}
            if sampling_policy == "uniform":
                provenance["sampling_policy"] = "uniform"
            return OCRCacheResult(observations, provenance)
    except OSError:
        raise _error("OCR_CACHE_STORAGE_ERROR") from None
