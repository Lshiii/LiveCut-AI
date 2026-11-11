"""1.4 preflight uses synthetic model responses and real local FFmpeg only."""
import copy
import hashlib
import json
import threading
from dataclasses import replace
from pathlib import Path

import httpx
import pytest
from livecut.agent_speech_lookup import (
    POLICY,
    prepare_speech_lookup,
    prime_speech_lookup,
    question_query,
    validate_speech_answer,
)
from livecut.agent_visual import _decision, build_visual_answer
from livecut.segmentation import SegmentationError
from livecut.text_resilience import TextRecoveryError, TextRetryPolicy
from livecut.video_agent import AgentOutputError
from livecut.worker import LeaseGuard, WorkerError, _native_path
from test_agent_evidence import block, evidence_ledger_client, reply
from test_agent_visual import real_source as _real_source
from test_agent_visual import source_items, tool, topics
from test_agent_visual_worker import mock_executor, visual_context

real_source = _real_source
FIXTURE = Path(__file__).resolve().parents[2] / "fixtures/agent-speech-lookup.synthetic.json"
CASES = json.loads(FIXTURE.read_text("utf-8"))["cases"]


@pytest.mark.parametrize("sample", CASES, ids=lambda sample: sample["id"])
def test_shared_complete_1_4_policy_source_packets(sample):
    assert validate_speech_answer(sample["result"], sample["utterances"], duration_ms=sample["duration_ms"]) == sample["result"]


@pytest.mark.parametrize("question,query,truncated", [("\ufeff\u00a0缓存\u00a0 ", "缓存", False),
    ("😀"*150, "😀"*150, False), ("a"*299+"😀", "a"*299, True), ("a"*299+" 😀", "a"*299, True)])
def test_query_has_safe_utf16_prefix_and_exact_shared_js_trim(question, query, truncated):
    assert question_query(question) == (query, truncated)


@pytest.mark.parametrize("question", ["\ufeff\u00a0", "x\x00", "x\x85", "\ud800", "😀"*1001])
def test_new_question_rejects_invalid_text_without_changing_old_question_rules(question):
    with pytest.raises(SegmentationError) as raised:
        question_query(question)
    assert raised.value.code == "INVALID_AGENT_SPEECH_INPUT"


def prepared(tmp_path, utterances, question="缓存", focus=None):
    return prepare_speech_lookup(utterances, question, focus_timestamp_ms=focus,
        source_hash="a"*64, transcript_hash="b"*64, index_cache_dir=tmp_path / "indexes")


def test_anchor_priority_keeps_whole_sentence_and_does_not_keep_only_earlier_rows(tmp_path):
    items = [{"utterance_id": f"u{i}", "start_ms": i*1000, "end_ms": (i+1)*1000,
              "text": "x"*4000, "source_chunk_id": "c1"} for i in range(3)]
    result = prepared(tmp_path, items, focus=1500)
    assert result["receipt"]["candidate_utterance_count"] == 3
    assert result["receipt"]["status"] == "CONTEXT_LIMIT"
    assert result["receipt"]["returned_utterance_ids"] == ["u0", "u1"]
    assert [len(u["text"]) for u in result["observation"]["utterances"]] == [4000, 4000]


def run(root, source, responses, *, focus=None, question="合成原句一", limit=4, budget=None, **extra):
    seen = []
    def provider(request):
        seen.append(json.loads(request.content))
        assert len(seen) <= len(responses), "An unplanned extra model call was attempted"
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(responses[len(seen)-1], ensure_ascii=False)}}]})
    options = dict(session_id="session-1", turn_id="turn-1", job_id="job-1", video_id="video-1",
        source_hash=hashlib.sha256(source.read_bytes()).hexdigest(), transcript_hash="a"*64,
        model="synthetic-vlm", api_key="synthetic-independent-visual-key", base_url="https://visual.invalid/v1",
        max_calls=limit, evidence_mode="structured", evidence_policy=POLICY,
        budget_remaining=budget or (lambda: limit-len(seen)))
    if focus is not None:
        options["focus_timestamp_ms"] = focus
    options.update(extra)
    with httpx.Client(transport=httpx.MockTransport(provider)) as client:
        result = build_visual_answer(source_items(), topics(), 20000, question, root, source=source, client=client, **options)
    return result, seen


@pytest.mark.parametrize("focus", [None, 0])
def test_one_budgeted_model_call_receives_real_preflight_speech_and_optional_image(tmp_path, real_source, focus):
    result, seen = run(tmp_path, real_source, [reply(block("SPEECH", speech=["u1"]))], focus=focus, limit=1,
        dense_embedder=lambda _: pytest.fail("Policy must not call embeddings"), embedding_model_id="ignored-for-new-policy")
    assert len(seen) == 1 and result["schema_version"] == "1.4"
    assert result["evidence_policy"] == POLICY
    payload = json.loads(seen[0]["messages"][1]["content"][0]["text"])
    policy = payload["observations"][-1]
    assert policy["origin"] == "EVIDENCE_POLICY"
    assert [u["utterance_id"] for u in policy["result"]["utterances"]] == result["speech_lookup"]["returned_utterance_ids"]
    assert "u1" in result["speech_lookup"]["returned_utterance_ids"] and payload["remaining_tool_steps"] == 0
    assert len(result["tool_calls"]) == (2 if focus is not None else 1)
    assert result["tool_calls"][-1]["origin"] == "EVIDENCE_POLICY"
    recovered, calls = run(tmp_path, real_source, [], focus=focus, limit=1, budget=lambda: 0)
    assert recovered == result and calls == []


def test_preflight_returns_speech_without_forcing_an_irrelevant_citation(tmp_path, real_source):
    result, calls = run(tmp_path, real_source, [reply(block("INSUFFICIENT", text="预查原句不支持这个问题。"), scope="INSUFFICIENT")], focus=0, limit=1)
    assert len(calls) == 1 and result["speech_lookup"]["status"] == "RETURNED"
    assert result["citations"] == [] and result["answer_scope"] == "INSUFFICIENT"


@pytest.mark.parametrize("focus", [None, 0])
def test_policy_and_user_focus_consume_the_same_original_four_tool_steps(tmp_path, real_source, focus):
    responses = [tool("inspect_frames", timestamps_ms=[0])] if focus is None else []
    responses += [tool("inspect_clip", start_ms=0, end_ms=1000), tool("read_context", utterance_id="u1", before=0, after=0),
                  reply(block("SAMPLED_CHANGE", images=["f0001", "f0002"]))]
    result, seen = run(tmp_path, real_source, responses, focus=focus, limit=8)
    assert len(result["tool_calls"]) == 4 and len(seen) == len(responses)
    assert [s["origin"] for s in result["tool_calls"]] == (["USER_FOCUS"] if focus is not None else []) + ["EVIDENCE_POLICY"] + ["MODEL"]*(2 if focus is not None else 3)
    assert json.loads(seen[-1]["messages"][1]["content"][0]["text"])["remaining_tool_steps"] == 0


def test_only_new_profile_restricts_model_search_query_utf16_length():
    decision = tool("search_transcript", query="😀"*200, top_k=4)
    raw = {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(decision)}}]}
    assert _decision(raw, {}, set(), [], 1, 20000, evidence_mode="structured")["arguments"]["query"] == "😀"*200
    with pytest.raises(AgentOutputError, match="SPEECH_QUERY_UTF16_LIMIT"):
        _decision(raw, {}, set(), [], 1, 20000, evidence_mode="structured", speech_policy=True)


def test_final_1_4_validator_rejects_forged_model_search_that_old_codepoint_projection_would_allow():
    sample = copy.deepcopy(next(c for c in CASES if c["id"] == "policy_and_model_sampled_change_have_distinct_origins"))
    result = sample["result"]
    result["tool_calls"].append({"step": 4, "tool_name": "search_transcript", "origin": "MODEL",
        "arguments": {"query": "😀"*200, "top_k": 4}, "result_utterance_ids": [], "result_frame_ids": []})
    result["provenance"]["retrieval_mode"] = "bm25"
    with pytest.raises(SegmentationError) as raised:
        validate_speech_answer(result, sample["utterances"], duration_ms=sample["duration_ms"])
    assert raised.value.code == "INVALID_AGENT_SPEECH_ANSWER"


@pytest.mark.parametrize("budget", [lambda: 0, lambda: None])
def test_preflight_never_reads_or_dispatches_without_a_verified_initial_model_budget(tmp_path, real_source, monkeypatch, budget):
    monkeypatch.setattr("livecut.agent_speech_lookup.prepare_speech_lookup", lambda *a, **k: pytest.fail("No budget: no new policy observation"))
    with pytest.raises(SegmentationError):
        run(tmp_path, real_source, [], budget=budget)
    assert not (tmp_path / "agent-evidence-speech/speech-observation.json").exists()


def test_ready_checkpoint_tamper_missing_or_incomplete_is_not_regenerated(tmp_path, real_source, monkeypatch):
    run(tmp_path, real_source, [reply(block("SPEECH", speech=["u1"]))], focus=0, limit=1)
    path = tmp_path / "agent-evidence-speech/speech-observation.json"
    original = path.read_bytes()
    for change in ("tamper", "missing", "preparing"):
        path.write_bytes(original)
        if change == "missing":
            path.unlink()
        else:
            document = json.loads(original)
            document["status"] = "PREPARING" if change == "preparing" else "READY"
            document["prepared_sha256"] = "f"*64
            path.write_text(json.dumps(document), encoding="utf-8")
        with pytest.raises(SegmentationError) as raised:
            run(tmp_path, real_source, [], focus=0, limit=1, budget=lambda: 0)
        assert raised.value.code == "INVALID_AGENT_SPEECH_CHECKPOINT"


def test_policy_checkpoint_publication_failure_stops_before_provider(tmp_path, monkeypatch):
    from livecut import agent_speech_lookup as module
    save = module._save
    def fail_ready(path, value):
        if value.get("status") == "READY":
            raise OSError("synthetic publication failure")
        save(path, value)
    monkeypatch.setattr(module, "_save", fail_ready)
    with pytest.raises(SegmentationError) as raised:
        prime_speech_lookup(tmp_path, "a"*64, source_items(), "合成原句", focus_timestamp_ms=0,
            source_hash="a"*64, transcript_hash="b"*64, index_cache_dir=tmp_path / "indexes")
    assert raised.value.code == "SPEECH_OBSERVATION_STORAGE_FAILED"


def test_unpaid_preparing_can_resume_same_local_observation_but_dispatched_preparing_cannot(tmp_path):
    path = tmp_path / "speech-observation.json"
    preparing = {"config_hash": "a"*64, "status": "PREPARING", "prepared": None, "prepared_sha256": None}
    options = dict(focus_timestamp_ms=0, source_hash="a"*64, transcript_hash="b"*64, index_cache_dir=tmp_path / "indexes")
    path.write_text(json.dumps(preparing), encoding="utf-8")
    result = prime_speech_lookup(tmp_path, "a"*64, source_items(), "合成原句", **options)
    assert result["receipt"]["status"] == "RETURNED" and json.loads(path.read_text("utf-8"))["status"] == "READY"
    path.write_text(json.dumps(preparing), encoding="utf-8")
    before = path.read_bytes()
    with pytest.raises(SegmentationError) as raised:
        prime_speech_lookup(tmp_path, "a"*64, source_items(), "合成原句", prior_requests=True, **options)
    assert raised.value.code == "INVALID_AGENT_SPEECH_CHECKPOINT" and path.read_bytes() == before


@pytest.mark.parametrize("when", ["before_preparing", "before_ready"])
def test_policy_tmp_link_never_overwrites_external_bytes_or_dispatches_model(tmp_path, real_source, monkeypatch, when):
    from livecut import agent_speech_lookup as module
    outside = tmp_path / "outside-policy-receipt.txt"
    outside.write_bytes(b"existing bytes must remain exact")
    directory = tmp_path / "agent-evidence-speech"
    directory.mkdir()
    temporary = directory / "speech-observation.json.tmp"
    def install_link():
        try:
            temporary.symlink_to(outside)
        except OSError as error:
            if getattr(error, "winerror", None) != 1314:
                raise
            # Unprivileged Windows cannot create links; still exercise the
            # same refusal branch. Linux CI creates the actual filesystem link.
            original = Path.is_symlink
            monkeypatch.setattr(Path, "is_symlink", lambda path: path == temporary or original(path))
    if when == "before_preparing":
        install_link()
    else:
        original_prepare = module.prepare_speech_lookup
        def prepare(*args, **kwargs):
            value = original_prepare(*args, **kwargs)
            install_link()
            return value
        monkeypatch.setattr(module, "prepare_speech_lookup", prepare)
    with pytest.raises(SegmentationError) as raised:
        run(tmp_path, real_source, [], focus=0, limit=1)
    assert raised.value.code == "INVALID_AGENT_SPEECH_CHECKPOINT"
    assert outside.read_bytes() == b"existing bytes must remain exact"


def speech_ledger(tmp_path, source, provider, *, limit, focus, policy=None):
    client, ledger, ctx, options = evidence_ledger_client(tmp_path, source, provider, limit=limit, focus=focus, policy=policy)
    config = copy.deepcopy(ctx.lease.config)
    config["agent_turn"]["evidence_policy"] = POLICY
    ctx.lease = replace(ctx.lease, config=config, required_worker_protocol=2)
    snapshot = ledger.agent_turn
    ledger.agent_turn = lambda key: {**snapshot(key), "evidence_policy": POLICY}
    options["evidence_policy"] = POLICY
    return client, ledger, ctx, options


@pytest.mark.parametrize("focus", [False, True])
def test_paid_success_replays_original_body_at_zero_new_budget(tmp_path, real_source, focus):
    hits = []
    def provider(request):
        hits.append(request.content)
        return httpx.Response(500 if len(hits) == 1 else 200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(reply(block("SPEECH", speech=["u1"])))}}]})
    client, ledger, ctx, options = speech_ledger(tmp_path, real_source, provider, limit=2, focus=focus)
    with client:
        original = build_visual_answer(source_items(), topics(), 20000, "合成原句一", ctx.directory, **options)
        path = ctx.directory / "agent-evidence-speech/state.json"
        state = json.loads(path.read_text("utf-8"))
        state["calls"][0]["status"] = "uncertain"
        state["calls"][0].pop("response_sha256")
        path.write_text(json.dumps(state), encoding="utf-8")
        (path.parent / state["calls"][0]["response_file"]).unlink()
        before = copy.deepcopy(ledger.calls)
        assert build_visual_answer(source_items(), topics(), 20000, "合成原句一", ctx.directory, **options) == original
    assert ledger.calls == before and len(hits) == 2 and hits[0] == hits[1]


@pytest.mark.parametrize("focus", [False, True])
def test_unknown_original_call_cannot_take_another_slot(tmp_path, real_source, focus):
    hits = []
    def provider(request):
        hits.append(1)
        raise httpx.ReadTimeout("synthetic unknown", request=request)
    client, ledger, ctx, options = speech_ledger(tmp_path, real_source, provider, limit=1, focus=focus,
        policy=TextRetryPolicy(max_attempts=1, backoff_seconds=0))
    with client:
        with pytest.raises(TextRecoveryError):
            build_visual_answer(source_items(), topics(), 20000, "合成原句一", ctx.directory, **options)
        with pytest.raises(TextRecoveryError, match="MODEL_BUDGET_EXHAUSTED"):
            build_visual_answer(source_items(), topics(), 20000, "合成原句一", ctx.directory, **options)
    assert len(hits) == len(ledger.calls) == 1


def test_worker_protocol_two_policy_produces_fenced_1_4_manifest(tmp_path, real_source, monkeypatch):
    ctx = visual_context(tmp_path, real_source)
    ctx.lease.config["agent_turn"].update(evidence_mode="structured", evidence_policy=POLICY, focus_timestamp_ms=0)
    ctx.lease = replace(ctx.lease, stage_type="AGENT_EVIDENCE", required_worker_protocol=2)
    ctx.guard = LeaseGuard(ctx.lease, threading.Event())
    monkeypatch.setenv("LIVECUT_INTENT_EMBEDDING_DIR", "must-not-be-opened")
    seen = []
    executor = mock_executor(monkeypatch, [reply(block("SPEECH", speech=["z0"]))], seen)
    manifest = executor(ctx)
    assert manifest["stage_type"] == "AGENT_EVIDENCE" and manifest["run_token"] == ctx.lease.run_token
    assert executor(ctx) == manifest and len(seen) == 1
    result = json.loads(_native_path(ctx.directory / "agent-answer.json").read_text("utf-8"))
    assert result["schema_version"] == "1.4" and result["tool_calls"][1]["origin"] == "EVIDENCE_POLICY"


@pytest.mark.parametrize("protocol,policy", [(None, POLICY), (2, None), (2, "unknown")])
def test_worker_rejects_policy_protocol_mismatch_before_transport(tmp_path, real_source, monkeypatch, protocol, policy):
    ctx = visual_context(tmp_path, real_source)
    ctx.lease.config["agent_turn"].update(evidence_mode="structured")
    if policy is not None:
        ctx.lease.config["agent_turn"]["evidence_policy"] = policy
    ctx.lease = replace(ctx.lease, stage_type="AGENT_EVIDENCE", required_worker_protocol=protocol)
    with pytest.raises(WorkerError, match="INVALID_AGENT_SPEECH_INPUT"):
        mock_executor(monkeypatch, [], [])(ctx)
