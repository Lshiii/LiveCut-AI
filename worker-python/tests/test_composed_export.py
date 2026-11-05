"""Real synthetic edits prove removed source intervals do not return during concat."""
import subprocess

import pytest
import test_worker_export as export_fixtures
from livecut.media import MediaError, export_composed_clip
from livecut.worker import Lease, WorkerError, _native_path
from livecut.worker_export import ExportSpec, ExportStageExecutor
from test_worker_export import export_claim, export_config, prepare_context

tools_and_sources = export_fixtures.tools_and_sources


def config(**changes):
    return export_config(start_ms=0, end_ms=4000, parts=[{"start_ms": 0, "end_ms": 1000},
        {"start_ms": 3000, "end_ms": 4000}], duration_ms=2000,
        clip_request_id="de3804a2-84d7-4582-9ff2-254e3b3ebed1", **changes)


@pytest.mark.parametrize("parts", [[], [{"start_ms": False, "end_ms": 1000}],
    [{"start_ms": 1000, "end_ms": 0}], [{"start_ms": 0, "end_ms": 1500}, {"start_ms": 1000, "end_ms": 2000}],
    [{"start_ms": 0, "end_ms": 1000, "extra": 1}]])
def test_composed_frozen_input_rejects_unsafe_edits(parts):
    value = config()
    value["parts"] = parts
    with pytest.raises(WorkerError, match="INVALID_EXPORT_INPUT"):
        Lease.parse(export_claim(config={"export": value}))


def test_composed_duration_and_source_bounds_are_frozen():
    for change in ({"duration_ms": 4000}, {"end_ms": 3500}, {"clip_request_id": "../invalid"}):
        value = {**config(), **change}
        with pytest.raises(WorkerError, match="INVALID_EXPORT_INPUT"):
            ExportSpec.from_lease(Lease.parse(export_claim(config={"export": value})))


@pytest.mark.parametrize("audio", [True, False])
def test_actual_composed_export_keeps_red_yellow_and_excludes_blue_green(tmp_path, tools_and_sources, audio):
    ffmpeg, ffprobe, sources = tools_and_sources
    context = prepare_context(tmp_path, sources[audio], config=config())
    manifest = ExportStageExecutor(ffmpeg_path=ffmpeg, ffprobe_path=ffprobe)(context)
    assert manifest["parts"] == config()["parts"] and manifest["duration_ms"] == 2000
    clip = _native_path(context.storage_root / next(a["key"] for a in manifest["artifacts"] if a["kind"] == "clip"))
    for seconds, yellow in ((.5, False), (1.5, True)):
        result = subprocess.run([ffmpeg, "-v", "error", "-nostdin", "-ss", str(seconds), "-i", str(clip),
            "-frames:v", "1", "-vf", "scale=1:1", "-pix_fmt", "rgb24", "-f", "rawvideo", "-"],
            capture_output=True, timeout=30, check=True)
        red, green, blue = result.stdout
        assert red > 200 and blue < 70
        assert green > 200 if yellow else green < 70


def test_composed_output_cannot_alias_the_source(tmp_path, tools_and_sources):
    ffmpeg, ffprobe, sources = tools_and_sources
    with pytest.raises(MediaError, match="overwrite|alias"):
        export_composed_clip(sources[True], sources[True], config()["parts"],
                             ffmpeg_path=ffmpeg, ffprobe_path=ffprobe)
