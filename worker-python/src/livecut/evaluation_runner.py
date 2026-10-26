"""Offline, source-bound evaluation and audits. No model requests or business writes."""

from __future__ import annotations

import hashlib
import html
import json
import os
from pathlib import Path
from uuid import uuid4

from .evaluation import EvaluationError, evaluate_localization
from .evaluation_inputs import load_json, validate_annotation, validate_evaluation_binding
from .evidence_audit import audit_timestamps
from .media import probe_media


def _hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while block := handle.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _document(path: str | Path) -> tuple[dict, str]:
    path = Path(path)
    if path.stat().st_size > 8 * 1024 * 1024:
        raise EvaluationError("EVALUATION_LIMIT_EXCEEDED", "Evaluation JSON exceeds 8 MiB")
    before = _hash(path)
    value = load_json(path)
    if _hash(path) != before:
        raise EvaluationError("EVALUATION_INPUT_CHANGED", "Evaluation input changed while being read")
    return value, before


def _source(path: str | Path, ffprobe_path: str) -> tuple[str, int]:
    path = Path(path)
    if not path.is_file() or not 0 < path.stat().st_size <= 1024**3:
        raise EvaluationError("INVALID_EVALUATION_INPUT", "Source must be a nonempty local video up to 1 GiB")
    digest = _hash(path)
    media = probe_media(path, ffprobe_path=ffprobe_path)
    if _hash(path) != digest:
        raise EvaluationError("EVALUATION_INPUT_CHANGED", "Source video changed during media inspection")
    return digest, media.duration_ms


def annotation_template(source: str | Path, *, video_id: str, original_video_id: str,
                        split: str = "dev", ffprobe_path: str = "ffprobe") -> dict:
    digest, duration = _source(source, ffprobe_path)
    result = {"schema_version": "1.0", "data_kind": "localization_annotation", "video_id": video_id,
              "source_sha256": digest, "duration_ms": duration, "annotation_status": "not_annotated",
              "provenance": {"label_source": "unlabeled", "split": split,
                             "original_video_id": original_video_id, "independently_reviewed": False},
              "annotated_ranges": [], "segments": [],
              "notes": "待标注模板。请独立于自动预测完成标注；空数组不代表背景。"}
    validate_annotation(result, require_annotated=False)
    return result


def _scope(annotation: dict, fixture: bool) -> str:
    origin = annotation["provenance"]
    if fixture:
        return "synthetic_fixture"
    if origin["label_source"] == "agent_assisted":
        return "agent_assisted_development"
    if origin["split"] == "test" and origin["independently_reviewed"]:
        return "independent_human_reference_declared"
    return "human_development_reference"


def _prepare(prediction_path: str | Path, *, source: str | Path | None,
             prediction_manifest: str | Path | None, fixture: bool,
             ffprobe_path: str) -> tuple[dict, dict, dict | None, str | None, int | None]:
    if fixture and (source is not None or prediction_manifest is not None):
        raise EvaluationError("INVALID_EVALUATION_INPUT", "Fixture mode cannot imply verified real source inputs")
    prediction, digest = _document(prediction_path)
    manifest, manifest_digest = _document(prediction_manifest) if prediction_manifest is not None else (None, None)
    source_digest, duration = _source(source, ffprobe_path) if source is not None else (None, None)
    binding = {"prediction_sha256": digest, "manifest_sha256": manifest_digest,
               "source_verification": "fixture_metadata_only" if fixture else "actual_video_and_local_manifest"}
    return prediction, binding, manifest, source_digest, duration


def _prediction_summary(prediction: dict) -> dict:
    counts: dict[str, int] = {}
    for segment in prediction["segments"]:
        kind = segment["content_kind"]
        counts[kind] = counts.get(kind, 0) + 1
    return {"video_id": prediction["video_id"], "job_id": prediction["job_id"],
            "analysis_generation": prediction["analysis_generation"], "data_kind": prediction["data_kind"],
            "pipeline_version": prediction["pipeline_version"], "config_hash": prediction["provenance"]["config_hash"],
            "segment_counts_by_content_kind": counts,
            "detection_candidate_kinds": ["product_talk", "mixed", "uncertain"]}


def _evidence_audit(prediction: dict) -> dict:
    return audit_timestamps(prediction["duration_ms"], [frame["timestamp_ms"] for frame in prediction["frames"]])


def evaluate_files(prediction_path: str | Path, annotation_path: str | Path, *, output_dir: str | Path,
                   source: str | Path | None = None, prediction_manifest: str | Path | None = None,
                   fixture: bool = False, thresholds=("0.3", "0.5", "0.7"),
                   ffprobe_path: str = "ffprobe") -> dict:
    prediction, binding, manifest, source_digest, duration = _prepare(
        prediction_path, source=source, prediction_manifest=prediction_manifest, fixture=fixture,
        ffprobe_path=ffprobe_path)
    annotation, annotation_digest = _document(annotation_path)
    validate_evaluation_binding(prediction, annotation, source_sha256=source_digest, duration_ms=duration,
                                manifest=manifest, prediction_sha256=binding["prediction_sha256"], fixture=fixture)
    candidates = [{"id": item["segment_id"], "start_ms": item["start_ms"], "end_ms": item["end_ms"]}
                  for item in prediction["segments"] if item["content_kind"] != "non_product"]
    result = evaluate_localization(candidates, annotation["segments"], coverage=annotation["annotated_ranges"],
                                   duration_ms=prediction["duration_ms"], thresholds=thresholds)
    report = {"schema_version": "1.0", "report_kind": "localization_evaluation",
              "evaluation_version": "class-agnostic-localization-v1", "data_scope": _scope(annotation, fixture),
              "binding": {**binding, "annotation_sha256": annotation_digest,
                          "source_sha256": annotation["source_sha256"]},
              "prediction": _prediction_summary(prediction),
              "reference": {"annotation_status": annotation["annotation_status"], **annotation["provenance"]},
              "comparison_inputs": {"predictions": candidates, "references": [dict(item) for item in annotation["segments"]]},
              "result": result, "evidence_audit": _evidence_audit(prediction),
              "limitations": ["Class-agnostic localization only; no product identity or SKU accuracy is measured.",
                              "Reference provenance is declared by the annotator, not independently verified by this tool.",
                              "Local manifest binding proves input consistency, not external authenticity."]}
    _publish(Path(output_dir), report)
    return report


def audit_files(prediction_path: str | Path, *, output_dir: str | Path, source: str | Path | None = None,
                prediction_manifest: str | Path | None = None, fixture: bool = False,
                ffprobe_path: str = "ffprobe") -> dict:
    prediction, binding, manifest, source_digest, duration = _prepare(
        prediction_path, source=source, prediction_manifest=prediction_manifest, fixture=fixture,
        ffprobe_path=ffprobe_path)
    annotation = {"schema_version": "1.0", "data_kind": "localization_annotation", "video_id": prediction.get("video_id"),
                  "source_sha256": source_digest or "0" * 64, "duration_ms": prediction.get("duration_ms"),
                  "annotation_status": "not_annotated", "provenance": {
                      "label_source": "unlabeled", "split": "dev",
                      "original_video_id": prediction.get("video_id"), "independently_reviewed": False},
                  "annotated_ranges": [], "segments": []}
    validate_evaluation_binding(prediction, annotation, source_sha256=source_digest, duration_ms=duration,
                                manifest=manifest, prediction_sha256=binding["prediction_sha256"], fixture=fixture,
                                require_annotated=False)
    report = {"schema_version": "1.0", "report_kind": "localization_audit", "data_scope": "unscored_audit",
              "binding": {**binding, "source_sha256": source_digest},
              "prediction": _prediction_summary(prediction), "duration_ms": prediction["duration_ms"],
              "evidence_audit": _evidence_audit(prediction),
              "limitations": ["No annotated reference was supplied. No detection or boundary quality scores are computed.",
                              "Timestamp density does not measure recognition accuracy or continuous visual coverage."]}
    _publish(Path(output_dir), report)
    return report


def _number(value) -> str:
    return "N/A" if value is None else f"{value:.4f}" if isinstance(value, float) else str(value)


def _render(report: dict) -> str:
    def esc(value) -> str:
        return html.escape(str(value), quote=True)
    audit = report["evidence_audit"]
    scope_labels = {"synthetic_fixture": "合成验证：不代表识别质量", "agent_assisted_development": "Agent 辅助开发核查：非人工金标准",
                    "human_development_reference": "人工开发参考：不代表独立测试表现",
                    "independent_human_reference_declared": "独立人工参考（按标注者声明）",
                    "unscored_audit": "未评分审计：没有参考标注"}
    sections = []
    if "result" in report:
        result = report["result"]
        predictions = {item["id"]: item for item in report["comparison_inputs"]["predictions"]}
        references = {item["id"]: item for item in report["comparison_inputs"]["references"]}

        def interval_text(identity, values) -> str:
            item = values[identity]
            return f"{identity} [{item['start_ms']}, {item['end_ms']}) ms"
        sections.append(f"<p>已标注 {result['coverage']['annotated_ms']} / {result['coverage']['duration_ms']} ms；"
                        f"纳入预测 {result['counts']['prediction_included']}，参考 {result['counts']['reference_total']}；"
                        f"范围外预测 {result['counts']['prediction_excluded']}。商品讲解、mixed 和 uncertain 候选均纳入。</p>")
        for item in result["thresholds"]:
            rows = "".join(f"<tr><td>{esc(interval_text(match['prediction_id'], predictions))}</td>"
                           f"<td>{esc(interval_text(match['reference_id'], references))}</td>"
                           f"<td>{_number(match['iou'])}</td><td>{_number(match['start_error_ms'])}</td>"
                           f"<td>{_number(match['end_error_ms'])}</td><td>{'是' if match['boundary_censored'] else '否'}</td></tr>"
                           for match in item["matches"])
            sections.append(f"<section><h2>IoU ≥ {esc(item['iou_threshold_fraction'])}</h2><p>"
                            f"TP {item['tp']} · FP {item['fp']} · FN {item['fn']} · Precision {_number(item['precision'])} · "
                            f"Recall {_number(item['recall'])} · F1 {_number(item['f1'])}</p>"
                            f"<div class='scroll'><table><thead><tr><th>预测</th><th>参考</th><th>IoU</th><th>起点偏差 ms</th>"
                            f"<th>终点偏差 ms</th><th>边界受标注范围截断</th></tr></thead><tbody>{rows}</tbody></table></div>"
                            f"<p>未匹配预测：{esc('; '.join(interval_text(identity, predictions) for identity in item['false_positive_ids'])) or '无'}</p>"
                            f"<p>未匹配参考：{esc('; '.join(interval_text(identity, references) for identity in item['false_negative_ids'])) or '无'}</p>"
                            f"<p>绝对边界误差：{esc(json.dumps(item['boundary'], ensure_ascii=False))}</p></section>")
    else:
        sections.append("<p>未计算 Precision、Recall、F1 或边界误差。请先独立完成参考标注。</p>")
    bins = "".join(f"<tr><td>{item['start_ms']}–{item['end_ms']}</td><td>{item['observation_count']}</td></tr>" for item in audit["windows"])
    warnings = "".join(f"<li>{esc(item)}</li>" for item in report["limitations"])
    return ("<!doctype html><html lang='zh-CN'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            "<title>LiveCut 离线定位报告</title><style>body{font:15px/1.7 system-ui,sans-serif;margin:0;background:#f5f7f4;color:#26372c}"
            "main{max-width:1100px;margin:32px auto;padding:24px}section,.notice{background:white;padding:20px;margin:20px 0;border-radius:10px}"
            "h1{font-size:28px}h2{font-size:20px}.notice{border-left:4px solid #df8d51}table{border-collapse:collapse;width:100%}"
            "th,td{text-align:left;border-bottom:1px solid #dce3da;padding:9px;white-space:nowrap}.scroll{overflow:auto}p,li,code{overflow-wrap:anywhere}"
            "@media(max-width:600px){main{margin:0;padding:14px}section,.notice{padding:14px}h1{font-size:23px}}</style><main>"
            f"<h1>LiveCut 离线定位报告</h1><div class='notice'><strong>{esc(scope_labels[report['data_scope']])}</strong>"
            f"<p>视频：{esc(report['prediction']['video_id'])} · 分析：{esc(report['prediction']['job_id'])}</p>"
            "<p>原视频整数毫秒、半开区间；评分采用最大一对一匹配，未评分审计不执行匹配。单对偏差为预测减参考，汇总取绝对值；P90 使用 nearest-rank。N/A 表示分母或样本不足。</p></div>"
            + "".join(sections) + f"<section><h2>采样证据审计</h2><p>观察 {audit['observation_count']} 个；"
            f"最后时间 {_number(audit['last_timestamp_ms'])} ms；包含首尾的最大空档 {audit['maximum_gap_including_edges_ms']} ms；"
            f"无采样时间桶 {audit['windows_without_observations']} / {audit['window_count']} 个，"
            f"其中完整 180 秒桶 {audit['full_windows_without_observations']} 个。</p>"
            "<p>时间桶包含末尾不足 180 秒的余段，不等于模型请求窗口。孤立采样帧不代表连续视觉覆盖或识别准确率。</p>"
            f"<div class='scroll'><table><tr><th>时间范围 ms</th><th>采样时间点数</th></tr>{bins}</table></div></section>"
            f"<section><h2>输入绑定与解释边界</h2><code>{esc(json.dumps(report['binding'], ensure_ascii=False))}</code><ul>{warnings}</ul>"
            "<p><a href='report.json'>下载原始报告 JSON</a></p></section></main></html>")


def _publish(directory: Path, report: dict) -> None:
    directory = directory.absolute()
    if directory.exists() or directory.is_symlink():
        raise EvaluationError("EVALUATION_OUTPUT_EXISTS", "Use a new report directory; existing outputs are preserved")
    directory.parent.mkdir(parents=True, exist_ok=True)
    temporary = directory.parent / (".evaluation-" + uuid4().hex)
    temporary.mkdir()
    try:
        for name, data in (("report.json", json.dumps(report, ensure_ascii=False, allow_nan=False, indent=2)),
                           ("index.html", _render(report))):
            with (temporary / name).open("x", encoding="utf-8", newline="\n") as output:
                output.write(data + "\n")
                output.flush()
                os.fsync(output.fileno())
        if directory.exists() or directory.is_symlink():
            raise EvaluationError("EVALUATION_OUTPUT_EXISTS", "Report directory was created by another invocation")
        temporary.rename(directory)
    finally:
        # Only these two owned files, no recursive cleanup or source mutation.
        if temporary.exists():
            for name in ("report.json", "index.html"):
                (temporary / name).unlink(missing_ok=True)
            temporary.rmdir()
