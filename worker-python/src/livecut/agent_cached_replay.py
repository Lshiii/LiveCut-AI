"""Protocol-3 replay of an authenticated first answer, with no model transport."""
from __future__ import annotations

import copy
import hashlib
import json
import re
import tempfile
from pathlib import Path

import httpx

from .agent_speech_lookup import POLICY
from .agent_visual import _decision, build_visual_answer, validate_visual_answer
from .asr_reuse import _confined
from .worker import WorkerError, _native_path, _write, sha256

PROFILE = "speech-optional-hint-v1"
DESCRIPTOR_FIELDS = {"schema_version", "decoder_profile", "source_turn_id", "source_session_id", "source_stage_id",
    "source_attempt", "source_run_token", "source_request_hash", "source_input_hash", "source_config_hash",
    "original_max_text_calls", "original_max_request_attempts", "endpoint", "files", "calls"}
CALL_FIELDS = {"request_id", "operation_key", "input_hash", "model_id", "kind", "status", "origin_attempt",
               "dispatch_attempt", "response_key", "response_hash", "window_index", "repair_attempt"}
KINDS = {"state", "request_plans", "speech_observation", "focus_observation", "window", "provider_response",
         "frame_cache", "frame", "trace", "retry_policy"}
MULTIPLE = {"window", "provider_response", "frame", "retry_policy"}


def _fail():
    raise WorkerError("INVALID_CACHED_REPLAY_SOURCE")


def _canonical(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")).encode()


def _pairs(values):
    result = {}
    for key, value in values:
        if key in result:
            _fail()
        result[key] = value
    return result


def _object(data):
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=_pairs,
                          parse_constant=lambda _: _fail())
    except (ValueError, UnicodeError, RecursionError):
        _fail()


def _hash(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{64}", value):
        _fail()
    return value


def _uuid(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", value):
        _fail()
    return value


class DenyProviderTransport(httpx.BaseTransport):
    def handle_request(self, request):
        raise WorkerError("CACHED_REPLAY_NETWORK_FORBIDDEN")


def _snapshot(context):
    """Consume only the server-frozen descriptor; never discover another attempt."""
    lease, turn = context.lease, context.lease.config.get("agent_turn", {})
    descriptor = turn.get("cache_replay")
    if (lease.stage_type != "AGENT_EVIDENCE" or lease.required_worker_protocol != 3
            or type(lease.config.get("max_text_calls")) is not int or lease.config["max_text_calls"] != 0
            or type(lease.config.get("max_asr_submissions")) is not int or lease.config["max_asr_submissions"] != 0
            or turn.get("evidence_policy") != POLICY or turn.get("evidence_mode") != "structured"
            or turn.get("visual_mode") != "frames" or not isinstance(descriptor, dict)
            or set(descriptor) != DESCRIPTOR_FIELDS or descriptor["schema_version"] != 1
            or type(descriptor["schema_version"]) is not int or descriptor["decoder_profile"] != PROFILE):
        _fail()
    if (turn.get("replay_source_turn_id") != _uuid(descriptor["source_turn_id"])
            or turn.get("session_id") != _uuid(descriptor["source_session_id"])
            or turn.get("turn_id") == descriptor["source_turn_id"]
            or descriptor["source_attempt"] != 1 or type(descriptor["source_attempt"]) is not int
            or type(descriptor["original_max_text_calls"]) is not int or not 1 <= descriptor["original_max_text_calls"] <= 24
            or type(descriptor["original_max_request_attempts"]) is not int or not 1 <= descriptor["original_max_request_attempts"] <= 3
            or turn.get("replay_snapshot_hash") != hashlib.sha256(_canonical(descriptor)).hexdigest()):
        _fail()
    _uuid(turn.get("turn_id"))
    for field in ("source_request_hash", "source_input_hash", "source_config_hash"):
        _hash(descriptor[field])
    prefix = f"jobs/{lease.job_id}/stages/{_uuid(descriptor['source_stage_id'])}/attempts/1/{_uuid(descriptor['source_run_token'])}/"
    files, unique, size = {}, set(), 0
    if not isinstance(descriptor["files"], list) or not 4 <= len(descriptor["files"]) <= 64:
        _fail()
    for item in descriptor["files"]:
        if not isinstance(item, dict) or set(item) != {"kind", "key", "sha256", "bytes"}:
            _fail()
        kind, key = item["kind"], item["key"]
        if (kind not in KINDS or not isinstance(key, str) or key in unique
                or kind not in MULTIPLE and kind in files or type(item["bytes"]) is not int
                or not 0 < item["bytes"] <= (1024*1024 if kind == "frame" else 4*1024*1024)):
            _fail()
        if kind == "retry_policy":
            if not re.fullmatch(re.escape(f"jobs/{lease.job_id}/stages/{descriptor['source_stage_id']}/text-retry-policies/")+r"[a-f0-9]{64}\.json", key):
                _fail()
        elif not key.startswith(prefix):
            _fail()
        path = _confined(context.storage_root, key)
        context.guard.check()
        with _native_path(path).open("rb") as stream:
            data = stream.read(item["bytes"]+1)
        if len(data) != item["bytes"] or hashlib.sha256(data).hexdigest() != _hash(item["sha256"]):
            _fail()
        files.setdefault(kind, []).append({**item, "data": data, "path": path})
        size += len(data)
        unique.add(key)
    if size > 64*1024*1024 or not {"state", "request_plans", "speech_observation", "window", "provider_response"} <= files.keys():
        _fail()
    names = {"state": "agent-evidence-speech/state.json", "request_plans": "agent-evidence-speech/request-plans.json",
             "speech_observation": "agent-evidence-speech/speech-observation.json", "focus_observation": "agent-evidence-speech/focus-observation.json",
             "frame_cache": "visual-frame-cache.json", "trace": "text-execution-trace.json"}
    for kind, entries in files.items():
        for item in entries:
            relative = item["key"][len(prefix):]
            if (kind in names and relative != names[kind]
                    or kind == "window" and not re.fullmatch(r"agent-evidence-speech/window-0000-attempt-[01]\.json", relative)
                    or kind == "frame" and not re.fullmatch(r"visual-frames/f\d{4}\.jpg", relative)
                    or kind == "provider_response" and not re.fullmatch(r"provider-responses/[a-f0-9]{64}\.json", relative)):
                _fail()
    state = _object(files["state"][0]["data"])
    plans = _object(files["request_plans"][0]["data"])
    observation = _object(files["speech_observation"][0]["data"])
    if (state.get("config_hash") != descriptor["source_config_hash"] or plans.get("config_hash") != descriptor["source_config_hash"]
            or observation.get("config_hash") != descriptor["source_config_hash"] or observation.get("status") != "READY"):
        _fail()
    calls = descriptor["calls"]
    if not isinstance(calls, list) or not 1 <= len(calls) <= 2 or not isinstance(state.get("calls"), list) or len(state["calls"]) != len(calls):
        _fail()
    first = None
    for call in calls:
        if (not isinstance(call, dict) or set(call) != CALL_FIELDS or call["kind"] != "TEXT" or call["status"] != "SUCCEEDED"
                or type(call["window_index"]) is not int or call["window_index"] != 0
                or type(call["repair_attempt"]) is not int or call["repair_attempt"] not in {0, 1}
                or call["origin_attempt"] != 1 or type(call["origin_attempt"]) is not int
                or call["dispatch_attempt"] != 1 or type(call["dispatch_attempt"]) is not int
                or call["model_id"] != lease.config.get("text_model")
                or call["operation_key"] != "text-"+_hash(call["input_hash"])
                or _hash(call["request_id"]) != hashlib.sha256((descriptor["source_stage_id"]+":"+call["operation_key"]).encode()).hexdigest()):
            _fail()
        windows = [f for f in files["window"] if f["key"] == prefix+f"agent-evidence-speech/window-0000-attempt-{call['repair_attempt']}.json"]
        records = [c for c in state["calls"] if c.get("window_index") == 0 and c.get("attempt") == call["repair_attempt"]]
        wrappers = [f for f in files["provider_response"] if f["key"] == call["response_key"]
                    and f["key"] == prefix+f"provider-responses/{call['request_id']}.json" and f["sha256"] == _hash(call["response_hash"])]
        if len(windows) != 1 or len(records) != 1 or len(wrappers) != 1:
            _fail()
        record, window, wrapper = records[0], windows[0], _object(wrappers[0]["data"])
        if (record.get("status") != "received" or record.get("provider_failed") is not False
                or record.get("response_sha256") != window["sha256"]
                or record.get("response_file") != Path(window["key"]).name
                or set(wrapper) != {"http_status", "body", "body_is_json", "headers"}
                or type(wrapper["http_status"]) is not int or not 200 <= wrapper["http_status"] < 300
                or wrapper["body_is_json"] is not True or _canonical(wrapper["body"]) != _canonical(_object(window["data"]))
                or not isinstance(wrapper["headers"], dict) or wrapper["headers"].get("x-dashscope-partialresponse", "").lower() == "true"):
            _fail()
        if call["repair_attempt"] == 0:
            if first is not None:
                _fail()
            first = call, window
    if first is None or len(files["window"]) != len(calls) or len(files["provider_response"]) != len(calls):
        _fail()
    if files.get("retry_policy"):
        for policy in files["retry_policy"]:
            recorded = _object(policy["data"])["policy"]
            if (recorded["max_attempts"] != descriptor["original_max_request_attempts"]
                    or recorded["profiles"][0]["endpoint"] != descriptor["endpoint"]
                    or recorded["profiles"][0]["model"] != lease.config["text_model"]):
                _fail()
    return descriptor, files, prefix, first


def execute_cached_replay(context, *, ffmpeg_path="ffmpeg", ffprobe_path="ffprobe"):
    """Validate old identity in a private copy, then rebind only the child turn."""
    try:
        return _execute(context, ffmpeg_path, ffprobe_path)
    except WorkerError as error:
        if error.code.startswith("ASR_REUSE_"):
            raise WorkerError("INVALID_CACHED_REPLAY_SOURCE") from None
        raise
    except (KeyError, ValueError, TypeError, OSError, AttributeError, IndexError):
        raise WorkerError("INVALID_CACHED_REPLAY_SOURCE") from None


def _execute(context, ffmpeg_path, ffprobe_path):
    descriptor, files, prefix, (first, window) = _snapshot(context)
    lease, config = context.lease, context.lease.config["agent_turn"]
    if config["source_job_id"] != lease.job_id:
        _fail()
    sources = {}
    from .worker import _read, shared_path

    for name, stage, kind in (("transcript", "ASR", "transcript"), ("analysis", "TIMELINE", "analysis_result")):
        path = context.predecessor(stage, kind)
        if (path != shared_path(context.storage_root, config[f"source_{name}_key"], must_exist=True)
                or sha256(path, guard=context.guard.check) != config[f"source_{name}_hash"]):
            _fail()
        sources[name] = _object(_native_path(path).read_bytes())
    transcript, analysis = sources["transcript"], sources["analysis"]
    if (analysis.get("job_id") != lease.job_id or analysis.get("video_id") != lease.video_id
            or analysis.get("analysis_generation") != lease.generation or analysis.get("utterances") != transcript
            or analysis.get("duration_ms") != config["duration_ms"]):
        _fail()
    from .topic_contracts import validate_topic_result

    validate_topic_result(analysis)
    observation = _object(files["speech_observation"][0]["data"])["prepared"]
    frames = _object(files["frame_cache"][0]["data"])["frames"] if files.get("frame_cache") else []
    focus = "focus_timestamp_ms" in config
    if len(frames) != int(focus) or len(files.get("frame", [])) != len(frames) or bool(files.get("focus_observation")) != focus:
        _fail()
    trace = []
    if focus:
        trace.append({"step": 1, "tool_name": "inspect_frames", "arguments": {"timestamps_ms": [config["focus_timestamp_ms"]]},
                      "origin": "USER_FOCUS", "result_utterance_ids": [], "result_frame_ids": ["f0001"]})
    trace.append({"step": len(trace)+1, "tool_name": "read_context" if observation["receipt"]["strategy"] == "FOCUS_CONTEXT" else "search_transcript",
                  "arguments": observation["arguments"], "origin": "EVIDENCE_POLICY",
                  "result_utterance_ids": observation["receipt"]["returned_utterance_ids"], "result_frame_ids": []})
    decision = _decision(_object(window["data"]), {u["utterance_id"]: u for u in transcript},
                         set(observation["receipt"]["returned_utterance_ids"]), frames, 0, config["duration_ms"],
                         evidence_mode="structured", tool_calls=trace, speech_policy=True)
    if decision["type"] != "answer":
        _fail()
    verified_requests = []
    def verify_paid_input(endpoint, body):
        url = httpx.URL(endpoint)
        identity = {"endpoint": {"scheme": url.scheme, "host": url.host, "port": url.port, "path": url.path}, "body": body}
        if verified_requests or hashlib.sha256(_canonical(identity)).hexdigest() != first["input_hash"]:
            raise WorkerError("CACHED_REPLAY_INPUT_HASH_MISMATCH")
        verified_requests.append(first["input_hash"])
    _confined(context.storage_root, lease.attempt_key, file=False)
    with tempfile.TemporaryDirectory(prefix="cached-answer-replay-", dir=_native_path(context.storage_root)) as temporary:
        workspace = Path(temporary)
        for entries in files.values():
            for item in entries:
                context.guard.check()
                if item["kind"] in {"retry_policy", "trace"}:
                    continue
                target = workspace / item["key"][len(prefix):]
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(item["data"])
        options = dict(session_id=descriptor["source_session_id"], turn_id=descriptor["source_turn_id"], job_id=lease.job_id,
            video_id=lease.video_id, source_hash=lease.source_hash, transcript_hash=config["source_transcript_hash"],
            model=lease.config["text_model"], max_calls=descriptor["original_max_text_calls"],
            max_request_attempts=descriptor["original_max_request_attempts"], max_tool_steps=config["max_tool_steps"],
            api_key="cached-replay-no-provider-key", base_url=descriptor["endpoint"], budget_remaining=lambda: 0,
            evidence_mode="structured", evidence_policy=POLICY, source=_native_path(shared_path(context.storage_root, lease.source_key, must_exist=True)),
            history=config["history"], ffmpeg_path=ffmpeg_path, ffprobe_path=ffprobe_path,
            media_info=_read(context.predecessor("MEDIA", "media_info")), guard=context.guard.check,
            cached_request_validator=verify_paid_input)
        if focus:
            options["focus_timestamp_ms"] = config["focus_timestamp_ms"]
        with httpx.Client(transport=DenyProviderTransport(), trust_env=False) as client:
            original = build_visual_answer(transcript, analysis["segments"], config["duration_ms"], config["question"], workspace,
                                           client=client, **options)
        if (original["session_id"] != descriptor["source_session_id"] or original["turn_id"] != descriptor["source_turn_id"]
                or original["answer_blocks"] != decision["answer_blocks"] or original["answer_scope"] != decision["answer_scope"]
                or original["suggested_clip_instruction"] != decision["suggested_clip_instruction"]):
            _fail()
        if len(verified_requests) != 1:
            _fail()
        for entries in files.values():
            for item in entries:
                if sha256(_confined(context.storage_root, item["key"]), guard=context.guard.check) != item["sha256"]:
                    _fail()
        for name, stage, kind in (("transcript", "ASR", "transcript"), ("analysis", "TIMELINE", "analysis_result")):
            if sha256(context.predecessor(stage, kind), guard=context.guard.check) != config[f"source_{name}_hash"]:
                _fail()
        context.predecessor("MEDIA", "media_info")
        if sha256(shared_path(context.storage_root, lease.source_key, must_exist=True), guard=context.guard.check) != lease.source_hash:
            _fail()
        result = copy.deepcopy(original)
        result["turn_id"] = config["turn_id"]
        validate_visual_answer(result, transcript, expected={"turn_id": config["turn_id"], "session_id": config["session_id"]},
                               expected_frames=frames, duration_ms=config["duration_ms"])
        output = context.directory
        for frame in result["visual_evidence"]:
            directory = output / "visual-frames"
            if _native_path(directory).exists():
                _confined(context.storage_root, lease.attempt_key+"/visual-frames", file=False)
            else:
                _native_path(directory).mkdir()
            target = directory / (frame["frame_id"]+".jpg")
            if _native_path(target).exists() or _native_path(target).is_symlink():
                checked = _confined(context.storage_root, lease.attempt_key+"/visual-frames/"+target.name)
                if sha256(checked, guard=context.guard.check) != frame["sha256"]:
                    _fail()
            else:
                context.guard.check()
                _native_path(target).write_bytes((workspace / "visual-frames" / target.name).read_bytes())
        receipt = {"schema_version": 1, "data_kind": "cached_agent_answer_replay", "turn_id": config["turn_id"],
            "source_turn_id": descriptor["source_turn_id"], "source_stage_id": descriptor["source_stage_id"], "source_attempt": 1,
            "snapshot_hash": config["replay_snapshot_hash"], "source_request_id": first["request_id"],
            "source_response_hash": first["response_hash"], "source_window_sha256": window["sha256"],
            "decoder_profile": PROFILE, "rebound_fields": ["turn_id"], "provider_requests": 0}
        for name, value in (("agent-answer.json", result), ("cached-answer-replay.json", receipt)):
            path = output / name
            if _native_path(path).exists() or _native_path(path).is_symlink():
                checked = _confined(context.storage_root, lease.attempt_key+"/"+name)
                if _canonical(_object(_native_path(checked).read_bytes())) != _canonical(value):
                    _fail()
            else:
                _write(path, value, guard=context.guard.check)
    artifacts = [context.artifact("agent_answer", context.directory / "agent-answer.json"),
                 context.artifact("agent_cached_answer_replay", context.directory / "cached-answer-replay.json")]
    artifacts.extend(context.artifact("agent_visual_frame", context.directory / "visual-frames" / (f["frame_id"]+".jpg")) for f in result["visual_evidence"])
    return {"schema_version": 1, "job_id": lease.job_id, "video_id": lease.video_id, "analysis_generation": lease.generation,
            "stage_id": lease.stage_id, "stage_type": lease.stage_type, "attempt": lease.attempt, "run_token": lease.run_token,
            "input_sha256": lease.source_hash, "data_kind": "model_result", "review_status": "DRAFT", "artifacts": artifacts}
