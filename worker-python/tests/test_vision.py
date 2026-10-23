"""Visual protocol, bounded paid calls, and actual frame PTS tests."""

import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx
from livecut import vision

BASE = "https://vision.example/api/v1"
SECRET = "unit-test-not-a-real-api-key"


def response_for(request):
    payload = json.loads(request.content)
    content = payload["input"]["messages"][0]["content"]
    ids = [re.search(r"frame_id=(\w+)", item["text"]).group(1) for item in content
           if "text" in item and item["text"].startswith("FRAME ")]
    observations = [{"frame_id": frame_id, "presented_category": "保湿霜", "observed_text": "臺灣 舒芙蕾保濕霜",
                     "visibility": "clear"} for frame_id in ids]
    return {"output": {"choices": [{"finish_reason": "stop", "message": {
        "role": "assistant", "content": [{"text": json.dumps({"observations": observations}, ensure_ascii=False)}]}}]},
        "usage": {"input_tokens": 500, "output_tokens": 100, "image_tokens": 450}, "request_id": "visual-request-1"}


class VisionTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.source = self.root / "source.mp4"
        self.source.write_bytes(b"placeholder video for mocked extraction")
        self.output = self.root / "data" / "vision"
        self.requests = []
        self.extracted = []
        extractor = patch("livecut.vision._extract_frame", side_effect=self.extract)
        self.extraction = extractor.start()
        self.addCleanup(extractor.stop)

    def extract(self, source, output, requested_ms, *, ffmpeg_path):
        self.assertEqual(self.source.resolve(), source)
        self.extracted.append(requested_ms)
        output.write_bytes(b"\xff\xd8" + f"frame-at-{requested_ms}".encode() + b"\xff\xd9")
        actual_ms = requested_ms + (20 if requested_ms else 0)
        return {"timestamp_ms": actual_ms, "source_pts": actual_ms * 10, "source_time_base": "1/10000"}

    def transport(self, override=None):
        def handler(request):
            self.requests.append(request)
            self.assertEqual("POST", request.method)
            self.assertEqual("/api/v1/services/aigc/multimodal-generation/generation", request.url.path)
            self.assertEqual(f"Bearer {SECRET}", request.headers["Authorization"])
            if override:
                result = override(request)
                if result is not None:
                    return result
            return httpx.Response(200, json=response_for(request))
        return httpx.MockTransport(handler)

    def observe(self, **kwargs):
        arguments = {"duration_ms": 2500, "interval_ms": 1000, "max_frames": 3, "batch_size": 2,
                     "api_key": SECRET, "base_url": BASE, "transport": self.transport()}
        arguments.update(kwargs)
        return vision.observe_video(self.source, self.output, **arguments)

    def checkpoint(self):
        return json.loads((self.output / "checkpoint.json").read_text(encoding="utf-8"))

    def assert_error(self, code, function, *args, **kwargs):
        with self.assertRaises(vision.VisionError) as raised:
            function(*args, **kwargs)
        self.assertEqual(code, raised.exception.code)
        self.assertNotIn(SECRET, str(raised.exception))

    def test_bounded_native_multimodal_requests_use_actual_timestamps_and_image_labels(self):
        observations = self.observe()
        self.assertEqual([0, 1000, 2000], self.extracted)
        self.assertEqual([0, 1020, 2020], [item["timestamp_ms"] for item in observations])
        self.assertEqual(2, len(self.requests))
        self.assertEqual("保湿霜", observations[0]["presented_category"])
        self.assertEqual("臺灣 舒芙蕾保濕霜", observations[0]["observed_text"])
        self.assertEqual("frames/f_000001.jpg", observations[0]["object_key"])
        self.assertEqual(3, len({item["frame_id"] for item in observations}))
        for request in self.requests:
            payload = json.loads(request.content)
            self.assertEqual("qwen3-vl-plus", payload["model"])
            self.assertFalse(payload["parameters"]["enable_thinking"])
            content = payload["input"]["messages"][0]["content"]
            self.assertIn("桌上闲置", content[0]["text"])
            self.assertIn("简体中文", content[0]["text"])
            self.assertIn("没有音轨", content[0]["text"])
            for index in range(1, len(content), 2):
                self.assertTrue(content[index]["text"].startswith("FRAME frame_id="))
                self.assertTrue(content[index + 1]["image"].startswith("data:image/jpeg;base64,"))
        second_label = json.loads(self.requests[0].content)["input"]["messages"][0]["content"][3]["text"]
        self.assertIn("timestamp_ms=1020", second_label)
        self.assertEqual(2, self.checkpoint()["calls_started"])
        self.assertFalse(self.checkpoint()["coverage_truncated"])
        self.assertTrue((self.output / "raw-b_0000.json").exists())
        usage = json.loads((self.output / "usage.json").read_text(encoding="utf-8"))
        self.assertEqual(500, usage["batches"][0]["usage"]["input_tokens"])
        for file in self.output.glob("*.json"):
            self.assertNotIn(SECRET, file.read_text(encoding="utf-8"))

    def test_frame_budget_truncation_is_explicit_and_does_not_scan_huge_range(self):
        observations = self.observe(duration_ms=10**15, interval_ms=1_000_000, max_frames=2)
        self.assertEqual(2, len(observations))
        self.assertEqual([0, 1_000_000], self.extracted)
        self.assertTrue(self.checkpoint()["coverage_truncated"])
        self.assertEqual(1, self.checkpoint()["calls_started"])

    def test_resume_reuses_frames_and_responses_without_key_or_paid_calls(self):
        first = self.observe()
        self.requests.clear()
        self.extraction.reset_mock()
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(first, self.observe(api_key=None))
        self.assertEqual([], self.requests)
        self.extraction.assert_not_called()

    def test_input_config_and_changed_frame_hash_cannot_reuse_cached_observations(self):
        self.observe()
        self.requests.clear()
        self.assert_error("CHECKPOINT_INPUT_MISMATCH", self.observe, model="other-model")
        self.assert_error("CHECKPOINT_INPUT_MISMATCH", self.observe, interval_ms=2000)
        (self.output / "frames" / "f_000001.jpg").write_bytes(b"changed frame")
        self.assert_error("CACHE_INTEGRITY_ERROR", self.observe)
        self.assertEqual([], self.requests)

    def test_unknown_network_outcome_is_persisted_and_never_paid_retried(self):
        def timeout(request):
            raise httpx.ReadTimeout("uncertain charge", request=request)
        self.assert_error("UNKNOWN_OUTCOME", self.observe, transport=self.transport(timeout))
        self.assertEqual(1, len(self.requests))
        self.assertEqual(1, self.checkpoint()["calls_started"])
        self.assertEqual("UNKNOWN_OUTCOME", self.checkpoint()["batches"]["b_0000"]["state"])
        self.requests.clear()
        self.assert_error("UNKNOWN_OUTCOME", self.observe)
        self.assertEqual([], self.requests)

    def test_http_errors_are_not_retried_and_do_not_echo_secret(self):
        for status, code in [(400, "INVALID_REQUEST"), (401, "AUTHENTICATION_FAILED"), (403, "PERMISSION_DENIED"),
                             (429, "RATE_LIMITED"), (503, "UNKNOWN_OUTCOME")]:
            with self.subTest(status=status):
                self.output = self.root / f"http-{status}"
                self.requests.clear()
                self.assert_error(code, self.observe, transport=self.transport(lambda request: httpx.Response(status, text=SECRET)))
                self.assertEqual(1, len(self.requests))
                self.requests.clear()
                self.assert_error(code, self.observe)
                self.assertEqual([], self.requests)

    def test_invalid_provider_json_is_saved_redacted_without_retry(self):
        self.assert_error("INVALID_RESPONSE", self.observe, transport=self.transport(lambda request: httpx.Response(200, text=f"invalid JSON {SECRET}")))
        self.assertEqual(1, len(self.requests))
        saved = (self.output / "raw-b_0000-invalid.json").read_text(encoding="utf-8")
        self.assertNotIn(SECRET, saved)
        self.assertIn("[REDACTED]", saved)
        self.requests.clear()
        self.assert_error("INVALID_RESPONSE", self.observe)
        self.assertEqual([], self.requests)

    def test_free_tier_exhaustion_is_distinct_and_not_retried(self):
        transport = self.transport(lambda request: httpx.Response(
            403, json={"code": "AllocationQuota.FreeTierOnly", "message": "quota exceeded"}))
        self.assert_error("FREE_QUOTA_EXHAUSTED", self.observe, transport=transport)
        self.assertEqual(1, len(self.requests))
        self.assertEqual("AllocationQuota.FreeTierOnly",
                         self.checkpoint()["batches"]["b_0000"]["provider_code"])
        self.requests.clear()
        self.assert_error("FREE_QUOTA_EXHAUSTED", self.observe)
        self.assertEqual([], self.requests)

    def test_invalid_model_json_is_saved_with_usage_and_never_regenerated(self):
        def invalid(request):
            raw = response_for(request)
            raw["output"]["choices"][0]["message"]["content"] = [{"text": "not JSON"}]
            return httpx.Response(200, json=raw)
        self.assert_error("INVALID_VISION_JSON", self.observe, transport=self.transport(invalid))
        self.assertEqual(1, len(self.requests))
        self.assertEqual(500, self.checkpoint()["batches"]["b_0000"]["usage"]["input_tokens"])
        self.assertTrue((self.output / "raw-b_0000.json").is_file())
        self.requests.clear()
        self.assert_error("INVALID_VISION_JSON", self.observe)
        self.assertEqual([], self.requests)

    def test_raw_response_recovers_crash_before_success_checkpoint(self):
        first = self.observe()
        checkpoint = self.checkpoint()
        checkpoint["batches"]["b_0001"]["state"] = "REQUESTING"
        (self.output / "checkpoint.json").write_text(json.dumps(checkpoint), encoding="utf-8")
        self.requests.clear()
        self.assertEqual(first, self.observe())
        self.assertEqual([], self.requests)
        self.assertEqual("SUCCEEDED", self.checkpoint()["batches"]["b_0001"]["state"])

    def test_partial_batch_success_remains_cached_when_next_call_is_unknown(self):
        def second_fails(request):
            if len(self.requests) == 2:
                raise httpx.ReadTimeout("second batch unknown", request=request)
        self.assert_error("UNKNOWN_OUTCOME", self.observe, transport=self.transport(second_fails))
        self.assertEqual("SUCCEEDED", self.checkpoint()["batches"]["b_0000"]["state"])
        self.assertEqual("UNKNOWN_OUTCOME", self.checkpoint()["batches"]["b_0001"]["state"])
        self.requests.clear()
        self.assert_error("UNKNOWN_OUTCOME", self.observe)
        self.assertEqual([], self.requests)

    def test_invalid_duplicate_or_invented_frame_identity_is_rejected(self):
        for index, mutate in enumerate([
            lambda observations: observations[0].update(frame_id="invented"),
            lambda observations: observations[1].update(frame_id=observations[0]["frame_id"]),
            lambda observations: observations.pop(),
            lambda observations: observations[0].update(visibility="not_visible"),
            lambda observations: observations[0].update(timestamp_ms=100),
        ]):
            with self.subTest(index=index):
                self.output = self.root / f"invalid-observations-{index}"
                def invalid(request):
                    raw = response_for(request)
                    item = raw["output"]["choices"][0]["message"]["content"][0]
                    data = json.loads(item["text"])
                    mutate(data["observations"])
                    item["text"] = json.dumps(data)
                    return httpx.Response(200, json=raw)
                self.assert_error("INVALID_VISION_JSON", self.observe, transport=self.transport(invalid))

    def test_truncated_model_response_is_an_error_with_no_automatic_retry(self):
        def truncated(request):
            raw = response_for(request)
            raw["output"]["choices"][0]["finish_reason"] = "length"
            return httpx.Response(200, json=raw)
        self.assert_error("TRUNCATED_RESPONSE", self.observe, transport=self.transport(truncated))
        self.assertEqual(1, len(self.requests))

    def test_frame_extraction_failure_never_reaches_model(self):
        self.extraction.side_effect = vision.VisionError("FRAME_TIMESTAMP_MISSING", "no timestamp")
        self.assert_error("FRAME_TIMESTAMP_MISSING", self.observe)
        self.assertEqual([], self.requests)

    def test_invalid_configuration_and_missing_key_do_not_call_model(self):
        self.assert_error("INVALID_CONFIGURATION", self.observe, duration_ms=True)
        self.assert_error("INVALID_CONFIGURATION", self.observe, max_frames=121)
        self.assert_error("INVALID_CONFIGURATION", self.observe, batch_size=0)
        self.assert_error("INVALID_CONFIGURATION", self.observe, interval_ms=0)
        self.assert_error("INVALID_CONFIGURATION", self.observe, base_url="http://unsafe.example")
        self.assert_error("INVALID_CONFIGURATION", self.observe, api_key="invalid\nheader")
        with patch.dict(os.environ, {}, clear=True):
            self.assert_error("MISSING_API_KEY", self.observe, api_key=None)
        self.assertEqual([], self.requests)

    def test_same_output_lock_and_corrupt_budget_checkpoint_prevent_parallel_regeneration(self):
        self.output.mkdir(parents=True)
        with vision._lock(self.output):
            self.assert_error("VISION_BUSY", self.observe)
        self.observe()
        checkpoint = self.checkpoint()
        checkpoint["calls_started"] = 999
        (self.output / "checkpoint.json").write_text(json.dumps(checkpoint), encoding="utf-8")
        self.requests.clear()
        self.assert_error("INVALID_CHECKPOINT", self.observe)
        self.assertEqual([], self.requests)


FFMPEG = os.environ.get("LIVECUT_FFMPEG_PATH") or shutil.which("ffmpeg")


@unittest.skipUnless(FFMPEG, "Real FFmpeg missing; set LIVECUT_FFMPEG_PATH")
class ActualSourceTimestampTests(unittest.TestCase):
    def test_non_frame_aligned_seek_records_real_pts_and_does_not_upscale(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, frame = root / "source.mp4", root / "frame.jpg"
            made = subprocess.run(
                [FFMPEG, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=25",
                 "-t", "1", "-c:v", "libx264", str(source)],
                capture_output=True, text=True, shell=False, timeout=30, check=False,
            )
            self.assertEqual(0, made.returncode, made.stderr)
            info = vision._extract_frame(source, frame, 250, ffmpeg_path=FFMPEG)
            self.assertEqual(280, info["timestamp_ms"])
            self.assertNotEqual(250, info["timestamp_ms"])
            self.assertEqual("1/12800", info["source_time_base"])
            decoded = subprocess.run(
                [FFMPEG, "-hide_banner", "-loglevel", "info", "-i", str(frame), "-vf", "showinfo", "-frames:v", "1", "-f", "null", "-"],
                capture_output=True, text=True, shell=False, timeout=30, check=False,
            )
            self.assertEqual(0, decoded.returncode, decoded.stderr)
            self.assertIn("s:160x120", decoded.stderr)


if __name__ == "__main__":
    unittest.main()
