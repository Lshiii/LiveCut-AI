"""Caption burn-in uses synthetic local media, pinned fonts, and no providers."""

import dataclasses
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from collections import Counter
from pathlib import Path

import pytest
from livecut.media import export_composed_clip, probe_media
from livecut.worker import Lease, LeaseGuard, StageContext, WorkerError, _native_path, sha256
from livecut.worker_caption_render import (
    FONT_FAMILY,
    FONT_HASH,
    FONT_PROFILE,
    FONT_SIZE,
    Cue,
    FontMetrics,
    RenderProgress,
    ass_time,
    build_ass,
    canonical,
    literal_ass,
    read_cues,
    validate_spec,
)
from livecut.worker_client import WorkerClientError
from livecut.worker_export import ExportSpec, ExportStageExecutor
from test_worker_export import export_claim, export_config


def spec(frame="SOURCE", style="WHITE_OUTLINE_V1", audio=True):
    return {
        "schema_version": 1,
        "profile_version": "caption-burn-v1",
        "source_export_id": "021e78e2-86ef-4f1d-a541-cf528f1a9334",
        "source_clip_id": "431439e4-70d2-440e-9cc2-87edc608e7e4",
        "source_snapshot_hash": "a" * 64,
        "input_media": {
            "key": "clean/clip.mp4",
            "sha256": "b" * 64,
            "size_bytes": 1,
            "duration_ms": 2000,
            "width": 320,
            "height": 180,
            "has_audio": audio,
        },
        "captions": {
            "revision": 3,
            "content_hash": "c" * 64,
            "cues_key": "captions/cues.json",
            "cues_sha256": "d" * 64,
            "duration_ms": 2010,
        },
        "style_preset": style,
        "frame_preset": frame,
        "output": dict(
            zip(
                ("width", "height"),
                {"SOURCE": (320, 180), "LANDSCAPE_720": (1280, 720), "PORTRAIT_720": (720, 1280)}[
                    frame
                ],
            )
        ),
        "font": {
            "profile_id": FONT_PROFILE,
            "family": FONT_FAMILY,
            "key": "fonts/fixed.otf",
            "sha256": FONT_HASH,
            "size_bytes": FONT_SIZE,
        },
        "timing_basis": "CLIP_RELATIVE_ASS_CS_FLOOR",
    }


def configuration(render):
    return export_config(
        start_ms=0,
        end_ms=render["input_media"]["duration_ms"],
        encoding_profile="h264-aac-burn-v1",
        render_mode="CAPTIONED",
        caption_render_hash=hashlib.sha256(canonical(render)).hexdigest(),
        caption_render=render,
    )


@pytest.mark.parametrize(
    "change",
    [
        lambda value: value.update(extra=1),
        lambda value: value["font"].update(sha256="e" * 64),
        lambda value: value["font"].update(size_bytes=True),
        lambda value: value["input_media"].update(has_audio=1),
        lambda value: value["input_media"].update(key="../outside"),
        lambda value: value["input_media"].update(key="clean//clip.mp4"),
        lambda value: value["output"].update(width=319),
        lambda value: value["captions"].update(revision=True),
    ],
)
def test_render_spec_rejects_unfrozen_fields_and_geometry(change):
    render = spec()
    change(render)
    with pytest.raises(WorkerError):
        ExportSpec.from_lease(Lease.parse(export_claim(config={"export": configuration(render)})))


def test_render_hash_profile_and_legacy_exact_fields_are_separate():
    render = spec()
    validate_spec(render, hashlib.sha256(canonical(render)).hexdigest())
    config = configuration(render)
    lease = Lease.parse(export_claim(config={"export": config}))
    assert ExportSpec.from_lease(lease).caption_render == render
    for extra in (
        {"parts": []},
        {"encoding_profile": "h264-aac-v1"},
        {"caption_render_hash": "e" * 64},
    ):
        with pytest.raises(WorkerError):
            Lease.parse(export_claim(config={"export": {**config, **extra}}))
    assert ExportSpec.from_lease(Lease.parse(export_claim())).caption_render is None


@pytest.mark.parametrize(
    "cues,code",
    [
        ([], "CAPTION_RENDER_EMPTY"),
        (
            [{"id": "c1", "start_ms": 1001, "end_ms": 1009, "text": "短句"}],
            "CAPTION_RENDER_TIMING_UNSUPPORTED",
        ),
        (
            [{"id": "c1", "start_ms": 0, "end_ms": 100, "text": "😀" * 1001}],
            "CAPTION_RENDER_INVALID_INPUT",
        ),
        (
            [{"id": "c1", "start_ms": 0, "end_ms": 100, "text": "x\u0000"}],
            "CAPTION_RENDER_INVALID_INPUT",
        ),
        (
            [{"id": "c1", "start_ms": False, "end_ms": 100, "text": "x"}],
            "CAPTION_RENDER_INVALID_INPUT",
        ),
        (
            [
                {"id": "c1", "start_ms": 0, "end_ms": 100, "text": "x"},
                {"id": "c2", "start_ms": 99, "end_ms": 200, "text": "y"},
            ],
            "CAPTION_RENDER_INVALID_INPUT",
        ),
    ],
)
def test_cues_reject_empty_quantized_empty_overlap_controls_and_utf16_overflow(
    tmp_path, cues, code
):
    path = tmp_path / "cues.json"
    path.write_bytes(canonical({"schema_version": 1, "duration_ms": 2000, "cues": cues}))
    with pytest.raises(WorkerError, match=code):
        read_cues(path, 2000)


def test_cues_keep_ms_gaps_and_ass_literal_markup():
    assert ass_time(1239) == "0:00:01.23"
    assert literal_ass("{\\alpha&HFF&}<b>原文</b>\\N\n下一行") == (
        "\\{{}\\\u2060alpha&HFF&}<b>原文</b>\\\u2060N\\N下一行"
    )


@pytest.fixture(scope="module")
def font():
    path = Path(
        os.environ.get(
            "LIVECUT_CAPTION_FONT_PATH",
            Path(__file__).resolve().parents[2]
            / "data/fonts/noto-sans-cjk-sc-2.004/NotoSansCJKsc-Regular.otf",
        )
    )
    if not path.is_file():
        pytest.skip("install the pinned Noto font for real caption tests")
    data = path.read_bytes()
    assert len(data) == FONT_SIZE and hashlib.sha256(data).hexdigest() == FONT_HASH
    return path, FontMetrics(data)


def test_pinned_font_checks_actual_glyphs_and_rejects_overflow_without_truncating(font):
    _, metrics = font
    assert metrics.advance("中", 32) > 0 and metrics.advance("<", 32) > 0
    with pytest.raises(WorkerError, match="CAPTION_RENDER_GLYPH_UNSUPPORTED"):
        metrics.advance("\U0010ffff", 32)
    with pytest.raises(WorkerError, match="CAPTION_RENDER_TEXT_TOO_LARGE"):
        build_ass([Cue("c1", 0, 1000, "长" * 2000)], spec(), metrics)
    text = "中文 <b>原文</b> {\\alpha&HFF&} \\N\n第二行"
    ass = build_ass([Cue("c1", 259, 1259, text)], spec(), metrics).decode()
    assert "0:00:00.25,0:00:01.25" in ass
    assert "<b>" in ass and "\\\u2060N" in ass and "\\{{}" in ass


@pytest.fixture(scope="module")
def media(tmp_path_factory):
    ffmpeg = os.environ.get("LIVECUT_FFMPEG_PATH") or shutil.which("ffmpeg")
    ffprobe = os.environ.get("LIVECUT_FFPROBE_PATH") or shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        pytest.skip("real FFmpeg is required")
    root, sources = tmp_path_factory.mktemp("synthetic-caption-media"), {}
    for audio in (True, False):
        original = root / f"original-{audio}.mp4"
        command = [ffmpeg, "-v", "error", "-nostdin", "-y"]
        for color in ("red", "blue", "green", "yellow"):
            command += ["-f", "lavfi", "-i", f"color=c={color}:s=320x180:r=25:d=1"]
        if audio:
            command += ["-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=4"]
        command += ["-filter_complex", "[0:v][1:v][2:v][3:v]concat=n=4:v=1:a=0[v]", "-map", "[v]"]
        if audio:
            command += ["-map", "4:a:0", "-c:a", "aac"]
        command += ["-c:v", "libx264", "-pix_fmt", "yuv420p", str(original)]
        subprocess.run(command, capture_output=True, timeout=30, check=True)
        clean = root / f"clean-{audio}.mp4"
        export_composed_clip(
            original,
            clean,
            [{"start_ms": 0, "end_ms": 1000}, {"start_ms": 3000, "end_ms": 4000}],
            ffmpeg_path=ffmpeg,
            ffprobe_path=ffprobe,
        )
        sources[audio] = clean
    return ffmpeg, ffprobe, sources


def context(
    tmp_path, media, font, *, frame="SOURCE", style="WHITE_OUTLINE_V1", audio=True, text=None
):
    ffmpeg, ffprobe, sources = media
    render = spec(frame, style, audio)
    root = tmp_path / "中文 parent ' path"
    for key, source in (("clean/clip.mp4", sources[audio]), ("fonts/fixed.otf", font[0])):
        target = root / key
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    info = probe_media(root / "clean/clip.mp4", ffprobe_path=ffprobe)
    render["input_media"].update(
        sha256=sha256(root / "clean/clip.mp4"),
        size_bytes=info.size_bytes,
        duration_ms=info.duration_ms,
    )
    cues = {
        "schema_version": 1,
        "duration_ms": 2010,
        "cues": [
            {
                "id": "c1",
                "start_ms": 259,
                "end_ms": 1259,
                "text": text or "中文 <b>字</b> {\\alpha&HFF&} \\N\n第二行",
            }
        ],
    }
    cue_file = root / "captions/cues.json"
    cue_file.parent.mkdir()
    cue_file.write_bytes(canonical(cues))
    render["captions"]["cues_sha256"] = sha256(cue_file)
    # Original video intentionally does not exist. Caption rendering must use
    # only the verified already-composed clean clip, never the source parts.
    lease = Lease.parse(
        export_claim(
            source_key="missing/original.mp4",
            source_hash="f" * 64,
            config={"export": configuration(render)},
        )
    )
    directory = root / lease.attempt_key
    directory.mkdir(parents=True)
    events = []
    ctx = StageContext(
        lease, root, directory, LeaseGuard(lease, threading.Event()), render_progress=events.append
    )
    return ctx, events, render


def pixels(ffmpeg, path, seconds, width, height):
    result = subprocess.run(
        [
            ffmpeg,
            "-v",
            "error",
            "-ss",
            str(seconds),
            "-i",
            str(_native_path(path)),
            "-frames:v",
            "1",
            "-pix_fmt",
            "rgb24",
            "-f",
            "rawvideo",
            "-",
        ],
        capture_output=True,
        timeout=30,
        check=True,
    )
    assert len(result.stdout) == width * height * 3
    return result.stdout


@pytest.mark.parametrize("frame", ["SOURCE", "LANDSCAPE_720", "PORTRAIT_720"])
@pytest.mark.parametrize("style", ["WHITE_OUTLINE_V1", "DARK_BOX_V1"])
def test_real_burn_all_frames_styles_literal_cjk_timing_optional_audio_and_no_recut(
    tmp_path, media, font, frame, style
):
    ctx, events, render = context(
        tmp_path, media, font, frame=frame, style=style, audio=style == "WHITE_OUTLINE_V1"
    )
    ffmpeg, ffprobe, _ = media
    old_hash = sha256(ctx.storage_root / "clean/clip.mp4")
    manifest = ExportStageExecutor(ffmpeg_path=ffmpeg, ffprobe_path=ffprobe)(ctx)
    assert manifest["data_kind"] == "captioned_export" and manifest["render_mode"] == "CAPTIONED"
    assert "caption_render" not in manifest
    assert {a["kind"] for a in manifest["artifacts"]} == {
        "clip",
        "media_info",
        "caption_render_info",
    }
    assert sha256(ctx.storage_root / "clean/clip.mp4") == old_hash
    info = json.loads((ctx.directory / "media-info.json").read_text())
    width, height = render["output"]["width"], render["output"]["height"]
    assert (info["width"], info["height"]) == (width, height)
    assert abs(info["duration_ms"] - render["input_media"]["duration_ms"]) <= 100
    assert info["audio_codec"] == ("aac" if style == "WHITE_OUTLINE_V1" else None)
    for at, visible in ((0.12, False), (0.6, True), (1.6, False)):
        rgb = pixels(ffmpeg, ctx.directory / "clip.mp4", at, width, height)
        white = sum(
            r > 140 and g > 140 and b > 140 for r, g, b in zip(rgb[::3], rgb[1::3], rgb[2::3])
        )
        assert (white > 20) == visible
    assert events[0]["phase"] == "PREPARING" and events[-1]["phase"] == "PUBLISHING"
    assert all(a["sequence"] < b["sequence"] for a, b in zip(events, events[1:]))
    assert all(0 <= event["processed_ms"] <= event["total_ms"] for event in events)


def test_same_attempt_recovery_hash_binding_and_no_unverified_reuse(
    tmp_path, media, font, monkeypatch
):
    ctx, _, _ = context(tmp_path, media, font)
    executor = ExportStageExecutor(ffmpeg_path=media[0], ffprobe_path=media[1])
    original = executor(ctx)
    monkeypatch.setattr(
        "livecut.worker_export.burn_caption_clip", lambda *a, **k: pytest.fail("must reuse")
    )
    assert executor(ctx) == original
    (ctx.directory / "clip.mp4").write_bytes(b"tampered")
    with pytest.raises(WorkerError, match="EXPORT_ARTIFACT_CHANGED"):
        executor(ctx)


def test_same_attempt_progress_reentry_keeps_server_phase_and_processed_high_water(
    tmp_path, media, font
):
    ctx, _, _ = context(tmp_path, media, font)
    received = []

    def strict_java(value):
        if received:
            old = received[-1]
            assert value["sequence"] > old["sequence"]
            assert value["processed_ms"] >= old["processed_ms"]
            assert RenderProgress(ctx, value["total_ms"]).phases.index(value["phase"]) >= (
                RenderProgress(ctx, value["total_ms"]).phases.index(old["phase"])
            )
        received.append(value)

    ctx.render_progress = strict_java
    executor = ExportStageExecutor(ffmpeg_path=media[0], ffprobe_path=media[1])
    original = executor(ctx)
    assert received[-1]["phase"] == "PUBLISHING"
    assert executor(ctx) == original
    assert received[-1]["phase"] == "PUBLISHING"
    assert all(
        item["processed_ms"] == item["total_ms"]
        for item in received
        if item["phase"] == "PUBLISHING"
    )


@pytest.mark.parametrize("temporary", [True, False])
def test_stage_progress_network_is_best_effort_but_fence_stops_executor(tmp_path, temporary):
    from livecut.worker import StageWorker
    from test_worker import FakeClient

    client, continued = FakeClient(), []
    client.reply = export_claim(config={"export": configuration(spec())})
    original = client.callback

    def callback(action, stage, value):
        if action == "render-progress":
            assert {"attempt", "run_token", "sequence", "phase", "processed_ms", "total_ms"} == set(
                value
            )
            raise WorkerClientError(
                "JAVA_UNAVAILABLE" if temporary else "STALE_ATTEMPT", retryable=temporary
            )
        return original(action, stage, value)

    client.callback = callback

    def execute(current):
        current.render_progress(
            {"sequence": 1, "phase": "PREPARING", "processed_ms": 0, "total_ms": 2000}
        )
        continued.append(True)
        raise WorkerError("SYNTHETIC_DONE")

    runner = StageWorker(
        client,
        tmp_path / "storage",
        tmp_path / "journal",
        "render-worker",
        execute,
        registry_enabled=False,
    )
    assert runner.run_once(stage_type="EXPORT_CLIP") == "failed"
    assert bool(continued) == temporary
    assert client.callbacks[-1][2]["error_code"] == (
        "SYNTHETIC_DONE" if temporary else "STALE_ATTEMPT"
    )


@pytest.mark.parametrize("phase", ["encode", "decode"])
def test_burn_guard_loss_reaps_owned_media_child_and_never_publishes(
    tmp_path, media, font, monkeypatch, phase
):
    ctx, _, _ = context(tmp_path, media, font)
    started, children = tmp_path / "started", []
    actual_popen = subprocess.Popen

    def launch(command, *args, **kwargs):
        encoding = "-progress" in command
        decoding = command[-1] == "-" and "null" in command
        if encoding or decoding:
            payload = "import time; from pathlib import Path; "
            if encoding:
                payload += "import shutil; shutil.copyfile(" + repr(
                    str(_native_path(ctx.storage_root / "clean/clip.mp4"))
                )
                payload += ", " + repr(command[-1]) + "); "
            if encoding == (phase == "encode"):
                payload += "Path(" + repr(str(started)) + ").touch(); time.sleep(6)"
            else:
                payload += "pass"
            command = [getattr(sys, "_base_executable", sys.executable), "-c", payload]
        child = actual_popen(command, *args, **kwargs)
        children.append(child)
        return child

    monkeypatch.setattr("livecut.media.subprocess.Popen", launch)

    def cancel():
        until = time.monotonic() + 5
        while not started.exists() and time.monotonic() < until:
            time.sleep(0.01)
        if started.exists():
            ctx.guard.lose()

    interrupter = threading.Thread(target=cancel, daemon=True)
    interrupter.start()
    began = time.monotonic()
    try:
        with pytest.raises(WorkerError, match="LEASE_LOST"):
            ExportStageExecutor(ffmpeg_path=media[0], ffprobe_path=media[1])(ctx)
        assert time.monotonic() - began < 3
        assert started.exists() and all(child.poll() is not None for child in children)
        for filename in ("clip.mp4", "media-info.json", "export-checkpoint.json", "manifest.json"):
            assert not (ctx.directory / filename).exists()
        assert not list(ctx.directory.glob(".clip.*.mp4"))
    finally:
        ctx.guard.lose()
        interrupter.join(6)
        for child in children:
            if child.poll() is None:
                child.kill()
            child.wait(timeout=2)


@pytest.mark.parametrize("target", ["clean/clip.mp4", "captions/cues.json", "fonts/fixed.otf"])
def test_input_tampering_fails_before_encoding(tmp_path, media, font, monkeypatch, target):
    ctx, _, _ = context(tmp_path, media, font)
    (ctx.storage_root / target).write_bytes(b"tampered")
    monkeypatch.setattr(
        "livecut.worker_export.burn_caption_clip", lambda *a, **k: pytest.fail("must not encode")
    )
    with pytest.raises(WorkerError, match="CAPTION_RENDER_SOURCE_HASH_MISMATCH"):
        ExportStageExecutor(ffmpeg_path=media[0], ffprobe_path=media[1])(ctx)


def test_missing_glyph_and_long_text_are_real_failures_before_encoder(
    tmp_path, media, font, monkeypatch
):
    monkeypatch.setattr(
        "livecut.worker_export.burn_caption_clip", lambda *a, **k: pytest.fail("must not encode")
    )
    for index, (text, code) in enumerate(
        (
            ("\U0010ffff", "CAPTION_RENDER_GLYPH_UNSUPPORTED"),
            ("长" * 2000, "CAPTION_RENDER_TEXT_TOO_LARGE"),
        )
    ):
        ctx, _, _ = context(tmp_path / str(index), media, font, text=text)
        with pytest.raises(WorkerError, match=code):
            ExportStageExecutor(ffmpeg_path=media[0], ffprobe_path=media[1])(ctx)
        assert not (ctx.directory / "clip.mp4").exists()
        assert not (ctx.directory / "manifest.json").exists()
        assert not (ctx.directory / "export-checkpoint.json").exists()


LONG_ENGLISH = (
    "Different programming languages let people describe instructions in different ways. "
    "A browser reads the page, builds a structure, applies its styles, and displays the result. "
    "Preserve every word, punctuation mark and space, including literal HTML tags and symbols."
)


def ass_layout(data):
    lines = data.decode("utf-8").splitlines()
    size = int(next(line.split(",")[2] for line in lines if line.startswith("Style: Default,")))
    text = [line.split(",", 9)[9] for line in lines if line.startswith("Dialogue:")]
    # Split only program-added ASS line breaks. Original literal backslashes
    # contain U+2060, so a literal backslash-N cannot be mistaken for a break.
    decoded = [
        [
            part.replace("\\h", " ").replace("\\{{}", "{").replace("\\\u2060", "\\")
            for part in item.split("\\N")
        ]
        for item in text
    ]
    return size, decoded


@pytest.mark.parametrize(
    "frame,style,expected",
    [
        (
            "SOURCE",
            "WHITE_OUTLINE_V1",
            "ec9a00b7d58aec39cf35e9ead62433e168497374d33b21e8127cc5e9f2885fc3",
        ),
        (
            "SOURCE",
            "DARK_BOX_V1",
            "5e4a3b5fd535789fb0ff087117e3f5e7e3c60290f861298c7b6b95f5ff0e53c2",
        ),
        (
            "LANDSCAPE_720",
            "WHITE_OUTLINE_V1",
            "f87f9914193a94731e7a6f1d0da3b6e2c0cc3a9416328a2bca9f848d7bf88252",
        ),
        (
            "LANDSCAPE_720",
            "DARK_BOX_V1",
            "fff98b7ceca649bc995362830d718e9beacc67e4468f97e734eeff8a10b730de",
        ),
        (
            "PORTRAIT_720",
            "WHITE_OUTLINE_V1",
            "a67c27e8f62d48b689139b19bc1b36308511f7882f0ec75cc99ade25eaf81096",
        ),
        (
            "PORTRAIT_720",
            "DARK_BOX_V1",
            "a7fa4d3cd2d9461b0db1194ba93ba025a03af03312baab83fd14b4e6d2253756",
        ),
    ],
)
def test_default_fitting_unwrapped_ass_bytes_remain_identical(font, frame, style, expected):
    # Captured before bounded sizing/word wrapping. Includes literal markup,
    # explicit empty lines and trailing spaces, without requiring new wraps.
    text = "中文 <b>X</b> {x} \\N\n\n原文  "
    data = build_ass([Cue("c1", 259, 1259, text)], spec(frame, style), font[1])
    assert hashlib.sha256(data).hexdigest() == expected


def test_long_english_uses_one_largest_fitting_font_for_every_cue(font):
    assert len(LONG_ENGLISH) == 264
    cues = [Cue("c1", 0, 200, "short words"), Cue("c2", 259, 14859, LONG_ENGLISH)]
    data = build_ass(cues, spec("PORTRAIT_720"), font[1])
    size, lines = ass_layout(data)
    assert size == 27 and 26 <= size < 32
    assert len(lines) == 2 and len(lines[1]) == 6
    assert "".join(lines[1]) == LONG_ENGLISH
    assert all(line[-1:].isspace() for line in lines[1][:-1])
    assert data.count(b"Style: Default,") == 1
    assert all(
        line.split(",")[3] == "Default"
        for line in data.decode().splitlines()
        if line.startswith("Dialogue:")
    )
    available = (720 - 2 * round(720 * 0.045) - 4) / 1.08
    assert all(
        sum(font[1].advance(ch, size) for ch in line) <= available + 1e-7 for line in lines[1]
    )


def test_real_font_fits_at_floor_but_refuses_beyond_the_floor(font):
    fits = LONG_ENGLISH + " More detail."
    size, lines = ass_layout(build_ass([Cue("c1", 0, 14600, fits)], spec("PORTRAIT_720"), font[1]))
    assert size == 26 and "".join(lines[0]) == fits
    seen = set()

    class MeasuredFont:
        def advance(self, ch, size):
            seen.add(size)
            return font[1].advance(ch, size)

    with pytest.raises(WorkerError, match="CAPTION_RENDER_TEXT_TOO_LARGE"):
        build_ass(
            [Cue("c1", 0, 14600, fits + " More detail.")], spec("PORTRAIT_720"), MeasuredFont()
        )
    assert seen == set(range(26, 33)), "Never shrink below 80% to hide unsupported content"


@pytest.mark.parametrize(
    "text",
    [
        "中文 explained with English words and  spaces 保留 {x} <b>literal</b> \\N " * 3,
        "中文内容" * 20,
        "X" * 180,
    ],
)
def test_added_wraps_keep_entire_character_sequence_and_whole_normal_words(font, text):
    _, lines = ass_layout(build_ass([Cue("c1", 0, 1000, text)], spec("PORTRAIT_720"), font[1]))
    assert len(lines[0]) > 1
    assert "".join(lines[0]) == text
    if " " in text:
        assert Counter(re.findall(r"[A-Za-z]+", text)) == Counter(
            word for line in lines[0] for word in re.findall(r"[A-Za-z]+", line)
        )


def test_explicit_newlines_empty_lines_and_spaces_are_preserved(font):
    text = "first  line\n\nsecond line  "
    size, lines = ass_layout(build_ass([Cue("c1", 0, 1000, text)], spec("PORTRAIT_720"), font[1]))
    assert size == 32 and lines == [["first  line", "", "second line  "]]


def test_real_long_english_portrait_burn_is_visible_in_bounds_and_decodes(tmp_path, media, font):
    ffmpeg, ffprobe, _ = media
    clean = tmp_path / "long-english-clean.mp4"
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
            "color=c=red:s=640x360:r=10:d=15.2",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            str(clean),
        ],
        capture_output=True,
        timeout=30,
        check=True,
    )


    synthetic_media = (ffmpeg, ffprobe, {False: clean})
    ctx, _, render = context(tmp_path, synthetic_media, font, frame="PORTRAIT_720", audio=False)
    cues = {
        "schema_version": 1,
        "duration_ms": 15200,
        "cues": [
            {"id": "c1", "start_ms": 0, "end_ms": 100, "text": "short words"},
            {"id": "c2", "start_ms": 259, "end_ms": 14859, "text": LONG_ENGLISH},
        ],
    }
    cue_path = ctx.storage_root / "captions/cues.json"
    cue_path.write_bytes(canonical(cues))
    render["input_media"].update(width=640, height=360)
    render["captions"].update(duration_ms=15200, cues_sha256=sha256(cue_path))
    ctx.lease = dataclasses.replace(ctx.lease, config={"export": configuration(render)})
    result = ExportStageExecutor(ffmpeg_path=ffmpeg, ffprobe_path=ffprobe)(ctx)
    assert result["data_kind"] == "captioned_export"
    size, lines = ass_layout((ctx.directory / "caption-render/captions.ass").read_bytes())
    assert size == 27 and "".join(lines[1]) == LONG_ENGLISH and len(lines[1]) == 6
    info = json.loads((ctx.directory / "media-info.json").read_text())
    assert (info["width"], info["height"]) == (720, 1280)
    assert abs(info["duration_ms"] - 15200) <= 200
    for at, visible in ((0.12, False), (1.0, True), (15.0, False)):
        rgb = pixels(ffmpeg, ctx.directory / "clip.mp4", at, 720, 1280)
        bright = [
            index
            for index, (r, g, b) in enumerate(zip(rgb[::3], rgb[1::3], rgb[2::3]))
            if min(r, g, b) > 140
        ]
        assert (len(bright) > 100) == visible
        if visible:
            assert min(index % 720 for index in bright) >= 28
            assert max(index % 720 for index in bright) < 692
            assert min(index // 720 for index in bright) >= 768
            assert max(index // 720 for index in bright) < 1252
    subprocess.run(
        [
            ffmpeg,
            "-v",
            "error",
            "-nostdin",
            "-xerror",
            "-i",
            str(_native_path(ctx.directory / "clip.mp4")),
            "-f",
            "null",
            "-",
        ],
        capture_output=True,
        timeout=30,
        check=True,
    )


def test_real_tiny_source_respects_height_limit_and_landscape_can_fit(tmp_path, media, font):
    ffmpeg, ffprobe, _ = media
    clean = tmp_path / "tiny-height-clean.mp4"
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
            "color=c=red:s=640x32:r=10:d=2",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            str(clean),
        ],
        capture_output=True,
        timeout=30,
        check=True,
    )
    synthetic_media = (ffmpeg, ffprobe, {False: clean})
    for frame in ("SOURCE", "LANDSCAPE_720"):
        ctx, _, render = context(
            tmp_path / frame, synthetic_media, font, frame=frame, audio=False, text="short words"
        )
        render["input_media"].update(width=640, height=32)
        if frame == "SOURCE":
            render["output"].update(width=640, height=32)
        ctx.lease = dataclasses.replace(ctx.lease, config={"export": configuration(render)})
        executor = ExportStageExecutor(ffmpeg_path=ffmpeg, ffprobe_path=ffprobe)
        if frame == "SOURCE":
            with pytest.raises(WorkerError, match="CAPTION_RENDER_TEXT_TOO_LARGE"):
                executor(ctx)
            for name in ("clip.mp4", "manifest.json", "export-checkpoint.json"):
                assert not (ctx.directory / name).exists()
        else:
            assert executor(ctx)["data_kind"] == "captioned_export"
            info = json.loads((ctx.directory / "media-info.json").read_text())
            assert (info["width"], info["height"]) == (1280, 720)
            rgb = pixels(ffmpeg, ctx.directory / "clip.mp4", 0.6, 1280, 720)
            assert sum(min(r, g, b) > 140 for r, g, b in zip(rgb[::3], rgb[1::3], rgb[2::3])) > 100
