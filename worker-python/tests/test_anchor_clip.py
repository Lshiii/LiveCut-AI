"""Synthetic repeated terms prove that the selected occurrence stays authoritative."""

import copy
import json
from dataclasses import replace

import httpx
import pytest
from livecut.anchor_clip import AnchorSourceError, build_anchor_clip_plan, validate_answer_source
from livecut.intent import validate_clip_plan
from livecut.model_budget import BudgetTransport, ModelBudgetError, transport_factory
from livecut.segmentation import SegmentationError
from livecut.worker import STAGES, AnalysisStageExecutor, WorkerError, sha256
from test_intent import raw
from test_text_resilience import Ledger
from test_worker_execution import context, predecessor

JOB = "11111111-1111-4111-8111-111111111111"
VIDEO = "22222222-2222-4222-8222-222222222222"
REQUEST = "33333333-3333-4333-8333-333333333333"
TURN = "44444444-4444-4444-8444-444444444444"
SESSION = "55555555-5555-4555-8555-555555555555"


def utterances():
    texts = [
        "开始第一部分。",
        "这里解释 prefix 前缀匹配。",
        "第一部分结束。",
        "其他话题。",
        "继续其他话题。",
        "另一段闲聊。",
        "现在开始解释第二处 prefix 的上下文。",
        "这里解释 prefix 前缀匹配。",
        "这就是第二处解释的解决方法。",
        "下面是新话题。",
        "其他正文。",
        "结束。",
    ]
    return [
        {
            "utterance_id": f"u{i + 1:06d}",
            "start_ms": i * 1000,
            "end_ms": i * 1000 + 900,
            "text": text,
            "source_chunk_id": "audio-0",
        }
        for i, text in enumerate(texts)
    ]


def answer_source(items=None, ids=None, **updates):
    items, ids = utterances() if items is None else items, ["u000008"] if ids is None else ids
    by_id = {row["utterance_id"]: row for row in items}
    order = {uid: index for index, uid in enumerate(by_id)}
    selected = set(ids)
    size = sum(len(by_id[uid]["text"].encode("utf-16-le")) // 2 for uid in ids)
    neighbors = {
        index
        for uid in ids
        for index in (order[uid] - 1, order[uid] + 1)
        if 0 <= index < len(items)
    }
    for index in sorted(neighbors):
        row = items[index]
        width = len(row["text"].encode("utf-16-le")) // 2
        if row["utterance_id"] not in selected and size + width <= 12000:
            selected.add(row["utterance_id"])
            size += width
    return {
        "schema_version": "1.0",
        "turn_id": TURN,
        "session_id": SESSION,
        "answer_hash": "f" * 64,
        "job_id": JOB,
        "video_id": VIDEO,
        "source_hash": "a" * 64,
        "transcript_hash": "b" * 64,
        "duration_ms": len(items) * 1000,
        "question": "解释第二处 prefix 的完整方法。",
        "answer": "所选引用是第二处解释，需结合相邻正文核对。",
        "utterance_ids": ids,
        "citations": [
            {key: by_id[uid][key] for key in ("utterance_id", "start_ms", "end_ms", "text")}
            for uid in ids
        ],
        "context_utterance_ids": sorted(selected, key=order.get),
        **updates,
    }


def response(**updates):
    return {
        "outputs": [
            {
                "goal_id": "answer-source",
                "title": "第二处 prefix 解释",
                "status": "FOUND",
                "reason": "所选原句及相邻正文支持此解释，仍需人工审核。",
                "parts": [{"start_utterance_id": "u000007", "end_utterance_id": "u000009"}],
                "excluded": [],
                "evidence_utterance_ids": ["u000008"],
                **updates,
            }
        ]
    }


def arguments(path, **updates):
    return {
        "utterances": utterances(),
        "duration_ms": 12000,
        "instruction": "剪出所选第二处prefix解释，保留完整说明。",
        "answer_source": answer_source(),
        "output_dir": path,
        "request_id": REQUEST,
        "job_id": JOB,
        "video_id": VIDEO,
        "source_hash": "a" * 64,
        "transcript_hash": "b" * 64,
        "api_key": "synthetic-key",
        "base_url": "https://synthetic.invalid",
        **updates,
    }


def run(path, replies, *, repeat=False, **updates):
    requests = []

    def provider(request):
        requests.append(json.loads(request.content))
        assert len(requests) <= len(replies), "Unexpected paid request"
        return httpx.Response(200, json=raw(replies[len(requests) - 1]))

    with httpx.Client(transport=httpx.MockTransport(provider)) as client:
        result = build_anchor_clip_plan(**arguments(path, client=client, **updates))
        if repeat:
            assert build_anchor_clip_plan(**arguments(path, client=client, **updates)) == result
    return result, requests


def test_second_occurrence_uses_frozen_context_and_replays_without_free_query_or_extra_calls(
    tmp_path,
):
    source = answer_source()
    original = copy.deepcopy(source)
    result, requests = run(tmp_path, [response()], repeat=True, answer_source=source)
    assert source == original and len(requests) == 1
    payload = json.loads(requests[0]["input"]["messages"][1]["content"])
    assert [row["utterance_id"] for row in payload["retrieval"]["answer-source"]["utterances"]] == [
        "u000007",
        "u000008",
        "u000009",
    ]
    assert "u000002" not in payload["retrieval"]["answer-source"]["pinned_utterance_ids"]
    assert result["outputs"][0]["parts"][0]["start_ms"] == 6000
    assert result["outputs"][0]["parts"][0]["end_ms"] == 8900
    assert result["provenance"]["retrieval_mode"] == "anchor_context"
    assert result["provenance"]["prompt_version"] == "anchor-clips-v1"
    assert result["goals"][0]["goal_id"] == "answer-source" and not result["outputs"][0]["enabled"]
    validate_clip_plan(result, utterances(), answer_source=source)
    assert (tmp_path / "retrieval-index.json").is_file() and (
        tmp_path / "retrieval-trace.json"
    ).is_file()


@pytest.mark.parametrize(
    "instruction",
    [
        " 剪出所选第二处prefix解释。",
        "剪出所选第二处prefix解释。 ",
        " 剪出所选第二处prefix解释。 ",
    ],
)
def test_goal_description_preserves_instruction_whitespace_exactly_like_java(tmp_path, instruction):
    result, requests = run(tmp_path, [response()], instruction=instruction, max_calls=1)
    payload = json.loads(requests[0]["input"]["messages"][1]["content"])
    assert len(requests) == 1
    assert payload["instruction"] == result["instruction"] == instruction
    assert payload["goals"][0]["description"] == result["goals"][0]["description"] == instruction


@pytest.mark.parametrize(
    "instruction,expected",
    [
        (" " + "前" * 298 + "😀" + "后 ", " " + "前" * 298),
        ("前" * 298 + "😀" + "后 ", "前" * 298 + "😀"),
    ],
)
def test_goal_prefix_matches_java_utf16_boundary_without_splitting_an_emoji(
    tmp_path, instruction, expected
):
    result, requests = run(tmp_path, [response()], instruction=instruction)
    description = result["goals"][0]["description"]
    assert description == expected and len(description.encode("utf-16-le")) // 2 <= 300
    assert result["instruction"] == instruction and len(requests) == 1


def test_whitespace_only_derived_prefix_rejects_before_transport_or_paid_state(
    tmp_path, monkeypatch
):
    monkeypatch.setattr(
        "livecut.anchor_clip._request", lambda *args, **kwargs: pytest.fail("No model request")
    )
    with pytest.raises(AnchorSourceError, match="TEXT"):
        run(tmp_path, [], instruction=" " * 300 + "后面才是可见的剪辑要求")
    assert not (tmp_path / "anchor-intent/state.json").exists()
    assert not (tmp_path / "clip-plan.json").exists()


@pytest.mark.parametrize(
    "bad",
    [
        response(
            parts=[{"start_utterance_id": "u000002", "end_utterance_id": "u000002"}],
            evidence_utterance_ids=["u000002"],
        ),
        response(
            parts=[{"start_utterance_id": "u000007", "end_utterance_id": "u000007"}],
            evidence_utterance_ids=["u000007"],
        ),
        response(
            parts=[
                {
                    "start_utterance_id": "u000007",
                    "end_utterance_id": "u000009",
                    "start_ms": 7999,
                    "end_ms": 8001,
                }
            ]
        ),
        response(
            parts=[],
            evidence_utterance_ids=[],
            excluded=[{"utterance_id": "u000008", "reason": "不要该句"}],
            status="NEEDS_REVIEW",
        ),
        {"outputs": response()["outputs"] * 2},
    ],
)
def test_wrong_occurrence_missing_anchor_fake_millisecond_or_exclusion_never_publish(tmp_path, bad):
    with pytest.raises(SegmentationError):
        run(tmp_path, [bad, bad])
    state = json.loads((tmp_path / "anchor-intent/state.json").read_text(encoding="utf-8"))
    assert len(state["calls"]) == 2 and not (tmp_path / "clip-plan.json").exists()


def test_bad_occurrence_can_be_corrected_once_with_anchor_specific_repair(tmp_path):
    bad = response(
        parts=[{"start_utterance_id": "u000002", "end_utterance_id": "u000002"}],
        evidence_utterance_ids=["u000002"],
    )
    result, requests = run(tmp_path, [bad, response()])
    feedback = json.loads(requests[1]["input"]["messages"][-1]["content"])
    assert "不能换到其他同词位置" in feedback["anchor_requirement"]
    assert result["outputs"][0]["parts"][0]["start_utterance_id"] == "u000007"


def test_multiple_anchors_keep_both_occurrences_without_bridging_unseen_sentences(tmp_path):
    source = answer_source(ids=["u000002", "u000008"])
    value = response(
        parts=[
            {"start_utterance_id": "u000001", "end_utterance_id": "u000003"},
            {"start_utterance_id": "u000007", "end_utterance_id": "u000009"},
        ],
        evidence_utterance_ids=["u000002", "u000008"],
    )
    result, requests = run(tmp_path / "valid", [value], answer_source=source)
    payload = json.loads(requests[0]["input"]["messages"][1]["content"])
    assert len(payload["retrieval"]["answer-source"]["context_ranges"]) == 2
    assert len(result["outputs"][0]["parts"]) == 2
    with pytest.raises(SegmentationError):
        run(tmp_path / "missing", [response()] * 2, answer_source=source)
    bridge = response(parts=[{"start_utterance_id": "u000001", "end_utterance_id": "u000009"}])
    with pytest.raises(SegmentationError):
        run(tmp_path / "bridge", [bridge] * 2, answer_source=source)


def test_public_plan_cannot_represent_anchor_with_a_one_millisecond_overlap(tmp_path):
    result, _ = run(
        tmp_path,
        [response(parts=[{"start_utterance_id": "u000008", "end_utterance_id": "u000008"}])],
    )
    result["outputs"][0]["parts"][0].update(start_ms=7899, end_ms=7900)
    with pytest.raises(SegmentationError, match="PART_SOURCE_TIME"):
        validate_clip_plan(result, utterances(), answer_source=answer_source())


@pytest.mark.parametrize(
    "status,parts,evidence",
    [
        (
            "NEEDS_REVIEW",
            [{"start_utterance_id": "u000008", "end_utterance_id": "u000008"}],
            ["u000008"],
        ),
        ("NEEDS_REVIEW", [], []),
        ("NOT_FOUND", [], []),
    ],
)
def test_uncertain_or_missing_content_is_never_promoted_to_found(tmp_path, status, parts, evidence):
    result, _ = run(
        tmp_path, [response(status=status, parts=parts, evidence_utterance_ids=evidence)]
    )
    assert result["outputs"][0]["status"] == status and not result["outputs"][0]["enabled"]


@pytest.mark.parametrize(
    "mutate",
    [
        lambda s: s.update(job_id=VIDEO),
        lambda s: s.update(video_id=JOB),
        lambda s: s.update(source_hash="c" * 64),
        lambda s: s.update(transcript_hash="c" * 64),
        lambda s: s.update(utterance_ids=["u000002"]),
        lambda s: s.update(utterance_ids=["u000008", "u000008"]),
        lambda s: s["citations"][0].update(start_ms=7999),
        lambda s: s["citations"][0].update(end_ms=True),
        lambda s: s["citations"][0].update(text="伪造原句"),
        lambda s: s.update(context_utterance_ids=["u000008"]),
        lambda s: s.update(context_utterance_ids=["u000002", "u000007", "u000008", "u000009"]),
    ],
)
def test_claim_source_identity_citations_and_exact_neighbor_closure_validate_before_model(
    tmp_path, mutate
):
    source = answer_source()
    mutate(source)
    with pytest.raises(AnchorSourceError):
        run(tmp_path, [], answer_source=source)


def test_utf16_neighbor_budget_is_whole_sentence_global_order_and_does_not_split_emoji():
    items = utterances()
    items[6]["text"] = "先" * 11000
    items[8]["text"] = "😀" * 600
    source = answer_source(items)
    assert source["context_utterance_ids"] == ["u000007", "u000008"]
    validate_answer_source(source, items, 12000)
    source["context_utterance_ids"].append("u000009")
    with pytest.raises(AnchorSourceError, match="ANCHOR_CONTEXT"):
        validate_answer_source(source, items, 12000)


def test_changed_source_response_tampering_and_unknown_transport_never_spend_again(tmp_path):
    run(tmp_path / "source", [response()])
    changed = answer_source(answer_hash="e" * 64)
    with pytest.raises(SegmentationError, match="checkpoint"):
        run(tmp_path / "source", [], answer_source=changed)
    run(tmp_path / "raw", [response()])
    (tmp_path / "raw/anchor-intent/window-0000-attempt-0.json").write_text(
        json.dumps(raw(response(status="NEEDS_REVIEW"))), encoding="utf-8"
    )
    with pytest.raises(SegmentationError, match="changed"):
        run(tmp_path / "raw", [])
    hits = []

    def timeout(request):
        hits.append(request)
        raise httpx.ReadTimeout("synthetic timeout", request=request)

    path = tmp_path / "unknown"
    with httpx.Client(transport=httpx.MockTransport(timeout)) as client:
        with pytest.raises(SegmentationError):
            build_anchor_clip_plan(**arguments(path, client=client))
        with pytest.raises(SegmentationError) as failure:
            build_anchor_clip_plan(**arguments(path, client=client))
    assert failure.value.code == "CALL_STATE_UNCERTAIN" and len(hits) == 1


def test_budget_and_cancellation_keep_paid_work_without_publishing(tmp_path):
    with pytest.raises(SegmentationError) as failure:
        run(tmp_path / "budget", [response(parts=[])], max_calls=1)
    assert failure.value.code == "BUDGET_EXHAUSTED"
    cancelled = False

    def provider(_):
        nonlocal cancelled
        cancelled = True
        return httpx.Response(200, json=raw(response()))

    def guard():
        if cancelled:
            raise WorkerError("WORKER_STOPPED")

    with httpx.Client(transport=httpx.MockTransport(provider)) as client:
        with pytest.raises(WorkerError, match="WORKER_STOPPED"):
            build_anchor_clip_plan(**arguments(tmp_path / "cancel", client=client, guard=guard))
    assert not (tmp_path / "cancel/clip-plan.json").exists()


def anchor_context(tmp_path, maximum=2):
    ctx = context(tmp_path, "ANCHOR_CLIP")
    ctx.lease = replace(ctx.lease, job_id=JOB, video_id=VIDEO)
    ctx.directory = tmp_path / ctx.lease.attempt_key
    ctx.directory.mkdir(parents=True, exist_ok=True)
    predecessor(ctx, "MEDIA", "media_info", {"duration_ms": 12000})
    _, transcript = predecessor(ctx, "ASR", "transcript", utterances())
    frozen = answer_source(source_hash=ctx.lease.source_hash, transcript_hash=sha256(transcript))
    ctx.lease.config.update(
        text_model="qwen-plus",
        max_text_calls=maximum,
        max_asr_submissions=0,
        clip_request={
            "request_id": REQUEST,
            "source_job_id": JOB,
            "instruction": "剪出所选第二处prefix解释。",
            "duration_ms": 12000,
            "max_text_calls": maximum,
            "text_model_id": "qwen-plus",
            "source_transcript_hash": sha256(transcript),
            "source_transcript_key": transcript.relative_to(ctx.storage_root).as_posix(),
            "answer_source": frozen,
        },
    )
    return ctx


def test_real_worker_factory_uses_new_capability_counts_retry_and_never_submits_asr(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("LIVECUT_TEXT_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_TEXT_BASE_URL", "https://synthetic.invalid")
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "dashscope")
    monkeypatch.setenv("LIVECUT_TEXT_RETRY_BACKOFF_SECONDS", "0")
    monkeypatch.setenv("LIVECUT_TEXT_FALLBACKS_JSON", "[]")
    monkeypatch.setenv("LIVECUT_INTENT_EMBEDDING_DIR", "synthetic-path-that-must-not-load")
    ctx = anchor_context(tmp_path)
    hits = []

    def provider(request):
        hits.append(request)
        return (
            httpx.Response(503, json={"error": "synthetic"})
            if len(hits) == 1
            else httpx.Response(200, json=raw(response()))
        )

    monkeypatch.setattr(
        "livecut.text_resilience.DeadlineHTTPTransport", lambda: httpx.MockTransport(provider)
    )

    class API(Ledger):
        def callback(self, *args):
            return {"outcome": "UPDATED"}

    ledger = API(limit=2)
    executor = AnalysisStageExecutor(transport_factory=transport_factory(ledger))
    manifest = executor(ctx)
    assert "ANCHOR_CLIP" in STAGES and manifest["stage_type"] == "ANCHOR_CLIP"
    assert len(hits) == len(ledger.calls) == 2
    assert all(row["kind"] == "TEXT" for row in ledger.calls.values())
    assert {row["kind"] for row in manifest["artifacts"]} == {
        "clip_plan",
        "retrieval_index",
        "retrieval_trace",
        "text_execution_trace",
    }
    assert executor(ctx) == manifest and len(hits) == 2


@pytest.mark.parametrize(
    "mutate",
    [
        lambda ctx: setattr(ctx, "lease", replace(ctx.lease, stage_type="SEGMENT")),
        lambda ctx: ctx.lease.config["clip_request"].pop("answer_source"),
        lambda ctx: ctx.lease.config.update(max_asr_submissions=1),
        lambda ctx: ctx.lease.config.update(max_text_calls=3),
    ],
)
def test_old_segment_worker_or_unbound_anchor_claim_cannot_run_before_transport(tmp_path, mutate):
    ctx = anchor_context(tmp_path)
    mutate(ctx)
    with pytest.raises(WorkerError, match="INVALID_ANCHOR_CLIP_INPUT"):
        AnalysisStageExecutor(transport_factory=lambda *args: pytest.fail("No provider"))(ctx)


def test_anchor_text_transport_cannot_submit_asr(tmp_path):
    ctx = anchor_context(tmp_path)
    ledger = Ledger()
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
