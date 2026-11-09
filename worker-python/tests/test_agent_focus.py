"""Explicit focus is a frozen hint; only real local JPEGs become visual evidence."""
import copy
import hashlib
import json
import threading
from dataclasses import replace

import httpx
import pytest
from livecut.agent_visual import SYSTEM, build_visual_answer, validate_visual_answer
from livecut.model_budget import BudgetTransport
from livecut.segmentation import SegmentationError
from livecut.text_resilience import ResilientTextTransport, TextRecoveryError, TextRetryPolicy
from livecut.vision import VisionError
from livecut.worker import Lease, LeaseGuard, StageContext, StageWorker, WorkerError
from livecut.worker_client import WorkerClientError
from test_agent_visual import answer, image_parts, source_items, tool, topics
from test_agent_visual import real_source as _real_source
from test_agent_visual import run as visual_run
from test_agent_visual_worker import mock_executor, visual_budget_transport, visual_context
from test_text_resilience import Ledger
from test_worker import FakeClient, claim

real_source = _real_source


def run(root, source, replies, **options):
    options.setdefault("focus_timestamp_ms", 0)
    options.setdefault("budget_remaining", lambda: options.get("max_calls", 8))
    return visual_run(root, source, replies, **options)


def test_focus_zero_primes_real_image_in_first_and_only_budgeted_request(tmp_path, real_source):
    result, seen = run(tmp_path, real_source, [answer(visual=["f0001"])], max_calls=1)
    assert len(seen) == 1 and len(image_parts(seen[0])) == 1
    assert result["schema_version"] == "1.2" and result["focus_timestamp_ms"] == 0
    assert result["provenance"]["prompt_version"] == "video-agent-visual-focus-v1"
    assert result["tool_calls"] == [{"step": 1, "tool_name": "inspect_frames", "arguments": {"timestamps_ms": [0]},
        "origin": "USER_FOCUS", "result_utterance_ids": [], "result_frame_ids": ["f0001"]}]
    payload = json.loads(seen[0]["messages"][1]["content"][0]["text"])
    assert payload["focus_timestamp_ms"] == 0 and payload["remaining_tool_steps"] == 0
    assert payload["observations"][0]["origin"] == "USER_FOCUS"
    receipt = json.loads((tmp_path / "agent-visual/focus-observation.json").read_text("utf-8"))
    assert receipt["status"] == "READY" and receipt["frame"] == result["visual_evidence"][0]
    validate_visual_answer(result, source_items(), duration_ms=20000, expected={"focus_timestamp_ms": 0})


def test_focus_is_requested_position_and_not_fabricated_actual_pts(tmp_path, real_source):
    result, seen = run(tmp_path, real_source, [answer(visual=["f0001"])], focus_timestamp_ms=1)
    frame = result["visual_evidence"][0]
    assert frame["requested_ms"] == 1 and frame["timestamp_ms"] > 1
    assert "附加时间" not in json.dumps(frame, ensure_ascii=False) and len(image_parts(seen[0])) == 1


@pytest.mark.parametrize("focus", [None, False, True, -1, 0.0, 1.5, 20000, 2**53, "0"])
def test_invalid_focus_rejected_without_model_or_extraction(tmp_path, real_source, monkeypatch, focus):
    monkeypatch.setattr("livecut.agent_frames._extract_frame", lambda *args, **kwargs: pytest.fail("No extraction"))
    with pytest.raises(SegmentationError, match="attached position must"):
        run(tmp_path, real_source, [], focus_timestamp_ms=focus)


@pytest.mark.parametrize("budget", [0, None, True])
def test_new_focus_without_verified_budget_does_not_extract_or_request(tmp_path, real_source, monkeypatch, budget):
    monkeypatch.setattr("livecut.agent_frames._extract_frame", lambda *args, **kwargs: pytest.fail("No extraction"))
    with pytest.raises(SegmentationError, match="verified model request budget"):
        run(tmp_path, real_source, [], budget_remaining=lambda: budget)
    assert not (tmp_path / "agent-visual/focus-observation.json").exists()


def test_received_focus_replays_with_zero_budget_without_extract_or_provider(tmp_path, real_source, monkeypatch):
    expected, _ = run(tmp_path, real_source, [answer(visual=["f0001"])], max_calls=1)
    monkeypatch.setattr("livecut.agent_frames._extract_frame", lambda *args, **kwargs: pytest.fail("Never resample on recovery"))
    recovered, seen = run(tmp_path, real_source, [], max_calls=1, budget_remaining=lambda: 0)
    assert recovered == expected and seen == []


def test_failed_initial_observation_is_persisted_and_never_retried(tmp_path, real_source, monkeypatch):
    attempts = []
    def unavailable(*args, **kwargs):
        attempts.append(1)
        raise VisionError("FRAME_TIMESTAMP_MISSING", "synthetic missing source timestamp")
    monkeypatch.setattr("livecut.agent_frames._extract_frame", unavailable)
    with pytest.raises(SegmentationError, match="attached position has no verified frame"):
        run(tmp_path, real_source, [])
    path = tmp_path / "agent-visual/focus-observation.json"
    saved = json.loads(path.read_text("utf-8"))
    assert saved["status"] == "FAILED" and saved["error_code"] == "FRAME_TIMESTAMP_MISSING"
    before = path.read_bytes()
    with pytest.raises(SegmentationError, match="original initial focus observation failed"):
        run(tmp_path, real_source, [])
    assert attempts == [1] and path.read_bytes() == before


@pytest.mark.parametrize("failure", ["intent", "ready"])
def test_initial_observation_persistence_failure_stops_before_any_model(tmp_path, real_source, monkeypatch, failure):
    from livecut import agent_focus
    save = agent_focus._save
    attempts = []
    def interrupted(path, value):
        attempts.append(value["status"])
        if value["status"] == ("PREPARING" if failure == "intent" else "READY"):
            raise OSError("synthetic disk failure")
        return save(path, value)
    monkeypatch.setattr(agent_focus, "_save", interrupted)
    with pytest.raises(SegmentationError, match="could not be persisted"):
        run(tmp_path, real_source, [])
    assert attempts == (["PREPARING"] if failure == "intent" else ["PREPARING", "READY"])


@pytest.mark.parametrize("change", ["missing", "changed_frame", "preparing", "changed_focus"])
def test_focus_observation_tamper_cannot_regenerate_or_publish(tmp_path, real_source, monkeypatch, change):
    run(tmp_path, real_source, [answer(visual=["f0001"])])
    path = tmp_path / "agent-visual/focus-observation.json"
    receipt = json.loads(path.read_text("utf-8"))
    if change == "missing":
        path.unlink()
    else:
        if change == "changed_frame":
            receipt["frame"]["source_pts"] += 1
        elif change == "preparing":
            receipt.update(status="PREPARING", frame=None)
        else:
            receipt["focus_timestamp_ms"] = 1
        path.write_text(json.dumps(receipt), encoding="utf-8")
    monkeypatch.setattr("livecut.agent_frames._extract_frame", lambda *args, **kwargs: pytest.fail("No resampling"))
    with pytest.raises(SegmentationError):
        run(tmp_path, real_source, [])


def test_focused_turn_counts_initial_tool_and_has_only_four_decisions(tmp_path, real_source):
    replies = [tool("read_context", utterance_id="u1", before=0, after=0),
        tool("list_topics", offset=0, limit=1), tool("inspect_frames", timestamps_ms=[500]), answer(ids=["u1"], visual=["f0001"])]
    result, seen = run(tmp_path, real_source, replies)
    assert len(result["tool_calls"]) == len(seen) == 4
    assert [step["origin"] for step in result["tool_calls"]] == ["USER_FOCUS", "MODEL", "MODEL", "MODEL"]
    payloads = [json.loads(body["messages"][1]["content"][0]["text"]) for body in seen]
    assert [body["remaining_tool_steps"] for body in payloads] == [3, 2, 1, 0]
    path = tmp_path / "agent-visual/request-plans.json"
    plans = json.loads(path.read_text("utf-8"))
    assert [p["window_index"] for p in plans["requests"]] == [0, 1, 2, 3]
    plans["requests"][-1]["window_index"] = 4
    path.write_text(json.dumps(plans), encoding="utf-8")
    with pytest.raises(SegmentationError, match="budget plans changed"):
        run(tmp_path, real_source, [])


def test_duplicate_focus_tool_does_not_create_another_observation(tmp_path, real_source):
    result, seen = run(tmp_path, real_source, [tool("inspect_frames", timestamps_ms=[0]), answer(visual=["f0001"])])
    assert result["tool_calls"][1]["origin"] == "MODEL" and result["tool_calls"][1]["result_frame_ids"] == []
    assert len(result["visual_evidence"]) == len(image_parts(seen[-1])) == 1


@pytest.mark.parametrize("mutate", [lambda value: value.update(focus_timestamp_ms=1),
    lambda value: value["tool_calls"][0].update(origin="MODEL"),
    lambda value: value["tool_calls"][1].update(origin="USER_FOCUS"),
    lambda value: value["tool_calls"][0].update(arguments={"timestamps_ms": [1]}),
    lambda value: value["tool_calls"][0].update(result_frame_ids=[]),
    lambda value: value["visual_evidence"].clear()])
def test_focus_and_origin_cannot_be_forged(tmp_path, real_source, mutate):
    result, _ = run(tmp_path, real_source, [tool("read_context", utterance_id="u1", before=0, after=0), answer(visual=["f0001"])])
    mutate(result)
    with pytest.raises(SegmentationError):
        validate_visual_answer(result, source_items(), duration_ms=20000, expected={"focus_timestamp_ms": 0})


def test_new_worker_requires_dedicated_stage_and_rejects_ignored_focus(tmp_path, real_source, monkeypatch):
    ctx = visual_context(tmp_path, real_source)
    ctx.lease.config["agent_turn"]["focus_timestamp_ms"] = 0
    seen = []
    executor = mock_executor(monkeypatch, [answer(visual=["f0001"])], seen)
    with pytest.raises(WorkerError, match="INVALID_AGENT_FOCUS_INPUT"):
        executor(ctx)
    ctx.lease = replace(ctx.lease, stage_type="AGENT_VISUAL_FOCUS")
    ctx.guard = LeaseGuard(ctx.lease, threading.Event())
    manifest = executor(ctx)
    assert manifest["stage_type"] == "AGENT_VISUAL_FOCUS" and len(seen) == 1
    assert {item["kind"] for item in manifest["artifacts"]} == {"agent_answer", "agent_visual_frame"}


@pytest.mark.parametrize("stage", ["AGENT_VISUAL", "SEGMENT", "CAPTION_PROOFREAD"])
def test_nonfocus_stages_reject_present_null_focus_without_ignoring_it(tmp_path, monkeypatch, stage):
    from test_caption_proofread_worker import proofread_context
    from test_video_agent_worker import agent_context
    ctx = proofread_context(tmp_path) if stage == "CAPTION_PROOFREAD" else agent_context(tmp_path)
    ctx.lease = replace(ctx.lease, stage_type=stage)
    ctx.lease.config["agent_turn"]["focus_timestamp_ms"] = None
    if stage == "AGENT_VISUAL":
        ctx.lease.config["agent_turn"]["visual_mode"] = "frames"
    with pytest.raises(WorkerError, match="INVALID_AGENT_FOCUS_INPUT"):
        mock_executor(monkeypatch, [], [])(ctx)


def focus_ledger_client(tmp_path, source, provider, *, limit=1, policy=None, ledger=None):
    lease = Lease.parse(claim(stage_type="AGENT_VISUAL_FOCUS", config={"max_text_calls": limit,
        "text_fallback_models": [], "agent_turn": {"turn_id": "turn-1", "session_id": "session-1", "visual_mode": "frames", "focus_timestamp_ms": 0}}))
    context = StageContext(lease, tmp_path, tmp_path / "attempt", LeaseGuard(lease, threading.Event()))
    ledger = ledger or Ledger(limit=limit)
    ledger.agent_turn = lambda _: {"id": "turn-1", "session_id": "session-1", "job_id": lease.job_id, "visual_mode": "frames",
        "focus_timestamp_ms": 0, "status": "RUNNING", "stage": {"id": lease.stage_id, "stage_type": lease.stage_type,
            "status": "RUNNING", "attempt": lease.attempt}, "budget": {"max_text_calls": limit, "text_calls_reserved": len(ledger.calls)}}
    transport = ResilientTextTransport(BudgetTransport(ledger, context, inner=httpx.MockTransport(provider)), context,
        policy=policy or TextRetryPolicy(backoff_seconds=0))
    client = httpx.Client(transport=transport)
    client.livecut_resumable_text = True
    options = dict(session_id="session-1", turn_id="turn-1", job_id="job-1", video_id="video-1", source=source,
        source_hash=hashlib.sha256(source.read_bytes()).hexdigest(), transcript_hash="a"*64, model="synthetic-vlm",
        api_key="synthetic-independent-visual-key", base_url="https://visual.invalid/v1", max_calls=limit,
        focus_timestamp_ms=0, client=client, budget_remaining=transport.visual_text_budget, max_request_attempts=transport.policy.max_attempts)
    return client, ledger, context, options


def test_focus_ledger_replays_paid_success_at_zero_budget_without_new_reservation(tmp_path, real_source):
    hits = []
    def provider(request):
        hits.append(1)
        return httpx.Response(500 if len(hits) == 1 else 200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(answer(visual=["f0001"]))}}]})
    client, ledger, ctx, options = focus_ledger_client(tmp_path, real_source, provider, limit=2)
    with client:
        original = build_visual_answer(source_items(), topics(), 20000, "这张图是什么", ctx.directory, **options)
        state_path = ctx.directory / "agent-visual/state.json"
        state = json.loads(state_path.read_text("utf-8"))
        state["calls"][0]["status"] = "uncertain"
        state["calls"][0].pop("response_sha256")
        state_path.write_text(json.dumps(state), encoding="utf-8")
        (ctx.directory / "agent-visual" / state["calls"][0]["response_file"]).unlink()
        before = copy.deepcopy(ledger.calls)
        recovered = build_visual_answer(source_items(), topics(), 20000, "这张图是什么", ctx.directory, **options)
    assert recovered == original and len(hits) == 2 and ledger.calls == before
    assert [item["status"] for item in ledger.calls.values()] == ["UNKNOWN_OUTCOME", "SUCCEEDED"]


def test_focus_unknown_original_cannot_consume_another_slot_after_budget_zero(tmp_path, real_source):
    hits = []
    def unknown(request):
        hits.append(1)
        raise httpx.ReadTimeout("synthetic unknown", request=request)
    client, ledger, ctx, options = focus_ledger_client(tmp_path, real_source, unknown,
        policy=TextRetryPolicy(max_attempts=1, backoff_seconds=0))
    with client:
        with pytest.raises(TextRecoveryError):
            build_visual_answer(source_items(), topics(), 20000, "这张图是什么", ctx.directory, **options)
        with pytest.raises(TextRecoveryError, match="MODEL_BUDGET_EXHAUSTED"):
            build_visual_answer(source_items(), topics(), 20000, "这张图是什么", ctx.directory, **options)
    assert len(hits) == len(ledger.calls) == 1 and next(iter(ledger.calls.values()))["status"] == "UNKNOWN_OUTCOME"


def test_unconfigured_worker_advertises_neither_visual_stage(tmp_path, monkeypatch):
    monkeypatch.delenv("LIVECUT_AGENT_VISUAL_API_KEY", raising=False)
    monkeypatch.delenv("LIVECUT_AGENT_VISUAL_BASE_URL", raising=False)
    client = FakeClient()
    worker = StageWorker(client, tmp_path, tmp_path / "journal", "synthetic-worker", lambda _: None, registry_enabled=False)
    assert len(worker._available_stages(None)) == 7
    with pytest.raises(WorkerError, match="VISUAL_NOT_CONFIGURED"):
        worker.run_once(stage_type="AGENT_VISUAL_FOCUS")
    assert client.claims == []


def test_omitted_focus_retains_pre_focus_system_wire_payload_and_configuration_fingerprint(tmp_path, real_source):
    # Golden hashes captured from the committed pre-focus implementation de2b504.
    assert hashlib.sha256(SYSTEM.encode()).hexdigest() == "b98ccb70991a4afec2fbd3d8b3ab2461c17eeffbdd389ceaa74b90fa25d16997"
    result, seen = visual_run(tmp_path, real_source, [answer(scope="GENERAL")])
    wire = httpx.Request("POST", "https://visual.invalid/v1/chat/completions", json=seen[0]).content
    assert hashlib.sha256(wire).hexdigest() == "6779c4159310f7403848cc3a4453f6577bd1797ecc06c8909afa30404575446b"
    baseline_config = {"prompt": "video-agent-visual-v1", "source_hash": hashlib.sha256(real_source.read_bytes()).hexdigest(),
        "transcript_hash": "a"*64, "duration_ms": 20000, "question": "屏幕上的图是什么？", "model": "synthetic-vlm",
        "endpoint": "https://visual.invalid/v1/chat/completions", "session": "session-1", "turn": "turn-1", "job": "job-1",
        "video": "video-1", "utterances": source_items(), "topics": topics(), "history": [], "max_steps": 4,
        "embedding_model": None, "dense": False, "max_request_attempts": 1, "max_calls": 8, "central_budget": False}
    fingerprint = hashlib.sha256(json.dumps(baseline_config, ensure_ascii=False, sort_keys=True, allow_nan=False).encode()).hexdigest()
    state = json.loads((tmp_path / "agent-visual/state.json").read_text("utf-8"))
    assert state["config_hash"] == fingerprint and result["schema_version"] == "1.1"
    assert "focus_timestamp_ms" not in result and not (tmp_path / "agent-visual/focus-observation.json").exists()


@pytest.mark.parametrize("focus", [None, False, 0.0, -1, 20000, "MISSING"])
def test_focus_stage_requires_valid_explicit_position_before_transport(tmp_path, real_source, monkeypatch, focus):
    ctx = visual_context(tmp_path, real_source)
    ctx.lease = replace(ctx.lease, stage_type="AGENT_VISUAL_FOCUS")
    if focus != "MISSING":
        ctx.lease.config["agent_turn"]["focus_timestamp_ms"] = focus
    with pytest.raises(WorkerError, match="INVALID_AGENT_FOCUS_INPUT"):
        mock_executor(monkeypatch, [], [])(ctx)


@pytest.mark.parametrize("mutate", [lambda value: value.pop("focus_timestamp_ms"),
    lambda value: value.update(focus_timestamp_ms=None), lambda value: value.update(focus_timestamp_ms=False),
    lambda value: value.update(focus_timestamp_ms=1)])
def test_focus_budget_snapshot_must_match_exact_original_focus(tmp_path, mutate):
    transport, snapshot, _, events = visual_budget_transport(tmp_path)
    config = copy.deepcopy(transport.context.lease.config)
    config["agent_turn"]["focus_timestamp_ms"] = 0
    transport.context.lease = replace(transport.context.lease, stage_type="AGENT_VISUAL_FOCUS", config=config)
    snapshot.update(focus_timestamp_ms=0)
    snapshot["stage"]["stage_type"] = "AGENT_VISUAL_FOCUS"
    assert transport.visual_text_budget() == 3
    mutate(snapshot)
    assert transport.visual_text_budget() is None and events == []


def test_focus_transport_unknown_budget_cannot_reserve_or_dispatch_even_with_existing_policy(tmp_path, real_source):
    client, ledger, _, _ = focus_ledger_client(tmp_path, real_source, lambda _: pytest.fail("No paid dispatch"))
    def unavailable(_):
        raise WorkerClientError("JAVA_UNAVAILABLE", retryable=True)
    ledger.agent_turn = unavailable
    with client, pytest.raises(TextRecoveryError, match="FOCUS_MODEL_BUDGET_UNAVAILABLE"):
        client.post("https://visual.invalid/v1/chat/completions", json={"model": "synthetic-vlm", "messages": []})
    assert ledger.calls == {} and ledger.events == []
