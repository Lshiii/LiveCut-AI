"""Confirmed, frozen clip exports through the existing fenced Worker protocol."""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass
from uuid import UUID

from .media import MediaInfo, _check_duration, _run_process, export_clip, probe_media
from .worker import (
    Lease,
    StageContext,
    WorkerError,
    _native_path,
    _read,
    _write,
    sha256,
    shared_path,
)

ENCODING_PROFILE = "h264-aac-v1"


@dataclass(frozen=True)
class ExportSpec:
    export_id: str
    clip_id: str
    approved_revision: int
    snapshot_hash: str
    segment_id: str
    start_ms: int
    end_ms: int
    encoding_profile: str

    @classmethod
    def from_lease(cls, lease: Lease) -> ExportSpec:
        value = lease.config.get("export")
        if (lease.stage_type != "EXPORT_CLIP" or set(lease.config) != {"export"}
                or not isinstance(value, dict) or set(value) != set(cls.__dataclass_fields__)
                or not isinstance(lease.input_hash, str)
                or not re.fullmatch(r"[0-9a-f]{64}", lease.input_hash)):
            raise WorkerError("INVALID_EXPORT_INPUT")
        try:
            for field in ("export_id", "clip_id"):
                if not isinstance(value[field], str) or str(UUID(value[field])) != value[field]:
                    raise ValueError
            if not isinstance(value["segment_id"], str) or not re.fullmatch(
                r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}", value["segment_id"]
            ):
                raise ValueError
            if (type(value["approved_revision"]) is not int
                    or not 0 <= value["approved_revision"] <= 2**63 - 1
                    or any(type(value[field]) is not int for field in ("start_ms", "end_ms"))
                    or not 0 <= value["start_ms"] < value["end_ms"] <= 2**63 - 1
                    or not isinstance(value["snapshot_hash"], str)
                    or not re.fullmatch(r"[0-9a-f]{64}", value["snapshot_hash"])
                    or value["encoding_profile"] != ENCODING_PROFILE):
                raise ValueError
        except (ValueError, TypeError, WorkerError):
            raise WorkerError("INVALID_EXPORT_INPUT") from None
        return cls(**value)


class ExportStageExecutor:
    """Export only frozen, confirmed inputs; never discover products or call models.

    A published clip in this exact attempt can survive a process crash between
    FFmpeg publication and completion. It is reused only after frozen input,
    probe, full decoding, stream presence and duration checks all pass. A saved
    checkpoint additionally fences file hashes. Other attempts are never read.
    """

    def __init__(self, *, ffmpeg_path: str = "ffmpeg", ffprobe_path: str = "ffprobe") -> None:
        self.ffmpeg_path, self.ffprobe_path = ffmpeg_path, ffprobe_path

    def _verify_output(self, context: StageContext, clip, spec: ExportSpec,
                       source_info: MediaInfo) -> MediaInfo:
        context.guard.check()
        info = probe_media(_native_path(clip), ffprobe_path=self.ffprobe_path,
                           timeout_seconds=min(30, context.guard.remaining_seconds()))
        if info.video_codec != "h264" or info.audio_codec != (
            "aac" if source_info.audio_codec is not None else None
        ):
            raise WorkerError("EXPORT_PROFILE_MISMATCH")
        _check_duration(info.duration_ms, spec.end_ms - spec.start_ms, info.frame_rate)
        context.guard.check()
        _run_process([
            self.ffmpeg_path, "-hide_banner", "-loglevel", "error", "-nostdin", "-xerror",
            "-err_detect", "explode", "-i", str(_native_path(clip)),
            "-map", "0:v:0", "-map", "0:a:0?", "-f", "null", "-",
        ], timeout_seconds=min(300, context.guard.remaining_seconds()),
            failure_code="EXPORT_DECODE_FAILED")
        context.guard.check()
        return info

    def __call__(self, context: StageContext) -> dict:
        context.guard.check()
        lease = context.lease
        spec = ExportSpec.from_lease(lease)
        source = shared_path(context.storage_root, lease.source_key, must_exist=True)
        if sha256(source) != lease.source_hash:
            raise WorkerError("SOURCE_HASH_MISMATCH")
        source_info = probe_media(_native_path(source), ffprobe_path=self.ffprobe_path,
                                  timeout_seconds=min(30, context.guard.remaining_seconds()))
        if spec.end_ms > source_info.duration_ms:
            raise WorkerError("INVALID_EXPORT_INTERVAL")
        directory = context.directory
        _native_path(directory).mkdir(parents=True, exist_ok=True)
        binding_path = directory / "export-input.json"
        checkpoint_path = directory / "export-checkpoint.json"
        clip, media_info = directory / "clip.mp4", directory / "media-info.json"
        binding = {
            "schema_version": 1, "job_id": lease.job_id, "video_id": lease.video_id,
            "analysis_generation": lease.generation, "stage_id": lease.stage_id,
            "attempt": lease.attempt, "run_token": lease.run_token,
            "source_sha256": lease.source_hash, "stage_input_hash": lease.input_hash,
            "export": asdict(spec),
        }
        for path in (binding_path, checkpoint_path, clip, media_info):
            if _native_path(path).is_symlink():
                raise WorkerError("UNSAFE_EXPORT_OUTPUT")
        if _native_path(binding_path).exists():
            if _read(binding_path) != binding:
                raise WorkerError("EXPORT_INPUT_CHANGED")
        else:
            if any(_native_path(path).exists() for path in (checkpoint_path, clip, media_info)):
                raise WorkerError("UNCLAIMED_EXPORT_OUTPUT")
            _write(binding_path, binding)
        existing = _read(checkpoint_path) if _native_path(checkpoint_path).exists() else None
        if existing is not None:
            if (existing.get("binding_sha256") != sha256(binding_path)
                    or not _native_path(clip).is_file() or not _native_path(media_info).is_file()
                    or existing.get("clip_sha256") != sha256(clip)
                    or existing.get("media_info_sha256") != sha256(media_info)):
                raise WorkerError("EXPORT_ARTIFACT_CHANGED")
        elif not _native_path(clip).exists():
            if _native_path(media_info).exists():
                raise WorkerError("EXPORT_ARTIFACT_CHANGED")
            context.guard.check()
            export_clip(_native_path(source), _native_path(clip), spec.start_ms, spec.end_ms,
                        ffmpeg_path=self.ffmpeg_path, ffprobe_path=self.ffprobe_path,
                        timeout_seconds=min(300, context.guard.remaining_seconds()))
        info = self._verify_output(context, clip, spec, source_info)
        if existing is None:
            _write(media_info, {key: value for key, value in asdict(info).items() if key != "path"})
            _write(checkpoint_path, {"binding_sha256": sha256(binding_path),
                                     "clip_sha256": sha256(clip),
                                     "media_info_sha256": sha256(media_info)})
        context.guard.check()
        return {
            "schema_version": 1, "job_id": lease.job_id, "video_id": lease.video_id,
            "analysis_generation": lease.generation, "stage_id": lease.stage_id,
            "stage_type": "EXPORT_CLIP", "attempt": lease.attempt, "run_token": lease.run_token,
            "input_sha256": lease.source_hash, "stage_input_hash": lease.input_hash,
            "data_kind": "reviewed_export", "review_status": "CONFIRMED", **asdict(spec),
            "artifacts": [context.artifact("clip", clip), context.artifact("media_info", media_info)],
        }
