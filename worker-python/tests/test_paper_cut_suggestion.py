"""Exercise the actual paper-edit worker with locally simulated provider replies."""
import json
from dataclasses import replace

import httpx
import pytest
from livecut.paper_cut_suggestion import validate_proposal
from livecut.segmentation import SegmentationError
from livecut.worker import AnalysisStageExecutor, WorkerError, _native_path
from test_video_agent_audit import envelope
from test_worker_execution import context


def sentences():
    return [
        {"utterance_id": "a", "text": "先定义缓存。", "start_ms": 0, "end_ms": 900, "part_index": 0, "anchor": True, "evidence": True},
        {"utterance_id": "b", "text": "再举一个例子。", "start_ms": 1200, "end_ms": 2400, "part_index": 0, "anchor": False, "evidence": False},
        {"utterance_id": "c", "text": "结论是要更新键。", "start_ms": 4000, "end_ms": 5100, "part_index": 0, "anchor": False, "evidence": True},
    ]


def proposal(ids, target=2000):
    return {"remove_ids": ids, "target_ms": target, "reason": "保留定义和结论。"}


def make_context(tmp_path):
    ctx = context(tmp_path, "SEGMENT")
    paper = {"rows": sentences(), "instruction": "去掉例子，保留结论，控制在两秒。"}
    ctx.lease = replace(ctx.lease, required_worker_protocol=8,
                        config={"text_model": "qwen-plus", "max_text_calls": 2, "max_asr_submissions": 0,
                                "agent_turn": {"turn_id": "turn-1", "kind": "PAPER_CUT", "paper_context": paper}})
    return ctx


def test_paper_edit_real_adapter_grounds_prompt_and_repairs_one_invalid_suggestion(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVECUT_TEXT_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_TEXT_BASE_URL", "https://synthetic.invalid")
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "dashscope")
    ctx = make_context(tmp_path)
    requests = []

    def receive(request):
        data = json.loads(request.content)
        messages = data.get("messages") or data["input"]["messages"]
        requests.append(messages)
        assert json.loads(messages[1]["content"]) == {
            **ctx.lease.config["agent_turn"]["paper_context"], "target_ms": None}
        assert len(requests) <= 2
        return httpx.Response(200, json=envelope(proposal(["outside"] if len(requests) == 1 else ["b"])))

    def transport(stage, operation):
        assert stage is ctx and operation == "text"
        return httpx.MockTransport(receive)

    manifest = AnalysisStageExecutor(transport_factory=transport)(ctx)
    assert len(requests) == 2
    assert "Only current sentences" in requests[1][-1]["content"]
    assert [item["kind"] for item in manifest["artifacts"]] == ["paper_cut_suggestion"]
    output = _native_path(ctx.directory / "paper-cut-suggestion.json")
    assert json.loads(output.read_text(encoding="utf-8")) == {"turn_id": "turn-1", "proposal": proposal(["b"])}


def test_rejected_proposal_uses_no_more_than_two_calls(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVECUT_TEXT_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_TEXT_BASE_URL", "https://synthetic.invalid")
    ctx = make_context(tmp_path)
    calls = []

    def receive(request):
        calls.append(request)
        return httpx.Response(200, json=envelope(proposal(["a"])))

    with pytest.raises(SegmentationError, match="anchor"):
        AnalysisStageExecutor(transport_factory=lambda *_: httpx.MockTransport(receive))(ctx)
    assert len(calls) == 2
    assert not _native_path(ctx.directory / "paper-cut-suggestion.json").exists()


@pytest.mark.parametrize("ids,target", [(["a"], 2000), (["unknown"], 2000), (["b", "b"], 2000), (["b"], 1999)])
def test_invalid_ids_anchors_durations_are_rejected(ids, target):
    with pytest.raises(SegmentationError):
        validate_proposal(proposal(ids, target), sentences(), target)


def test_untouched_instruction_or_unachievable_target_may_return_a_reason_without_changes():
    for target in [20, None]:
        value = proposal([], target)
        assert validate_proposal(value, sentences(), target) == value


def test_evidence_and_explicit_target_checked_independently():
    rows = sentences()
    for row in rows:
        row["anchor"] = False
    with pytest.raises(SegmentationError, match="evidence"):
        validate_proposal(proposal(["a", "c"]), rows, 2000)
    with pytest.raises(SegmentationError):
        validate_proposal(proposal(["b"], None), rows, 2000)


def test_paper_edit_requires_durable_transport(tmp_path):
    with pytest.raises(WorkerError, match="DURABLE_BUDGET_REQUIRED"):
        AnalysisStageExecutor()(make_context(tmp_path))
