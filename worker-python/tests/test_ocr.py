from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import pytest
from livecut.ocr import observe_video_ocr
from livecut.vision import VisionError


def extract(source, output, requested_ms, *, ffmpeg_path):
    output.write_bytes(b"synthetic-frame-for-plumbing-test")
    return {"timestamp_ms": requested_ms, "source_pts": requested_ms, "source_time_base": "1/1000"}


def test_local_ocr_records_text_and_actual_time_without_invented_category(tmp_path):
    source = tmp_path / "source.mp4"
    source.write_bytes(b"synthetic-test-source")
    engine = Mock(return_value=SimpleNamespace(txts=["面膜", "噪声"], scores=[0.95, 0.1]))
    with patch("livecut.ocr._extract_frame", side_effect=extract):
        observations = observe_video_ocr(source, tmp_path / "ocr", duration_ms=1000,
                                         interval_ms=500, engine=engine)
    assert len(observations) == 2
    assert observations[0]["observed_text"] == "面膜"
    assert observations[0]["presented_category"] is None
    assert observations[1]["timestamp_ms"] == 500
    assert engine.call_count == 2


def test_completed_ocr_cache_does_not_rerun_engine(tmp_path):
    source = tmp_path / "source.mp4"
    source.write_bytes(b"synthetic-test-source")
    engine = Mock(return_value=SimpleNamespace(txts=[], scores=[]))
    with patch("livecut.ocr._extract_frame", side_effect=extract):
        a = observe_video_ocr(source, tmp_path / "ocr", duration_ms=1000, engine=engine)
        b = observe_video_ocr(source, tmp_path / "ocr", duration_ms=1000, engine=engine)
    assert a == b and engine.call_count == 1
    assert b[0]["visibility"] == "not_visible"
    with pytest.raises(VisionError, match="different input"):
        observe_video_ocr(source, tmp_path / "ocr", duration_ms=2000, engine=engine)


def test_frame_tampering_is_not_accepted_as_cached_evidence(tmp_path):
    source = tmp_path / "source.mp4"
    source.write_bytes(b"synthetic-test-source")
    engine = Mock(return_value=SimpleNamespace(txts=["文本"], scores=[0.9]))
    directory = tmp_path / "ocr"
    with patch("livecut.ocr._extract_frame", side_effect=extract):
        observe_video_ocr(source, directory, duration_ms=1000, engine=engine)
    (directory / "frames/ocr_000001.jpg").write_bytes(b"changed")
    with pytest.raises(VisionError, match="changed"):
        observe_video_ocr(source, directory, duration_ms=1000, engine=engine)


def test_invalid_frame_budget_does_not_initialize_optional_runtime(tmp_path):
    with pytest.raises(VisionError):
        observe_video_ocr(Path("missing.mp4"), tmp_path, duration_ms=1, max_frames=True)
