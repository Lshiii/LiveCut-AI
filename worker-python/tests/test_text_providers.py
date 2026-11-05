"""Synthetic provider compatibility tests; no network or real credentials."""
import json

import httpx
import pytest
from livecut.intent import build_clip_plan
from livecut.model_budget import ModelBudgetError
from livecut.segmentation import SegmentationError, _endpoint, _request, discover_products
from livecut.topics import discover_topics
from test_intent import arguments, demand, plan
from test_model_budget import setup_transport
from test_segmentation import assignment, product, utterance
from test_topics import topic, utterances


def completion(value, *, finish="stop", usage=None):
    return {"id": "synthetic-chat", "choices": [{"index": 0, "finish_reason": finish,
        "message": {"role": "assistant", "content": json.dumps(value, ensure_ascii=False)}}],
        "usage": usage or {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18}}


@pytest.mark.parametrize("base,expected", [
    ("https://api.deepseek.com", "https://api.deepseek.com/chat/completions"),
    ("https://provider.example/v1/", "https://provider.example/v1/chat/completions"),
    ("https://provider.example/v1/chat/completions", "https://provider.example/v1/chat/completions"),
])
def test_explicit_openai_style_preserves_configured_base_path(monkeypatch, base, expected):
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "openai")
    assert _endpoint(base) == expected


@pytest.mark.parametrize("base", ["http://provider.example/v1", "https://secret@provider.example/v1",
    "https://provider.example/v1?secret=value", "https://provider.example/v1#fragment"])
def test_openai_base_rejects_unsafe_urls(monkeypatch, base):
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "openai")
    with pytest.raises(SegmentationError, match="base_url"):
        _endpoint(base)


def test_native_dashscope_is_still_default_and_invalid_style_fails(monkeypatch):
    monkeypatch.delenv("LIVECUT_TEXT_API_STYLE", raising=False)
    assert _endpoint("https://provider.example") == "https://provider.example/api/v1/services/aigc/text-generation/generation"
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "guess")
    with pytest.raises(SegmentationError, match="LIVECUT_TEXT_API_STYLE"):
        _endpoint("https://provider.example")


@pytest.mark.parametrize("host,thinking", [("api.deepseek.com", True), ("provider.example", False),
    ("api.deepseek.com.evil.example", False)])
def test_openai_wire_payload_raw_recording_and_host_specific_thinking(tmp_path, host, thinking):
    seen = []
    raw = completion({"ok": True})
    def provider(request):
        seen.append(json.loads(request.content))
        assert request.headers["authorization"] == "Bearer synthetic-text-key"
        return httpx.Response(200, json=raw)
    with httpx.Client(transport=httpx.MockTransport(provider)) as client:
        result = _request(client, endpoint=f"https://{host}/v1/chat/completions", key="synthetic-text-key",
            model="deepseek-flash", messages=[{"role": "user", "content": "输出JSON"}], directory=tmp_path,
            state={"calls": []}, window_index=0, attempt=0, max_calls=1)
    body = seen[0]
    assert body["messages"] == [{"role": "user", "content": "输出JSON"}]
    assert body["response_format"] == {"type": "json_object"} and body["stream"] is False
    assert "input" not in body and "parameters" not in body
    assert (body.get("thinking") == {"type": "disabled"}) == thinking
    assert result == raw == json.loads((tmp_path / "window-0000-attempt-0.json").read_text(encoding="utf-8"))
    assert "synthetic-text-key" not in (tmp_path / "state.json").read_text(encoding="utf-8")


def test_topics_use_separate_text_key_and_do_not_fallback_to_asr_key(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "openai")
    monkeypatch.setenv("LIVECUT_TEXT_BASE_URL", "https://api.deepseek.com")
    monkeypatch.setenv("DASHSCOPE_API_KEY", "synthetic-asr-only-key")
    monkeypatch.delenv("LIVECUT_TEXT_API_KEY", raising=False)
    seen = []
    def provider(request):
        seen.append(request)
        return httpx.Response(200, json=completion({"topics": [topic("u0"), topic("u2", title="缓存击穿")]}))
    with httpx.Client(transport=httpx.MockTransport(provider)) as client:
        options = {"client": client, "model": "deepseek-flash", "job_id": "j", "video_id": "v"}
        with pytest.raises(SegmentationError) as missing:
            discover_topics(utterances(), 4000, tmp_path, **options)
        assert missing.value.code == "MISSING_API_KEY" and not seen
        monkeypatch.setenv("LIVECUT_TEXT_API_KEY", "synthetic-text-only-key")
        result = discover_topics(utterances(), 4000, tmp_path, **options)
        assert discover_topics(utterances(), 4000, tmp_path, **options) == result
    assert len(seen) == 1
    assert str(seen[0].url) == "https://api.deepseek.com/chat/completions"
    assert seen[0].headers["authorization"] == "Bearer synthetic-text-only-key"
    assert [(s["start_ms"], s["end_ms"]) for s in result["segments"]] == [(0, 1900), (2000, 3900)]


def test_intent_two_phases_and_product_adapter_accept_unchanged_openai_envelopes(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "openai")
    seen, replies = [], [demand(), plan(), {"products": [product("tmp1", "面膜")],
        "assignments": {"u1": assignment(["tmp1"])}}]
    def provider(request):
        seen.append(json.loads(request.content))
        return httpx.Response(200, json=completion(replies[len(seen) - 1]))
    with httpx.Client(transport=httpx.MockTransport(provider)) as client:
        options = arguments(tmp_path / "intent", model="deepseek-flash", base_url="https://provider.example/v1", client=client)
        result = build_clip_plan(**options)
        assert build_clip_plan(**options) == result
        products = discover_products([utterance("u1", 0, 1000)], 1000, tmp_path / "commerce", client=client,
            api_key="synthetic-text-key", model="deepseek-flash", base_url="https://provider.example/v1")
    assert len(seen) == 3 and len(result["outputs"]) == 2
    assert all(not output["enabled"] for output in result["outputs"])
    assert products["products"][0]["name"] == "面膜"
    assert all("messages" in body and "input" not in body for body in seen)


@pytest.mark.parametrize("status,raw", [(401, {"error": {"message": "synthetic unauthorized"}}),
    (429, {"error": {"message": "synthetic rate limit"}}), (500, {"error": {"message": "synthetic failure"}}),
    (200, {"error": {"message": "synthetic envelope failure"}})])
def test_openai_provider_errors_are_recorded_without_format_repair_or_resend(tmp_path, monkeypatch, status, raw):
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "openai")
    seen = []
    def provider(request):
        seen.append(request)
        return httpx.Response(status, json=raw)
    with httpx.Client(transport=httpx.MockTransport(provider)) as client:
        for _ in range(2):
            with pytest.raises(SegmentationError) as error:
                discover_topics(utterances(), 4000, tmp_path, client=client, api_key="synthetic-text-key",
                    base_url="https://provider.example/v1", model="deepseek-flash", job_id="j", video_id="v")
            assert error.value.code == "PROVIDER_ERROR"
    assert len(seen) == 1


def test_openai_timeout_is_unknown_and_cannot_spend_twice(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "openai")
    bodies = []
    def provider(request):
        bodies.append(json.loads(request.content))
        raise httpx.ReadTimeout("synthetic unknown", request=request)
    client, api, events, _ = setup_transport(tmp_path, provider)
    with client:
        for expected in ("PROVIDER_ERROR", "CALL_STATE_UNCERTAIN"):
            with pytest.raises(SegmentationError) as failure:
                discover_topics(utterances(), 4000, tmp_path / "topics", client=client, api_key="synthetic-text-key",
                    base_url="https://provider.example/v1", model="deepseek-flash", job_id="j", video_id="v")
            assert failure.value.code == expected
        assert api.status == "UNKNOWN_OUTCOME"
        with pytest.raises(ModelBudgetError, match="MODEL_CALL_REQUIRES_RECOVERY"):
            client.post("https://provider.example/v1/chat/completions", json=bodies[0])
    assert events.count("provider") == 1


@pytest.mark.parametrize("finish,content", [("length", '{"topics":['), ("stop", ""),
    ("content_filter", ""), ("tool_calls", "")])
def test_openai_incomplete_content_gets_only_bounded_structure_repair(tmp_path, monkeypatch, finish, content):
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "openai")
    malformed = {"choices": [{"finish_reason": finish, "message": {"content": content}}]}
    valid = completion({"topics": [topic("u0")]})
    seen = []
    def provider(request):
        seen.append(json.loads(request.content))
        return httpx.Response(200, json=malformed if len(seen) == 1 else valid)
    with httpx.Client(transport=httpx.MockTransport(provider)) as client:
        result = discover_topics(utterances(), 4000, tmp_path, client=client, api_key="synthetic-text-key",
            base_url="https://provider.example/v1", model="deepseek-flash", job_id="j", video_id="v", max_calls=2)
    assert len(seen) == 2 and len(result["segments"]) == 1
    assert len(seen[1]["messages"]) == len(seen[0]["messages"]) + 1


def test_openai_non_json_response_is_not_repaired_or_resent(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "openai")
    client, api, events, _ = setup_transport(tmp_path, lambda request: httpx.Response(200, text="synthetic upstream failure"))
    with client:
        for _ in range(2):
            with pytest.raises(SegmentationError) as failure:
                discover_topics(utterances(), 4000, tmp_path / "topics", client=client, api_key="synthetic-text-key",
                    base_url="https://provider.example/v1", model="deepseek-flash", job_id="j", video_id="v")
            assert failure.value.code == "PROVIDER_ERROR"
    assert events.count("provider") == 1 and api.status == "UNKNOWN_OUTCOME"


def test_openai_text_request_cannot_run_in_asr_stage(tmp_path):
    client, _, events, _ = setup_transport(tmp_path, lambda request: httpx.Response(200, json={}), stage_type="ASR")
    with client, pytest.raises(ModelBudgetError, match="MODEL_OPERATION_NOT_ALLOWED"):
        client.post("https://provider.example/v1/chat/completions", json={"model": "deepseek-flash"})
    assert events == []


def test_openai_budget_records_usage_and_replays_without_provider(tmp_path):
    raw = completion({"ok": True}, usage={"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18,
        "prompt_cache_hit_tokens": 4, "prompt_tokens_details": {"cached_tokens": 4}})
    client, api, events, _ = setup_transport(tmp_path, lambda request: httpx.Response(200, json=raw))
    updates = []
    original = api.update_call
    def update(request_id, payload):
        updates.append(payload)
        return original(request_id, payload)
    api.update_call = update
    with client:
        body = {"model": "deepseek-flash", "messages": [{"role": "user", "content": "JSON"}]}
        for _ in range(2):
            assert client.post("https://provider.example/v1/chat/completions", json=body).json() == raw
    assert events.count("provider") == 1 and api.status == "SUCCEEDED"
    assert api.reservations[0]["kind"] == "TEXT"
    assert updates[-1]["usage"] == {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18, "prompt_cache_hit_tokens": 4}


@pytest.mark.parametrize("status,expected", [(401, "FAILED"), (500, "UNKNOWN_OUTCOME"), (200, "UNKNOWN_OUTCOME")])
def test_openai_error_envelope_never_records_success_or_reissues(tmp_path, status, expected):
    client, api, events, _ = setup_transport(tmp_path, lambda request: httpx.Response(status,
        json={"error": {"message": "synthetic"}}))
    with client:
        body = {"model": "deepseek-flash", "messages": []}
        client.post("https://provider.example/v1/chat/completions", json=body)
        assert api.status == expected
        with pytest.raises(ModelBudgetError, match="MODEL_CALL_REQUIRES_RECOVERY"):
            client.post("https://provider.example/v1/chat/completions", json=body)
    assert events.count("provider") == 1
