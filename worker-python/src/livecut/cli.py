"""Explicit, machine-readable commands for pipeline development."""

import argparse
import json
import os
import shutil
import sys
from dataclasses import asdict
from pathlib import Path

from .asr import ASRError, transcribe_file
from .contracts import ContractValidationError, validate_analysis_result
from .inputs import DatasetValidationError, validate_dataset_manifest
from .media import MediaError, export_clip, extract_audio, probe_media
from .ocr import observe_video_ocr, ocr_model_id
from .pipeline import PipelineError, analyze_video
from .segmentation import SegmentationError, discover_products
from .vision import VisionError, observe_video


def _read_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise ValueError("JSON document must be an object")
    return value


def _emit(value: dict) -> None:
    print(json.dumps(value, ensure_ascii=False, default=str))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="livecut", description="LiveCut AI development CLI")
    sub = parser.add_subparsers(dest="command", required=True)
    validate = sub.add_parser("validate-result", help="Validate automatic prediction and references")
    validate.add_argument("path", type=Path)
    validate.add_argument("--schema", type=Path)
    dataset = sub.add_parser("validate-dataset", help="Check source tracking and split leakage")
    dataset.add_argument("path", type=Path)
    dataset.add_argument("--metadata-only", action="store_true", help="Do not check local video files")
    sub.add_parser("doctor", help="Report tools and credential presence, never secret values")
    asr = sub.add_parser("transcribe", help="Run or resume real file ASR; never silently mock")
    asr.add_argument("source", type=Path, help="16kHz mono PCM WAV")
    asr.add_argument("--output-dir", type=Path, required=True)
    asr.add_argument("--model", default=os.environ.get("LIVECUT_ASR_MODEL", "paraformer-v2"))
    asr.add_argument("--base-url")
    asr.add_argument("--timeout", type=float, default=600)
    discover = sub.add_parser("discover", help="Discover products from ASR, without a catalog")
    discover.add_argument("transcript", type=Path)
    discover.add_argument("--duration-ms", type=int, required=True)
    discover.add_argument("--output-dir", type=Path, required=True)
    discover.add_argument("--model", default=os.environ.get("LIVECUT_TEXT_MODEL", "qwen-plus"))
    discover.add_argument("--base-url")
    discover.add_argument("--grouping", choices=("category", "observable_variant"), default="category")
    discover.add_argument("--max-calls", type=int, default=8)
    analyze = sub.add_parser("analyze", help="Video-only real analysis and DRAFT preview clips")
    analyze.add_argument("source", type=Path)
    analyze.add_argument("--output-dir", type=Path, required=True)
    analyze.add_argument("--product", help="Optional discovered product name/alias filter")
    analyze.add_argument("--visual-provider", choices=("ocr", "api", "none"), default="ocr")
    analyze.add_argument("--vision-model", default=os.environ.get("LIVECUT_VISION_MODEL", "qwen3-vl-plus"))
    analyze.add_argument("--asr-model", default=os.environ.get("LIVECUT_ASR_MODEL", "paraformer-v2"))
    analyze.add_argument("--text-model", default=os.environ.get("LIVECUT_TEXT_MODEL", "qwen-plus"))
    analyze.add_argument("--asr-base-url")
    analyze.add_argument("--text-base-url")
    analyze.add_argument("--vision-base-url")
    analyze.add_argument("--asr-cache-dir", type=Path)
    analyze.add_argument("--max-calls", type=int, default=8)
    analyze.add_argument("--max-preview-clips", type=int, default=6)
    analyze.add_argument("--interval-ms", type=int, default=45000)
    analyze.add_argument("--max-frames", type=int, default=16)
    analyze.add_argument("--ffprobe", default=os.environ.get("LIVECUT_FFPROBE_PATH", "ffprobe"))
    analyze.add_argument("--ffmpeg", default=os.environ.get("LIVECUT_FFMPEG_PATH", "ffmpeg"))
    for name, description in (("probe", "Inspect supported MP4 media"),
                              ("extract-audio", "Extract 16kHz mono PCM audio"),
                              ("clip", "Transcode a millisecond interval accurately")):
        media = sub.add_parser(name, help=description)
        media.add_argument("source", type=Path)
        media.add_argument("--ffprobe", default=os.environ.get("LIVECUT_FFPROBE_PATH", "ffprobe"))
        media.add_argument("--ffmpeg", default=os.environ.get("LIVECUT_FFMPEG_PATH", "ffmpeg"))
        media.add_argument("--timeout", type=float, default=30 if name == "probe" else 300)
        if name != "probe":
            media.add_argument("--output", type=Path, required=True)
        if name == "clip":
            media.add_argument("--start-ms", type=int, required=True)
            media.add_argument("--end-ms", type=int, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "validate-result":
            data = _read_json(args.path)
            validate_analysis_result(data, schema_path=args.schema)
            _emit({"status": "valid", "schema_version": data["schema_version"],
                   "data_kind": data["data_kind"], "segments": len(data["segments"])})
        elif args.command == "validate-dataset":
            data = _read_json(args.path)
            validate_dataset_manifest(data, base_dir=args.path.resolve().parent,
                                      check_files=not args.metadata_only)
            _emit({"status": "valid", "videos": len(data["videos"]),
                   "file_existence_checked": not args.metadata_only})
        elif args.command == "analyze":
            observations = None
            observer_id = "unspecified-observer"
            if args.visual_provider != "none":
                info = probe_media(args.source, ffprobe_path=args.ffprobe)
                visual_dir = args.output_dir.with_name(args.output_dir.name + "-" + args.visual_provider)
                sampling = {"duration_ms": info.duration_ms, "ffmpeg_path": args.ffmpeg,
                            "interval_ms": args.interval_ms, "max_frames": args.max_frames}
                if args.visual_provider == "ocr":
                    observer_id = ocr_model_id()
                    observations = observe_video_ocr(args.source, visual_dir, **sampling)
                else:
                    observer_id = args.vision_model
                    observations = observe_video(args.source, visual_dir, **sampling,
                                                 model=args.vision_model, base_url=args.vision_base_url)
                observations = [dict(item, object_key=str((visual_dir / item["object_key"]).resolve()))
                                for item in observations]
            report = analyze_video(
                args.source, args.output_dir, ffmpeg_path=args.ffmpeg, ffprobe_path=args.ffprobe,
                asr_base_url=args.asr_base_url, text_base_url=args.text_base_url,
                asr_model=args.asr_model, text_model=args.text_model, product_query=args.product,
                max_model_calls=args.max_calls, max_preview_clips=args.max_preview_clips,
                visual_observations=observations, visual_model_id=observer_id,
                asr_output_dir=args.asr_cache_dir,
            )
            _emit(report)
            return 0 if report["status"] in {"ok", "no_match", "no_identified_segments", "preview_disabled"} else 1
        elif args.command == "discover":
            utterances = json.loads(args.transcript.read_text(encoding="utf-8-sig"))
            result = discover_products(utterances, args.duration_ms, args.output_dir,
                                       model=args.model, base_url=args.base_url,
                                       grouping=args.grouping, max_calls=args.max_calls)
            _emit({"status": "ok", "products": [p["name"] for p in result["products"]],
                   "segments": len(result["segments"]), "review_status": "DRAFT"})
        elif args.command == "transcribe":
            utterances = transcribe_file(args.source, args.output_dir, model=args.model,
                                        base_url=args.base_url, timeout_seconds=args.timeout)
            _emit({"status": "ok", "utterances": len(utterances),
                   "transcript_path": str(args.output_dir / "transcript.json")})
        elif args.command == "probe":
            info = probe_media(args.source, ffprobe_path=args.ffprobe, timeout_seconds=args.timeout)
            _emit({"status": "ok", "media": asdict(info)})
        elif args.command == "extract-audio":
            args.output.parent.mkdir(parents=True, exist_ok=True)
            output = extract_audio(args.source, args.output, ffmpeg_path=args.ffmpeg,
                                   ffprobe_path=args.ffprobe, timeout_seconds=args.timeout)
            _emit({"status": "ok", "audio_path": str(output)})
        elif args.command == "clip":
            args.output.parent.mkdir(parents=True, exist_ok=True)
            info = export_clip(args.source, args.output, args.start_ms, args.end_ms,
                               ffmpeg_path=args.ffmpeg, ffprobe_path=args.ffprobe,
                               timeout_seconds=args.timeout)
            _emit({"status": "ok", "media": asdict(info)})
        else:
            tools = {}
            for tool in ("ffmpeg", "ffprobe"):
                configured = os.environ.get(f"LIVECUT_{tool.upper()}_PATH", tool)
                tools[tool] = shutil.which(configured)
            _emit({"python": sys.version.split()[0], "tools": tools,
                   "dashscope_key_configured": bool(os.environ.get("DASHSCOPE_API_KEY")),
                   "credentials_verified": False})
        return 0
    except (MediaError, ASRError, SegmentationError, PipelineError, VisionError) as exc:
        _emit({"status": "error", "error_code": exc.code, "message": str(exc)})
        return 2
    except (ContractValidationError, DatasetValidationError, ValueError, OSError) as exc:
        _emit({"status": "error", "error_type": type(exc).__name__, "message": str(exc)})
        return 2
