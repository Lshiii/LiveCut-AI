"""Real fixed-filter portrait blur, with complete foreground and legacy regressions."""

import dataclasses
import hashlib
import json
import subprocess

import pytest
from livecut import media as media_module
from livecut.media import MediaError, _portrait_blur_foreground_size, burn_caption_clip, probe_media
from livecut.worker import WorkerError, sha256
from livecut.worker_caption_render import Cue, RenderProgress, build_ass, canonical, validate_spec
from livecut.worker_export import ExportStageExecutor
from test_caption_render import configuration, context, pixels, spec
from test_caption_render import font as font
from test_caption_render import media as media


@pytest.fixture(scope="module")
def pattern(media, tmp_path_factory):
    ffmpeg, ffprobe, _ = media
    directory = tmp_path_factory.mktemp("portrait-blur-pattern")
    sources = {}
    for audio in (True, False):
        path = directory / f"complete-corners-{audio}.mp4"
        command = [
            ffmpeg,
            "-v",
            "error",
            "-nostdin",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "color=c=0x80a0c0:s=640x360:r=20:d=2",
        ]
        if audio:
            command += ["-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2"]
        command += [
            "-vf",
            "drawgrid=w=12:h=12:color=0x204060:t=3,"
            "drawbox=x=0:y=0:w=64:h=64:color=0xff0000:t=fill,"
            "drawbox=x=576:y=0:w=64:h=64:color=0x00ff00:t=fill,"
            "drawbox=x=0:y=296:w=64:h=64:color=0x0000ff:t=fill,"
            "drawbox=x=576:y=296:w=64:h=64:color=0xffff00:t=fill",
            "-c:v",
            "libx264",
            "-threads",
            "2",
            "-pix_fmt",
            "yuv420p",
        ]
        if audio:
            command += ["-c:a", "aac"]
        command += [str(path)]
        subprocess.run(command, capture_output=True, timeout=30, check=True)
        sources[audio] = path
    return ffmpeg, ffprobe, sources


def prepared(tmp_path, media, font, *, frame="PORTRAIT_BLUR_720", audio=False):
    ctx, events, render = context(tmp_path, media, font, frame=frame, audio=audio)
    info = probe_media(media[2][audio], ffprobe_path=media[1])
    render["input_media"].update(width=info.width, height=info.height)
    ctx.lease = dataclasses.replace(ctx.lease, config={"export": configuration(render)})
    return ctx, events, render


def patch(rgb, x, y, width=12, height=12):
    return [
        tuple(rgb[(row * 720 + col) * 3 : (row * 720 + col) * 3 + 3])
        for row in range(y, y + height)
        for col in range(x, x + width)
    ]


def mean_color(values):
    return tuple(sum(p[i] for p in values) / len(values) for i in range(3))


def horizontal_detail(rgb, x, y, width=100, height=80):
    values = patch(rgb, x, y, width, height)
    return sum(
        abs(values[row * width + col][channel] - values[row * width + col + 1][channel])
        for row in range(height)
        for col in range(width - 1)
        for channel in range(3)
    ) / (height * (width - 1) * 3)


@pytest.mark.parametrize("style", ["WHITE_OUTLINE_V1", "DARK_BOX_V1"])
def test_blur_preset_geometry_and_ass_bytes_equal_plain_portrait(font, style):
    plain, blurred = spec("PORTRAIT_720", style), spec("PORTRAIT_BLUR_720", style)
    validate_spec(blurred, hashlib.sha256(canonical(blurred)).hexdigest())
    cues = [Cue("c1", 259, 1259, "中文 <b>literal</b> {x} \\N\nnext line")]
    assert build_ass(cues, plain, font[1]) == build_ass(cues, blurred, font[1])
    blurred["output"]["width"] = 1280
    with pytest.raises(WorkerError):
        validate_spec(blurred, hashlib.sha256(canonical(blurred)).hexdigest())


@pytest.mark.parametrize(
    "frame,width,height,source_frame",
    [
        ("SOURCE", 320, 180, True),
        ("LANDSCAPE_720", 1280, 720, False),
        ("PORTRAIT_720", 720, 1280, False),
    ],
)
def test_legacy_complete_encoding_argv_stays_identical(
    tmp_path, media, monkeypatch, frame, width, height, source_frame
):
    source, commands = media[2][False], []
    info = probe_media(source, ffprobe_path=media[1])
    monkeypatch.setattr(media_module, "probe_media", lambda *a, **k: info)

    def capture(command, **kwargs):
        commands.append(command)
        raise MediaError("STOP_CAPTURE", "No encoder is invoked for this argv assertion")

    monkeypatch.setattr(media_module, "_run_process", capture)
    with pytest.raises(MediaError) as raised:
        burn_caption_clip(
            source,
            tmp_path / "clip.mp4",
            tmp_path,
            width=width,
            height=height,
            source_frame=source_frame,
            expected_duration_ms=2000,
            has_audio=False,
            ffmpeg_path=media[0],
            guard=lambda: None,
            on_encoding=lambda value: None,
            on_phase=lambda phase: None,
        )
    assert raised.value.code == "STOP_CAPTURE"
    command = commands[0]
    filters = (
        ""
        if source_frame
        else f"scale={width}:{height}:force_original_aspect_ratio=decrease:force_divisible_by=2,"
    )
    filters += f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,ass=filename=captions.ass:fontsdir=fonts"
    assert command == [
        media[0],
        "-hide_banner",
        "-loglevel",
        "warning",
        "-nostdin",
        "-y",
        "-i",
        str(info.path),
        "-map",
        "0:v:0",
        "-map",
        "0:a:0?",
        "-vf",
        filters,
        "-c:v",
        "libx264",
        "-threads",
        "2",
        "-preset",
        "veryfast",
        "-crf",
        "23",
        "-pix_fmt",
        "yuv420p",
        "-c:a",
        "aac",
        "-movflags",
        "+faststart",
        "-nostats",
        "-stats_period",
        "0.5",
        "-progress",
        "ffmpeg-progress.txt",
        command[-1],
    ]


def test_blur_geometry_is_bounded_even_for_all_sampled_aspect_extremes():
    for width in (2, 3, 4, 31, 32, 359, 360, 640, 1280, 4095, 4096):
        for height in (2, 3, 4, 31, 32, 359, 360, 640, 1280, 4095, 4096):
            fw, fh = _portrait_blur_foreground_size(width, height)
            assert all(value % 2 == 0 for value in (fw, fh))
            assert 2 <= fw <= 720 and 2 <= fh <= 1280 and (fw == 720 or fh == 1280)
    assert _portrait_blur_foreground_size(640, 360) == (720, 404)
    assert _portrait_blur_foreground_size(4096, 2) == (720, 2)
    assert _portrait_blur_foreground_size(2, 4096) == (2, 1280)


@pytest.mark.parametrize("audio", [True, False])
def test_real_blur_preserves_every_foreground_corner_softens_darkens_background_and_keeps_timing(
    tmp_path, pattern, font, monkeypatch, audio
):
    ctx, events, render = prepared(tmp_path, pattern, font, audio=audio)
    if not audio:
        render["style_preset"] = "DARK_BOX_V1"
        ctx.lease = dataclasses.replace(ctx.lease, config={"export": configuration(render)})
    before, measured_us = sha256(ctx.storage_root / "clean/clip.mp4"), []
    original = RenderProgress.encoding

    def record(progress, us):
        measured_us.append(us)
        return original(progress, us)

    monkeypatch.setattr(RenderProgress, "encoding", record)
    result = ExportStageExecutor(ffmpeg_path=pattern[0], ffprobe_path=pattern[1])(ctx)
    assert result["data_kind"] == "captioned_export"
    assert result["render_mode"] == "CAPTIONED" and "caption_render" not in result
    assert sha256(ctx.storage_root / "clean/clip.mp4") == before
    info = json.loads((ctx.directory / "media-info.json").read_text())
    assert (info["width"], info["height"], info["video_codec"]) == (720, 1280, "h264")
    assert info["audio_codec"] == ("aac" if audio else None)
    assert abs(info["duration_ms"] - render["input_media"]["duration_ms"]) <= 100
    rgb = pixels(pattern[0], ctx.directory / "clip.mp4", 0.12, 720, 1280)
    # All four original edge markers survive in the complete contained foreground.
    for x, y, high in ((24, 462, {0}), (684, 462, {1}), (24, 798, {2}), (684, 798, {0, 1})):
        value = mean_color(patch(rgb, x, y))
        assert all(value[i] > 180 if i in high else value[i] < 50 for i in range(3))
    background = mean_color(patch(rgb, 300, 100, 100, 80))
    foreground = mean_color(patch(rgb, 300, 550, 100, 80))
    assert 10 < sum(background) / 3 < sum(foreground) / 3 - 2
    assert background[2] > background[0] + 15  # Comes from the blue-grey source, not solid black.
    assert horizontal_detail(rgb, 300, 100) < horizontal_detail(rgb, 300, 550) / 4
    assert sum(mean_color(patch(rgb, 300, 1180, 100, 40))) > 30
    # No bright marks exist in the lower background except active white ASS text.
    for at, visible in ((0.12, False), (0.6, True), (1.6, False)):
        sample = pixels(pattern[0], ctx.directory / "clip.mp4", at, 720, 1280)
        bright = sum(min(pixel) > 180 for pixel in patch(sample, 28, 900, 664, 340))
        assert (bright > 40) == visible
    assert measured_us and max(measured_us) > 0
    assert events[0]["phase"] == "PREPARING" and events[-1]["phase"] == "PUBLISHING"
    assert all(
        a["sequence"] < b["sequence"] and a["processed_ms"] <= b["processed_ms"]
        for a, b in zip(events, events[1:])
    )
    assert {row["kind"] for row in result["artifacts"]} == {
        "clip",
        "media_info",
        "caption_render_info",
    }


@pytest.mark.parametrize("width,height", [(4096, 2), (2, 4096), (2, 2)])
def test_real_blur_extreme_aspect_ratios_decode_with_bounded_graph_and_foreground_endpoints(
    tmp_path, media, font, monkeypatch, width, height
):
    ffmpeg, ffprobe, _ = media
    source = tmp_path / "extreme.mp4"
    filters = (
        f"drawbox=x={width // 2}:y=0:w={width // 2}:h={height}:color=blue:t=fill"
        if width > height
        else f"drawbox=x=0:y={height // 2}:w={width}:h={height // 2}:color=blue:t=fill"
        if height > width
        else "null"
    )
    subprocess.run(
        [
            ffmpeg,
            "-v",
            "error",
            "-nostdin",
            "-y",
            "-f",
            "lavfi",
            "-i",
            f"color=c=red:s={width}x{height}:r=10:d=2",
            "-vf",
            filters,
            "-c:v",
            "libx264",
            "-threads",
            "2",
            "-pix_fmt",
            "yuv420p",
            str(source),
        ],
        capture_output=True,
        timeout=30,
        check=True,
    )
    ctx, _, _ = prepared(tmp_path / "case", (ffmpeg, ffprobe, {False: source}), font)
    commands, original = [], media_module._run_process

    def capture(command, **kwargs):
        if "-progress" in command:
            commands.append(command)
        return original(command, **kwargs)

    monkeypatch.setattr(media_module, "_run_process", capture)
    assert (
        ExportStageExecutor(ffmpeg_path=ffmpeg, ffprobe_path=ffprobe)(ctx)["data_kind"]
        == "captioned_export"
    )
    command = commands[0]
    assert command[command.index("-filter_complex_threads") + 1] == "1" and "-vf" not in command
    graph = command[command.index("-filter_complex") + 1]
    assert "force_original_aspect_ratio=increase" not in graph
    assert "crop=" not in graph
    assert "[background]scale=360:640,gblur=sigma=20:steps=2,scale=720:1280" in graph
    assert "eq=contrast=0.8:brightness=-0.04:saturation=0.8" in graph
    assert graph.endswith("setsar=1,ass=filename=captions.ass:fontsdir=fonts[v]")
    assert "-shortest" not in command and "setpts" not in graph and "trim" not in graph
    rgb = pixels(ffmpeg, ctx.directory / "clip.mp4", 0.12, 720, 1280)
    if width > height:
        red, blue = patch(rgb, 60, 638, 40, 2), patch(rgb, 620, 638, 40, 2)
    elif height > width:
        red, blue = patch(rgb, 358, 60, 2, 40), patch(rgb, 358, 1180, 2, 40)
    else:
        red, blue = patch(rgb, 60, 320, 40, 40), None
    assert mean_color(red)[0] > mean_color(red)[2] + 80
    if blue is not None:
        assert mean_color(blue)[2] > mean_color(blue)[0] + 80


@pytest.mark.parametrize("phase", ["encode", "decode"])
def test_real_blur_guard_loss_reaps_its_actual_child_and_never_publishes(
    tmp_path, pattern, font, monkeypatch, phase
):
    ctx, _, _ = prepared(tmp_path, pattern, font)
    children, original = [], subprocess.Popen

    def launch(command, *args, **kwargs):
        child = original(command, *args, **kwargs)
        if (phase == "encode" and "-progress" in command) or (
            phase == "decode" and command[-1] == "-" and "null" in command
        ):
            children.append(child)
            ctx.guard.lose()
        return child

    monkeypatch.setattr(media_module.subprocess, "Popen", launch)
    with pytest.raises(WorkerError, match="LEASE_LOST"):
        ExportStageExecutor(ffmpeg_path=pattern[0], ffprobe_path=pattern[1])(ctx)
    assert children and all(child.poll() is not None for child in children)
    assert not list(ctx.directory.glob(".clip.*.mp4"))
    assert all(
        not (ctx.directory / name).exists()
        for name in ("clip.mp4", "media-info.json", "export-checkpoint.json", "manifest.json")
    )
