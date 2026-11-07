"""Frozen caption render inputs and literal libass text, without model access."""

from __future__ import annotations

import bisect
import hashlib
import json
import os
import re
import struct
import time
import unicodedata
from dataclasses import dataclass
from pathlib import PurePosixPath
from uuid import UUID

from .worker import StageContext, WorkerError, _native_path, _write, sha256, shared_path

PROFILE = "caption-burn-v1"
FONT_PROFILE = "noto-sans-cjk-sc-2.004"
FONT_FAMILY = "Noto Sans CJK SC"
FONT_HASH = "2c76254f6fc379fddfce0a7e84fb5385bb135d3e399294f6eeb6680d0365b74b"
FONT_SIZE = 16437364
MAX_SAFE = 2**53 - 1


def invalid(code="CAPTION_RENDER_INVALID_INPUT"):
    return WorkerError(code)


def canonical(value):
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")


def fields(value, names):
    if not isinstance(value, dict) or set(value) != set(names.split()):
        raise invalid()


def integer(value, minimum=0, maximum=MAX_SAFE):
    if type(value) is not int or not minimum <= value <= maximum:
        raise invalid()
    return value


def digest(value):
    if not isinstance(value, str) or not re.fullmatch("[0-9a-f]{64}", value):
        raise invalid()


def key(value):
    if (
        not isinstance(value, str)
        or not 1 <= len(value) <= 1024
        or "\\" in value
        or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/-]*", value)
        or PurePosixPath(value).is_absolute()
        or any(part in {"", ".", ".."} for part in value.split("/"))
    ):
        raise invalid()


def validate_spec(value, expected_hash):
    fields(
        value,
        "schema_version profile_version source_export_id source_clip_id "
        "source_snapshot_hash input_media captions style_preset frame_preset output font timing_basis",
    )
    if (
        type(value["schema_version"]) is not int
        or value["schema_version"] != 1
        or value["profile_version"] != PROFILE
        or value["timing_basis"] != "CLIP_RELATIVE_ASS_CS_FLOOR"
        or value["style_preset"] not in {"WHITE_OUTLINE_V1", "DARK_BOX_V1"}
        or value["frame_preset"] not in {"SOURCE", "LANDSCAPE_720", "PORTRAIT_720", "PORTRAIT_BLUR_720"}
    ):
        raise invalid()
    for name in ("source_export_id", "source_clip_id"):
        try:
            if str(UUID(value[name])) != value[name]:
                raise ValueError
        except (ValueError, TypeError, AttributeError):
            raise invalid() from None
    digest(value["source_snapshot_hash"])
    media, captions, output, font = (
        value[name] for name in ("input_media", "captions", "output", "font")
    )
    fields(media, "key sha256 size_bytes duration_ms width height has_audio")
    fields(captions, "revision content_hash cues_key cues_sha256 duration_ms")
    fields(output, "width height")
    fields(font, "profile_id family key sha256 size_bytes")
    key(media["key"])
    digest(media["sha256"])
    key(captions["cues_key"])
    digest(captions["content_hash"])
    digest(captions["cues_sha256"])
    key(font["key"])
    for name in ("size_bytes", "duration_ms"):
        integer(media[name], 1)
    integer(captions["revision"])
    integer(captions["duration_ms"], 1)
    if type(media["has_audio"]) is not bool:
        raise invalid()
    for dimensions in (media, output):
        integer(dimensions["width"], 2, 4096)
        integer(dimensions["height"], 2, 4096)
    expected = {
        "SOURCE": ((media["width"] + 1) // 2 * 2, (media["height"] + 1) // 2 * 2),
        "LANDSCAPE_720": (1280, 720),
        "PORTRAIT_720": (720, 1280),
        "PORTRAIT_BLUR_720": (720, 1280),
    }[value["frame_preset"]]
    if (
        (output["width"], output["height"]) != expected
        or font["profile_id"] != FONT_PROFILE
        or font["family"] != FONT_FAMILY
        or font["sha256"] != FONT_HASH
        or type(font["size_bytes"]) is not int
        or font["size_bytes"] != FONT_SIZE
    ):
        raise invalid()
    digest(expected_hash)
    if hashlib.sha256(canonical(value)).hexdigest() != expected_hash:
        raise invalid("CAPTION_RENDER_SPEC_HASH_MISMATCH")


def _unique(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise invalid()
        result[name] = value
    return result


@dataclass(frozen=True)
class Cue:
    id: str
    start_ms: int
    end_ms: int
    text: str


def read_cues(path, expected_duration, expected_hash=None):
    if _native_path(path).stat().st_size > 8 * 1024 * 1024:
        raise invalid()
    try:
        with _native_path(path).open("rb") as source:
            raw = source.read(8 * 1024 * 1024 + 1)
        if len(raw) > 8 * 1024 * 1024:
            raise invalid()
        if expected_hash is not None and hashlib.sha256(raw).hexdigest() != expected_hash:
            raise invalid("CAPTION_RENDER_SOURCE_HASH_MISMATCH")
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique)
        fields(value, "schema_version duration_ms cues")
        if (
            type(value["schema_version"]) is not int
            or value["schema_version"] != 1
            or type(value["duration_ms"]) is not int
            or value["duration_ms"] != expected_duration
            or not isinstance(value["cues"], list)
            or len(value["cues"]) > 10000
        ):
            raise invalid()
        if not value["cues"]:
            raise invalid("CAPTION_RENDER_EMPTY")
        cues, ids, previous, total = [], set(), 0, 0
        for cue in value["cues"]:
            fields(cue, "id start_ms end_ms text")
            identifier, text = cue["id"], cue["text"]
            start, end = integer(cue["start_ms"]), integer(cue["end_ms"], 1)
            if (
                not isinstance(identifier, str)
                or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}", identifier)
                or identifier in ids
                or not previous <= start < end <= expected_duration
                or not isinstance(text, str)
                or not text.strip()
                or len(text.encode("utf-16-le")) // 2 > 2000
                or any(ord(ch) < 32 and ch not in "\n\r\t" or 127 <= ord(ch) <= 159 for ch in text)
            ):
                raise invalid()
            total += len(text.encode("utf-8"))
            if total > 2 * 1024 * 1024:
                raise invalid()
            if start // 10 >= end // 10:
                raise invalid("CAPTION_RENDER_TIMING_UNSUPPORTED")
            ids.add(identifier)
            previous = end
            cues.append(Cue(identifier, start, end, text))
        return cues
    except (ValueError, UnicodeError, KeyError, TypeError):
        raise invalid() from None


class FontMetrics:
    """Read pinned OpenType cmap/hmtx directly; never rely on system fallback."""

    def __init__(self, data):
        self.data = data
        try:
            count = struct.unpack_from(">H", data, 4)[0]
            tables = {}
            for index in range(count):
                tag, _, offset, length = struct.unpack_from(">4sIII", data, 12 + index * 16)
                if offset + length > len(data):
                    raise ValueError
                tables[tag] = (offset, length)
            self.units = struct.unpack_from(">H", data, tables[b"head"][0] + 18)[0]
            self.metrics = struct.unpack_from(">H", data, tables[b"hhea"][0] + 34)[0]
            self.hmtx = tables[b"hmtx"][0]
            self.cmaps = []
            cmap = tables[b"cmap"][0]
            for index in range(struct.unpack_from(">H", data, cmap + 2)[0]):
                platform, encoding, offset = struct.unpack_from(">HHI", data, cmap + 4 + index * 8)
                start = cmap + offset
                format = struct.unpack_from(">H", data, start)[0]
                if (platform == 0 or platform == 3 and encoding in {1, 10}) and format in {4, 12}:
                    self.cmaps.append((format, start))
            self.cmaps.sort(reverse=True)
            self.widths = {}
            if not self.units or not self.metrics or not self.cmaps:
                raise ValueError
        except (struct.error, KeyError, ValueError):
            raise invalid("CAPTION_RENDER_FONT_UNAVAILABLE") from None

    def glyph(self, code):
        data = self.data
        for format, start in self.cmaps:
            if format == 12:
                count = struct.unpack_from(">I", data, start + 12)[0]
                lo, hi = 0, count
                while lo < hi:
                    middle = (lo + hi) // 2
                    first, last, glyph = struct.unpack_from(">III", data, start + 16 + middle * 12)
                    if code < first:
                        hi = middle
                    elif code > last:
                        lo = middle + 1
                    else:
                        return glyph + code - first
            elif code <= 65535:
                count = struct.unpack_from(">H", data, start + 6)[0] // 2
                ends = struct.unpack_from(f">{count}H", data, start + 14)
                index = bisect.bisect_left(ends, code)
                if index == count:
                    continue
                first = struct.unpack_from(">H", data, start + 16 + 2 * count + 2 * index)[0]
                if code < first:
                    continue
                delta = struct.unpack_from(">h", data, start + 16 + 4 * count + 2 * index)[0]
                position = start + 16 + 6 * count + 2 * index
                offset = struct.unpack_from(">H", data, position)[0]
                glyph = (
                    struct.unpack_from(">H", data, position + offset + 2 * (code - first))[0]
                    if offset
                    else code
                )
                return (glyph + delta) & 65535 if glyph else 0
        return 0

    def advance(self, ch, size):
        if unicodedata.category(ch) == "Cf" or 0xFE00 <= ord(ch) <= 0xFE0F:
            return 0
        if ch in self.widths:
            return self.widths[ch] * size / self.units
        glyph = self.glyph(ord(ch))
        if not glyph:
            raise invalid("CAPTION_RENDER_GLYPH_UNSUPPORTED")
        width = struct.unpack_from(">H", self.data, self.hmtx + min(glyph, self.metrics - 1) * 4)[0]
        self.widths[ch] = width
        return width * size / self.units


def literal_ass(text):
    # Mirrors FFmpeg ff_ass_bprint_text_event(keep_ass_markup=0). Word joiner
    # keeps a literal backslash-N from becoming a line break in libass.
    return (
        text.replace("\\", "\\\u2060")
        .replace("{", "\\{{}")
        .replace("\n", "\\N")
        .replace(" ", "\\h")
    )


def ass_time(ms):
    cs = ms // 10
    return f"{cs // 360000}:{cs // 6000 % 60:02}:{cs // 100 % 60:02}.{cs % 100:02}"


def build_ass(cues, render, font, guard=lambda: None):
    width, height = render["output"]["width"], render["output"]["height"]
    default_size = max(12, round(min(width, height) * 0.045))
    margin_x, margin_y = max(6, round(width * 0.045)), max(6, round(height * 0.045))
    available = (width - 2 * margin_x - 4) / 1.08
    cues = tuple(cues)

    def layout(size):
        max_lines = min(6, int(height * 0.32 / (size * 1.3)))
        if max_lines < 1:
            return None
        dialogues = []
        for cue in cues:
            guard()
            lines = []
            for original in (
                cue.text.replace("\r\n", "\n").replace("\r", "\n").replace("\t", "    ").split("\n")
            ):
                current, used, boundary = "", 0.0, 0
                for index, ch in enumerate(original):
                    if index % 128 == 0:
                        guard()
                    advance = font.advance(ch, size)
                    if advance > available:
                        return None
                    while current and used + advance > available:
                        # Keep the whitespace at the end of the preceding
                        # line. Moving the unfinished word preserves both
                        # whole English words and every original character.
                        split = boundary or len(current)
                        lines.append(current[:split])
                        current = current[split:]
                        used = sum(font.advance(char, size) for char in current)
                        boundary = 0
                    current += ch
                    used += advance
                    if ch.isspace():
                        boundary = len(current)
                lines.append(current)
                if len(lines) > max_lines:
                    return None
            text = literal_ass("\n".join(lines))
            dialogues.append(
                f"Dialogue: 0,{ass_time(cue.start_ms)},{ass_time(cue.end_ms)},Default,,0,0,0,,{text}"
            )
        return dialogues

    # One style applies to the entire clip. Try the largest size first, with
    # a strict 80% floor (rounded up), never per-cue shrinking or truncation.
    minimum_size = max(12, (default_size * 4 + 4) // 5)
    for size in range(default_size, minimum_size - 1, -1):
        guard()
        dialogues = layout(size)
        if dialogues is not None:
            break
    else:
        raise invalid("CAPTION_RENDER_TEXT_TOO_LARGE")
    box = render["style_preset"] == "DARK_BOX_V1"
    return (
        f"[Script Info]\nScriptType: v4.00+\nPlayResX: {width}\nPlayResY: {height}\n"
        "WrapStyle: 2\nScaledBorderAndShadow: yes\nYCbCr Matrix: None\n\n[V4+ Styles]\n"
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, "
        "Alignment, MarginL, MarginR, MarginV, Encoding\n"
        f"Style: Default,{FONT_FAMILY},{size},&H00FFFFFF,&H00FFFFFF,"
        f"{'&H70000000' if box else '&H00101010'},&H70000000,0,0,0,0,100,100,0,0,"
        f"{3 if box else 1},{max(2, round(size * (0.16 if box else 0.07)))},0,2,"
        f"{margin_x},{margin_x},{margin_y},1\n\n[Events]\n"
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
        + "\n".join(dialogues)
        + "\n"
    ).encode("utf-8")


def write_exact(path, data, guard):
    guard()
    target = _native_path(path)
    if target.is_symlink():
        raise invalid("CAPTION_RENDER_ARTIFACT_CHANGED")
    if target.exists():
        if target.stat().st_size != len(data) or target.read_bytes() != data:
            raise invalid("CAPTION_RENDER_ARTIFACT_CHANGED")
        return
    guard()
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".tmp")
    if temporary.is_symlink():
        raise invalid("CAPTION_RENDER_ARTIFACT_CHANGED")
    try:
        with temporary.open("wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        guard()
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def prepare_inputs(context: StageContext, render):
    media = render["input_media"]
    references = [
        (media["key"], media["sha256"], media["size_bytes"]),
        (render["font"]["key"], FONT_HASH, FONT_SIZE),
        (render["captions"]["cues_key"], render["captions"]["cues_sha256"], None),
    ]
    paths = []
    for name, expected, size in references:
        context.guard.check()
        path = shared_path(context.storage_root, name, must_exist=True)
        if size is None and _native_path(path).stat().st_size > 8 * 1024 * 1024:
            raise invalid()
        if (size is not None and _native_path(path).stat().st_size != size) or sha256(
            path, guard=context.guard.check
        ) != expected:
            raise invalid("CAPTION_RENDER_SOURCE_HASH_MISMATCH")
        paths.append(path)
    cues = read_cues(paths[2], render["captions"]["duration_ms"], render["captions"]["cues_sha256"])
    with _native_path(paths[1]).open("rb") as source:
        font_bytes = source.read(FONT_SIZE + 1)
    if len(font_bytes) != FONT_SIZE or hashlib.sha256(font_bytes).hexdigest() != FONT_HASH:
        raise invalid("CAPTION_RENDER_SOURCE_HASH_MISMATCH")
    ass = build_ass(cues, render, FontMetrics(font_bytes), context.guard.check)
    directory = context.directory / "caption-render"
    # Existing source/link components are checked by shared_path before use.
    shared_path(context.storage_root, directory.relative_to(context.storage_root).as_posix())
    write_exact(directory / "captions.ass", ass, context.guard.check)
    shared_path(
        context.storage_root, (directory / "fonts").relative_to(context.storage_root).as_posix()
    )
    write_exact(directory / "fonts" / "font.otf", font_bytes, context.guard.check)
    return paths[0], directory


class RenderProgress:
    def __init__(self, context, total):
        self.context, self.total = context, total
        self.sequence, self.processed, self.last_sent = 0, 0, 0.0
        self.phases = ("PREPARING", "ENCODING", "VALIDATING", "PUBLISHING")
        self.current_phase = "PREPARING"
        self.path = context.directory / "render-progress-sequence.json"
        if _native_path(self.path).is_symlink():
            raise invalid("CAPTION_RENDER_ARTIFACT_CHANGED")
        if _native_path(self.path).exists():
            try:
                saved = json.loads(_native_path(self.path).read_text())
                fields(saved, "sequence phase processed_ms total_ms")
                self.sequence = integer(saved["sequence"])
                self.processed = integer(saved["processed_ms"], 0, total)
                self.current_phase = saved["phase"]
                if (
                    self.current_phase not in self.phases
                    or type(saved["total_ms"]) is not int
                    or saved["total_ms"] != total
                ):
                    raise invalid("CAPTION_RENDER_ARTIFACT_CHANGED")
            except (OSError, ValueError, KeyError, TypeError):
                raise invalid("CAPTION_RENDER_ARTIFACT_CHANGED") from None

    def emit(self, phase, processed=None):
        self.context.guard.check()
        if self.phases.index(phase) < self.phases.index(self.current_phase):
            return
        if processed is not None:
            self.processed = max(self.processed, min(self.total, processed))
        if self.context.render_progress is None:
            return
        if self.sequence >= MAX_SAFE:
            raise invalid("CAPTION_RENDER_ARTIFACT_CHANGED")
        self.current_phase = phase
        self.sequence += 1
        value = {
            "sequence": self.sequence,
            "phase": phase,
            "processed_ms": self.processed,
            "total_ms": self.total,
        }
        _write(self.path, value, guard=self.context.guard.check)
        self.context.render_progress(value)

    def encoding(self, us):
        now = time.monotonic()
        if now - self.last_sent >= 0.75:
            self.last_sent = now
            self.emit("ENCODING", max(0, us // 1000))

    def phase(self, phase):
        self.emit(phase, self.total if phase == "PUBLISHING" else None)
