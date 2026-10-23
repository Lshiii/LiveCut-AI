"""Mocked HTTP protocol/recovery tests; never invokes a paid ASR provider."""

import json
import os
import tempfile
import unittest
import wave
from pathlib import Path
from unittest.mock import patch

import httpx
from livecut import asr

BASE = "https://asr.example/api/v1"
SECRET = "unit-test-api-key-never-real"
TASK_ID = "task-123"


def raw_transcript():
    return {"properties": {"original_duration_in_milliseconds": 1000}, "transcripts": [
        {"channel_id": 0, "sentences": [
            {"begin_time": 100, "end_time": 600, "text": "先介绍这款商品。", "sentence_id": 1},
            {"begin_time": 700, "end_time": 1000, "text": "稍后再返场。", "sentence_id": 2},
        ]},
    ]}


def successful_poll():
    return {"output": {"task_id": TASK_ID, "task_status": "SUCCEEDED", "results": [
        {"subtask_status": "SUCCEEDED", "transcription_url": "https://result.example/result.json?signature=local-only"},
    ]}, "usage": {"duration": 1}}


class ASRTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.audio = self.root / "private filename.wav"
        with wave.open(str(self.audio), "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(16000)
            audio.writeframes(b"\x00\x00" * 16000)
        self.output = self.root / "data" / "asr"
        self.requests = []

    def protocol(self, request):
        if request.url.host == "asr.example":
            self.assertEqual(f"Bearer {SECRET}", request.headers.get("Authorization"))
        else:
            self.assertNotIn("Authorization", request.headers)
        path = request.url.path
        if path == "/api/v1/uploads":
            self.assertEqual("GET", request.method)
            self.assertEqual("getPolicy", request.url.params["action"])
            self.assertEqual("paraformer-v2", request.url.params["model"])
            return httpx.Response(200, json={"data": {
                "upload_dir": "temporary/task-dir", "upload_host": "https://upload.example",
                "oss_access_key_id": "temporary-access-key", "signature": "temporary-signature",
                "policy": "temporary-policy", "x_oss_object_acl": "private", "x_oss_forbid_overwrite": "true",
            }})
        if request.url.host == "upload.example":
            self.assertEqual("POST", request.method)
            self.assertIn(b'name="OSSAccessKeyId"', request.content)
            self.assertIn(b'name="x-oss-forbid-overwrite"', request.content)
            self.assertIn(b"RIFF", request.content)
            self.assertNotIn(self.audio.name.encode(), request.content)
            return httpx.Response(200)
        if path == "/api/v1/services/audio/asr/transcription":
            self.assertEqual("POST", request.method)
            self.assertEqual("enable", request.headers["X-DashScope-Async"])
            self.assertEqual("enable", request.headers["X-DashScope-OssResourceResolve"])
            payload = json.loads(request.content)
            self.assertEqual("paraformer-v2", payload["model"])
            self.assertEqual([0], payload["parameters"]["channel_id"])
            self.assertEqual(1, len(payload["input"]["file_urls"]))
            self.assertTrue(payload["input"]["file_urls"][0].startswith("oss://temporary/task-dir/"))
            return httpx.Response(200, json={"output": {"task_id": TASK_ID, "task_status": "PENDING"}, "request_id": "request-1"})
        if path == f"/api/v1/tasks/{TASK_ID}":
            self.assertEqual("POST", request.method)
            return httpx.Response(200, json=successful_poll())
        if request.url.host == "result.example":
            self.assertEqual("GET", request.method)
            return httpx.Response(200, json=raw_transcript())
        raise AssertionError(f"Unexpected test request: {request.method} {request.url.host}{path}")

    def transport(self, override=None):
        def handler(request):
            self.requests.append(request)
            if override is not None:
                result = override(request)
                if result is not None:
                    return result
            return self.protocol(request)
        return httpx.MockTransport(handler)

    def transcribe(self, **kwargs):
        arguments = {"api_key": SECRET, "base_url": BASE, "poll_interval_seconds": 0.001,
                     "transport": self.transport()}
        arguments.update(kwargs)
        return asr.transcribe_file(self.audio, self.output, **arguments)

    def assert_error(self, code, function, *args, **kwargs):
        with self.assertRaises(asr.ASRError) as raised:
            function(*args, **kwargs)
        self.assertEqual(code, raised.exception.code)
        self.assertNotIn(SECRET, str(raised.exception))
        return raised.exception

    def checkpoint(self):
        return json.loads((self.output / "checkpoint.json").read_text(encoding="utf-8"))

    def test_full_upload_submit_query_download_and_global_time_mapping(self):
        result = self.transcribe(source_chunk_id="c_002", chunk_start_ms=12000)
        self.assertEqual({"utterance_id": "c_002_u000001", "start_ms": 12100, "end_ms": 12600,
                          "text": "先介绍这款商品。", "source_chunk_id": "c_002"}, result[0])
        self.assertEqual(13000, result[1]["end_ms"])
        self.assertEqual(5, len(self.requests))
        checkpoint = self.checkpoint()
        self.assertEqual("SUCCEEDED", checkpoint["state"])
        self.assertEqual(TASK_ID, checkpoint["provider_task_id"])
        self.assertEqual({"duration": 1}, checkpoint["usage"])
        self.assertGreater(checkpoint["upload_expires_at"], checkpoint["completed_at"])
        for name in ("raw-submit.json", "raw-poll.json", "raw-result.json", "transcript.json", "checkpoint.json"):
            content = (self.output / name).read_text(encoding="utf-8")
            self.assertNotIn(SECRET, content)
        self.assertFalse((self.output / "upload-policy.json").exists())
        self.assertEqual([], list(self.output.glob("*.tmp")))

    def test_official_endpoint_environment_override_and_model_upload_limit(self):
        with patch.dict(os.environ, {"DASHSCOPE_HTTP_BASE_URL": "https://workspace.example"}, clear=True):
            self.assertEqual("https://workspace.example/api/v1", asr._base_url(None))
        def small_limit(request):
            if request.url.path.endswith("/uploads"):
                response = self.protocol(request)
                data = response.json()
                data["data"]["max_file_size_mb"] = "0.001"
                return httpx.Response(200, json=data)
        self.assert_error("INVALID_AUDIO", self.transcribe, transport=self.transport(small_limit))
        self.assertEqual(1, len(self.requests))
        self.assertEqual("/api/v1/uploads", self.requests[0].url.path)

    def test_completed_cached_result_requires_no_key_and_makes_no_requests(self):
        result = self.transcribe()
        self.requests.clear()
        with patch.dict(os.environ, {}, clear=True):
            again = self.transcribe(api_key=None)
        self.assertEqual(result, again)
        self.assertEqual([], self.requests)

    def test_changed_input_or_config_does_not_reuse_or_resubmit(self):
        self.transcribe()
        self.requests.clear()
        self.assert_error("CHECKPOINT_INPUT_MISMATCH", self.transcribe, source_chunk_id="different")
        self.assert_error("CHECKPOINT_INPUT_MISMATCH", self.transcribe, model="different-model")
        self.assertEqual([], self.requests)

    def test_submission_timeout_persists_unknown_and_rerun_never_submits_again(self):
        def timeout(request):
            if request.url.path.endswith("/transcription"):
                raise httpx.ReadTimeout("uncertain acceptance", request=request)
        self.assert_error("UNKNOWN_OUTCOME", self.transcribe, transport=self.transport(timeout))
        self.assertEqual("UNKNOWN_OUTCOME", self.checkpoint()["state"])
        self.assertEqual(1, sum(r.url.path.endswith("/transcription") for r in self.requests))
        self.requests.clear()
        self.assert_error("UNKNOWN_OUTCOME", self.transcribe)
        self.assertEqual([], self.requests)

    def test_submission_5xx_and_invalid_success_response_are_not_retried(self):
        for index, (status, content) in enumerate([(503, {"message": "temporary failure"}), (200, {"output": {"task_status": "PENDING"}}), (200, None)]):
            with self.subTest(status=status, content=content):
                self.output = self.root / f"case-{index}"
                self.requests.clear()
                def uncertain(request):
                    if request.url.path.endswith("/transcription"):
                        return httpx.Response(status, json=content) if content is not None else httpx.Response(status, text="not JSON")
                self.assert_error("UNKNOWN_OUTCOME", self.transcribe, transport=self.transport(uncertain))
                self.assertEqual("UNKNOWN_OUTCOME", self.checkpoint()["state"])
                self.assertEqual(1, sum(r.url.path.endswith("/transcription") for r in self.requests))

    def test_authorization_rate_limit_and_permission_rejections_are_clear(self):
        for status, code in [(401, "AUTHENTICATION_FAILED"), (403, "PERMISSION_DENIED"), (429, "RATE_LIMITED")]:
            with self.subTest(status=status):
                self.output = self.root / f"rejection-{status}"
                self.requests.clear()
                def reject(request):
                    if request.url.path.endswith("/transcription"):
                        return httpx.Response(status, json={"message": f"must never echo {SECRET}"})
                self.assert_error(code, self.transcribe, transport=self.transport(reject))
                self.assertEqual("SUBMIT_REJECTED", self.checkpoint()["state"])
                self.assertEqual(1, sum(r.url.path.endswith("/transcription") for r in self.requests))
                self.requests.clear()
                self.assert_error(code, self.transcribe)
                self.assertEqual([], self.requests)

    def test_pending_poll_timeout_keeps_task_and_resume_does_not_reupload(self):
        def pending(request):
            if "/tasks/" in request.url.path:
                return httpx.Response(200, json={"output": {"task_id": TASK_ID, "task_status": "RUNNING"}})
        exc = self.assert_error("POLL_TIMEOUT", self.transcribe, transport=self.transport(pending), timeout_seconds=0.002)
        self.assertTrue(exc.retryable)
        self.assertEqual(TASK_ID, self.checkpoint()["provider_task_id"])
        self.requests.clear()
        result = self.transcribe()
        self.assertEqual(2, len(result))
        self.assertEqual([f"/api/v1/tasks/{TASK_ID}", "/result.json"], [r.url.path for r in self.requests])

    @patch("livecut.asr.time.sleep")
    def test_only_read_queries_retry_transient_failures(self, sleep):
        attempts = {"policy": 0, "poll": 0, "result": 0}
        def transient(request):
            if request.url.path.endswith("/uploads"):
                attempts["policy"] += 1
                if attempts["policy"] == 1:
                    return httpx.Response(429, json={"code": "Throttling"})
            if "/tasks/" in request.url.path:
                attempts["poll"] += 1
                if attempts["poll"] == 1:
                    return httpx.Response(503)
            if request.url.host == "result.example":
                attempts["result"] += 1
                if attempts["result"] == 1:
                    raise httpx.ReadTimeout("safe query timeout", request=request)
        self.assertEqual(2, len(self.transcribe(transport=self.transport(transient))))
        self.assertEqual({"policy": 2, "poll": 2, "result": 2}, attempts)
        self.assertEqual(1, sum(r.url.path.endswith("/transcription") for r in self.requests))
        self.assertEqual(3, sleep.call_count)

    @patch("livecut.asr.time.sleep")
    def test_query_retries_are_bounded_and_preserve_task_id(self, sleep):
        def limited(request):
            if "/tasks/" in request.url.path:
                return httpx.Response(429)
        exc = self.assert_error("RATE_LIMITED", self.transcribe, transport=self.transport(limited), max_query_retries=2)
        self.assertTrue(exc.retryable)
        self.assertEqual(3, sum("/tasks/" in r.url.path for r in self.requests))
        self.assertEqual(TASK_ID, self.checkpoint()["provider_task_id"])
        self.assertEqual("POLLING", self.checkpoint()["state"])

    def test_query_timeout_is_distinct_and_preserves_resumable_task(self):
        def timeout(request):
            if "/tasks/" in request.url.path:
                raise httpx.ReadTimeout("query timed out", request=request)
        exc = self.assert_error("QUERY_TIMEOUT", self.transcribe, transport=self.transport(timeout), max_query_retries=0)
        self.assertTrue(exc.retryable)
        self.assertEqual(TASK_ID, self.checkpoint()["provider_task_id"])
        self.assertEqual("POLLING", self.checkpoint()["state"])

    def test_submit_response_recovers_crash_before_task_checkpoint(self):
        self.transcribe()
        checkpoint = self.checkpoint()
        checkpoint["state"] = "SUBMITTING"
        del checkpoint["provider_task_id"]
        (self.output / "checkpoint.json").write_text(json.dumps(checkpoint), encoding="utf-8")
        (self.output / "raw-result.json").unlink()
        self.requests.clear()
        self.assertEqual(2, len(self.transcribe()))
        self.assertFalse(any(r.url.path.endswith("/transcription") for r in self.requests))
        self.assertEqual(TASK_ID, self.checkpoint()["provider_task_id"])

    def test_successful_parent_with_failed_subtask_is_rejected_and_persisted(self):
        def subtask_failed(request):
            if "/tasks/" in request.url.path:
                result = successful_poll()
                result["output"]["results"][0] = {"subtask_status": "FAILED", "code": "InvalidFile.DownloadFailed"}
                return httpx.Response(200, json=result)
        self.assert_error("SUBTASK_FAILED", self.transcribe, transport=self.transport(subtask_failed))
        self.assertEqual("FAILED", self.checkpoint()["state"])
        self.assertEqual("InvalidFile.DownloadFailed", self.checkpoint()["provider_code"])
        self.assertFalse((self.output / "transcript.json").exists())

    def test_failed_task_does_not_trigger_new_submission(self):
        def failed(request):
            if "/tasks/" in request.url.path:
                return httpx.Response(200, json={"output": {"task_id": TASK_ID, "task_status": "FAILED", "code": "InternalError"}})
        self.assert_error("TASK_FAILED", self.transcribe, transport=self.transport(failed))
        self.requests.clear()
        self.assert_error("TASK_FAILED", self.transcribe)
        self.assertEqual([], self.requests)

    def test_polling_wrong_task_id_is_rejected(self):
        def wrong_task(request):
            if "/tasks/" in request.url.path:
                result = successful_poll()
                result["output"]["task_id"] = "other-task"
                return httpx.Response(200, json=result)
        self.assert_error("INVALID_RESPONSE", self.transcribe, transport=self.transport(wrong_task))
        self.assertEqual(TASK_ID, self.checkpoint()["provider_task_id"])

    def test_cached_corrupt_state_and_missing_task_id_never_resubmit(self):
        self.transcribe()
        checkpoint = self.checkpoint()
        self.requests.clear()
        for state in ("UNKNOWN_STATE", "POLLING", "SUCCEEDED"):
            with self.subTest(state=state):
                corrupted = {**checkpoint, "state": state}
                corrupted.pop("provider_task_id", None)
                (self.output / "checkpoint.json").write_text(json.dumps(corrupted), encoding="utf-8")
                self.assert_error("INVALID_CHECKPOINT", self.transcribe)
                self.assertEqual([], self.requests)

    def test_same_directory_concurrent_process_is_rejected_then_lock_releases(self):
        self.output.mkdir(parents=True)
        with asr._exclusive_output(self.output):
            self.assert_error("ASR_BUSY", self.transcribe)
        self.assertEqual(2, len(self.transcribe()))

    def test_missing_key_bad_audio_and_invalid_configuration_make_no_requests(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assert_error("MISSING_API_KEY", self.transcribe, api_key=None)
        self.assert_error("INVALID_CONFIGURATION", self.transcribe, base_url="http://asr.example/api/v1")
        self.assert_error("INVALID_CONFIGURATION", self.transcribe, max_query_retries=99)
        self.assert_error("INVALID_CONFIGURATION", self.transcribe, source_chunk_id="../unsafe")
        self.assert_error("INVALID_CONFIGURATION", self.transcribe, chunk_start_ms=True)
        self.assert_error("INVALID_CONFIGURATION", self.transcribe, poll_interval_seconds=0)
        self.assert_error("INVALID_CONFIGURATION", self.transcribe, api_key="accidental\nnewline")
        self.audio.write_bytes(b"not WAV")
        self.assert_error("INVALID_AUDIO", self.transcribe)
        self.assertEqual([], self.requests)

    def test_untrusted_download_endpoint_and_redirect_are_not_followed(self):
        def unsafe(request):
            if "/tasks/" in request.url.path:
                result = successful_poll()
                result["output"]["results"][0]["transcription_url"] = "http://unsafe.example/result.json"
                return httpx.Response(200, json=result)
        self.assert_error("INVALID_RESPONSE", self.transcribe, transport=self.transport(unsafe))
        self.assertFalse(any(r.url.host == "unsafe.example" for r in self.requests))
        self.output = self.root / "redirect-case"
        self.requests.clear()
        def redirect(request):
            if request.url.host == "result.example":
                return httpx.Response(302, headers={"Location": "https://unsafe.example/result.json"})
        self.assert_error("REDIRECT_NOT_ALLOWED", self.transcribe, transport=self.transport(redirect))
        self.assertFalse(any(r.url.host == "unsafe.example" for r in self.requests))


class TranscriptMappingTests(unittest.TestCase):
    def test_preserves_repeated_text_and_stable_distinct_ids(self):
        raw = raw_transcript()
        raw["transcripts"][0]["sentences"][1]["text"] = raw["transcripts"][0]["sentences"][0]["text"]
        first = asr.map_transcript(raw, source_chunk_id="c_010", chunk_start_ms=1000)
        self.assertEqual(first, asr.map_transcript(raw, source_chunk_id="c_010", chunk_start_ms=1000))
        self.assertEqual(2, len(first))
        self.assertNotEqual(first[0]["utterance_id"], first[1]["utterance_id"])
        self.assertEqual(1100, first[0]["start_ms"])

    def test_missing_negative_overlapping_and_noninteger_timestamps_are_not_guessed(self):
        for update in [{"begin_time": None}, {"begin_time": -1}, {"end_time": 100}, {"begin_time": True}, {"end_time": 600.0}, {"text": ""}]:
            with self.subTest(update=update):
                raw = raw_transcript()
                raw["transcripts"][0]["sentences"][0].update(update)
                with self.assertRaises(asr.ASRError) as raised:
                    asr.map_transcript(raw)
                self.assertEqual("INVALID_RESULT", raised.exception.code)
        raw = raw_transcript()
        raw["transcripts"][0]["sentences"][1]["begin_time"] = 500
        with self.assertRaises(asr.ASRError):
            asr.map_transcript(raw)

    def test_empty_speech_is_explicit_empty_result(self):
        self.assertEqual([], asr.map_transcript({"transcripts": []}))
        self.assertEqual([], asr.map_transcript({"transcripts": [{"channel_id": 0, "sentences": []}]}))


if __name__ == "__main__":
    unittest.main()
