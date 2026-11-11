"""Typed evidence engineering checks; model replies are explicitly synthetic."""
import copy
import hashlib
import json
import threading
from dataclasses import replace
from pathlib import Path

import httpx
import pytest
from livecut.agent_evidence import assemble, validate_evidence_answer
from livecut.agent_visual import SYSTEM_FOCUS, build_visual_answer, validate_visual_answer
from livecut.model_budget import ModelBudgetError
from livecut.segmentation import SegmentationError
from livecut.text_resilience import TextRecoveryError, TextRetryPolicy
from livecut.video_agent import AgentOutputError
from livecut.worker import LeaseGuard, StageWorker, WorkerError, _native_path
from test_agent_focus import focus_ledger_client
from test_agent_visual import answer as old_answer
from test_agent_visual import real_source as _real_source
from test_agent_visual import source_items, tool, topics
from test_agent_visual_worker import mock_executor, visual_budget_transport, visual_context
from test_model_budget import setup_transport
from test_worker import FakeClient

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures"
real_source = _real_source


def block(kind="FRAME", *, speech=(), images=(), text="合成分项，仅验证协议。", identity="b01"):
    return {"block_id": identity, "kind": kind, "text": text,
            "utterance_ids": list(speech), "frame_ids": list(images)}


def reply(*blocks, scope="VIDEO"):
    return {"type": "answer", "answer_scope": scope, "answer_blocks": list(blocks), "suggested_clip_instruction": None}


def fixture(focus=False):
    return json.loads((FIXTURES / ("agent-evidence-focus.synthetic.json" if focus else "agent-evidence.synthetic.json")).read_text("utf-8"))


@pytest.mark.parametrize("focus", [False, True])
def test_shared_wire_fixture_exact_render_and_unions(focus):
    value = fixture(focus)
    assert validate_evidence_answer(value["result"], value["utterances"], duration_ms=value["duration_ms"]) == value["result"]
    assert validate_visual_answer(value["result"], value["utterances"], duration_ms=value["duration_ms"]) == value["result"]


@pytest.mark.parametrize("mutate", [
    lambda r: r.update(answer=r["answer"] + "绕过分项的自由结论"),
    lambda r: r["answer_blocks"][0].update(extra="not permitted"),
    lambda r: r["answer_blocks"][0].update(block_id="b02"),
    lambda r: r["answer_blocks"][0].update(kind="UNKNOWN"),
    lambda r: r["answer_blocks"][0].update(frame_ids=["f0001"]),
    lambda r: r["answer_blocks"][0].update(utterance_ids=[]),
    lambda r: r["answer_blocks"][0].update(utterance_ids=["not-observed"]),
    lambda r: r["answer_blocks"][1].update(frame_ids=[]),
    lambda r: r["answer_blocks"][2].update(frame_ids=["f0001"]),
    lambda r: r["answer_blocks"][2].update(frame_ids=["f0002", "f0001"]),
    lambda r: r["answer_blocks"][2].update(frame_ids=["f0001", "f0001"]),
    lambda r: r["visual_citations"][0].update(explanation="模型自选理由"),
    lambda r: r.update(citations=[]),
    lambda r: r.update(visual_citations=r["visual_citations"][:1]),
    lambda r: r.update(answer_scope="GENERAL"),
    lambda r: r["provenance"].update(prompt_version="video-agent-visual-v1"),
    lambda r: r.update(evidence_mode="none"),
])
def test_forged_or_incomplete_structured_results_are_rejected(mutate):
    value = fixture()
    mutate(value["result"])
    with pytest.raises(SegmentationError) as raised:
        validate_evidence_answer(value["result"], value["utterances"], duration_ms=value["duration_ms"])
    assert raised.value.code == "INVALID_AGENT_EVIDENCE_ANSWER"


def test_change_cannot_combine_arbitrary_frames_from_separate_tools():
    value = fixture()
    result = value["result"]
    result["tool_calls"][-1] = {"step": 2, "tool_name": "inspect_frames", "arguments": {"timestamps_ms": [1000, 1500]},
                                "result_utterance_ids": [], "result_frame_ids": ["f0001", "f0002"]}
    with pytest.raises(AgentOutputError, match="SAME_CLIP_PAIR"):
        assemble("VIDEO", [block("SAMPLED_CHANGE", images=["f0001", "f0002"])],
                 value["utterances"], {"u1"}, result["visual_evidence"], result["tool_calls"])
    result["tool_calls"] = [
        {"tool_name": "inspect_clip", "result_frame_ids": ["f0001"]},
        {"tool_name": "inspect_clip", "result_frame_ids": ["f0002"]},
    ]
    with pytest.raises(AgentOutputError, match="SAME_CLIP_PAIR"):
        assemble("VIDEO", [block("SAMPLED_CHANGE", images=["f0001", "f0002"])],
                 value["utterances"], {"u1"}, result["visual_evidence"], result["tool_calls"])


def test_change_order_uses_exact_pts_rather_than_frame_id_or_display_rounding():
    frames = [{"frame_id": "f0001", "source_pts": 20001, "source_time_base": "1/10000"},
              {"frame_id": "f0002", "source_pts": 20000, "source_time_base": "1/10000"}]
    trace = [{"tool_name": "inspect_clip", "result_frame_ids": ["f0002", "f0001"]}]
    valid = block("SAMPLED_CHANGE", images=["f0002", "f0001"])
    assert assemble("VIDEO", [valid], [], set(), frames, trace)["answer"].endswith("采样间隙或因果。）")
    frames[1]["source_pts"] = frames[0]["source_pts"]
    with pytest.raises(AgentOutputError, match="SAME_CLIP_PAIR"):
        assemble("VIDEO", [valid], [], set(), frames, trace)


@pytest.mark.parametrize("text", ["", " \t\r\n", "\ufeff\ufeff", "\u00a0\u00a0", "x\x00", "x\x7f", "x\x85", "\ud800", "😀" * 501])
def test_block_text_rejects_controls_surrogates_blank_and_utf16_overflow(text):
    with pytest.raises(AgentOutputError):
        assemble("GENERAL", [block("GENERAL", text=text)], [], set(), [], [])


def test_block_text_keeps_whitespace_and_counts_utf16_without_normalization():
    text = " \t原文\r\n😀 "
    assert assemble("GENERAL", [block("GENERAL", text=text)], [], set(), [], [])["answer"] == "【通用解释 b01】\n" + text
    bom_text = "\ufeff \u00a0正文\u00a0 \ufeff"
    assert assemble("GENERAL", [block("GENERAL", text=bom_text)], [], set(), [], [])["answer"] == "【通用解释 b01】\n" + bom_text
    assert "😀" * 500 in assemble("GENERAL", [block("GENERAL", text="😀" * 500)], [], set(), [], [])["answer"]
    blocks = [block("GENERAL", text="x" * 1000, identity=f"b{i:02d}") for i in range(1, 7)]
    with pytest.raises(AgentOutputError, match="TOTAL_LIMIT"):
        assemble("GENERAL", blocks, [], set(), [], [])


@pytest.mark.parametrize("visual", [False, True])
def test_top_level_citation_union_caps_reject_without_truncating(visual):
    if visual:
        frames = [{"frame_id": f"f{i:04d}"} for i in range(1, 14)]
        blocks = [block(images=[f["frame_id"] for f in frames[i:i+4]], identity=f"b{i//4+1:02d}")
                  for i in range(0, 13, 4)]
        source = []
    else:
        frames = []
        source = [{"utterance_id": f"u{i:02d}", "text": "原句", "start_ms": i, "end_ms": i+1} for i in range(25)]
        blocks = [block("SPEECH", speech=[u["utterance_id"] for u in source[i:i+8]], identity=f"b{i//8+1:02d}")
                  for i in range(0, 25, 8)]
    with pytest.raises(AgentOutputError, match="TOTAL_LIMIT"):
        assemble("VIDEO", blocks, source, {u["utterance_id"] for u in source}, frames, [])


def test_speech_ids_use_source_order_and_must_have_been_observed_this_turn():
    source = [{"utterance_id": uid, "text": uid, "start_ms": i, "end_ms": i+1}
              for i, uid in enumerate(("z9", "a1"))]
    selected = block("SPEECH", speech=["z9", "a1"])
    citations = assemble("VIDEO", [selected], source, {"z9", "a1"}, [], [])["citations"]
    assert [c["utterance_id"] for c in citations] == ["z9", "a1"]
    with pytest.raises(AgentOutputError, match="UNSEEN_OR_UNORDERED"):
        assemble("VIDEO", [selected], source, {"z9"}, [], [])


@pytest.mark.parametrize("kind,speech,images", [("INFERENCE", [], []), ("GENERAL", ["u1"], []),
                                                ("INSUFFICIENT", [], ["f0001"])])
def test_modality_and_inference_do_not_create_video_fact_authority(kind, speech, images):
    value = fixture()
    with pytest.raises(AgentOutputError):
        assemble("VIDEO", [block(kind, speech=speech, images=images)], value["utterances"], {"u1"}, value["result"]["visual_evidence"], [])
    with pytest.raises(AgentOutputError, match="VIDEO_REQUIRES_OBSERVATION"):
        assemble("VIDEO", [block("INFERENCE", speech=["u1"])], value["utterances"], {"u1"}, [], [])


def run(root, source, replies, *, limit=8, focus=None, budget=None, **extra):
    seen = []
    def provider(request):
        seen.append(json.loads(request.content))
        assert len(seen) <= len(replies), "Unexpected extra provider call"
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(replies[len(seen)-1], ensure_ascii=False)}}]})
    options = dict(session_id="session-1", turn_id="turn-1", job_id="job-1", video_id="video-1",
                   source_hash=hashlib.sha256(source.read_bytes()).hexdigest(), transcript_hash="a"*64,
                   model="synthetic-vlm", api_key="synthetic-independent-visual-key", base_url="https://visual.invalid/v1",
                   max_calls=limit, evidence_mode="structured", budget_remaining=budget or (lambda: limit-len(seen)))
    if focus is not None:
        options["focus_timestamp_ms"] = focus
    options.update(extra)
    with httpx.Client(transport=httpx.MockTransport(provider)) as client:
        result = build_visual_answer(source_items(), topics(), 20000, "修改前后发生什么变化？", root, source=source, client=client, **options)
    return result, seen


def test_real_missing_side_repairs_via_bounded_clip_tool_then_publishes_change(tmp_path, real_source):
    result, seen = run(tmp_path, real_source, [
        tool("inspect_frames", timestamps_ms=[0]),
        reply(block("SAMPLED_CHANGE", images=["f0001"])),
        tool("inspect_clip", start_ms=0, end_ms=1000),
        reply(block("SAMPLED_CHANGE", images=["f0001", "f0002"])),
    ], limit=4)
    assert len(seen) == 4 and result["schema_version"] == "1.3"
    assert len(result["tool_calls"]) == len(result["visual_evidence"]) == 2
    assert result["tool_calls"][-1]["result_frame_ids"] == ["f0001", "f0002"]
    assert "合法只读工具补证" in seen[2]["messages"][-1]["content"]
    assert result["answer"].endswith("（仅比较已取样画面，不证明采样间隙或因果。）")
    validate_evidence_answer(result, source_items(), duration_ms=20000)
    replayed, replay_calls = run(tmp_path, real_source, [], limit=4, budget=lambda: 0)
    assert replayed == result and replay_calls == []


def test_final_reserved_request_reports_gap_instead_of_extra_tool(tmp_path, real_source):
    result, seen = run(tmp_path, real_source, [tool("inspect_frames", timestamps_ms=[0]),
        reply(block("SAMPLED_CHANGE", images=["f0001"])),
        reply(block("INSUFFICIENT", text="尚无后态画面，预算内无法补齐。"), scope="INSUFFICIENT")], limit=3)
    assert len(seen) == 3 and len(result["tool_calls"]) == 1 and result["answer_scope"] == "INSUFFICIENT"
    assert result["visual_citations"] == [] and len(result["visual_evidence"]) == 1
    assert json.loads(seen[-1]["messages"][1]["content"][0]["text"])["remaining_tool_steps"] == 0


@pytest.mark.parametrize("budget", [lambda: None, lambda: 0])
def test_new_structured_request_without_verified_budget_never_dispatches(tmp_path, real_source, budget):
    with pytest.raises(SegmentationError) as raised:
        run(tmp_path, real_source, [], budget=budget)
    assert raised.value.code in {"BUDGET_EXHAUSTED", "EVIDENCE_MODEL_BUDGET_UNAVAILABLE"}


def test_focused_structured_one_call_uses_real_user_frame_without_hidden_audit(tmp_path, real_source):
    result, seen = run(tmp_path, real_source, [reply(block(images=["f0001"]))], focus=0, limit=1)
    assert len(seen) == 1 and result["focus_timestamp_ms"] == 0
    assert result["tool_calls"][0]["origin"] == "USER_FOCUS"
    assert result["provenance"]["prompt_version"] == "video-agent-evidence-v1"


def test_structured_cached_image_tamper_stops_without_regeneration_or_model(tmp_path, real_source, monkeypatch):
    run(tmp_path, real_source, [reply(block(images=["f0001"]))], focus=0, limit=1)
    (tmp_path / "visual-frames/f0001.jpg").write_bytes(b"modified evidence")
    monkeypatch.setattr("livecut.agent_frames._extract_frame", lambda *a, **k: pytest.fail("Cached evidence must not be regenerated"))
    with pytest.raises(SegmentationError) as raised:
        run(tmp_path, real_source, [], focus=0, limit=1, budget=lambda: 0)
    assert raised.value.code == "INVALID_VISUAL_CACHE"


def test_structured_checkpoint_identity_tamper_never_dispatches(tmp_path, real_source):
    run(tmp_path, real_source, [reply(block("GENERAL"), scope="GENERAL")], limit=1)
    path = tmp_path / "agent-evidence/state.json"
    state = json.loads(path.read_text("utf-8"))
    state["config_hash"] = "f"*64
    path.write_text(json.dumps(state), encoding="utf-8")
    # The config hash includes mode, prompt and system identity. Any changed
    # identity invalidates the original immutable checkpoint, not a new call.
    with pytest.raises(SegmentationError) as raised:
        run(tmp_path, real_source, [], limit=1, budget=lambda: 0)
    assert raised.value.code == "INVALID_AGENT_CHECKPOINT"


def evidence_ledger_client(tmp_path, source, provider, *, limit=1, policy=None, focus=True):
    client, ledger, ctx, options = focus_ledger_client(tmp_path, source, provider, limit=limit, policy=policy)
    config = copy.deepcopy(ctx.lease.config)
    config["agent_turn"]["evidence_mode"] = "structured"
    if not focus:
        config["agent_turn"].pop("focus_timestamp_ms")
        options.pop("focus_timestamp_ms")
    ctx.lease = replace(ctx.lease, stage_type="AGENT_EVIDENCE", config=config)
    ledger.agent_turn = lambda _: {"id": "turn-1", "session_id": "session-1", "job_id": ctx.lease.job_id,
        "visual_mode": "frames", "evidence_mode": "structured", **({"focus_timestamp_ms": 0} if focus else {}), "status": "RUNNING",
        "stage": {"id": ctx.lease.stage_id, "stage_type": "AGENT_EVIDENCE", "status": "RUNNING", "attempt": ctx.lease.attempt},
        "budget": {"max_text_calls": limit, "text_calls_reserved": len(ledger.calls)}}
    options["evidence_mode"] = "structured"
    return client, ledger, ctx, options


@pytest.mark.parametrize("focus", [False, True])
def test_paid_structured_success_replays_at_zero_budget_without_new_slot(tmp_path, real_source, monkeypatch, focus):
    monkeypatch.setenv("LIVECUT_TEXT_FALLBACKS_JSON", "invalid text-only fallback configuration must not be loaded")
    hits = []
    def provider(request):
        hits.append(1)
        response = reply(block(images=["f0001"])) if focus else reply(block("INSUFFICIENT"), scope="INSUFFICIENT")
        return httpx.Response(500 if len(hits) == 1 else 200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(response)}}]})
    client, ledger, ctx, options = evidence_ledger_client(tmp_path, real_source, provider, limit=2, focus=focus)
    with client:
        original = build_visual_answer(source_items(), topics(), 20000, "观察这张图", ctx.directory, **options)
        state_path = ctx.directory / "agent-evidence/state.json"
        state = json.loads(state_path.read_text("utf-8"))
        state["calls"][0]["status"] = "uncertain"
        state["calls"][0].pop("response_sha256")
        state_path.write_text(json.dumps(state), encoding="utf-8")
        (state_path.parent / state["calls"][0]["response_file"]).unlink()
        before = copy.deepcopy(ledger.calls)
        recovered = build_visual_answer(source_items(), topics(), 20000, "观察这张图", ctx.directory, **options)
    assert recovered == original and ledger.calls == before and len(hits) == 2
    assert [c["status"] for c in ledger.calls.values()] == ["UNKNOWN_OUTCOME", "SUCCEEDED"]


@pytest.mark.parametrize("endpoint", ["/services/audio/asr/transcription", "/services/aigc/text-generation/generation", "/arbitrary"])
def test_evidence_transport_only_dispatches_budgeted_visual_chat(tmp_path, endpoint):
    client, ledger, events, _ = setup_transport(tmp_path, lambda _: pytest.fail("Forbidden provider operation"), stage_type="AGENT_EVIDENCE")
    with client:
        with pytest.raises(ModelBudgetError, match="MODEL_OPERATION_NOT_ALLOWED"):
            client.post("https://visual.invalid" + endpoint, json={"model": "synthetic-vlm"})
    assert ledger.reservations == [] and "provider" not in events


@pytest.mark.parametrize("focus", [False, True])
def test_unknown_structured_call_cannot_take_another_slot_at_zero_budget(tmp_path, real_source, focus):
    hits = []
    def provider(request):
        hits.append(1)
        raise httpx.ReadTimeout("synthetic unknown outcome", request=request)
    client, ledger, ctx, options = evidence_ledger_client(tmp_path, real_source, provider,
        policy=TextRetryPolicy(max_attempts=1, backoff_seconds=0), focus=focus)
    with client:
        with pytest.raises(TextRecoveryError):
            build_visual_answer(source_items(), topics(), 20000, "观察这张图", ctx.directory, **options)
        with pytest.raises(TextRecoveryError, match="MODEL_BUDGET_EXHAUSTED"):
            build_visual_answer(source_items(), topics(), 20000, "观察这张图", ctx.directory, **options)
    assert len(hits) == len(ledger.calls) == 1 and next(iter(ledger.calls.values()))["status"] == "UNKNOWN_OUTCOME"


@pytest.mark.parametrize("focus", [None, 0])
def test_worker_evidence_stage_writes_fenced_manifest_and_never_repeats_request(tmp_path, real_source, monkeypatch, focus):
    ctx = visual_context(tmp_path, real_source)
    ctx.lease = replace(ctx.lease, stage_type="AGENT_EVIDENCE")
    ctx.lease.config["agent_turn"]["evidence_mode"] = "structured"
    if focus is not None:
        ctx.lease.config["agent_turn"]["focus_timestamp_ms"] = focus
    ctx.guard = LeaseGuard(ctx.lease, threading.Event())
    responses = [reply(block(images=["f0001"]))]
    if focus is None:
        responses.insert(0, tool("inspect_frames", timestamps_ms=[0]))
    seen = []
    executor = mock_executor(monkeypatch, responses, seen)
    manifest = executor(ctx)
    assert manifest["stage_type"] == "AGENT_EVIDENCE" and manifest["run_token"] == ctx.lease.run_token
    assert executor(ctx) == manifest and len(seen) == len(responses)
    assert {a["kind"] for a in manifest["artifacts"]} == {"agent_answer", "agent_visual_frame"}
    result = json.loads(_native_path(ctx.directory / "agent-answer.json").read_text("utf-8"))
    assert ("focus_timestamp_ms" in result) == (focus is not None)


@pytest.mark.parametrize("stage", ["AGENT_VISUAL", "AGENT_VISUAL_FOCUS", "SEGMENT"])
@pytest.mark.parametrize("mode", [None, "none", "structured"])
def test_old_stages_refuse_evidence_mode_before_transport(tmp_path, real_source, monkeypatch, stage, mode):
    ctx = visual_context(tmp_path, real_source)
    ctx.lease = replace(ctx.lease, stage_type=stage)
    ctx.lease.config["agent_turn"]["evidence_mode"] = mode
    with pytest.raises(WorkerError, match="INVALID_AGENT_EVIDENCE_INPUT"):
        mock_executor(monkeypatch, [], [])(ctx)


def test_new_evidence_stage_is_never_advertised_without_visual_configuration(tmp_path, monkeypatch):
    monkeypatch.delenv("LIVECUT_AGENT_VISUAL_API_KEY", raising=False)
    monkeypatch.delenv("LIVECUT_AGENT_VISUAL_BASE_URL", raising=False)
    worker = StageWorker(FakeClient(), tmp_path, tmp_path / "journal", "synthetic-worker", lambda _: None, registry_enabled=False)
    assert "AGENT_EVIDENCE" not in worker._available_stages(None)
    with pytest.raises(WorkerError, match="VISUAL_NOT_CONFIGURED"):
        worker.run_once(stage_type="AGENT_EVIDENCE")


@pytest.mark.parametrize("mode", [None, "none", False, "unexpected", "MISSING"])
def test_evidence_stage_requires_explicit_structured_mode_before_transport(tmp_path, real_source, monkeypatch, mode):
    ctx = visual_context(tmp_path, real_source)
    ctx.lease = replace(ctx.lease, stage_type="AGENT_EVIDENCE")
    if mode != "MISSING":
        ctx.lease.config["agent_turn"]["evidence_mode"] = mode
    with pytest.raises(WorkerError, match="INVALID_AGENT_EVIDENCE_INPUT"):
        mock_executor(monkeypatch, [], [])(ctx)


@pytest.mark.parametrize("mutate", [lambda s: s.pop("evidence_mode"), lambda s: s.update(evidence_mode="none"),
                                   lambda s: s.update(focus_timestamp_ms=0)])
def test_evidence_budget_requires_exact_mode_and_optional_focus_identity(tmp_path, mutate):
    transport, snapshot, _, events = visual_budget_transport(tmp_path)
    config = copy.deepcopy(transport.context.lease.config)
    config["agent_turn"]["evidence_mode"] = "structured"
    transport.context.lease = replace(transport.context.lease, stage_type="AGENT_EVIDENCE", config=config)
    snapshot.update(evidence_mode="structured")
    snapshot["stage"]["stage_type"] = "AGENT_EVIDENCE"
    assert transport.visual_text_budget() == 3
    mutate(snapshot)
    assert transport.visual_text_budget() is None and events == []


def test_old_focused_wire_prompt_and_checkpoint_identity_are_unchanged(tmp_path, real_source):
    from test_agent_focus import run as old_run
    result, seen = old_run(tmp_path, real_source, [old_answer(visual=["f0001"])], focus_timestamp_ms=0)
    assert result["schema_version"] == "1.2" and "evidence_mode" not in result and "answer_blocks" not in result
    # Captured from the committed pre-evidence implementation 9be0eaea.
    assert hashlib.sha256(SYSTEM_FOCUS.encode()).hexdigest() == "2a7371975aad18e715bb97420ccc0ae1fa297cdce796734472022500184dda40"
    assert seen[0]["messages"][0]["content"] == SYSTEM_FOCUS + "\n本轮明确附加位置：0毫秒。"
    payload = json.loads(seen[0]["messages"][1]["content"][0]["text"])
    assert set(payload) == {"video_question", "conversation", "observations", "video_info", "remaining_tool_steps", "remaining_model_calls", "sampling_notice", "focus_timestamp_ms"}
    plans = json.loads((tmp_path / "agent-visual/request-plans.json").read_text("utf-8"))
    wire = json.dumps(seen[0]["messages"], ensure_ascii=False, sort_keys=True, allow_nan=False)
    assert plans["requests"][0]["payload_sha256"] == hashlib.sha256(wire.encode()).hexdigest()
    baseline = {"prompt": "video-agent-visual-focus-v1", "source_hash": hashlib.sha256(real_source.read_bytes()).hexdigest(),
        "transcript_hash": "a"*64, "duration_ms": 20000, "question": "屏幕上的图是什么？", "model": "synthetic-vlm",
        "endpoint": "https://visual.invalid/v1/chat/completions", "session": "session-1", "turn": "turn-1", "job": "job-1",
        "video": "video-1", "utterances": source_items(), "topics": topics(), "history": [], "max_steps": 4,
        "embedding_model": None, "dense": False, "max_request_attempts": 1, "max_calls": 8, "central_budget": True,
        "focus_timestamp_ms": 0, "system_sha256": hashlib.sha256((SYSTEM_FOCUS+"\n本轮明确附加位置：0毫秒。").encode()).hexdigest()}
    expected_hash = hashlib.sha256(json.dumps(baseline, ensure_ascii=False, sort_keys=True, allow_nan=False).encode()).hexdigest()
    assert plans["config_hash"] == expected_hash
    assert not (tmp_path / "agent-evidence").exists()
