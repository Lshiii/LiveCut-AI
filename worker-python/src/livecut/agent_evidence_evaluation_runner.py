"""Finite offline evidence reports, with explicit local-file verification scope."""
from __future__ import annotations

import hashlib
import html
import json
import os
import re
import stat
from pathlib import Path
from uuid import uuid4

from .agent_evidence_evaluation import (
    EvidenceEvaluationError,
    evaluate_evidence_coverage,
    validate_agent_evidence_reference,
)
from .agent_frames import jpeg_dimensions
from .evaluation_inputs import _check_depth, _check_tree, _constant, _float, _pairs
from .media import _require_aligned_timeline, probe_media
from .segmentation import _inputs
from .worker import _native_path

MIB = 1024 * 1024
UUID = re.compile(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}\Z")
HASH = re.compile(r"[a-f0-9]{64}\Z")


def _fail(code="INVALID_AGENT_EVIDENCE_FILE"):
    raise EvidenceEvaluationError(code)


def _no_links(path):
    path = _native_path(Path(path).absolute())
    for item in (path, *path.parents):
        info = item.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            _fail("UNSAFE_AGENT_EVIDENCE_PATH")
    return path


def _identity(info):
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns


def _read(path, maximum=8*MIB):
    path = _no_links(path)
    with path.open("rb") as handle:
        before = os.fstat(handle.fileno())
        if not stat.S_ISREG(before.st_mode) or not 0 < before.st_size <= maximum:
            _fail()
        raw = handle.read(maximum + 1)
        after = os.fstat(handle.fileno())
    if (not 0 < len(raw) <= maximum or len(raw) != before.st_size
            or _identity(before) != _identity(after)
            or _identity(after) != _identity(_no_links(path).stat())):
        _fail("AGENT_EVIDENCE_INPUT_CHANGED")
    return raw


def _digest(raw):
    return hashlib.sha256(raw).hexdigest()


def _document(path, maximum=8*MIB, *, allow_list=False):
    raw = _read(path, maximum)
    text = raw.decode("utf-8-sig")
    _check_depth(text)
    value = json.loads(text, object_pairs_hook=_pairs, parse_constant=_constant, parse_float=_float)
    _check_tree(value)
    if not isinstance(value, dict) and not (allow_list and isinstance(value, list)):
        _fail()
    return value, _digest(raw)


def _file_hash(path):
    path = _no_links(path)
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        before = os.fstat(handle.fileno())
        if not stat.S_ISREG(before.st_mode) or not 0 < before.st_size <= 1024*MIB:
            _fail()
        size = 0
        while block := handle.read(MIB):
            size += len(block)
            if size > 1024*MIB:
                _fail()
            digest.update(block)
        after = os.fstat(handle.fileno())
    if size != before.st_size or _identity(before) != _identity(after) or _identity(after) != _identity(_no_links(path).stat()):
        _fail("AGENT_EVIDENCE_INPUT_CHANGED")
    return digest.hexdigest()


def _source(path, ffprobe_path):
    before = _file_hash(path)
    info = probe_media(_no_links(path), ffprobe_path=ffprobe_path)
    _require_aligned_timeline(info)
    if _file_hash(path) != before:
        _fail("AGENT_EVIDENCE_INPUT_CHANGED")
    return before, info


def _recheck_inputs(inputs):
    """Recheck consumed file versions immediately before report publication.

    This is a final stability gate, not a filesystem lock against external writers.
    """
    try:
        for path, expected_hash, maximum in inputs:
            actual_hash = _file_hash(path) if maximum is None else _digest(_read(path, maximum))
            if actual_hash != expected_hash:
                _fail("AGENT_EVIDENCE_INPUT_CHANGED")
    except Exception:
        _fail("AGENT_EVIDENCE_INPUT_CHANGED")


def _confined(root, key):
    if (not isinstance(key, str) or not key or len(key) > 512 or "\\" in key
            or any(not re.fullmatch(r"[A-Za-z0-9_.-]+", part) or part in {".", ".."}
                   for part in key.split("/"))):
        _fail("UNSAFE_AGENT_EVIDENCE_PATH")
    path = _no_links(root / key)
    if not path.resolve().is_relative_to(root.resolve()):
        _fail("UNSAFE_AGENT_EVIDENCE_PATH")
    return path


def _replay_receipt(receipt, answer):
    fields = {"schema_version", "data_kind", "decoder_profile", "rebound_fields", "provider_requests", "turn_id",
              "source_turn_id", "source_stage_id", "source_attempt", "source_request_id", "source_response_hash",
              "source_window_sha256", "snapshot_hash"}
    if (set(receipt) != fields or type(receipt["schema_version"]) is not int or receipt["schema_version"] != 1
            or receipt["data_kind"] != "cached_agent_answer_replay" or receipt["decoder_profile"] != "speech-optional-hint-v1"
            or receipt["rebound_fields"] != ["turn_id"] or type(receipt["provider_requests"]) is not int
            or receipt["provider_requests"] != 0 or receipt["turn_id"] != answer["turn_id"]
            or answer["schema_version"] != "1.4" or receipt["source_turn_id"] == answer["turn_id"]
            or type(receipt["source_attempt"]) is not int or not 1 <= receipt["source_attempt"] <= 2**31-1):
        _fail("INVALID_AGENT_EVIDENCE_MANIFEST")
    for name in ("turn_id", "source_turn_id", "source_stage_id"):
        if not isinstance(receipt[name], str) or not UUID.fullmatch(receipt[name]):
            _fail("INVALID_AGENT_EVIDENCE_MANIFEST")
    for name in ("source_request_id", "source_response_hash", "source_window_sha256", "snapshot_hash"):
        if not isinstance(receipt[name], str) or not HASH.fullmatch(receipt[name]):
            _fail("INVALID_AGENT_EVIDENCE_MANIFEST")


def _manifest(answer, answer_path, manifest_path, root, source_hash, info, answer_hash, *, verified_inputs=None):
    root = _no_links(root)
    manifest, manifest_hash = _document(manifest_path, MIB)
    required = {"schema_version", "job_id", "video_id", "analysis_generation", "stage_id", "stage_type",
                "attempt", "run_token", "input_sha256", "data_kind", "review_status", "artifacts"}
    if set(manifest) != required or type(manifest["schema_version"]) is not int or manifest["schema_version"] != 1:
        _fail("INVALID_AGENT_EVIDENCE_MANIFEST")
    for name in ("job_id", "video_id", "stage_id"):
        if not isinstance(manifest[name], str) or not UUID.fullmatch(manifest[name]):
            _fail("INVALID_AGENT_EVIDENCE_MANIFEST")
    for name in ("analysis_generation", "attempt"):
        if type(manifest[name]) is not int or not 1 <= manifest[name] <= 2**31-1:
            _fail("INVALID_AGENT_EVIDENCE_MANIFEST")
    expected_stage = {"1.0": "SEGMENT", "1.1": "AGENT_VISUAL", "1.2": "AGENT_VISUAL_FOCUS"}.get(answer["schema_version"], "AGENT_EVIDENCE")
    if (manifest["job_id"] != answer["job_id"] or manifest["video_id"] != answer["video_id"]
            or manifest["input_sha256"] != source_hash or manifest["stage_type"] != expected_stage
            or manifest["data_kind"] != "model_result" or manifest["review_status"] != "DRAFT"
            or not isinstance(manifest["run_token"], str) or not re.fullmatch(r"[A-Za-z0-9_-]{16,64}", manifest["run_token"])):
        _fail("INVALID_AGENT_EVIDENCE_MANIFEST")
    prefix = f"jobs/{manifest['job_id']}/stages/{manifest['stage_id']}/attempts/{manifest['attempt']}/{manifest['run_token']}/"
    if _confined(root, prefix + "manifest.json").resolve() != _no_links(manifest_path).resolve():
        _fail("INVALID_AGENT_EVIDENCE_MANIFEST")
    frames = {f"{prefix}visual-frames/{frame['frame_id']}.jpg": frame for frame in answer.get("visual_evidence", [])}
    expected = {prefix + "agent-answer.json": "agent_answer", **{key: "agent_visual_frame" for key in frames}}
    artifacts = manifest["artifacts"]
    if not isinstance(artifacts, list) or not 1 <= len(artifacts) <= 35:
        _fail("INVALID_AGENT_EVIDENCE_MANIFEST")
    seen, trace, replay = set(), False, None
    for artifact in artifacts:
        if (not isinstance(artifact, dict) or set(artifact) != {"kind", "key", "sha256"}
                or not isinstance(artifact["key"], str) or artifact["key"] in seen
                or not isinstance(artifact["sha256"], str) or not HASH.fullmatch(artifact["sha256"])):
            _fail("INVALID_AGENT_EVIDENCE_MANIFEST")
        key, kind = artifact["key"], artifact["kind"]
        is_trace = key == prefix + "text-execution-trace.json" and kind == "text_execution_trace"
        is_replay = key == prefix + "cached-answer-replay.json" and kind == "agent_cached_answer_replay"
        if not is_trace and not is_replay and expected.get(key) != kind:
            _fail("INVALID_AGENT_EVIDENCE_MANIFEST")
        path = _confined(root, key)
        maximum = MIB if key in frames else 64*1024 if is_replay else 8*MIB
        raw = _read(path, maximum)
        if _digest(raw) != artifact["sha256"]:
            _fail("AGENT_EVIDENCE_ARTIFACT_MISMATCH")
        if verified_inputs is not None:
            verified_inputs.append((path, artifact["sha256"], maximum))
        if kind == "agent_answer":
            if path.resolve() != _no_links(answer_path).resolve() or artifact["sha256"] != answer_hash:
                _fail("AGENT_EVIDENCE_ARTIFACT_MISMATCH")
        elif key in frames:
            frame = frames[key]
            dimensions = jpeg_dimensions(raw)
            width, height = (info.height, info.width) if info.rotation_degrees % 180 else (info.width, info.height)
            if (len(raw) != frame["bytes"] or artifact["sha256"] != frame["sha256"]
                    or dimensions != (frame["width"], frame["height"])
                    or dimensions[0] > width or dimensions[1] > height):
                _fail("AGENT_EVIDENCE_ARTIFACT_MISMATCH")
        elif is_replay:
            receipt, receipt_hash = _document(path, 64*1024)
            if receipt_hash != artifact["sha256"]:
                _fail("AGENT_EVIDENCE_INPUT_CHANGED")
            _replay_receipt(receipt, answer)
            replay = {"receipt_sha256": receipt_hash, "source_turn_id": receipt["source_turn_id"],
                      "verification": "declaration_consistency_only"}
        trace |= is_trace
        seen.add(key)
    if seen != set(expected) | ({prefix + "text-execution-trace.json"} if trace else set()) | ({prefix + "cached-answer-replay.json"} if replay else set()) or trace and replay:
        _fail("INVALID_AGENT_EVIDENCE_MANIFEST")
    if verified_inputs is not None:
        verified_inputs.append((manifest_path, manifest_hash, MIB))
    return {"manifest_sha256": manifest_hash, "stage_id": manifest["stage_id"],
            "analysis_generation": manifest["analysis_generation"], "attempt": manifest["attempt"],
            "verified_artifact_count": len(artifacts), "verified_jpeg_count": len(frames), "cached_answer_replay": replay}


def _scope(reference, fixture):
    if fixture:
        return "synthetic_fixture"
    if reference["annotation_status"] == "not_annotated":
        return "unscored_audit"
    provenance = reference["provenance"]
    if provenance["label_source"] == "agent_assisted":
        return "agent_assisted_development"
    if provenance["split"] == "test" and provenance["independently_reviewed"]:
        return "independent_human_reference_declared"
    return "human_development_reference"


def evidence_reference_template(source, transcript_path, *, video_id, question, ffprobe_path="ffprobe"):
    try:
        digest, info = _source(source, ffprobe_path)
        transcript, transcript_hash = _document(transcript_path, allow_list=True)
        _inputs(transcript if isinstance(transcript, list) else transcript["utterances"], info.duration_ms)
        result = {"schema_version": "1.0", "data_kind": "agent_evidence_reference", "reference_id": "reference-" + uuid4().hex,
                  "source": {"video_id": video_id, "source_sha256": digest, "transcript_sha256": transcript_hash,
                             "duration_ms": info.duration_ms}, "question": question, "annotation_status": "not_annotated",
                  "provenance": {"label_source": "unlabeled", "split": "dev", "independently_reviewed": False},
                  "requirements": [], "notes": "待独立检查原视频与转写后标注；空要求不代表不需要证据，不从待测回答生成标签。"}
        validate_agent_evidence_reference(result)
        return result
    except EvidenceEvaluationError:
        raise
    except Exception:
        _fail()


def write_agent_evidence_reference(path, reference):
    try:
        validate_agent_evidence_reference(reference)
        path = _native_path(Path(path).absolute())
        if path.exists() or path.is_symlink():
            _fail("AGENT_EVIDENCE_OUTPUT_EXISTS")
        path.parent.mkdir(parents=True, exist_ok=True)
        _no_links(path.parent)
        with path.open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(json.dumps(reference, ensure_ascii=False, allow_nan=False, indent=2) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
    except EvidenceEvaluationError:
        raise
    except Exception:
        _fail()


def evaluate_agent_evidence_files(answer_path, reference_path, *, transcript_path, output_dir,
                                  source=None, answer_manifest=None, storage_root=None, fixture=False,
                                  ffprobe_path="ffprobe"):
    try:
        if type(fixture) is not bool or (fixture and any(p is not None for p in (source, answer_manifest, storage_root))) or (not fixture and any(p is None for p in (source, answer_manifest, storage_root))):
            _fail("INVALID_AGENT_EVIDENCE_OPTIONS")
        if not fixture and _native_path(Path(output_dir).absolute()).resolve().is_relative_to(_no_links(storage_root).resolve()):
            _fail("UNSAFE_AGENT_EVIDENCE_OUTPUT")
        answer, answer_hash = _document(answer_path)
        reference, reference_hash = _document(reference_path)
        transcript, transcript_hash = _document(transcript_path, allow_list=True)
        verified_inputs = [(answer_path, answer_hash, 8*MIB), (reference_path, reference_hash, 8*MIB),
                           (transcript_path, transcript_hash, 8*MIB)]
        validate_agent_evidence_reference(reference)
        if transcript_hash != reference["source"]["transcript_sha256"]:
            _fail("AGENT_EVIDENCE_SOURCE_MISMATCH")
        duration = reference["source"]["duration_ms"]
        utterances = transcript if isinstance(transcript, list) else transcript["utterances"]
        result = evaluate_evidence_coverage(answer, utterances, reference, duration_ms=duration)
        binding = {"answer_sha256": answer_hash, "reference_sha256": reference_hash, "transcript_sha256": transcript_hash,
                   "source_sha256": reference["source"]["source_sha256"], "duration_ms": duration,
                   "source_verification": "fixture_metadata_only" if fixture else "local_video_manifest_and_jpeg_consistency",
                   "model_response_origin": "synthetic_fixture" if fixture else "not_verified",
                   "screen_index_verification": "not_recomputed" if answer["schema_version"] == "1.5" else "not_applicable"}
        if not fixture:
            actual_hash, info = _source(source, ffprobe_path)
            if actual_hash != reference["source"]["source_sha256"] or info.duration_ms != duration:
                _fail("AGENT_EVIDENCE_SOURCE_MISMATCH")
            verified_inputs.append((source, actual_hash, None))
            binding.update(_manifest(answer, answer_path, answer_manifest, storage_root, actual_hash, info, answer_hash,
                                     verified_inputs=verified_inputs))
        report = {"schema_version": "1.0", "report_kind": "agent_evidence_coverage",
                  "evaluation_version": "required-evidence-groups-v1", "data_scope": _scope(reference, fixture),
                  "binding": binding, "reference": {"reference_id": reference["reference_id"],
                      "annotation_status": reference["annotation_status"], **reference["provenance"]},
                  "prediction": {key: answer[key] for key in ("schema_version", "job_id", "video_id", "session_id", "turn_id")},
                  "result": result, "limitations": result["limitations"] + [
                      "Only listed requirement groups are scored; unlisted content is not a negative reference.",
                      "Manifest fields are local declarations; online Java publication and provider transmission or billing are not verified.",
                      "Public screen metadata is not a complete OCR index; no full-index ranking is recomputed.",
                      "The final publication check verifies observed file versions; it does not lock external writers or provide a filesystem snapshot."]}
        _publish(output_dir, report, validate_inputs=lambda: _recheck_inputs(verified_inputs))
        return report
    except EvidenceEvaluationError:
        raise
    except Exception:
        _fail()


def _render(report):
    def esc(value):
        return html.escape(str(value), quote=True)
    result, scope = report["result"], report["data_scope"]
    label = {"synthetic_fixture": "合成验证：不代表模型效果", "unscored_audit": "未标注审计：不计算覆盖率",
             "agent_assisted_development": "辅助开发参考：非独立测试集", "human_development_reference": "声明的人工开发参考",
             "independent_human_reference_declared": "声明已独立审阅的人工参考"}[scope]
    rows = []
    for item in result["per_requirement"]:
        matches = "; ".join(f"{value['id']} / 首次 {value['first_acquired_origin']} / {','.join(value['citation_roles']) or '未引用'}" for value in item["matched_evidence"])
        rows.append(f"<tr><td>{esc(item['id'])}</td><td>{esc(item['modality'])}</td><td>{esc(json.dumps(item['criterion'], ensure_ascii=False))}</td><td>{'已取得' if item['obtained'] else '未取得'}</td><td>{'已引用' if item['cited'] else '未引用'}</td><td>{esc(matches or '无匹配记录')}</td></tr>")
    counts = result["counts"]
    required = counts["required"]
    def coverage(key):
        return "N/A" if result["coverage"][key] is None else f"{counts[key]} / {required}"
    warnings = "".join(f"<li>{esc(value)}</li>" for value in report["limitations"])
    meta = esc(json.dumps(report["binding"], ensure_ascii=False, indent=2))
    acquisition = result["acquisition"]
    origin_labels = {"MODEL": "模型工具", "USER_FOCUS": "用户指定位置", "EVIDENCE_POLICY": "程序原句预查"}
    origins = "".join(
        f"<tr><td>{esc(origin_labels[origin])}</td><td>{values['ASR']}</td><td>{values['FRAME']}</td>"
        f"<td>{acquisition['returned_by_origin'][origin]['ASR']}</td><td>{acquisition['returned_by_origin'][origin]['FRAME']}</td></tr>"
        for origin, values in acquisition["first_by_origin"].items())
    steps = []
    for step in result["tool_diagnostics"]["steps"]:
        state = step["known_status"] or ("空返回，原因未知" if step["empty_return"] else "已返回证据，无独立状态回执")
        steps.append(f"<tr><td>{step['step']}</td><td>{esc(step['tool_name'])}</td><td>{esc(origin_labels[step['origin']])}</td>"
                     f"<td>{step['utterance_count']}</td><td>{step['frame_count']}</td><td>{esc(state)}</td></tr>")
    roles = (f"观察用途 {coverage('observation_cited')} · 推断用途 {coverage('inference_cited')}"
             if report["prediction"]["schema_version"] in {"1.3", "1.4", "1.5"} else
             f"旧版未分类引用 {coverage('legacy_untyped_cited')} · 观察 / 推断用途 N/A")
    return ("<!doctype html><html lang='zh-CN'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            "<title>LiveCut 必要证据覆盖报告</title><style>body{margin:0;background:#f4f6f3;color:#183c34;font:15px/1.65 system-ui,sans-serif}"
            "main{max-width:1120px;margin:28px auto;padding:24px}section,.notice{background:#fff;padding:20px;margin:18px 0;border-radius:10px}"
            ".notice{border-left:4px solid #c28c4d}h1{font-size:27px}h2{font-size:19px}.scroll{overflow:auto}table{border-collapse:collapse;width:100%;min-width:720px}"
            "th,td{text-align:left;vertical-align:top;border-bottom:1px solid #dce4df;padding:10px;max-width:320px;overflow-wrap:anywhere}"
            "pre,p,li{overflow-wrap:anywhere}pre{white-space:pre-wrap}a{color:#176a50}@media(max-width:600px){main{margin:0;padding:12px}section,.notice{padding:14px}h1{font-size:22px}}</style><main>"
            f"<h1>必要证据覆盖报告</h1><div class='notice'><strong>{esc(label)}</strong><p>参考：{esc(report['reference']['reference_id'])}</p>"
            "<p>覆盖只表示轨迹取得或引用了列出的证据组，语义正确性：N/A。稀疏画面不代表连续观看。</p></div>"
            f"<section><h2>组覆盖</h2><p>必要组 {required} · 取得 {coverage('obtained')} · 引用 {coverage('cited')}</p>"
            f"<p>{roles}</p>"
            "<p>同一证据可覆盖多组；观察与推断可重叠，不能相加。未列出的内容不作负例。</p>"
            "<div class='scroll'><table><thead><tr><th>要求 ID</th><th>模态</th><th>参考条件</th><th>取得</th><th>引用</th><th>匹配与用途</th></tr></thead><tbody>"
            + "".join(rows) + "</tbody></table></div></section>"
            f"<section><h2>取得路径与唯一证据量</h2><p>原句 {acquisition['utterance_count']} 条 · 画面 {acquisition['frame_count']} 帧。首次取得按证据 ID 去重；同一证据在不同路径再次返回可分别出现。</p>"
            "<div class='scroll'><table><tr><th>路径</th><th>首次原句</th><th>首次画面</th><th>返回过的原句</th><th>返回过的画面</th></tr>"
            f"{origins}</table></div><p>模型复用用户指定的帧，不会变成模型首次自主发现。</p></section>"
            "<section><h2>工具回执</h2><div class='scroll'><table><tr><th>步骤</th><th>工具</th><th>路径</th><th>原句数</th><th>画面数</th><th>状态</th></tr>"
            + "".join(steps) + "</table></div><p>普通空返回的原因未知；不能当作全片没有内容或工具失败。</p></section>"
            f"<section><h2>输入绑定与边界</h2><p>原视频、转写、回答和参考分别记录摘要。模型语义正确性未评测；本地 manifest 不是在线发布或供应商真实性证明。</p>"
            f"<details><summary>展开输入摘要与完整边界</summary><pre>{meta}</pre><ul>{warnings}</ul></details><p><a href='report.json'>下载 JSON 报告</a></p></section></main></html>")


def _publish(directory, report, *, validate_inputs=None):
    directory = _native_path(Path(directory).absolute())
    if directory.exists() or directory.is_symlink():
        _fail("AGENT_EVIDENCE_OUTPUT_EXISTS")
    directory.parent.mkdir(parents=True, exist_ok=True)
    _no_links(directory.parent)
    temporary = directory.parent / (".agent-evaluation-" + uuid4().hex)
    temporary.mkdir()
    try:
        for name, content in (("report.json", json.dumps(report, ensure_ascii=False, allow_nan=False, indent=2)), ("index.html", _render(report))):
            with (temporary / name).open("x", encoding="utf-8", newline="\n") as stream:
                stream.write(content + "\n")
                stream.flush()
                os.fsync(stream.fileno())
        if validate_inputs is not None:
            validate_inputs()
        try:
            directory.mkdir()
        except FileExistsError:
            _fail("AGENT_EVIDENCE_OUTPUT_EXISTS")
        # Exclusive directory reservation prevents POSIX rename from replacing
        # a concurrently created empty directory. Publish complete files without
        # replacing a peer's file; index.html is the last success marker.
        for name in ("report.json", "index.html"):
            os.link(temporary / name, directory / name)
    finally:
        if temporary.exists():
            for name in ("report.json", "index.html"):
                (temporary / name).unlink(missing_ok=True)
            temporary.rmdir()
