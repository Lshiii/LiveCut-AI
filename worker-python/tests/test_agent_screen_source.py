"""Published OCR source fixtures use real local JPEGs, synthetic OCR text."""
import json
import os
import stat
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import pytest
from livecut.agent_frames import FrameStore
from livecut.agent_screen import build_screen_index, load_screen_context
from livecut.segmentation import SegmentationError
from livecut.worker import WorkerError, _native_path, _write, sha256
from test_agent_evidence import block, reply
from test_agent_visual import real_source as _real_source
from test_agent_visual import tool
from test_agent_visual_worker import mock_executor, visual_context

real_source = _real_source


def published_context(tmp_path, source):
    ctx = visual_context(tmp_path, source)
    config = ctx.lease.config
    config["agent_turn"].update(evidence_mode="structured", evidence_policy="screen_search_v1")
    ctx.lease = replace(ctx.lease, stage_type="AGENT_EVIDENCE", required_worker_protocol=4)
    config["max_text_calls"] = 4
    stage_id, token = str(uuid4()), str(uuid4())
    prefix = f"jobs/{ctx.lease.job_id}/stages/{stage_id}/attempts/1/{token}/"
    directory = ctx.storage_root / prefix
    _native_path(directory / "ocr/frames").mkdir(parents=True)
    frame = FrameStore(source, tmp_path / "source-image", sha256(source), 20000).inspect("inspect_frames", {"timestamps_ms": [0]})[0]
    jpeg = tmp_path / "source-image/visual-frames" / (frame["frame_id"] + ".jpg")
    _native_path(directory / "ocr/frames/ocr_000001.jpg").write_bytes(jpeg.read_bytes())
    frames = [{"frame_id": "ocr_000001", "requested_timestamp_ms": 0, "timestamp_ms": frame["timestamp_ms"],
               "source_pts": frame["source_pts"], "source_time_base": frame["source_time_base"],
               "object_key": "frames/ocr_000001.jpg", "sha256": frame["sha256"]}]
    observations = [{"frame_id": "ocr_000001", "timestamp_ms": frame["timestamp_ms"], "object_key": "frames/ocr_000001.jpg",
                     "presented_category": None, "observed_text": "UNSPOKEN_SCREEN_TOKEN Cache", "visibility": "clear"}]
    state = {"input": {"source_sha256": ctx.lease.source_hash, "duration_ms": 20000, "interval_ms": 45000,
                       "max_frames": 16, "model_id": "synthetic-ocr"}, "frames": frames,
             "observations": observations, "coverage_truncated": False}
    provenance = {"schema_version": 1, "cache_hit": True, "cache_key": "a"*64, "binding_sha256": "a"*64,
                  "bundle_manifest_sha256": "b"*64, "frame_count": 1, "coverage_truncated": False, "observer_id": "synthetic-ocr"}
    for filename, value in (("frames", frames), ("observations", observations), ("ocr-state", state), ("cache-provenance", provenance)):
        _write(directory / "ocr" / f"{filename}.json", value)
    analysis_path = ctx.storage_root / config["agent_turn"]["source_analysis_key"]
    analysis = json.loads(_native_path(analysis_path).read_bytes())
    analysis["frames"] = [{"frame_id": "ocr_000001", "timestamp_ms": frame["timestamp_ms"], "object_key": prefix + "ocr/frames/ocr_000001.jpg"}]
    _write(analysis_path, analysis)
    timeline = next(row for row in ctx.lease.artifacts if row["stage_type"] == "TIMELINE")
    timeline_path = ctx.storage_root / timeline["manifest_key"]
    tm = json.loads(_native_path(timeline_path).read_bytes())
    tm["artifacts"][0]["sha256"] = sha256(analysis_path)
    _write(timeline_path, tm)
    timeline["manifest_hash"] = sha256(timeline_path)
    config["agent_turn"]["source_analysis_hash"] = sha256(analysis_path)
    # Deliberately different newlines from TIMELINE: bind decoded JSON, not file SHA equality.
    _native_path(directory / "analysis-result.json").write_bytes(json.dumps(analysis, ensure_ascii=False, indent=2).replace("\n", "\r\n").encode("utf-8"))
    paths = {"analysis_result": "analysis-result.json", "ocr_cache_provenance": "ocr/cache-provenance.json", "ocr_observations": "ocr/observations.json",
             "ocr_frames": "ocr/frames.json", "ocr_state": "ocr/ocr-state.json", "ocr_frame_000001": "ocr/frames/ocr_000001.jpg"}
    manifest = {"schema_version": 1, "job_id": ctx.lease.job_id, "video_id": ctx.lease.video_id, "analysis_generation": ctx.lease.generation,
                "stage_id": stage_id, "stage_type": "SEGMENT", "attempt": 1, "run_token": token, "input_sha256": ctx.lease.source_hash,
                "data_kind": "model_result", "review_status": "DRAFT", "artifacts": [{"kind": kind, "key": prefix+key,
                    "sha256": sha256(directory / key)} for kind, key in paths.items()]}
    _write(directory / "manifest.json", manifest)
    by_kind = {row["kind"]: row for row in manifest["artifacts"]}
    manifest_hash = sha256(directory / "manifest.json")
    row = {"ocr_frame_id": "ocr_000001", "requested_ms": 0, "ocr_timestamp_ms": frame["timestamp_ms"], "source_pts": frame["source_pts"],
           "source_time_base": frame["source_time_base"], "observed_text": observations[0]["observed_text"], "ocr_sha256": frame["sha256"]}
    index = build_screen_index([row], source_hash=ctx.lease.source_hash, duration_ms=20000, segment_manifest_hash=manifest_hash,
                              frames_hash=by_kind["ocr_frames"]["sha256"], observations_hash=by_kind["ocr_observations"]["sha256"])
    public = {"segment_stage_id": stage_id, "segment_manifest_hash": manifest_hash, "frames_hash": by_kind["ocr_frames"]["sha256"],
              "observations_hash": by_kind["ocr_observations"]["sha256"], "index_version": "screen-text-literal-v1", "index_hash": index["index_hash"],
              "duration_ms": 20000, "sampling_policy": "prefix", "interval_ms": 45000, "frame_count": 1, "text_frame_count": 1, "coverage_truncated": False}
    context = {"schema_version": 1, "segment": {key: manifest[key] for key in ("job_id", "video_id", "analysis_generation", "stage_id", "stage_type", "attempt", "run_token", "input_sha256")},
               "analysis": {"key": config["agent_turn"]["source_analysis_key"], "sha256": config["agent_turn"]["source_analysis_hash"]},
               "ocr_config": {"sampling_policy": "prefix", "max_frames": 16, "interval_ms": 45000}, "screen_source": public}
    context["segment"].update(manifest_key=prefix+"manifest.json", manifest_hash=manifest_hash)
    ctx.lease.artifacts.append({key: context["segment"][key] for key in ("stage_id", "stage_type", "manifest_key", "manifest_hash")})
    config["agent_turn"]["screen_context"] = context
    return ctx, analysis


def load(ctx, analysis):
    return load_screen_context(ctx.storage_root, ctx.lease.config["agent_turn"]["screen_context"], job_id=ctx.lease.job_id,
        video_id=ctx.lease.video_id, generation=ctx.lease.generation, source_hash=ctx.lease.source_hash,
        duration_ms=20000, analysis=analysis, predecessors=ctx.lease.artifacts, guard=ctx.guard.check)


def test_published_loader_accepts_verified_old_prefix_and_distinct_newline_hashes(tmp_path, real_source):
    ctx, analysis = published_context(tmp_path, real_source)
    index = load(ctx, analysis)
    assert index["screen_source"] == ctx.lease.config["agent_turn"]["screen_context"]["screen_source"]
    assert index["rows"][0]["observed_text"] == "UNSPOKEN_SCREEN_TOKEN Cache"


@pytest.mark.parametrize("mutation", ["bytes", "missing", "wrong_job", "wrong_generation", "wrong_analysis", "wrong_sampling", "wrong_index", "missing_ref"])
def test_source_rejection_precedes_any_model_transport(tmp_path, real_source, monkeypatch, mutation):
    ctx, analysis = published_context(tmp_path, real_source)
    context = ctx.lease.config["agent_turn"]["screen_context"]
    directory = ctx.storage_root / Path(context["segment"]["manifest_key"]).parent
    if mutation == "bytes":
        _native_path(directory / "ocr/frames/ocr_000001.jpg").write_bytes(b"changed")
    elif mutation == "missing":
        _native_path(directory / "ocr/observations.json").unlink()
    elif mutation == "wrong_job":
        context["segment"]["job_id"] = "other-job"
    elif mutation == "wrong_generation":
        context["segment"]["analysis_generation"] += 1
    elif mutation == "wrong_analysis":
        context["analysis"]["sha256"] = "0"*64
    elif mutation == "wrong_sampling":
        context["ocr_config"]["max_frames"] = 120
    elif mutation == "wrong_index":
        context["screen_source"]["index_hash"] = "0"*64
    else:
        ctx.lease.artifacts[:] = [ref for ref in ctx.lease.artifacts if ref["stage_type"] != "SEGMENT"]
    executor = mock_executor(monkeypatch, [], [])
    executor.transport_factory = lambda *_: pytest.fail("Invalid published source must fail before transport construction")
    with pytest.raises(Exception) as raised:
        executor(ctx)
    assert getattr(raised.value, "code", None) == "AGENT_SCREEN_SOURCE_INVALID"


def test_production_worker_consumes_published_source_and_actual_images_without_ocr(tmp_path, real_source, monkeypatch):
    ctx, _ = published_context(tmp_path, real_source)
    for name in ("cached_observe_video_ocr", "discover_topics", "transcribe_file"):
        monkeypatch.setattr("livecut.worker."+name, lambda *a, **k: pytest.fail("No preparation may be rerun"))
    requests = []
    executor = mock_executor(monkeypatch, [tool("search_screen_text", query="unspoken_screen_token", top_k=1), reply(block(images=["f0001"]))], requests)
    manifest = executor(ctx)
    assert len(requests) == 2 and manifest["stage_type"] == "AGENT_EVIDENCE"
    result = json.loads(_native_path(ctx.directory / "agent-answer.json").read_bytes())
    assert result["schema_version"] == "1.5" and result["screen_source"] == ctx.lease.config["agent_turn"]["screen_context"]["screen_source"]
    assert len(result["visual_evidence"]) == 1 and len(result["screen_searches"]) == 1
    assert all(sha256(ctx.storage_root / row["key"]) == row["sha256"] for row in manifest["artifacts"])
    assert executor(ctx) == manifest and len(requests) == 2


def test_published_source_rejects_linked_jpeg_without_touching_external_bytes(tmp_path, real_source, monkeypatch):
    ctx, analysis = published_context(tmp_path, real_source)
    segment = ctx.lease.config["agent_turn"]["screen_context"]["segment"]
    image = ctx.storage_root / Path(segment["manifest_key"]).parent / "ocr/frames/ocr_000001.jpg"
    data = _native_path(image).read_bytes()
    outside = tmp_path / "outside-evidence.jpg"
    outside.write_bytes(data)
    _native_path(image).unlink()
    try:
        _native_path(image).symlink_to(outside)
    except OSError as exc:
        if os.name != "nt" or getattr(exc, "winerror", None) != 1314:
            raise
        # Windows without native-link privilege still exercises the lstat
        # rejection deterministically; Linux CI uses the real symlink above.
        _native_path(image).write_bytes(data)
        original = Path.lstat
        def lstat(path, *args, **kwargs):
            if str(path).endswith("ocr_000001.jpg"):
                return SimpleNamespace(st_mode=stat.S_IFLNK, st_file_attributes=0)
            return original(path, *args, **kwargs)
        monkeypatch.setattr(Path, "lstat", lstat)
    with pytest.raises(SegmentationError) as raised:
        load(ctx, analysis)
    assert raised.value.code == "AGENT_SCREEN_SOURCE_INVALID" and outside.read_bytes() == data


def test_cancellation_is_not_reclassified_as_empty_or_source_error(tmp_path, real_source):
    ctx, analysis = published_context(tmp_path, real_source)
    def cancelled():
        raise WorkerError("SYNTHETIC_LEASE_LOST")
    ctx.guard.check = cancelled
    with pytest.raises(WorkerError, match="SYNTHETIC_LEASE_LOST"):
        load(ctx, analysis)


@pytest.mark.parametrize("mutation", ["state_frame_bool", "input_float", "provenance_binding"])
def test_resealed_metadata_still_requires_typed_cross_file_and_producer_invariants(tmp_path, real_source, mutation):
    ctx, analysis = published_context(tmp_path, real_source)
    original = load(ctx, analysis)
    context = ctx.lease.config["agent_turn"]["screen_context"]
    segment = context["segment"]
    directory = ctx.storage_root / Path(segment["manifest_key"]).parent
    filename = "ocr/cache-provenance.json" if mutation == "provenance_binding" else "ocr/ocr-state.json"
    path = directory / filename
    value = json.loads(_native_path(path).read_bytes())
    if mutation == "state_frame_bool":
        value["frames"][0]["requested_timestamp_ms"] = False  # == 0 in ordinary Python equality
    elif mutation == "input_float":
        value["input"]["max_frames"] = 16.0
    else:
        value["cache_key"] = "c"*64
    _write(path, value)
    manifest_path = ctx.storage_root / segment["manifest_key"]
    manifest = json.loads(_native_path(manifest_path).read_bytes())
    for item in manifest["artifacts"]:
        item["sha256"] = sha256(ctx.storage_root / item["key"])
    _write(manifest_path, manifest)
    segment["manifest_hash"] = sha256(manifest_path)
    next(ref for ref in ctx.lease.artifacts if ref["stage_type"] == "SEGMENT")["manifest_hash"] = segment["manifest_hash"]
    public = context["screen_source"]
    public["segment_manifest_hash"] = segment["manifest_hash"]
    public["index_hash"] = build_screen_index(original["rows"], source_hash=ctx.lease.source_hash, duration_ms=20000,
        segment_manifest_hash=segment["manifest_hash"], frames_hash=public["frames_hash"], observations_hash=public["observations_hash"])["index_hash"]
    with pytest.raises(SegmentationError) as raised:
        load(ctx, analysis)
    assert raised.value.code == "AGENT_SCREEN_SOURCE_INVALID"
