"""Synthetic Java ledger and provider verify the production caption transport chain."""

import copy
import hashlib
import json

import httpx
import pytest
from livecut.caption_proofread import build_caption_proofread
from livecut.model_budget import BudgetTransport, ModelBudgetError, transport_factory
from livecut.segmentation import SegmentationError
from livecut.worker import AnalysisStageExecutor, WorkerError, _native_path
from livecut.worker_client import WorkerClient, WorkerClientError
from test_caption_proofread import arguments, decision, run
from test_caption_proofread_worker import proofread_context
from test_text_resilience import Ledger
from test_video_agent_audit import envelope, payload, source, tool


class CaptionLedger(Ledger):
    def __init__(self, context, limit):
        super().__init__(limit)
        self.context, self.reads = context, 0
        self.budget_override = None

    def snapshot(self):
        self.reads += 1
        ctx, config = self.context, self.context.lease.config["agent_turn"]
        result = {
            "id": config["turn_id"],
            "session_id": config["session_id"],
            "job_id": ctx.lease.job_id,
            "status": "RUNNING",
            "stage": {
                "id": ctx.lease.stage_id,
                "stage_type": "CAPTION_PROOFREAD",
                "status": "RUNNING",
                "attempt": ctx.lease.attempt,
            },
            "budget": {"max_text_calls": self.limit, "text_calls_reserved": len(self.calls)},
        }
        if self.budget_override:
            return self.budget_override(result)
        return result

    def java(self, request):
        if request.method == "GET":
            assert (
                request.url.path
                == "/api/caption-proofread-turns/"
                + self.context.lease.config["agent_turn"]["turn_id"]
            )
            return httpx.Response(200, json=self.snapshot())
        value = json.loads(request.content)
        if request.url.path == "/internal/model-calls/reserve":
            result = self.reserve_call(value)
        elif request.url.path == "/internal/model-calls/lookup":
            result = self.lookup_call(value)
        elif request.url.path.startswith("/internal/model-calls/"):
            result = self.update_call(request.url.path.split("/")[-2], value)
        else:
            assert request.url.path.endswith("/text-progress")
            result = {"outcome": "UPDATED"}
        return httpx.Response(200, json=result)


def production(tmp_path, monkeypatch, provider, *, maximum=4, attempts=3):
    monkeypatch.setenv("LIVECUT_TEXT_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_TEXT_BASE_URL", "https://synthetic.invalid")
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "dashscope")
    monkeypatch.setenv("LIVECUT_TEXT_MAX_ATTEMPTS", str(attempts))
    monkeypatch.setenv("LIVECUT_TEXT_RETRY_BACKOFF_SECONDS", "0")
    monkeypatch.setenv("LIVECUT_TEXT_FALLBACKS_JSON", "[]")
    monkeypatch.delenv("LIVECUT_INTENT_EMBEDDING_DIR", raising=False)
    ctx = proofread_context(tmp_path)
    ctx.lease.config["max_text_calls"] = maximum
    ctx.lease.config["agent_turn"].update(
        turn_id="21111111-1111-4111-8111-111111111111",
        session_id="22222222-2222-4222-8222-222222222222",
    )
    ledger = CaptionLedger(ctx, maximum)
    java = WorkerClient(
        "http://127.0.0.1", "synthetic-worker-key", transport=httpx.MockTransport(ledger.java)
    )
    monkeypatch.setattr(
        "livecut.text_resilience.DeadlineHTTPTransport", lambda: httpx.MockTransport(provider)
    )
    return ctx, ledger, java, transport_factory(java)


def build_with(ctx, factory):
    transport = factory(ctx, "text")
    config = ctx.lease.config["agent_turn"]
    with httpx.Client(transport=transport) as client:
        client.livecut_resumable_text = True
        return build_caption_proofread(
            source(),
            config["duration_ms"],
            config["question"],
            config["caption_context"],
            _native_path(ctx.directory),
            session_id=config["session_id"],
            turn_id=config["turn_id"],
            job_id=ctx.lease.job_id,
            video_id=ctx.lease.video_id,
            source_hash=ctx.lease.source_hash,
            transcript_hash=config["source_transcript_hash"],
            model=ctx.lease.config["text_model"],
            max_calls=ctx.lease.config["max_text_calls"],
            max_tool_steps=config["max_tool_steps"],
            max_request_attempts=transport.policy.max_attempts,
            budget_remaining=transport.caption_text_budget,
            client=client,
            api_key="synthetic-key",
            base_url="https://synthetic.invalid",
            guard=ctx.guard.check,
        )


def test_format_repair_reduces_each_new_allowance_and_preserves_a_final_answer(tmp_path):
    replies = [
        tool("shell", command="forbidden"),
        tool("read_context", utterance_id="a1", before=0, after=0),
        tool("read_context", utterance_id="b3", before=0, after=0),
        decision(),
    ]
    result, requests = run(tmp_path, replies, max_calls=4, repeat=True)
    assert [payload(messages)["remaining_tool_steps"] for messages in requests] == [3, 2, 1, 0]
    assert len(result["tool_calls"]) == 2


@pytest.mark.parametrize("failure", ["503", "timeout"])
def test_real_worker_factory_reserves_answer_after_all_three_physical_attempts(
    tmp_path, monkeypatch, failure
):
    seen = []

    def provider(request):
        messages = json.loads(request.content)["input"]["messages"]
        current = payload(messages)
        seen.append(current["remaining_tool_steps"])
        if len(seen) <= 2:
            if failure == "timeout":
                raise httpx.ReadTimeout("synthetic timeout", request=request)
            return httpx.Response(503, json={"error": "synthetic-unavailable"})
        answer = (
            tool("read_context", utterance_id="a1", before=0, after=0)
            if current["remaining_tool_steps"]
            else decision()
        )
        return httpx.Response(200, json=envelope(answer))

    ctx, ledger, java, factory = production(tmp_path, monkeypatch, provider)
    try:
        manifest = AnalysisStageExecutor(transport_factory=factory)(ctx)
        assert manifest["artifacts"][0]["kind"] == "caption_proofread"
        assert seen == [1, 1, 1, 0]
        assert len(ledger.calls) == 4 and ledger.reads == 2
        result = json.loads(
            _native_path(ctx.directory / "caption-proofread.json").read_text(encoding="utf-8")
        )
        assert len(result["tool_calls"]) == 1
        reserved, reads = len(ledger.calls), ledger.reads
        ledger.budget_override = lambda _: pytest.fail(
            "Cached requests must not reread current budget"
        )
        assert build_with(ctx, factory) == result
        assert len(ledger.calls) == reserved and ledger.reads == reads
    finally:
        java.close()


def test_production_format_repair_and_provider_retry_freeze_separate_payloads(
    tmp_path, monkeypatch
):
    seen = []

    def provider(request):
        messages = json.loads(request.content)["input"]["messages"]
        current = payload(messages)
        seen.append(current["remaining_tool_steps"])
        if len(seen) == 1:
            return httpx.Response(200, json=envelope(tool("shell", command="forbidden")))
        if len(seen) == 2:
            return httpx.Response(503, json={"error": "synthetic-unavailable"})
        value = (
            tool("read_context", utterance_id="a1", before=0, after=0)
            if current["remaining_tool_steps"]
            else decision()
        )
        return httpx.Response(200, json=envelope(value))

    ctx, ledger, java, factory = production(tmp_path, monkeypatch, provider, maximum=6)
    try:
        result = build_with(ctx, factory)
        assert seen == [3, 2, 2, 0] and len(ledger.calls) == 4
        assert len(result["tool_calls"]) == 1
        plan = json.loads(
            _native_path(ctx.directory / "caption-proofread/request-budgets.json").read_text(
                encoding="utf-8"
            )
        )
        assert [item["remaining_tool_steps"] for item in plan["requests"]] == [3, 2, 0]
        assert all(len(item["payload_sha256"]) == 64 for item in plan["requests"])
        ledger.budget_override = lambda _: pytest.fail("Replayed prompt budget must stay frozen")
        assert build_with(ctx, factory) == result and len(ledger.calls) == 4
    finally:
        java.close()


@pytest.mark.parametrize(
    "mutate",
    [
        lambda v: v.update(id="other"),
        lambda v: v.update(session_id="other"),
        lambda v: v.update(job_id="other"),
        lambda v: v["stage"].update(id="other"),
        lambda v: v["stage"].update(stage_type="SEGMENT"),
        lambda v: v["budget"].update(max_text_calls=True),
        lambda v: v["budget"].update(max_text_calls=5),
        lambda v: v["budget"].update(text_calls_reserved=True),
        lambda v: v["budget"].update(text_calls_reserved=-1),
        lambda v: v["budget"].update(text_calls_reserved=5),
    ],
)
def test_untrusted_central_budget_forces_final_without_claiming_a_valid_snapshot(
    tmp_path, monkeypatch, mutate
):
    sent = []

    def provider(request):
        sent.append(payload(json.loads(request.content)["input"]["messages"]))
        return httpx.Response(200, json=envelope(decision()))

    ctx, ledger, java, factory = production(tmp_path, monkeypatch, provider)

    def changed(value):
        mutate(value)
        return value

    ledger.budget_override = changed
    try:
        assert build_with(ctx, factory)["tool_calls"] == []
        assert sent[0]["remaining_tool_steps"] == 0
        assert sent[0]["remaining_text_calls"] is None
        assert "未能核实" in sent[0]["budget_notice"] and len(ledger.calls) == 1
    finally:
        java.close()


def test_budget_get_failure_forces_final_and_cancel_during_get_stops_before_provider(
    tmp_path, monkeypatch
):
    def provider(request):
        current = payload(json.loads(request.content)["input"]["messages"])
        assert current["remaining_tool_steps"] == 0 and current["remaining_text_calls"] is None
        return httpx.Response(200, json=envelope(decision()))

    ctx, ledger, java, factory = production(tmp_path, monkeypatch, provider)
    original = ledger.java

    def java_failure(request):
        return (
            httpx.Response(503, json={"error_code": "JAVA_UNAVAILABLE"})
            if request.method == "GET"
            else original(request)
        )

    java._client.close()
    java._client = httpx.Client(transport=httpx.MockTransport(java_failure))
    try:
        assert build_with(ctx, factory)["tool_calls"] == [] and len(ledger.calls) == 1
    finally:
        java.close()
    other = proofread_context(tmp_path / "cancel")

    class CancellingClient:
        def caption_proofread_turn(self, _):
            other.guard._stop.set()
            raise WorkerClientError("JAVA_UNAVAILABLE", retryable=True)

    transport = BudgetTransport(
        CancellingClient(), other, inner=httpx.MockTransport(lambda _: pytest.fail("No provider"))
    )
    with pytest.raises(WorkerError, match="WORKER_STOPPED"):
        transport.caption_text_budget()


def test_caption_transport_still_forbids_asr_before_reservation(tmp_path):
    ctx = proofread_context(tmp_path)
    ledger = CaptionLedger(ctx, 4)
    with httpx.Client(
        transport=BudgetTransport(
            ledger, ctx, inner=httpx.MockTransport(lambda _: pytest.fail("No ASR"))
        )
    ) as client:
        with pytest.raises(ModelBudgetError, match="MODEL_OPERATION_NOT_ALLOWED"):
            client.post(
                "https://synthetic.invalid/services/audio/asr/transcription",
                json={"model": "paraformer-v2"},
            )
    assert not ledger.calls


def test_changed_or_missing_frozen_request_budget_never_replays_a_different_prompt(tmp_path):
    run(tmp_path, [decision()])
    path = tmp_path / "caption-proofread/request-budgets.json"
    plan = json.loads(path.read_text(encoding="utf-8"))
    changed = copy.deepcopy(plan)
    changed["requests"][0]["remaining_tool_steps"] = 0
    path.write_text(json.dumps(changed), encoding="utf-8")
    with pytest.raises(SegmentationError) as failure:
        run(tmp_path, [])
    assert failure.value.code == "INVALID_CAPTION_PROOFREAD_CHECKPOINT"
    path.unlink()
    with pytest.raises(SegmentationError) as failure:
        run(tmp_path, [])
    assert failure.value.code == "INVALID_CAPTION_PROOFREAD_CHECKPOINT"


@pytest.mark.parametrize(
    "mutate",
    [
        lambda v: v.update(status="CANCELLED"),
        lambda v: v["stage"].update(status="CANCELLED"),
        lambda v: v["stage"].update(status="SUCCEEDED"),
        lambda v: v["stage"].update(attempt=v["stage"]["attempt"] + 1),
    ],
)
def test_cancelled_or_reclaimed_caption_owner_stops_before_reserve(tmp_path, monkeypatch, mutate):
    ctx, ledger, java, factory = production(
        tmp_path, monkeypatch, lambda _: pytest.fail("No provider")
    )

    def changed(value):
        mutate(value)
        return value

    ledger.budget_override = changed
    try:
        with pytest.raises(WorkerError, match="LEASE_LOST"):
            build_with(ctx, factory)
        assert not ledger.calls
    finally:
        java.close()


def test_request_allowance_write_failure_is_before_any_paid_reservation(tmp_path, monkeypatch):
    from livecut import caption_proofread

    ctx, ledger, java, factory = production(
        tmp_path, monkeypatch, lambda _: pytest.fail("No provider")
    )
    original = caption_proofread._save

    def fail_plan(path, value):
        if path.name == "request-budgets.json":
            raise OSError("synthetic local storage failure")
        original(path, value)

    monkeypatch.setattr(caption_proofread, "_save", fail_plan)
    try:
        with pytest.raises(OSError):
            build_with(ctx, factory)
        assert not ledger.calls
    finally:
        java.close()


def make_legacy_checkpoint(path):
    """Synthetic old-v1 completed layout, preserving the recorded provider decisions."""
    from livecut.segmentation import _endpoint, _json

    options = arguments(path)
    keys = (
        "session_id",
        "turn_id",
        "job_id",
        "video_id",
        "source_hash",
        "transcript_hash",
        "instruction",
    )
    config = {key: options[key] for key in keys}
    config.update(
        prompt_version="caption-proofread-v1",
        utterances=options["utterances"],
        caption_context=options["caption_context"],
        duration_ms=options["duration_ms"],
        model="qwen-plus",
        endpoint=_endpoint(options["base_url"]),
        max_calls=8,
        max_tool_steps=4,
        embedding_model_id=None,
        dense_enabled=False,
    )
    state_path = path / "caption-proofread/state.json"
    state = json.loads(state_path.read_text(encoding="utf-8"))
    state["config_hash"] = hashlib.sha256(_json(config).encode("utf-8")).hexdigest()
    state_path.write_text(json.dumps(state), encoding="utf-8")
    (path / "caption-proofread/request-budgets.json").unlink()
    return state_path


def test_complete_legacy_answer_is_verified_and_reused_without_rewriting_old_call_state(tmp_path):
    expected, _ = run(
        tmp_path, [tool("read_context", utterance_id="a1", before=0, after=0), decision()]
    )
    path = make_legacy_checkpoint(tmp_path)
    old_state = path.read_bytes()

    def no_budget_read():
        pytest.fail("Completed legacy evidence must not request new budget")

    replayed, requests = run(tmp_path, [], budget_remaining=no_budget_read, max_request_attempts=3)
    assert replayed == expected and not requests
    assert path.read_bytes() == old_state
    assert not (tmp_path / "caption-proofread/request-budgets.json").exists()


@pytest.mark.parametrize(
    "damage", ["started", "uncertain", "missing_result", "result_mismatch", "bad_raw_hash"]
)
def test_incomplete_or_damaged_legacy_calls_are_not_reissued_or_reset(tmp_path, damage):
    run(tmp_path, [decision()])
    path = make_legacy_checkpoint(tmp_path)
    state = json.loads(path.read_text(encoding="utf-8"))
    if damage in {"started", "uncertain"}:
        state["calls"][0]["status"] = damage
        path.write_text(json.dumps(state), encoding="utf-8")
    elif damage == "missing_result":
        (tmp_path / "caption-proofread.json").unlink()
    elif damage == "result_mismatch":
        result_path = tmp_path / "caption-proofread.json"
        result = json.loads(result_path.read_text(encoding="utf-8"))
        result["suggestions"][0]["after"] = "另一条合成合法文字，但未出现在旧回答"
        result_path.write_text(json.dumps(result), encoding="utf-8")
    else:
        raw = tmp_path / "caption-proofread/window-0000-attempt-0.json"
        raw.write_text(json.dumps(envelope(decision(suggestions=[]))), encoding="utf-8")
    previous = path.read_bytes()
    with pytest.raises(SegmentationError) as failure:
        run(tmp_path, [])
    assert failure.value.code == "INVALID_CAPTION_PROOFREAD_CHECKPOINT"
    assert path.read_bytes() == previous
    assert not (tmp_path / "caption-proofread/request-budgets.json").exists()


def test_orphaned_legacy_response_cannot_silently_start_a_new_local_ledger(tmp_path):
    run(tmp_path, [decision()])
    state = make_legacy_checkpoint(tmp_path)
    state.unlink()
    with pytest.raises(SegmentationError) as failure:
        run(tmp_path, [])
    assert failure.value.code == "INVALID_CAPTION_PROOFREAD_CHECKPOINT"
    assert not state.exists()
