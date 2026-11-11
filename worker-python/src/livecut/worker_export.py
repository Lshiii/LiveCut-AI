"""Confirmed, frozen clip exports through the existing fenced Worker protocol."""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass
from uuid import UUID

from .media import (
    MediaInfo,
    _check_duration,
    _run_process,
    burn_caption_clip,
    export_clip,
    export_composed_clip,
    probe_media,
)
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
    parts: list[dict] | None = None
    duration_ms: int | None = None
    clip_request_id: str | None = None
    render_mode: str | None = None
    caption_render_hash: str | None = None
    caption_render: dict | None = None

    def frozen(self) -> dict:
        value = asdict(self)
        return {key: item for key, item in value.items() if item is not None}

    @classmethod
    def from_lease(cls, lease: Lease) -> ExportSpec:
        value = lease.config.get("export")
        render_fields = {"render_mode", "caption_render_hash", "caption_render"}
        base_fields = set(cls.__dataclass_fields__) - {"parts", "duration_ms", "clip_request_id"} - render_fields
        legacy_fields = base_fields | {"parts", "duration_ms", "clip_request_id"}
        if (lease.stage_type != "EXPORT_CLIP" or set(lease.config) != {"export"}
                or not isinstance(value, dict) or set(value) not in (base_fields, legacy_fields, base_fields | render_fields)
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
                    or value["encoding_profile"] != (
                        "h264-aac-burn-v1" if "render_mode" in value else ENCODING_PROFILE)):
                raise ValueError
            if "render_mode" in value:
                from .worker_caption_render import validate_spec

                if value["render_mode"] != "CAPTIONED":
                    raise ValueError
                validate_spec(value["caption_render"], value["caption_render_hash"])
                if value["start_ms"] != 0 or value["end_ms"] != value["caption_render"]["input_media"]["duration_ms"]:
                    raise ValueError
            if "parts" in value:
                parts = value["parts"]
                if not isinstance(parts, list) or not 1 <= len(parts) <= 12:
                    raise ValueError
                previous, total = -1, 0
                for part in parts:
                    if (not isinstance(part, dict) or set(part) != {"start_ms", "end_ms"}
                            or type(part["start_ms"]) is not int or type(part["end_ms"]) is not int
                            or not 0 <= part["start_ms"] < part["end_ms"] <= 2**63 - 1
                            or part["start_ms"] < previous):
                        raise ValueError
                    previous = part["end_ms"]
                    total += part["end_ms"] - part["start_ms"]
                if (type(value["duration_ms"]) is not int or value["duration_ms"] != total
                        or parts[0]["start_ms"] != value["start_ms"]
                        or parts[-1]["end_ms"] != value["end_ms"]
                        or not isinstance(value["clip_request_id"], str)
                        or str(UUID(value["clip_request_id"])) != value["clip_request_id"]):
                    raise ValueError
        except WorkerError:
            raise
        except (ValueError, TypeError):
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
                           timeout_seconds=min(30, context.guard.remaining_seconds()),
                           guard=context.guard.check)
        if info.video_codec != "h264" or info.audio_codec != (
            "aac" if source_info.audio_codec is not None else None
        ):
            raise WorkerError("EXPORT_PROFILE_MISMATCH")
        count = len(spec.parts) if spec.parts else 1
        expected = spec.duration_ms if spec.parts else spec.end_ms - spec.start_ms
        _check_duration(info.duration_ms, expected, info.frame_rate, interval_count=count)
        if spec.caption_render is not None:
            output = spec.caption_render["output"]
            if info.width != output["width"] or info.height != output["height"]:
                raise WorkerError("CAPTION_RENDER_OUTPUT_INVALID")
        context.guard.check()
        _run_process([
            self.ffmpeg_path, "-hide_banner", "-loglevel", "error", "-nostdin", "-xerror",
            "-err_detect", "explode", "-i", str(_native_path(clip)),
            "-map", "0:v:0", "-map", "0:a:0?", "-f", "null", "-",
        ], timeout_seconds=min(300, context.guard.remaining_seconds()),
            failure_code="EXPORT_DECODE_FAILED", guard=context.guard.check)
        context.guard.check()
        return info

    def __call__(self, context: StageContext) -> dict:
        context.guard.check()
        lease = context.lease
        spec = ExportSpec.from_lease(lease)
        progress, working_dir = None, None
        if spec.caption_render is not None:
            from .worker_caption_render import RenderProgress, prepare_inputs

            progress = RenderProgress(context, spec.end_ms)
            progress.emit("PREPARING", 0)
            source, working_dir = prepare_inputs(context, spec.caption_render)
        else:
            source = shared_path(context.storage_root, lease.source_key, must_exist=True)
            if sha256(source, guard=context.guard.check) != lease.source_hash:
                raise WorkerError("SOURCE_HASH_MISMATCH")
        source_info = probe_media(_native_path(source), ffprobe_path=self.ffprobe_path,
                                  timeout_seconds=min(30, context.guard.remaining_seconds()),
                                  guard=context.guard.check)
        if spec.caption_render is not None:
            frozen_media = spec.caption_render["input_media"]
            if (source_info.duration_ms != frozen_media["duration_ms"]
                    or source_info.width != frozen_media["width"] or source_info.height != frozen_media["height"]
                    or (source_info.audio_codec is not None) != frozen_media["has_audio"]):
                raise WorkerError("CAPTION_RENDER_SOURCE_METADATA_MISMATCH")
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
            "export": spec.frozen(),
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
            _write(binding_path, binding, guard=context.guard.check)
        existing = _read(checkpoint_path) if _native_path(checkpoint_path).exists() else None
        info = None
        if existing is not None:
            if (existing.get("binding_sha256") != sha256(binding_path, guard=context.guard.check)
                    or not _native_path(clip).is_file() or not _native_path(media_info).is_file()
                    or existing.get("clip_sha256") != sha256(clip, guard=context.guard.check)
                    or existing.get("media_info_sha256") != sha256(media_info, guard=context.guard.check)):
                raise WorkerError("EXPORT_ARTIFACT_CHANGED")
        elif not _native_path(clip).exists():
            if _native_path(media_info).exists():
                raise WorkerError("EXPORT_ARTIFACT_CHANGED")
            context.guard.check()
            if spec.caption_render is not None:
                progress.emit("ENCODING", 0)
                output = spec.caption_render["output"]
                info = burn_caption_clip(_native_path(source), _native_path(clip), _native_path(working_dir),
                    width=output["width"], height=output["height"], expected_duration_ms=spec.end_ms,
                    has_audio=spec.caption_render["input_media"]["has_audio"],
                    source_frame=spec.caption_render["frame_preset"] == "SOURCE",
                    blur_background=spec.caption_render["frame_preset"] == "PORTRAIT_BLUR_720",
                    ffmpeg_path=self.ffmpeg_path, ffprobe_path=self.ffprobe_path,
                    execution_root=_native_path(context.storage_root),
                    timeout_seconds=min(300, context.guard.remaining_seconds()), guard=context.guard.check,
                    on_encoding=progress.encoding, on_phase=progress.phase)
            elif spec.parts:
                export_composed_clip(_native_path(source), _native_path(clip), spec.parts,
                                     ffmpeg_path=self.ffmpeg_path, ffprobe_path=self.ffprobe_path,
                                     timeout_seconds=min(300, context.guard.remaining_seconds()),
                                     guard=context.guard.check)
            else:
                export_clip(_native_path(source), _native_path(clip), spec.start_ms, spec.end_ms,
                            ffmpeg_path=self.ffmpeg_path, ffprobe_path=self.ffprobe_path,
                            timeout_seconds=min(300, context.guard.remaining_seconds()),
                            guard=context.guard.check)
        if info is None:
            if progress is not None:
                progress.phase("VALIDATING")
            info = self._verify_output(context, clip, spec, source_info)
            if progress is not None:
                progress.phase("PUBLISHING")
        if existing is None:
            _write(media_info, {key: value for key, value in asdict(info).items() if key != "path"},
                   guard=context.guard.check)
            checkpoint = {"binding_sha256": sha256(binding_path, guard=context.guard.check),
                          "clip_sha256": sha256(clip, guard=context.guard.check),
                          "media_info_sha256": sha256(media_info, guard=context.guard.check)}
            _write(checkpoint_path, checkpoint, guard=context.guard.check)
        context.guard.check()
        artifacts = [context.artifact("clip", clip), context.artifact("media_info", media_info)]
        if spec.caption_render is not None:
            from .worker_caption_render import canonical, write_exact

            render_info = directory / "caption-render-info.json"
            write_exact(render_info, canonical({"schema_version": 1,
                "caption_render_hash": spec.caption_render_hash, "caption_render": spec.caption_render}),
                context.guard.check)
            artifacts.append(context.artifact("caption_render_info", render_info))
        return {
            "schema_version": 1, "job_id": lease.job_id, "video_id": lease.video_id,
            "analysis_generation": lease.generation, "stage_id": lease.stage_id,
            "stage_type": "EXPORT_CLIP", "attempt": lease.attempt, "run_token": lease.run_token,
            "input_sha256": lease.source_hash, "stage_input_hash": lease.input_hash,
            "data_kind": "captioned_export" if spec.caption_render is not None else "reviewed_export",
            "review_status": "CONFIRMED",
            **{key: value for key, value in spec.frozen().items() if key != "caption_render"},
            "artifacts": artifacts,
        }
