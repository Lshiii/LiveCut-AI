"""Media unit tests plus real, locally synthesized FFmpeg integration tests."""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
import wave
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from livecut import media


def metadata() -> dict:
    return {
        "format": {"format_name": "mov,mp4,m4a,3gp,3g2,mj2", "duration": "4.000000", "start_time": "0.000000"},
        "streams": [
            {"codec_type": "video", "codec_name": "h264", "width": 160, "height": 120,
             "avg_frame_rate": "25/1", "start_time": "0.000000"},
            {"codec_type": "audio", "codec_name": "aac", "sample_rate": "48000", "channels": 1,
             "start_time": "0.000000"},
        ],
    }


class MediaTests(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.source = self.root / "source with spaces.mp4"
        self.source.write_bytes(b"synthetic placeholder, not actual media")
        self.info = media.MediaInfo(
            path=self.source.resolve(), duration_ms=4000, size_bytes=self.source.stat().st_size,
            format_name="mov,mp4,m4a,3gp,3g2,mj2", video_codec="h264", audio_codec="aac",
            width=160, height=120, frame_rate=25.0, rotation_degrees=0,
            format_start_time_seconds=0.0, video_start_time_seconds=0.0, audio_start_time_seconds=0.0,
            audio_sample_rate=48000, audio_channels=1,
        )

    def assert_error(self, code, function, *args, **kwargs):
        with self.assertRaises(media.MediaError) as raised:
            function(*args, **kwargs)
        self.assertEqual(code, raised.exception.code)

    @patch("livecut.media.subprocess.run")
    def test_probe_preserves_precise_offsets_rotation_and_configured_tool(self, run):
        data = metadata()
        data["format"]["start_time"] = "1.000001"
        data["streams"][0]["start_time"] = "1.000001"
        data["streams"][0]["side_data_list"] = [{"rotation": -90}]
        data["streams"][1]["start_time"] = "1.021334"
        run.return_value = subprocess.CompletedProcess([], 0, json.dumps(data), "")
        info = media.probe_media(self.source, ffprobe_path="custom ffprobe.exe", timeout_seconds=7)
        self.assertEqual(4000, info.duration_ms)
        self.assertEqual(-90, info.rotation_degrees)
        self.assertEqual(1.000001, info.media_time_offset_seconds)
        self.assertAlmostEqual(0.021333, info.audio_video_offset_seconds)
        self.assertEqual(25.0, info.frame_rate)
        command = run.call_args.args[0]
        self.assertEqual("custom ffprobe.exe", command[0])
        self.assertEqual(str(self.source.resolve()), command[-1])
        self.assertFalse(run.call_args.kwargs["shell"])
        self.assertEqual(7, run.call_args.kwargs["timeout"])

    @patch("livecut.media.subprocess.run")
    def test_probe_reports_failure_invalid_json_and_missing_executable(self, run):
        run.return_value = subprocess.CompletedProcess([], 1, "", "broken container")
        self.assert_error("PROBE_FAILED", media.probe_media, self.source)
        run.return_value = subprocess.CompletedProcess([], 0, "not JSON", "")
        self.assert_error("INVALID_MEDIA", media.probe_media, self.source)
        run.side_effect = FileNotFoundError()
        self.assert_error("TOOL_NOT_FOUND", media.probe_media, self.source)

    @patch("livecut.media.subprocess.run")
    def test_probe_reports_timeout(self, run):
        run.side_effect = subprocess.TimeoutExpired("ffprobe", 5)
        self.assert_error("PROCESS_TIMEOUT", media.probe_media, self.source, timeout_seconds=5)

    @patch("livecut.media.subprocess.run")
    def test_probe_rejects_invalid_container_codecs_streams_and_duration(self, run):
        mutations = [
            ("UNSUPPORTED_MEDIA", lambda d: d["format"].update(format_name="matroska,webm")),
            ("UNSUPPORTED_MEDIA", lambda d: d["streams"][0].update(codec_name="hevc")),
            ("UNSUPPORTED_MEDIA", lambda d: d["streams"].append(d["streams"][1].copy())),
            ("UNSUPPORTED_MEDIA", lambda d: d.update(streams=[])),
            ("INVALID_MEDIA", lambda d: d["format"].update(duration="NaN")),
            ("INVALID_MEDIA", lambda d: d["format"].update(duration="-1")),
        ]
        for code, mutate in mutations:
            with self.subTest(code=code, mutate=mutate):
                data = metadata()
                mutate(data)
                run.return_value = subprocess.CompletedProcess([], 0, json.dumps(data), "")
                self.assert_error(code, media.probe_media, self.source)

    @patch("livecut.media.subprocess.run")
    def test_probe_rejects_wrong_extension_missing_and_empty_file(self, run):
        other = self.root / "input.mkv"
        other.write_bytes(b"content")
        self.assert_error("UNSUPPORTED_MEDIA", media.probe_media, other)
        self.assert_error("INVALID_MEDIA", media.probe_media, self.root / "missing.mp4")
        other = self.root / "empty.mp4"
        other.touch()
        self.assert_error("INVALID_MEDIA", media.probe_media, other)
        run.assert_not_called()

    @patch("livecut.media.probe_media")
    @patch("livecut.media.subprocess.run")
    def test_processing_rejects_unknown_nonzero_and_different_starts(self, run, probe):
        for changes in [
            {"format_start_time_seconds": None},
            {"audio_start_time_seconds": 0.021333},
            {"format_start_time_seconds": 1.0, "video_start_time_seconds": 1.0, "audio_start_time_seconds": 1.0},
        ]:
            with self.subTest(changes=changes):
                probe.return_value = replace(self.info, **changes)
                self.assert_error("UNSUPPORTED_TIMELINE", media.extract_audio, self.source, self.root / "audio.wav")
                self.assert_error("UNSUPPORTED_TIMELINE", media.export_clip, self.source, self.root / "clip.mp4", 0, 1000)
        run.assert_not_called()

    @patch("livecut.media.probe_media")
    @patch("livecut.media.subprocess.run")
    def test_illegal_interval_and_input_overwrite_never_transcode(self, run, probe):
        probe.return_value = self.info
        for start, end in [(-1, 10), (1000, 1000), (2000, 1000), (0, 4001), (True, 1000), (0, 2.5)]:
            with self.subTest(start=start, end=end):
                self.assert_error("INVALID_INTERVAL", media.export_clip, self.source, self.root / "clip.mp4", start, end)
        self.assert_error("OUTPUT_CONFLICT", media.export_clip, self.source, self.source, 0, 1000)
        self.assert_error("OUTPUT_CONFLICT", media.extract_audio, self.source, self.source)
        alias = self.root / "hardlink.mp4"
        os.link(self.source, alias)
        self.assert_error("OUTPUT_CONFLICT", media.export_clip, self.source, alias, 0, 1000)
        self.assert_error("INVALID_ARGUMENT", media.export_clip, self.source, self.root / "clip.avi", 0, 1000)
        run.assert_not_called()

    @patch("livecut.media.probe_media")
    @patch("livecut.media.subprocess.run")
    def test_failed_transcode_retains_existing_output_and_cleans_temporary(self, run, probe):
        probe.return_value = self.info
        target = self.root / "clip.mp4"
        target.write_bytes(b"previous good export")

        def failed(command, **kwargs):
            Path(command[-1]).write_bytes(b"incomplete output")
            return subprocess.CompletedProcess(command, 1, "", "encoder failed")

        run.side_effect = failed
        self.assert_error("TRANSCODE_FAILED", media.export_clip, self.source, target, 1000, 2000)
        self.assertEqual(b"previous good export", target.read_bytes())
        self.assertEqual([], list(self.root.glob(".clip.*.mp4")))

    @patch("livecut.media.probe_media")
    @patch("livecut.media.subprocess.run")
    def test_successful_export_uses_precise_seek_transcoding_and_atomic_publication(self, run, probe):
        target = self.root / "clip.mp4"
        target.write_bytes(b"previous")
        probe.side_effect = [self.info, replace(self.info, duration_ms=1520)]

        def encoded(command, **kwargs):
            temporary = Path(command[-1])
            self.assertEqual(target.parent, temporary.parent)
            self.assertNotEqual(target, temporary)
            self.assertEqual(b"previous", target.read_bytes())
            temporary.write_bytes(b"complete encoded mp4")
            self.assertFalse(kwargs["shell"])
            self.assertEqual(19, kwargs["timeout"])
            return subprocess.CompletedProcess(command, 0, "", "")

        run.side_effect = encoded
        result = media.export_clip(self.source, target, 1250, 2750, ffmpeg_path="custom ffmpeg", timeout_seconds=19)
        command = run.call_args.args[0]
        self.assertEqual("custom ffmpeg", command[0])
        self.assertEqual("1.250", command[command.index("-ss") + 1])
        self.assertEqual("1.500", command[command.index("-t") + 1])
        self.assertIn("-accurate_seek", command)
        self.assertIn("libx264", command)
        self.assertIn("aac", command)
        self.assertIn("+faststart", command)
        self.assertNotIn("copy", command)
        self.assertEqual(target.resolve(), result.path)
        self.assertEqual(b"complete encoded mp4", target.read_bytes())
        self.assertEqual([], list(self.root.glob(".clip.*.mp4")))

    @patch("livecut.media.probe_media")
    @patch("livecut.media.subprocess.run")
    def test_bad_output_duration_does_not_replace_previous_export(self, run, probe):
        target = self.root / "clip.mp4"
        target.write_bytes(b"previous")
        probe.side_effect = [self.info, replace(self.info, duration_ms=3900)]
        run.return_value = subprocess.CompletedProcess([], 0, "", "")
        self.assert_error("OUTPUT_VALIDATION_FAILED", media.export_clip, self.source, target, 1000, 2000)
        self.assertEqual(b"previous", target.read_bytes())
        self.assertEqual([], list(self.root.glob(".clip.*.mp4")))

    @patch("livecut.media.probe_media")
    @patch("livecut.media.subprocess.run")
    def test_audio_extraction_validates_wav_before_replacing_output(self, run, probe):
        probe.return_value = self.info
        target = self.root / "audio.wav"

        def encoded(command, **kwargs):
            with wave.open(command[-1], "wb") as audio:
                audio.setnchannels(1)
                audio.setsampwidth(2)
                audio.setframerate(16000)
                audio.writeframes(b"\x00\x00" * 64000)
            return subprocess.CompletedProcess(command, 0, "", "")

        run.side_effect = encoded
        self.assertEqual(target.resolve(), media.extract_audio(self.source, target))
        self.assertTrue(target.is_file())
        self.assertEqual([], list(self.root.glob(".audio.*.wav")))
        target.write_bytes(b"previous good audio")
        run.side_effect = None
        run.return_value = subprocess.CompletedProcess([], 0, "", "")
        self.assert_error("OUTPUT_VALIDATION_FAILED", media.extract_audio, self.source, target)
        self.assertEqual(b"previous good audio", target.read_bytes())

    @patch("livecut.media.probe_media")
    def test_audio_extraction_rejects_silent_source(self, probe):
        probe.return_value = replace(self.info, audio_codec=None, audio_start_time_seconds=None)
        self.assert_error("NO_AUDIO", media.extract_audio, self.source, self.root / "audio.wav")

    def test_invalid_timeout_is_reported_before_executable_is_started(self):
        for timeout in [0, -1, float("nan"), float("inf"), True, "30"]:
            with self.subTest(timeout=timeout):
                self.assert_error("INVALID_ARGUMENT", media.probe_media, self.source, timeout_seconds=timeout)


FFMPEG = os.environ.get("LIVECUT_FFMPEG_PATH") or shutil.which("ffmpeg")
FFPROBE = os.environ.get("LIVECUT_FFPROBE_PATH") or shutil.which("ffprobe")


@unittest.skipUnless(FFMPEG and FFPROBE, "Real FFmpeg/ffprobe not found; set LIVECUT_FFMPEG_PATH and LIVECUT_FFPROBE_PATH")
class RealFfmpegTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.directory = tempfile.TemporaryDirectory()
        cls.root = Path(cls.directory.name)
        cls.source = cls.root / "synthetic source.mp4"
        result = subprocess.run(
            [FFMPEG, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
             "-f", "lavfi", "-i", "color=c=red:size=160x120:rate=25",
             "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
             "-t", "4", "-vf", "drawbox=color=blue:t=fill:enable='gte(t,1)',"
             "drawbox=color=green:t=fill:enable='gte(t,2)',drawbox=color=yellow:t=fill:enable='gte(t,3)'",
             "-c:v", "libx264", "-g", "250", "-sc_threshold", "0", "-pix_fmt", "yuv420p", "-c:a", "aac", str(cls.source)],
            capture_output=True, text=True, shell=False, timeout=30, check=False,
        )
        if result.returncode:
            cls.directory.cleanup()
            raise RuntimeError(f"Unable to generate synthetic H.264/AAC test source: {result.stderr}")

    @classmethod
    def tearDownClass(cls) -> None:
        cls.directory.cleanup()

    def test_real_probe_and_16k_mono_audio(self):
        info = media.probe_media(self.source, ffprobe_path=FFPROBE)
        self.assertLessEqual(abs(info.duration_ms - 4000), 50)
        self.assertEqual("h264", info.video_codec)
        self.assertEqual("aac", info.audio_codec)
        output = media.extract_audio(
            self.source, self.root / "real audio.wav", ffmpeg_path=FFMPEG, ffprobe_path=FFPROBE
        )
        with wave.open(str(output), "rb") as audio:
            self.assertEqual((1, 16000, 2), (audio.getnchannels(), audio.getframerate(), audio.getsampwidth()))
            self.assertLessEqual(abs(audio.getnframes() * 1000 / 16000 - 4000), 100)

    def test_real_non_keyframe_clip_is_playable_and_has_expected_duration(self):
        output = self.root / "real clip.mp4"
        result = media.export_clip(
            self.source, output, 1250, 2750, ffmpeg_path=FFMPEG, ffprobe_path=FFPROBE
        )
        self.assertEqual(output.resolve(), result.path)
        self.assertEqual(("h264", "aac"), (result.video_codec, result.audio_codec))
        self.assertLessEqual(abs(result.duration_ms - 1500), 100)
        decoded = subprocess.run(
            [FFMPEG, "-hide_banner", "-loglevel", "error", "-i", str(output), "-f", "null", "-"],
            capture_output=True, text=True, shell=False, timeout=30, check=False,
        )
        self.assertEqual(0, decoded.returncode, decoded.stderr)
        # The source's first second is red, while 1.25s is blue. A crop that
        # incorrectly keeps the preceding keyframe can have the right duration
        # and still begin with the wrong visual event, so check actual pixels.
        first_frame = subprocess.run(
            [FFMPEG, "-hide_banner", "-loglevel", "error", "-i", str(output),
             "-frames:v", "1", "-pix_fmt", "rgb24", "-f", "rawvideo", "-"],
            capture_output=True, shell=False, timeout=30, check=False,
        )
        self.assertEqual(0, first_frame.returncode, first_frame.stderr)
        self.assertEqual(160 * 120 * 3, len(first_frame.stdout))
        self.assertLess(first_frame.stdout[0], 30)
        self.assertLess(first_frame.stdout[1], 30)
        self.assertGreater(first_frame.stdout[2], 200)


if __name__ == "__main__":
    unittest.main()
