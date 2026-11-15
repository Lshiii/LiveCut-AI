"""Protocol-6 QA through real adapters with a synthetic durable ledger/provider."""
import copy
import json
import threading
from dataclasses import replace

import httpx
import pytest
from livecut.model_budget import BudgetTransport
from livecut.text_resilience import ResilientTextTransport, TextRetryPolicy
from livecut.worker import AnalysisStageExecutor, LeaseGuard, WorkerError, _native_path
from test_text_resilience import Ledger
from test_video_agent_audit import envelope, payload, tool
from test_video_agent_worker import agent_context


def coverage_context(tmp_path):
    ctx = agent_context(tmp_path)
    config = ctx.lease.config["agent_turn"]
    config["qa_policy"] = "coverage_v1"
    ctx.lease.config["max_text_calls"] = 8
    ctx.lease = replace(ctx.lease, required_worker_protocol=6)
    ctx.guard = LeaseGuard(ctx.lease, threading.Event())
    return ctx


class CoverageLedger(Ledger):
    def __init__(self, context):
        super().__init__(context.lease.config["max_text_calls"])
        self.context, self.reads, self.mutate = context, 0, None

    def agent_turn(self, turn_id):
        self.reads += 1
        ctx, config = self.context, self.context.lease.config["agent_turn"]
        assert turn_id == config["turn_id"]
        value = {"id": turn_id, "session_id": config["session_id"], "job_id": ctx.lease.job_id,
            "qa_policy": "coverage_v1", "visual_mode": "none", "status": "RUNNING",
            "stage": {"id": ctx.lease.stage_id, "stage_type": "SEGMENT", "status": "RUNNING", "attempt": ctx.lease.attempt},
            "budget": {"max_text_calls": self.limit, "text_calls_reserved": len(self.calls)}}
        return self.mutate(value) if self.mutate else value


def final(messages):
    return {"type": "answer", "answer_scope": "VIDEO", "coverage": [
        {"part_id": row["part_id"], "status": "SUPPORTED", "text": "修改历史会让前缀匹配中断。", "citation_ids": ["a1"]}
        for row in payload(messages)["question_parts"]], "suggested_clip_instruction": None}


def test_production_qa_transport_counts_unknown_physical_retry_and_replays_without_more_calls(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVECUT_TEXT_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_TEXT_BASE_URL", "https://synthetic.invalid")
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "dashscope")
    monkeypatch.setenv("LIVECUT_TEXT_FALLBACKS_JSON", "[]")
    monkeypatch.delenv("LIVECUT_INTENT_EMBEDDING_DIR", raising=False)
    ctx = coverage_context(tmp_path)
    ledger, hits = CoverageLedger(ctx), []
    def provider(request):
        wire = json.loads(request.content)
        messages = wire.get("messages", wire.get("input", {}).get("messages"))
        hits.append(messages)
        if len(hits) == 1:
            raise httpx.ReadTimeout("synthetic unknown submission", request=request)
        value = tool("read_context", utterance_id="a1", before=1, after=1) if len(hits) == 2 else final(messages)
        return httpx.Response(200, json=envelope(value))
    def factory(context, operation):
        assert operation == "text"
        inner = BudgetTransport(ledger, context, inner=httpx.MockTransport(provider))
        return ResilientTextTransport(inner, context, policy=TextRetryPolicy(backoff_seconds=0))
    executor = AnalysisStageExecutor(transport_factory=factory)
    manifest = executor(ctx)
    assert len(hits) == len(ledger.calls) == 3
    assert [row["status"] for row in ledger.calls.values()] == ["UNKNOWN_OUTCOME", "SUCCEEDED", "SUCCEEDED"]
    reads = ledger.reads
    assert executor(ctx) == manifest
    assert len(hits) == len(ledger.calls) == 3 and ledger.reads == reads
    result = json.loads(_native_path(ctx.directory / "agent-answer.json").read_text("utf-8"))
    assert result["provenance"]["prompt_version"] == "video-agent-coverage-v1"
    assert result["schema_version"] == "1.0" and result["citations"][0]["utterance_id"] == "a1"


@pytest.mark.parametrize("mutate", [
    lambda value: value.update(qa_policy="other"),
    lambda value: value.update(job_id="different-source"),
    lambda value: value["stage"].update(id="other-stage"),
    lambda value: value["budget"].update(max_text_calls=9),
    lambda value: value["budget"].update(text_calls_reserved=True),
    lambda value: value["budget"].update(text_calls_reserved=-1),
    lambda value: value["budget"].update(text_calls_reserved=9),
])
def test_budget_reader_rejects_wrong_owner_or_invalid_counter(tmp_path, mutate):
    ctx = coverage_context(tmp_path)
    ledger = CoverageLedger(ctx)
    def changed(value):
        mutate(value)
        return value
    ledger.mutate = changed
    budget = BudgetTransport(ledger, ctx, inner=httpx.MockTransport(lambda r: pytest.fail("No provider")))
    assert budget.coverage_text_budget() is None
    assert ledger.calls == {}


def test_budget_reader_does_not_accept_decreasing_reservation_count(tmp_path):
    ctx = coverage_context(tmp_path)
    ledger = CoverageLedger(ctx)
    budget = BudgetTransport(ledger, ctx, inner=httpx.MockTransport(lambda r: None))
    ledger.calls["fixture-reservation"] = {}
    assert budget.coverage_text_budget() == 7
    ledger.calls.clear()
    assert budget.coverage_text_budget() is None


@pytest.mark.parametrize("mutation", [
    lambda c: setattr(c, "lease", replace(c.lease, required_worker_protocol=1)),
    lambda c: c.lease.config["agent_turn"].pop("qa_policy"),
    lambda c: c.lease.config["agent_turn"].update(qa_policy="unknown"),
    lambda c: c.lease.config["agent_turn"].update(visual_mode="frames"),
    lambda c: c.lease.config["agent_turn"].update(kind="CAPTION"),
])
def test_worker_cannot_ignore_policy_or_route_it_to_an_old_or_visual_executor(tmp_path, mutation):
    ctx = coverage_context(tmp_path)
    original = copy.deepcopy(ctx.lease.config)
    mutation(ctx)
    with pytest.raises(WorkerError) as failure:
        AnalysisStageExecutor(transport_factory=lambda *a: pytest.fail("No model transport"))(ctx)
    assert failure.value.code == "INVALID_AGENT_COVERAGE_INPUT"
    assert original["agent_turn"]["qa_policy"] == "coverage_v1"
