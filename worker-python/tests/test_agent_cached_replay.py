"""Authenticated synthetic responses plus real FFmpeg; no real supplier account."""
import base64
import copy
import hashlib
import json
import threading
from dataclasses import replace
from pathlib import Path
from uuid import uuid4

import httpx
import pytest
from jsonschema import Draft202012Validator
from livecut.agent_cached_replay import PROFILE, _canonical
from livecut.agent_evidence import FOCUS_SYSTEM
from livecut.agent_speech_lookup import SPEECH_SYSTEM
from livecut.agent_visual import build_visual_answer
from livecut.segmentation import SegmentationError, _json, _provider_request_body
from livecut.video_agent import AgentOutputError
from livecut.vision import _extract_frame
from livecut.worker import (
    AnalysisStageExecutor,
    LeaseGuard,
    WorkerError,
    _native_path,
    _write,
    sha256,
)
from test_agent_evidence import block, reply
from test_agent_visual import real_source as _real_source
from test_agent_visual_worker import visual_context

real_source = _real_source


def read(path):
    return json.loads(_native_path(path).read_text("utf-8"))


def snapshot(directory):
    root = _native_path(directory)
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob("*") if p.is_file()}


def prepare(tmp_path, source, monkeypatch, *, focus=True):
    ctx = visual_context(tmp_path, source)
    original = copy.deepcopy(ctx.lease.config)
    original["agent_turn"].update(turn_id=str(uuid4()), session_id=str(uuid4()), evidence_mode="structured", evidence_policy="speech_lookup_v1")
    if focus:
        original["agent_turn"]["focus_timestamp_ms"] = 0
    stage, token = str(uuid4()), str(uuid4())
    source_root = ctx.storage_root / f"jobs/{ctx.lease.job_id}/stages/{stage}/attempts/1/{token}"
    _native_path(source_root).mkdir(parents=True)
    transcript = read(ctx.storage_root / original["agent_turn"]["source_transcript_key"])
    analysis = read(ctx.storage_root / original["agent_turn"]["source_analysis_key"])
    response = reply(block("SPEECH", speech=[transcript[0]["utterance_id"]]))
    response.pop("suggested_clip_instruction")
    hits = []
    def provider(request):
        hits.append(request.content)
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(response)}}]})
    options = dict(session_id=original["agent_turn"]["session_id"], turn_id=original["agent_turn"]["turn_id"],
        job_id=ctx.lease.job_id, video_id=ctx.lease.video_id, source_hash=ctx.lease.source_hash,
        transcript_hash=original["agent_turn"]["source_transcript_hash"], model=original["text_model"],
        max_calls=original["max_text_calls"], max_request_attempts=1, max_tool_steps=4, api_key="synthetic-key",
        base_url="https://synthetic.invalid/v1", budget_remaining=lambda: 4-len(hits), evidence_mode="structured",
        evidence_policy="speech_lookup_v1", source=_native_path(ctx.storage_root / ctx.lease.source_key), history=[])
    if focus:
        options["focus_timestamp_ms"] = 0
    # Reproduce the old, strictly missing-hint failure, keeping its raw bytes.
    with monkeypatch.context() as old_decoder:
        old_decoder.setattr("livecut.agent_speech_lookup.normalize_speech_answer_decision", lambda value: value)
        with httpx.Client(transport=httpx.MockTransport(provider)) as client, pytest.raises(AgentOutputError):
            build_visual_answer(transcript, analysis["segments"], original["agent_turn"]["duration_ms"],
                original["agent_turn"]["question"], _native_path(source_root), client=client, **options)
    assert len(hits) == 2
    _write(source_root / "worker-error.json", {"error_code": "INVALID_AGENT_OUTPUT"})
    folder = source_root / "agent-evidence-speech"
    state = read(folder / "state.json")
    calls = []
    for record in state["calls"]:
        value = read(folder / record["response_file"])
        url = httpx.URL(options["base_url"]+"/chat/completions")
        identity = {"endpoint": {"scheme": url.scheme, "host": url.host, "port": url.port, "path": url.path},
                    "body": json.loads(hits[record["attempt"]])}
        digest = hashlib.sha256(_canonical(identity)).hexdigest()
        operation = "text-"+digest
        request = hashlib.sha256((stage+":"+operation).encode()).hexdigest()
        wrapper = source_root / "provider-responses" / (request+".json")
        _write(wrapper, {"http_status": 200, "body": value, "body_is_json": True, "headers": {}})
        calls.append({"request_id": request, "operation_key": operation, "input_hash": digest,
            "model_id": original["text_model"], "kind": "TEXT", "status": "SUCCEEDED", "origin_attempt": 1,
            "dispatch_attempt": 1, "response_key": wrapper.relative_to(ctx.storage_root).as_posix(),
            "response_hash": sha256(wrapper), "window_index": 0, "repair_attempt": record["attempt"]})
    kinds = {"state.json": "state", "request-plans.json": "request_plans", "speech-observation.json": "speech_observation",
             "focus-observation.json": "focus_observation", "visual-frame-cache.json": "frame_cache"}
    files = []
    for file in _native_path(source_root).rglob("*"):
        if not file.is_file() or file.name == ".discovery.lock" or file.name == "worker-error.json" or "indexes" in file.parts:
            continue
        kind = kinds.get(file.name, "window" if file.name.startswith("window-") else "frame" if file.suffix == ".jpg" else "provider_response")
        logical = source_root / file.relative_to(_native_path(source_root))
        files.append({"kind": kind, "key": logical.relative_to(ctx.storage_root).as_posix(), "sha256": sha256(logical), "bytes": file.stat().st_size})
    descriptor = {"schema_version": 1, "decoder_profile": PROFILE, "source_turn_id": original["agent_turn"]["turn_id"],
        "source_session_id": original["agent_turn"]["session_id"], "source_stage_id": stage, "source_attempt": 1,
        "source_run_token": token, "source_request_hash": "a"*64, "source_input_hash": "b"*64,
        "source_config_hash": state["config_hash"], "original_max_text_calls": original["max_text_calls"],
        "original_max_request_attempts": 1, "endpoint": "https://synthetic.invalid/v1/chat/completions", "files": files, "calls": calls}
    config = copy.deepcopy(original)
    config["max_text_calls"] = 0
    config["agent_turn"].update(turn_id=str(uuid4()), cache_replay=descriptor,
        replay_source_turn_id=descriptor["source_turn_id"], replay_snapshot_hash=hashlib.sha256(_canonical(descriptor)).hexdigest())
    ctx.lease = replace(ctx.lease, stage_id=str(uuid4()), run_token=str(uuid4()), stage_type="AGENT_EVIDENCE",
                        config=config, required_worker_protocol=3)
    ctx.directory = ctx.storage_root / ctx.lease.attempt_key
    _native_path(ctx.directory).mkdir(parents=True)
    ctx.guard = LeaseGuard(ctx.lease, threading.Event())
    ctx._synthetic_paid_requests = [json.loads(body) for body in hits]
    return ctx, source_root


def refresh(ctx):
    turn = ctx.lease.config["agent_turn"]
    descriptor = turn["cache_replay"]
    for item in descriptor["files"]:
        file = ctx.storage_root / item["key"]
        item.update(sha256=sha256(file), bytes=_native_path(file).stat().st_size)
    turn["replay_snapshot_hash"] = hashlib.sha256(_canonical(descriptor)).hexdigest()


@pytest.mark.parametrize("focus", [False, True])
def test_failed_first_answer_replays_without_keys_transport_or_original_mutation(tmp_path, real_source, monkeypatch, focus):
    ctx, original = prepare(tmp_path, real_source, monkeypatch, focus=focus)
    before = snapshot(original)
    monkeypatch.setattr("livecut.agent_frames._extract_frame", lambda *a, **k: pytest.fail("Cached replay must not extract another image"))
    environment = type(__import__("os").environ)
    original_get = environment.get
    def guarded(env, key, *args):
        assert key not in {"LIVECUT_AGENT_VISUAL_API_KEY", "LIVECUT_TEXT_API_KEY", "DASHSCOPE_API_KEY"}
        return original_get(env, key, *args)
    monkeypatch.setattr(environment, "get", guarded)
    executor = AnalysisStageExecutor(transport_factory=lambda *a: pytest.fail("No budget or resilient transport"))
    manifest = executor(ctx)
    assert executor(ctx) == manifest
    assert snapshot(original) == before
    assert not list(ctx.storage_root.glob("cached-answer-replay-*"))
    result = read(ctx.directory / "agent-answer.json")
    proof = read(ctx.directory / "cached-answer-replay.json")
    schema = json.loads((Path(__file__).resolve().parents[2] / "contracts/agent-cached-answer-replay.schema.json").read_text("utf-8"))
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema).validate(proof)
    assert result["turn_id"] == ctx.lease.config["agent_turn"]["turn_id"] and result["schema_version"] == "1.4"
    assert proof["provider_requests"] == 0 and proof["rebound_fields"] == ["turn_id"]
    assert result["suggested_clip_instruction"] is None
    assert {f["kind"] for f in manifest["artifacts"]} == {"agent_answer", "agent_cached_answer_replay"} | ({"agent_visual_frame"} if focus else set())
    assert all(f["key"].startswith(ctx.lease.attempt_key+"/") for f in manifest["artifacts"])


@pytest.mark.parametrize("change", ["unknown", "missing", "wrapper", "question", "endpoint", "state", "snapshot", "model", "budget", "tool",
    "first_invalid", "plan", "source", "media", "missing_record", "unsafe_path", "wrapper_type"])
def test_invalid_cache_or_identity_is_rejected_without_model_fallback(tmp_path, real_source, monkeypatch, change):
    ctx, original = prepare(tmp_path, real_source, monkeypatch)
    turn = ctx.lease.config["agent_turn"]
    descriptor = turn["cache_replay"]
    if change == "unknown":
        descriptor["calls"][0]["status"] = "UNKNOWN_OUTCOME"
    elif change == "missing":
        _native_path(original / "agent-evidence-speech/window-0000-attempt-0.json").unlink()
    elif change == "wrapper":
        path = ctx.storage_root / descriptor["calls"][0]["response_key"]
        wrapper = read(path)
        wrapper["body"]["changed"] = True
        _write(path, wrapper)
        descriptor["calls"][0]["response_hash"] = sha256(path)
        refresh(ctx)
    elif change == "question":
        turn["question"] += "changed"
    elif change == "endpoint":
        descriptor["endpoint"] = "https://another.invalid/chat/completions"
    elif change == "state":
        path = original / "agent-evidence-speech/state.json"
        value = read(path)
        value["calls"][0]["status"] = "uncertain"
        _write(path, value)
        refresh(ctx)
    elif change == "snapshot":
        turn["replay_snapshot_hash"] = "f"*64
    elif change == "model":
        ctx.lease.config["text_model"] = "different-model"
    elif change == "budget":
        ctx.lease.config["max_text_calls"] = 1
    elif change == "plan":
        path = original / "agent-evidence-speech/request-plans.json"
        value = read(path)
        value["requests"][0]["payload_sha256"] = "f"*64
        _write(path, value)
        refresh(ctx)
    elif change == "source":
        _native_path(ctx.storage_root / ctx.lease.source_key).write_bytes(b"different source")
    elif change == "media":
        _native_path(ctx.predecessor("MEDIA", "media_info")).write_bytes(b"different media metadata")
    elif change == "missing_record":
        descriptor["calls"] = []
    elif change == "unsafe_path":
        descriptor["files"][0]["key"] = "../outside.json"
    elif change in {"tool", "first_invalid", "wrapper_type"}:
        window = original / "agent-evidence-speech/window-0000-attempt-0.json"
        raw = read(window)
        if change == "tool":
            value = {"type": "tool", "tool_name": "inspect_frames", "arguments": {"timestamps_ms": [0]}}
        else:
            value = json.loads(raw["choices"][0]["message"]["content"])
            value["answer_blocks"][0]["utterance_ids"] = ["not-in-source"]
        if change == "wrapper_type":
            raw["marker"] = 0
        else:
            raw["choices"][0]["message"]["content"] = json.dumps(value)
        _write(window, raw)
        wrapper_path = ctx.storage_root / descriptor["calls"][0]["response_key"]
        wrapper = read(wrapper_path)
        wrapper["body"] = raw
        if change == "wrapper_type":
            wrapper["body"]["marker"] = False
        _write(wrapper_path, wrapper)
        descriptor["calls"][0]["response_hash"] = sha256(wrapper_path)
        state_path = original / "agent-evidence-speech/state.json"
        state = read(state_path)
        state["calls"][0]["response_sha256"] = sha256(window)
        _write(state_path, state)
        refresh(ctx)
    if change not in {"missing", "snapshot"}:
        turn["replay_snapshot_hash"] = hashlib.sha256(_canonical(descriptor)).hexdigest()
    before = snapshot(original)
    monkeypatch.setattr("httpx.Client.send", lambda *a, **k: pytest.fail("No HTTP, including fallback or repair"))
    with pytest.raises((WorkerError, SegmentationError)):
        AnalysisStageExecutor(transport_factory=lambda *a: pytest.fail("No transport factory"))(ctx)
    assert snapshot(original) == before and not list(ctx.storage_root.glob("cached-answer-replay-*"))
    assert not (ctx.directory / "agent-answer.json").exists()


def test_source_cache_link_is_rejected_before_copy_or_http(tmp_path, real_source, monkeypatch):
    ctx, original = prepare(tmp_path, real_source, monkeypatch)
    linked = original / "agent-evidence-speech/window-0000-attempt-0.json"
    outside = ctx.storage_root / "outside-window.json"
    outside.write_bytes(_native_path(linked).read_bytes())
    _native_path(linked).unlink()
    try:
        _native_path(linked).symlink_to(outside)
    except OSError as error:
        if getattr(error, "winerror", None) != 1314:
            raise
        from livecut import agent_cached_replay
        original_confined = agent_cached_replay._confined
        def linked_guard(root, key, **kwargs):
            if key.endswith("window-0000-attempt-0.json"):
                raise WorkerError("ASR_REUSE_UNSAFE_PATH")
            return original_confined(root, key, **kwargs)
        monkeypatch.setattr(agent_cached_replay, "_confined", linked_guard)
    before = outside.read_bytes()
    with pytest.raises(WorkerError):
        AnalysisStageExecutor(transport_factory=lambda *a: pytest.fail("No transport"))(ctx)
    assert outside.read_bytes() == before and not list(ctx.storage_root.glob("cached-answer-replay-*"))


def test_cancellation_cannot_publish_a_replayed_answer(tmp_path, real_source, monkeypatch):
    ctx, original = prepare(tmp_path, real_source, monkeypatch)
    before = snapshot(original)
    stop = threading.Event()
    ctx.guard = LeaseGuard(ctx.lease, stop)
    stop.set()
    with pytest.raises(WorkerError):
        AnalysisStageExecutor(transport_factory=lambda *a: pytest.fail("No transport"))(ctx)
    assert snapshot(original) == before and not (ctx.directory / "agent-answer.json").exists()


def test_source_change_after_cached_decoding_is_rechecked_before_publication(tmp_path, real_source, monkeypatch):
    ctx, original = prepare(tmp_path, real_source, monkeypatch)
    before = snapshot(original)
    from livecut import agent_cached_replay
    build = agent_cached_replay.build_visual_answer
    def changed(*args, **kwargs):
        answer = build(*args, **kwargs)
        _native_path(ctx.storage_root / ctx.lease.source_key).write_bytes(b"changed after decoding")
        return answer
    monkeypatch.setattr(agent_cached_replay, "build_visual_answer", changed)
    with pytest.raises(WorkerError, match="INVALID_CACHED_REPLAY_SOURCE"):
        AnalysisStageExecutor(transport_factory=lambda *a: pytest.fail("No transport"))(ctx)
    assert snapshot(original) == before and not (ctx.directory / "agent-answer.json").exists()
    assert not list(ctx.storage_root.glob("cached-answer-replay-*"))


def test_jointly_forged_jpeg_metadata_and_plan_cannot_change_a_paid_model_input(tmp_path, real_source, monkeypatch):
    ctx, original = prepare(tmp_path, real_source, monkeypatch)
    turn = ctx.lease.config["agent_turn"]
    descriptor = turn["cache_replay"]
    first = next(call for call in descriptor["calls"] if call["repair_attempt"] == 0)
    paid_input_hash = first["input_hash"]
    replacement = ctx.storage_root / "replacement.jpg"
    _extract_frame(real_source, replacement, 1000, max_edge=1280, ffmpeg_path="ffmpeg")
    image = replacement.read_bytes()
    cached_jpeg = original / "visual-frames/f0001.jpg"
    assert image != _native_path(cached_jpeg).read_bytes()
    _native_path(cached_jpeg).write_bytes(image)
    cache_path = original / "visual-frame-cache.json"
    frame_cache = read(cache_path)
    frame_cache["frames"][0].update(bytes=len(image), sha256=hashlib.sha256(image).hexdigest())
    forged_frame = frame_cache["frames"][0]
    _write(cache_path, frame_cache)
    focus_path = original / "agent-evidence-speech/focus-observation.json"
    focus = read(focus_path)
    focus["frame"] = copy.deepcopy(forged_frame)
    _write(focus_path, focus)
    messages = copy.deepcopy(ctx._synthetic_paid_requests[0]["messages"])
    content = messages[1]["content"]
    payload = json.loads(content[0]["text"])
    payload["observations"][0]["result"]["frames"] = [copy.deepcopy(forged_frame)]
    content[0]["text"] = _json(payload)
    next(part for part in content if part["type"] == "image_url")["image_url"]["url"] = "data:image/jpeg;base64,"+base64.b64encode(image).decode("ascii")
    plan_path = original / "agent-evidence-speech/request-plans.json"
    plans = read(plan_path)
    plans["requests"][0]["payload_sha256"] = hashlib.sha256(_json(messages).encode()).hexdigest()
    _write(plan_path, plans)
    refresh(ctx)
    before = snapshot(original)
    reached = []
    from livecut import agent_cached_replay
    build = agent_cached_replay.build_visual_answer
    def inspect(*args, **kwargs):
        verify = kwargs["cached_request_validator"]
        def observed(endpoint, body):
            reached.append(copy.deepcopy(body))
            verify(endpoint, body)
        kwargs["cached_request_validator"] = observed
        return build(*args, **kwargs)
    monkeypatch.setattr(agent_cached_replay, "build_visual_answer", inspect)
    monkeypatch.setattr("httpx.Client.send", lambda *a, **k: pytest.fail("Forgery must cause zero HTTP attempts"))
    with pytest.raises(WorkerError, match="CACHED_REPLAY_INPUT_HASH_MISMATCH"):
        AnalysisStageExecutor(transport_factory=lambda *a: pytest.fail("No model transport"))(ctx)
    assert len(reached) == 1 and reached[0] == _provider_request_body(descriptor["endpoint"], ctx.lease.config["text_model"], messages)
    assert first["input_hash"] == paid_input_hash and snapshot(original) == before
    assert not (ctx.directory / "agent-answer.json").exists() and not list(ctx.storage_root.glob("cached-answer-replay-*"))


@pytest.mark.parametrize("endpoint,thinking", [("https://api.deepseek.com/chat/completions", True),
    ("https://synthetic.invalid/v1/chat/completions", False)])
def test_shared_request_body_keeps_exact_openai_and_deepseek_fields(endpoint, thinking):
    messages = [{"role": "user", "content": "synthetic"}]
    expected = {"model": "synthetic-model", "messages": messages, "stream": False,
                "response_format": {"type": "json_object"}, "temperature": 0}
    if thinking:
        expected["thinking"] = {"type": "disabled"}
    assert _provider_request_body(endpoint, "synthetic-model", messages) == expected


@pytest.mark.parametrize("filename,expected", [("evidence-speech-v1.system.txt", SPEECH_SYSTEM),
                                             ("evidence-focus-v1.system.txt", FOCUS_SYSTEM)])
def test_java_paid_input_prompt_resources_are_exact_python_utf8_bytes(filename, expected):
    path = Path(__file__).resolve().parents[2] / "backend-java/src/main/resources/agent-replay" / filename
    data = path.read_bytes()
    assert data == expected.encode("utf-8") and b"\r" not in data and not data.startswith(b"\xef\xbb\xbf")
