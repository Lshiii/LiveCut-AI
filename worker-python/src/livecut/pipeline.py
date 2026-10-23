"""Resumable catalog-free analysis and explicitly unapproved draft previews.

Only media bytes and ASR text enter discovery. Publisher product lists, source
page markers and evaluation annotations are deliberately outside this API.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
import unicodedata
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

from .asr import transcribe_file
from .contracts import validate_analysis_result
from .media import MediaError, export_clip, extract_audio, probe_media
from .segmentation import PROMPT_VERSION, discover_products


class PipelineError(ValueError):
    """A local orchestration failure, without credentials or provider URLs."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _json_bytes(data: Any) -> bytes:
    return (json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode()


def _save(path: Path, data: Any) -> None:
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("wb", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(_json_bytes(data))
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _read(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise ValueError
        return value
    except (OSError, ValueError):
        raise PipelineError("INVALID_CHECKPOINT", "Run metadata or prediction is unreadable") from None


@contextmanager
def _exclusive_run(directory: Path) -> Iterator[None]:
    """Use an OS lock so a crashed process does not leave a permanent lock."""
    with (directory / ".pipeline.lock").open("a+b") as handle:
        if handle.tell() == 0:
            handle.write(b"\0")
            handle.flush()
        handle.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise PipelineError("PIPELINE_BUSY", "Another process owns this run directory") from None
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == "nt":
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def _normalize(value: str) -> str:
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", value)).casefold()


def _endpoint_hash(value: str | None, environment_names: tuple[str, ...]) -> str:
    effective = value or next((os.environ[name] for name in environment_names
                               if os.environ.get(name)), "https://dashscope.aliyuncs.com/api/v1")
    # Endpoint configuration can contain user-supplied secrets. Persist only its
    # digest, while the actual adapters validate and use the original value.
    return hashlib.sha256(effective.rstrip("/").encode()).hexdigest()


def _preview_filename(segment_id: str) -> str:
    safe = re.sub(r"[^A-Za-z0-9_-]", "_", segment_id)[:60].strip("_-") or "segment"
    suffix = hashlib.sha256(segment_id.encode()).hexdigest()[:12]
    return f"preview-{safe}-{suffix}.mp4"


def _check_result(result: dict, *, source_hash: str, duration_ms: int,
                  asr_model: str, text_model: str) -> None:
    validate_analysis_result(result)
    expected = {"video_id": "video-" + source_hash[:16], "job_id": "job-" + source_hash[:16],
                "duration_ms": duration_ms, "data_kind": "model_result", "review_status": "DRAFT",
                "product_discovery_version": PROMPT_VERSION}
    if any(result.get(key) != value for key, value in expected.items()) or (
        result["provenance"]["asr_model_id"] != asr_model
        or result["provenance"]["text_model_id"] != text_model
        or result["provenance"]["prompt_version"] != PROMPT_VERSION
    ):
        raise PipelineError("RESULT_INPUT_MISMATCH", "Prediction does not belong to this input/model run")


def analyze_video(
    source: str | Path, output_dir: str | Path, *,
    ffmpeg_path: str | Path = "ffmpeg", ffprobe_path: str | Path = "ffprobe",
    asr_base_url: str | None = None, text_base_url: str | None = None,
    asr_model: str = "paraformer-v2", text_model: str = "qwen-plus",
    product_query: str | None = None, max_model_calls: int = 8, max_preview_clips: int = 6,
    visual_observations: list[dict] | None = None,
    visual_model_id: str = "unspecified-observer",
    asr_output_dir: str | Path | None = None,
) -> dict:
    """Analyze real video without a SKU catalog, then produce DRAFT previews.

    A directory is permanently bound to its input bytes and model configuration.
    Resume calls reuse the audio and provider checkpoints, and a completed
    prediction is read without another provider request. Query/preview limits
    may change without changing or approving the original prediction.
    Explicit ``asr_output_dir`` reuses a provider cache across separate text runs;
    the ASR adapter itself verifies the audio/model/endpoint before using it.
    """
    if type(max_model_calls) is not int or max_model_calls < 1:
        raise PipelineError("INVALID_ARGUMENT", "max_model_calls must be a positive integer")
    if type(max_preview_clips) is not int or max_preview_clips < 0:
        raise PipelineError("INVALID_ARGUMENT", "max_preview_clips must be a nonnegative integer")
    if any(not isinstance(model, str) or not model.strip() for model in (asr_model, text_model)):
        raise PipelineError("INVALID_ARGUMENT", "ASR and text model IDs must be nonempty")
    if product_query is not None and (
        not isinstance(product_query, str) or not _normalize(product_query)
    ):
        raise PipelineError("INVALID_ARGUMENT", "product_query must be nonempty when supplied")
    if visual_observations is not None and (
        not isinstance(visual_observations, list)
        or any(not isinstance(item, dict) for item in visual_observations)
    ):
        raise PipelineError("INVALID_ARGUMENT", "visual_observations must be a list of frame observation objects")
    try:
        visual_json = json.dumps(visual_observations or [], ensure_ascii=False,
                                 sort_keys=True, separators=(",", ":"), allow_nan=False)
        # Freeze the caller's JSON evidence before hashing and forwarding it.
        visual_payload = json.loads(visual_json) if visual_observations is not None else None
    except (TypeError, ValueError):
        raise PipelineError("INVALID_ARGUMENT", "Frame observations must contain finite JSON values") from None

    info = probe_media(source, ffprobe_path=ffprobe_path)
    source_path = Path(info.path).resolve()
    source_hash = _sha256(source_path)
    directory = Path(output_dir).expanduser().resolve()
    asr_directory = (Path(asr_output_dir).expanduser().resolve()
                     if asr_output_dir is not None else directory / "asr")
    binding = {
        "source_sha256": source_hash, "duration_ms": info.duration_ms,
        "asr_model": asr_model, "text_model": text_model,
        "asr_endpoint_sha256": _endpoint_hash(asr_base_url, (
            "LIVECUT_ASR_BASE_URL", "DASHSCOPE_HTTP_BASE_URL", "DASHSCOPE_BASE_URL")),
        "text_endpoint_sha256": _endpoint_hash(text_base_url, ("DASHSCOPE_BASE_URL",)),
        "text_prompt_version": PROMPT_VERSION,
        "visual_observations_sha256": hashlib.sha256(visual_json.encode()).hexdigest(),
        "visual_model_id": visual_model_id if visual_payload else None,
        "asr_cache_reference_sha256": hashlib.sha256(str(asr_directory).encode()).hexdigest(),
    }
    directory.mkdir(parents=True, exist_ok=True)
    manifest_path, result_path = directory / "run-manifest.json", directory / "analysis-result.json"
    audio_path = directory / "audio.wav"
    for path in (manifest_path, result_path, audio_path, directory / "preview-clips"):
        if path.is_symlink():
            raise PipelineError("OUTPUT_CONFLICT", "Run artifacts must not be symbolic links")

    with _exclusive_run(directory):
        if manifest_path.exists():
            manifest = _read(manifest_path)
            if manifest.get("version") != 1 or manifest.get("input") != binding:
                raise PipelineError("RUN_INPUT_MISMATCH", "Use a new output directory for different input/models")
            for field, expected_path in (("audio", "audio.wav"), ("result", "analysis-result.json")):
                if field in manifest:
                    reference = manifest[field]
                    if (not isinstance(reference, dict) or reference.get("path") != expected_path
                            or not isinstance(reference.get("sha256"), str)
                            or not re.fullmatch(r"[0-9a-f]{64}", reference["sha256"])):
                        raise PipelineError("INVALID_CHECKPOINT", "Run artifact reference is invalid; do not resubmit")
        else:
            if any(path.name != ".pipeline.lock" for path in directory.iterdir()):
                raise PipelineError("UNCLAIMED_OUTPUT", "Nonempty output directory has no input manifest")
            manifest = {"version": 1, "input": binding, "review_status": "DRAFT"}
            _save(manifest_path, manifest)
        manifest["asr_cache"] = {"path": str(asr_directory),
                                 "explicit_shared_cache": asr_output_dir is not None}
        _save(manifest_path, manifest)

        if result_path.exists():
            result = _read(result_path)
            if manifest.get("result") and manifest["result"]["sha256"] != _sha256(result_path):
                raise PipelineError("PREDICTION_CHANGED", "Original prediction changed; preserve it and use a new run")
            _check_result(result, source_hash=source_hash, duration_ms=info.duration_ms,
                          asr_model=asr_model, text_model=text_model)
        else:
            if manifest.get("result"):
                raise PipelineError("PREDICTION_MISSING", "Recorded original prediction is missing; do not rerun models")
            audio_ref = manifest.get("audio")
            if audio_ref:
                if not audio_path.is_file() or _sha256(audio_path) != audio_ref["sha256"]:
                    raise PipelineError("AUDIO_CHANGED", "Recorded audio is missing or changed; do not resubmit ASR")
            else:
                extract_audio(source_path, audio_path, ffmpeg_path=ffmpeg_path,
                              ffprobe_path=ffprobe_path)
                manifest["audio"] = {"path": "audio.wav", "sha256": _sha256(audio_path)}
                _save(manifest_path, manifest)
            utterances = transcribe_file(audio_path, asr_directory, base_url=asr_base_url,
                                         model=asr_model)
            result = discover_products(
                utterances, info.duration_ms, directory, base_url=text_base_url, model=text_model,
                video_id="video-" + source_hash[:16], job_id="job-" + source_hash[:16],
                asr_model_id=asr_model, max_calls=max_model_calls, two_pass=True,
                **({"visual_observations": visual_payload, "visual_model_id": visual_model_id}
                   if visual_payload is not None else {}),
            )
            _check_result(result, source_hash=source_hash, duration_ms=info.duration_ms,
                          asr_model=asr_model, text_model=text_model)
            # The discovery adapter normally persists this file. Preserve those
            # exact bytes; never add preview fields to the prediction bundle.
            if result_path.exists():
                if _read(result_path) != result:
                    raise PipelineError("PREDICTION_CHANGED", "Saved prediction differs from the returned prediction")
            else:
                _save(result_path, result)
        manifest["result"] = {"path": "analysis-result.json", "sha256": _sha256(result_path)}
        _save(manifest_path, manifest)

        products = {item["product_id"]: item for item in result["products"]}
        eligible = [segment for segment in result["segments"]
                    if segment["content_kind"] == "product_talk"
                    and segment["identity"]["status"] == "identified"]
        query = _normalize(product_query) if product_query is not None else None
        matched = [segment for segment in eligible if query is None or any(
            query in _normalize(name) for name in (
                products[segment["identity"]["product_id"]]["name"],
                *products[segment["identity"]["product_id"]]["aliases"],
            )
        )]
        selected = matched[:max_preview_clips]
        preview_directory = directory / "preview-clips"
        preview_directory.mkdir(exist_ok=True)
        clips, failures = [], []
        for segment in selected:
            target = preview_directory / _preview_filename(segment["segment_id"])
            if target.is_symlink():
                raise PipelineError("OUTPUT_CONFLICT", "Preview output must not be a symbolic link")
            reference = {"segment_id": segment["segment_id"],
                         "product_id": segment["identity"]["product_id"],
                         "start_ms": segment["start_ms"], "end_ms": segment["end_ms"],
                         "review_status": "DRAFT", "human_approved": False}
            try:
                exported = export_clip(source_path, target, segment["start_ms"], segment["end_ms"],
                                       ffmpeg_path=ffmpeg_path, ffprobe_path=ffprobe_path)
                clips.append({**reference, "path": str(target),
                              "duration_ms": exported.duration_ms})
            except (MediaError, OSError) as exc:
                code = getattr(exc, "code", "PREVIEW_FAILED")
                if not isinstance(code, str) or not re.fullmatch(r"[A-Z0-9_]{1,80}", code):
                    code = "PREVIEW_FAILED"
                failures.append({**reference, "error_code": code,
                                 "message": "Draft preview was not produced; original prediction is preserved."})

        status = "ok"
        if failures:
            status = "partial" if clips else "preview_failed"
        elif not matched:
            status = "no_match" if query is not None else "no_identified_segments"
        elif not max_preview_clips:
            status = "preview_disabled"
        stats = {"products": len(products), "segments": len(result["segments"]),
                 "identified_product_talk_segments": len(eligible), "matched_segments": len(matched),
                 "preview_attempted": len(selected), "preview_succeeded": len(clips),
                 "preview_failed": len(failures), "preview_limit_skipped": len(matched) - len(selected)}
        report = {"status": status, "review_status": "DRAFT", "human_approved": False,
                  "official_export": False, "analysis_result_path": str(result_path),
                  "manifest_path": str(manifest_path), "preview_clips": clips,
                  "preview_failures": failures, "statistics": stats,
                  "message": "Automatically suggested DRAFT clips require human review before formal export."}
        _save(directory / "preview-report.json", report)
        manifest["outputs"] = {"report": "preview-report.json", "status": status, **stats}
        _save(manifest_path, manifest)
        return report
