"""Independent synthetic black-box checks; no real provider or quality claims."""
import copy
import json

import httpx
import pytest
from livecut.segmentation import SegmentationError
from livecut.video_agent import AgentOutputError, build_video_answer, validate_agent_answer


def source():
    texts = ["前缀匹配中断会让缓存失效。", "修改历史消息是这里的失效原因。", "把新消息追加到末尾可保留原前缀。",
             "这是第三种情况。", "请关注点赞。", "这里还有一个例子。", "需要保留完整解释。", "现在开始另一个话题。",
             "今天讲的是缓存。", "仍然需要核对原句。", "历史不是新的证据。", "这是收尾。", "这是尚未读取的独立末句。"]
    ids = ["z0", "a1", "c2", "b3", "q4", "p5", "f6", "g7", "h8", "i9", "j10", "k11", "last12"]
    return [{"utterance_id": uid, "start_ms": i * 1000, "end_ms": i * 1000 + 900,
             "text": text, "source_chunk_id": "audio-0"}
            for i, (uid, text) in enumerate(zip(ids, texts, strict=True))]


def topics():
    return [{"segment_id": "topic-1", "title": "缓存失效", "summary": "讨论缓存的原因和解决方法。",
             "start_utterance_id": "z0", "end_utterance_id": "c2"},
            {"segment_id": "topic-2", "title": "后续讨论", "summary": "其他内容。",
             "start_utterance_id": "b3", "end_utterance_id": "last12"}]


def tool(name, **args):
    return {"type": "tool", "tool_name": name, "arguments": args}


def answer(ids=(), *, scope="VIDEO", **extra):
    return {"type": "answer", "answer_scope": scope, "answer": "修改历史会让前缀匹配中断。",
            "citation_ids": list(ids), "suggested_clip_instruction": None, **extra}


def arguments(path, **extra):
    return {"utterances": source(), "topics": topics(), "duration_ms": 13000,
            "question": "视频说缓存为什么失效？", "output_dir": path, "session_id": "session-1",
            "turn_id": "turn-1", "job_id": "job-1", "video_id": "video-1", "source_hash": "a" * 64,
            "transcript_hash": "b" * 64, "api_key": "synthetic-agent-key",
            "base_url": "https://synthetic.invalid", **extra}


def envelope(value):
    return {"output": {"choices": [{"finish_reason": "stop", "message": {
        "content": json.dumps(value, ensure_ascii=False)}}]}}


def run(path, replies, *, repeat=False, **extra):
    requests = []

    def handler(request):
        wire = json.loads(request.content)
        messages = wire.get("messages", wire.get("input", {}).get("messages"))
        requests.append(messages)
        assert len(requests) <= len(replies), "Agent sent an unexpected extra request"
        reply = replies[len(requests) - 1]
        return httpx.Response(200, json=envelope(reply(messages) if callable(reply) else reply))

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = build_video_answer(**arguments(path, client=client, **extra))
        if repeat:
            assert build_video_answer(**arguments(path, client=client, **extra)) == result
    return result, requests


def payload(messages):
    return json.loads(messages[1]["content"])


def test_agent_searches_reads_context_then_answers_and_replays_without_new_calls(tmp_path):
    utterances = source()
    original = copy.deepcopy(utterances)
    result, requests = run(tmp_path, [tool("search_transcript", query="前缀匹配", top_k=1),
                                    tool("read_context", utterance_id="a1", before=1, after=1),
                                    answer(["a1", "z0"])], repeat=True, utterances=utterances)
    assert len(requests) == 3
    assert utterances == original
    assert result["provenance"]["retrieval_mode"] == "bm25"
    assert [call["tool_name"] for call in result["tool_calls"]] == ["search_transcript", "read_context"]
    assert result["tool_calls"][1]["result_utterance_ids"] == ["z0", "a1", "c2"]
    assert [citation["utterance_id"] for citation in result["citations"]] == ["z0", "a1"]
    assert result["citations"][0] == {k: utterances[0][k] for k in ("utterance_id", "start_ms", "end_ms", "text")}
    observed = payload(requests[-1])["observations"]
    assert observed[0]["result"]["utterances"]
    assert observed[1]["result"]["utterances"] == utterances[:3]
    assert validate_agent_answer(result, utterances) == result


@pytest.mark.parametrize("scope", ["GENERAL", "INSUFFICIENT"])
def test_general_and_insufficient_answers_need_no_invented_video_evidence(tmp_path, scope):
    result, requests = run(tmp_path, [answer(scope=scope)])
    assert len(requests) == 1 and result["answer_scope"] == scope
    assert result["citations"] == result["tool_calls"] == []
    assert result["provenance"]["retrieval_mode"] == "none"


def test_successful_history_is_given_to_model_but_is_not_current_turn_evidence(tmp_path):
    history = [{"question": "前一个问题", "answer": "前一个答案提到 last12，但本轮尚未读取。"}]
    result, requests = run(tmp_path, [tool("read_context", utterance_id="a1", before=0, after=0),
                                    answer(["last12"]), answer(["a1"])], history=history)
    assert payload(requests[0])["conversation"] == history
    assert json.loads(requests[-1][-1]["content"])["validation_rule"] == "ANSWER_EVIDENCE"
    assert [c["utterance_id"] for c in result["citations"]] == ["a1"]


def test_topic_tool_paginates_and_returns_actual_boundary_sentences(tmp_path):
    result, requests = run(tmp_path, [tool("list_topics", offset=1, limit=1), answer(["last12", "b3"])])
    observation = payload(requests[1])["observations"][0]["result"]
    assert observation["total_topics"] == 2
    assert observation["topics"] == topics()[1:]
    assert [u["utterance_id"] for u in observation["utterances"]] == ["b3", "last12"]
    assert [c["utterance_id"] for c in result["citations"]] == ["b3", "last12"]
    assert result["provenance"]["retrieval_mode"] == "none"


@pytest.mark.parametrize("bad", [
    tool("shell", command="whoami"),
    tool("search_transcript", query="缓存", top_k=9),
    tool("search_transcript", query="缓存", top_k=True),
    tool("search_transcript", query="x" * 301, top_k=1),
    tool("search_transcript", query="缓存", top_k=1, file_path="secret"),
    tool("read_context", utterance_id="a1", before=4, after=0),
    tool("read_context", utterance_id="foreign", before=0, after=0),
    tool("list_topics", offset=-1, limit=1),
    tool("list_topics", offset=0, limit=13),
    {"type": "tool", "tool_name": [], "arguments": {}},
    answer(scope=[]),
    answer(scope="GENERAL", answer=[]),
    answer(scope="GENERAL", citation_ids={}),
    answer(scope="GENERAL", unexpected="field"),
])
def test_unsafe_or_invalid_model_action_gets_one_repair_then_stops(tmp_path, bad):
    with pytest.raises(AgentOutputError) as failure:
        run(tmp_path, [bad, bad])
    assert failure.value.code == "INVALID_AGENT_OUTPUT"
    state = json.loads((tmp_path / "agent/state.json").read_text(encoding="utf-8"))
    assert len(state["calls"]) == 2
    assert not (tmp_path / "agent-answer.json").exists()


@pytest.mark.parametrize("ids", [["foreign"], ["last12"], ["a1", "a1"], []])
def test_video_answer_cannot_cite_unknown_unseen_duplicate_or_empty_evidence(tmp_path, ids):
    with pytest.raises(AgentOutputError) as failure:
        run(tmp_path, [tool("read_context", utterance_id="a1", before=0, after=0), answer(ids), answer(ids)])
    assert failure.value.rule == "ANSWER_EVIDENCE"
    assert not (tmp_path / "agent-answer.json").exists()


def test_duplicate_tool_is_reported_as_observation_without_repeating_source_results(tmp_path):
    duplicate = tool("read_context", utterance_id="a1", before=0, after=0)
    result, requests = run(tmp_path, [duplicate, duplicate, answer(["a1"])])
    assert payload(requests[-1])["observations"][1]["result"]["error"] == "DUPLICATE_TOOL_CALL"
    assert result["tool_calls"][1]["result_utterance_ids"] == []
    assert len(result["citations"]) == 1


def test_tool_loop_cannot_exceed_four_steps_even_if_model_keeps_requesting(tmp_path):
    replies = [tool("list_topics", offset=i, limit=1) for i in range(4)]
    replies += [tool("read_context", utterance_id="a1", before=0, after=0)] * 2
    with pytest.raises(AgentOutputError) as failure:
        run(tmp_path, replies)
    assert failure.value.rule == "TOOL_LIMIT"
    state = json.loads((tmp_path / "agent/state.json").read_text(encoding="utf-8"))
    assert len(state["calls"]) == 6
    assert not (tmp_path / "agent-answer.json").exists()


def test_repair_does_not_create_extra_request_budget(tmp_path):
    with pytest.raises(SegmentationError) as failure:
        run(tmp_path, [tool("shell", command="whoami")], max_calls=1)
    assert failure.value.code == "BUDGET_EXHAUSTED"
    assert len(json.loads((tmp_path / "agent/state.json").read_text(encoding="utf-8"))["calls"]) == 1


def test_tampered_cached_response_and_changed_question_do_not_dispatch_again(tmp_path):
    run(tmp_path, [answer(scope="GENERAL")])
    response = tmp_path / "agent/window-0000-attempt-0.json"
    response.write_text(json.dumps(envelope(answer(scope="INSUFFICIENT"))), encoding="utf-8")
    with pytest.raises(SegmentationError) as changed:
        run(tmp_path, [])
    assert changed.value.code == "INVALID_AGENT_CHECKPOINT"
    with pytest.raises(SegmentationError) as different:
        run(tmp_path, [], question="另一问题")
    assert different.value.code == "INVALID_AGENT_CHECKPOINT"


def test_missing_response_digest_is_not_trusted_without_ledger_transport(tmp_path):
    run(tmp_path, [answer(scope="GENERAL")])
    path = tmp_path / "agent/state.json"
    state = json.loads(path.read_text(encoding="utf-8"))
    del state["calls"][0]["response_sha256"]
    path.write_text(json.dumps(state), encoding="utf-8")
    with pytest.raises(SegmentationError) as failure:
        run(tmp_path, [])
    assert failure.value.code == "INVALID_AGENT_CHECKPOINT"


def test_cancel_guard_interrupts_after_tool_response_before_next_model_request(tmp_path):
    class Cancelled(Exception):
        pass

    cancelled = False

    def reply(_):
        nonlocal cancelled
        cancelled = True
        return tool("read_context", utterance_id="a1", before=0, after=0)

    def guard():
        if cancelled:
            raise Cancelled

    with pytest.raises(Cancelled):
        run(tmp_path, [reply], guard=guard)
    assert not (tmp_path / "agent-answer.json").exists()


@pytest.mark.parametrize("mutate", [
    lambda r: r["citations"][0].update(text="伪造原句"),
    lambda r: r["citations"][0].update(start_ms=999),
    lambda r: r["citations"][0].update(start_ms=True),
    lambda r: r["tool_calls"][0].update(step=2),
    lambda r: r["tool_calls"][0].update(result_utterance_ids=["a1", "z0"]),
    lambda r: r.update(source_hash="invalid"),
    lambda r: r["provenance"].update(retrieval_mode="bm25_dense_rrf"),
])
def test_published_result_validation_rejects_forged_sources_and_trace(mutate, tmp_path):
    result, _ = run(tmp_path, [tool("read_context", utterance_id="a1", before=1, after=0), answer(["a1"])])
    mutate(result)
    with pytest.raises(AgentOutputError):
        validate_agent_answer(result, source())


def test_configured_dense_search_records_actual_fused_mode(tmp_path):
    def embed(texts):
        return [[1.0, 0.0] if "前缀" in text else [0.0, 1.0] for text in texts]

    result, _ = run(tmp_path, [tool("search_transcript", query="前缀", top_k=1), answer(["z0"])],
                    dense_embedder=embed, embedding_model_id="synthetic-encoder-v1")
    assert result["provenance"]["retrieval_mode"] == "bm25_dense_rrf"


def test_clip_suggestion_is_only_data_and_does_not_execute_a_write(tmp_path):
    instruction = "保留缓存失效原因的完整解释，跳过关注引导。"
    result, _ = run(tmp_path, [answer(scope="GENERAL", suggested_clip_instruction=instruction)])
    assert result["suggested_clip_instruction"] == instruction
    assert not list(tmp_path.rglob("*.mp4"))


def test_large_context_keeps_whole_sentences_and_bounds_all_observations(tmp_path):
    utterances = [{"utterance_id": f"u{i}", "start_ms": i * 1000, "end_ms": i * 1000 + 900,
                   "text": str(i) + "长" * (4000 - len(str(i))), "source_chunk_id": "audio-0"}
                  for i in range(40)]
    long_topics = [{"segment_id": "topic-1", "title": "长视频", "summary": "概述",
                    "start_utterance_id": "u0", "end_utterance_id": "u39"}]
    replies = [tool("read_context", utterance_id=f"u{i}", before=3, after=3) for i in (3, 10, 17, 24)]
    result, requests = run(tmp_path, [*replies, answer(["u0"])], utterances=utterances,
                           topics=long_topics, duration_ms=40000)
    observations = payload(requests[-1])["observations"]
    assert len(observations) == 4
    assert sum(len(json.dumps(o["result"], ensure_ascii=False)) for o in observations) < 36500
    actual = {u["utterance_id"]: u for u in utterances}
    for observation in observations:
        assert observation["result"]["context_truncated"] is True
        for utterance in observation["result"]["utterances"]:
            assert utterance == actual[utterance["utterance_id"]]
    assert result["citations"][0]["text"] == utterances[0]["text"]


def test_empty_transcript_search_stays_explicitly_insufficient(tmp_path):
    result, requests = run(tmp_path, [tool("search_transcript", query="缓存", top_k=1),
                                    answer(scope="INSUFFICIENT")], utterances=[], topics=[])
    assert payload(requests[-1])["observations"][0]["result"]["utterances"] == []
    assert result["citations"] == []


@pytest.mark.parametrize("extra", [
    {"question": "x" * 2001},
    {"history": [{"question": "q", "answer": "a"}] * 7},
    {"history": [{"question": "q", "answer": "a" * 6000}] * 3},
    {"max_tool_steps": 0},
    {"max_tool_steps": True},
    {"max_calls": 0},
    {"max_calls": 25},
])
def test_invalid_input_and_history_bounds_fail_before_provider_request(tmp_path, extra):
    with pytest.raises(SegmentationError) as failure:
        run(tmp_path, [], **extra)
    assert failure.value.code == "INVALID_AGENT_INPUT"
