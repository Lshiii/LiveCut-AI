"""Explicit, machine-readable commands for pipeline development."""

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

from .contracts import ContractValidationError, validate_analysis_result
from .inputs import DatasetValidationError, validate_dataset_manifest


def _read_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise ValueError("JSON document must be an object")
    return value


def _emit(value: dict) -> None:
    print(json.dumps(value, ensure_ascii=False))


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
        else:
            tools = {}
            for tool in ("ffmpeg", "ffprobe"):
                configured = os.environ.get(f"LIVECUT_{tool.upper()}_PATH", tool)
                tools[tool] = shutil.which(configured)
            _emit({"python": sys.version.split()[0], "tools": tools,
                   "dashscope_key_configured": bool(os.environ.get("DASHSCOPE_API_KEY")),
                   "credentials_verified": False})
        return 0
    except (ContractValidationError, DatasetValidationError, ValueError, OSError) as exc:
        _emit({"status": "error", "error_type": type(exc).__name__, "message": str(exc)})
        return 2
