"""Synthetic frames/engines verify cache fencing, never actual OCR recognition quality."""

import hashlib
import json
import os
import queue
import re
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from livecut.ocr_cache import cached_observe_video_ocr
from livecut.vision import VisionError


class SyntheticEngine:
    def __init__(self):
        self.calls = 0
        self.fail_at = None

    def __call__(self, frame):
        self.calls += 1
        if self.calls == self.fail_at:
            raise RuntimeError("synthetic interrupted OCR fixture")
        assert Path(frame).read_bytes().startswith(b"\xff\xd8synthetic-frame-")
        return SimpleNamespace(txts=["合成垃圾袋字幕", "低分噪声"], scores=[0.91, 0.1])


class SyntheticExtractor:
    def __init__(self):
        self.calls = []

    def __call__(self, source, target, requested_ms, *, ffmpeg_path):
        assert Path(source).is_file() and Path(ffmpeg_path).is_file()
        self.calls.append(requested_ms)
        target.write_bytes(b"\xff\xd8" + f"synthetic-frame-{requested_ms:08d}".encode() + b"\xff\xd9")
        return {"timestamp_ms": requested_ms + 3, "source_pts": requested_ms + 3,
                "source_time_base": "1/1000"}


@pytest.fixture
def fixture(tmp_path):
    source = tmp_path / "source.mp4"
    source.write_bytes(b"synthetic-source-video-A")
    ffmpeg = tmp_path / "synthetic-ffmpeg.exe"
    ffmpeg.write_bytes(b"synthetic-ffmpeg-binary-A")
    return SimpleNamespace(root=tmp_path, source=source, ffmpeg=ffmpeg,
                           cache=tmp_path / "cache", engine=SyntheticEngine(),
                           extractor=SyntheticExtractor())


def run(fixture, attempt, **overrides):
    options = {"cache_root": fixture.cache, "duration_ms": 1200,
               "interval_ms": 500, "max_frames": 3, "ffmpeg_path": str(fixture.ffmpeg),
               "engine": fixture.engine, "engine_identity": "synthetic-engine-weights-v1",
               "extractor": fixture.extractor, "extractor_identity": "synthetic-extractor-v1"}
    options.update(overrides)
    return cached_observe_video_ocr(fixture.source, fixture.root / attempt, **options)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def entry(fixture, result):
    key = result.provenance["cache_key"]
    assert len(key) == 64 and all(character in "0123456789abcdef" for character in key)
    return fixture.cache / key


def manifest(fixture, result):
    return json.loads((entry(fixture, result) / "manifest.json").read_bytes())


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")


def artifact(fixture, result, suffix):
    matches = [record for record in manifest(fixture, result)["files"]
               if record["path"].endswith(suffix)]
    assert len(matches) == 1
    return entry(fixture, result) / matches[0]["path"]


def change_artifact(fixture, result, suffix, transform):
    """Reseal file metadata so semantic/strict-JSON tests cannot pass on stale SHA alone."""
    bundle = entry(fixture, result)
    document = manifest(fixture, result)
    record = next(record for record in document["files"] if record["path"].endswith(suffix))
    path = bundle / record["path"]
    changed = transform(path.read_bytes())
    path.write_bytes(changed)
    record.update(size_bytes=len(changed), sha256=digest(path))
    save_json(bundle / "manifest.json", document)


def json_mutation(change):
    def transform(raw):
        value = json.loads(raw)
        change(value)
        return (json.dumps(value, ensure_ascii=False, allow_nan=False) + "\n").encode()
    return transform


def published(fixture):
    if not fixture.cache.exists():
        return []
    return [path for path in fixture.cache.iterdir()
            if path.is_dir() and len(path.name) == 64
            and all(character in "0123456789abcdef" for character in path.name)]


def test_cross_attempt_hit_skips_extraction_and_ocr_and_preserves_original_evidence(fixture):
    first = run(fixture, "attempt-1")
    calls = fixture.engine.calls
    assert fixture.extractor.calls == [0, 500, 1000] and calls == 3
    before = {path.relative_to(entry(fixture, first)): path.read_bytes()
              for path in entry(fixture, first).rglob("*") if path.is_file()}
    fixture.engine.fail_at = calls + 1
    second = run(fixture, "attempt-2")
    assert first.observations == second.observations
    assert first.provenance["cache_hit"] is False
    assert second.provenance["cache_hit"] is True
    for field in ("cache_key", "binding_sha256", "bundle_manifest_sha256", "observer_id"):
        assert first.provenance[field] == second.provenance[field]
    assert second.provenance["schema_version"] == 1
    assert second.provenance["frame_count"] == 3
    assert second.provenance["coverage_truncated"] is False
    assert fixture.engine.calls == calls and fixture.extractor.calls == [0, 500, 1000]
    assert [value["timestamp_ms"] for value in second.observations] == [3, 503, 1003]
    assert all(value["presented_category"] is None for value in second.observations)
    assert all(value["observed_text"] == "合成垃圾袋字幕" for value in second.observations)
    assert {path.relative_to(entry(fixture, first)): path.read_bytes()
            for path in entry(fixture, first).rglob("*") if path.is_file()} == before
    for forbidden in (str(fixture.root), "attempt-1", "run_token"):
        assert forbidden not in json.dumps(second.provenance, ensure_ascii=False)


def test_default_engine_cache_hit_never_initializes_optional_ocr_runtime(fixture, monkeypatch):
    identity = {"kind": "rapidocr-onnxruntime-default", "model_id": "rapidocr-synthetic",
                "packages": {"rapidocr": "synthetic", "onnxruntime": "synthetic"},
                "assets": [{"path": "models/synthetic-model.onnx", "size_bytes": 8,
                            "sha256": "a" * 64}]}
    monkeypatch.setattr("livecut.ocr_cache.default_runtime_identity", lambda **_: identity)
    monkeypatch.setattr("livecut.ocr.ocr_model_id", lambda: "rapidocr-synthetic")
    constructor = Mock(return_value=fixture.engine)
    monkeypatch.setitem(sys.modules, "rapidocr", SimpleNamespace(RapidOCR=constructor))
    first = run(fixture, "default-1", engine=None, engine_identity=None)
    assert constructor.call_count == 1
    constructor.side_effect = AssertionError("cache hit initialized RapidOCR")
    second = run(fixture, "default-2", engine=None, engine_identity=None)
    assert second.provenance["cache_hit"] is True
    assert first.observations == second.observations and constructor.call_count == 1
    assert fixture.extractor.calls == [0, 500, 1000]


@pytest.mark.parametrize("changed", ["package", "weights"])
def test_default_runtime_package_and_model_weight_identity_are_part_of_the_key(fixture, monkeypatch, changed):
    identity = {"kind": "rapidocr-onnxruntime-default", "model_id": "rapidocr-synthetic",
                "packages": {"rapidocr": "synthetic-v1", "onnxruntime": "synthetic-v1"},
                "assets": [{"path": "models/synthetic-model.onnx", "size_bytes": 8,
                            "sha256": "a" * 64}]}
    monkeypatch.setattr("livecut.ocr_cache.default_runtime_identity", lambda **_: identity)
    monkeypatch.setattr("livecut.ocr.ocr_model_id", lambda: "rapidocr-synthetic")
    constructor = Mock(return_value=fixture.engine)
    monkeypatch.setitem(sys.modules, "rapidocr", SimpleNamespace(RapidOCR=constructor))
    original = run(fixture, "runtime-v1", engine=None, engine_identity=None)
    if changed == "package":
        identity["packages"]["onnxruntime"] = "synthetic-v2"
    else:
        identity["assets"][0]["sha256"] = "b" * 64
    current = run(fixture, "runtime-v2", engine=None, engine_identity=None)
    assert current.provenance["cache_hit"] is False
    assert current.provenance["cache_key"] != original.provenance["cache_key"]
    assert constructor.call_count == 2 and fixture.engine.calls == 6


@pytest.mark.parametrize("change", ["source", "duration", "interval", "max_frames",
                                         "engine_weights", "extractor", "ffmpeg_bytes"])
def test_every_evidence_identity_dimension_isolates_shared_entries(fixture, change):
    first = run(fixture, "original")
    options = {}
    if change == "source":
        fixture.source.write_bytes(b"synthetic-source-video-B")
    elif change == "duration":
        options["duration_ms"] = 1300
    elif change == "interval":
        options["interval_ms"] = 450
    elif change == "max_frames":
        options["max_frames"] = 2
    elif change == "engine_weights":
        options["engine_identity"] = "synthetic-engine-weights-v2"
    elif change == "extractor":
        options["extractor_identity"] = "synthetic-extractor-v2"
    else:
        original_size = fixture.ffmpeg.stat().st_size
        fixture.ffmpeg.write_bytes(b"synthetic-ffmpeg-binary-B")
        assert fixture.ffmpeg.stat().st_size == original_size
    second = run(fixture, "changed", **options)
    assert second.provenance["cache_hit"] is False
    assert second.provenance["cache_key"] != first.provenance["cache_key"]
    assert len(published(fixture)) == 2


@pytest.mark.parametrize("missing", ["engine", "extractor"])
def test_custom_callables_cannot_share_an_implicit_generic_identity(fixture, missing):
    options = {missing + "_identity": None}
    with pytest.raises(VisionError) as failure:
        run(fixture, "unidentified", **options)
    assert failure.value.code == "OCR_CACHE_IDENTITY_REQUIRED"
    assert fixture.engine.calls == 0 and fixture.extractor.calls == []
    assert published(fixture) == []


def test_different_custom_engines_require_distinct_explicit_identities(fixture):
    first = run(fixture, "engine-a")
    other = Mock(return_value=SimpleNamespace(txts=["合成面膜字幕"], scores=[0.98]))
    second = run(fixture, "engine-b", engine=other, engine_identity="synthetic-other-engine-v1")
    assert first.provenance["cache_key"] != second.provenance["cache_key"]
    assert other.call_count == 3
    assert second.observations[0]["observed_text"] == "合成面膜字幕"


@pytest.mark.parametrize("mutation", ["schema_bool", "schema_unknown", "extra_field",
                                           "binding", "duplicate_file", "missing_file_record",
                                           "outside_path", "backslash_path", "absolute_path",
                                           "size_bool", "size_float", "digest", "record_extra_field",
                                           "row_not_object", "path_not_string", "files_not_array"])
def test_manifest_schema_identity_and_file_paths_are_checked_before_reuse(fixture, mutation):
    result = run(fixture, "seed")
    document = manifest(fixture, result)
    if mutation == "schema_bool":
        document["schema_version"] = True
    elif mutation == "schema_unknown":
        document["schema_version"] = 99
    elif mutation == "extra_field":
        document["unexpected"] = "must not be silently ignored"
    elif mutation == "binding":
        document["binding"]["unbound_identity"] = "different"
    elif mutation == "duplicate_file":
        document["files"].append(dict(document["files"][0]))
    elif mutation == "missing_file_record":
        document["files"].pop()
    elif mutation == "outside_path":
        document["files"][0]["path"] = "../outside.json"
    elif mutation == "backslash_path":
        document["files"][0]["path"] = "evidence\\observations.json"
    elif mutation == "absolute_path":
        document["files"][0]["path"] = str(fixture.root / "outside.json")
    elif mutation == "size_bool":
        document["files"][0]["size_bytes"] = True
    elif mutation == "size_float":
        document["files"][0]["size_bytes"] = float(document["files"][0]["size_bytes"])
    elif mutation == "digest":
        document["files"][0]["sha256"] = "0" * 64
    elif mutation == "row_not_object":
        document["files"][0] = None
    elif mutation == "path_not_string":
        document["files"][0]["path"] = []
    elif mutation == "files_not_array":
        document["files"] = {"not": "an array"}
    else:
        document["files"][0]["unknown"] = 1
    save_json(entry(fixture, result) / "manifest.json", document)
    with pytest.raises(VisionError) as failure:
        run(fixture, "must-not-regenerate")
    assert failure.value.code == "OCR_CACHE_CORRUPT"
    assert fixture.engine.calls == 3 and fixture.extractor.calls == [0, 500, 1000]


@pytest.mark.parametrize("target", ["manifest.json", "ocr-state.json"])
@pytest.mark.parametrize("replacement", [True, 1.0])
def test_binding_rejects_boolean_or_float_aliases_for_the_integer_one(fixture, target, replacement):
    result = run(fixture, "seed", max_frames=1)
    if target == "manifest.json":
        document = manifest(fixture, result)
        document["binding"]["max_frames"] = replacement
        save_json(entry(fixture, result) / "manifest.json", document)
    else:
        change_artifact(fixture, result, "/ocr-state.json",
                        json_mutation(lambda state: state["input"].update(max_frames=replacement)))
    with pytest.raises(VisionError) as failure:
        run(fixture, "integer-only", max_frames=1)
    assert failure.value.code == "OCR_CACHE_CORRUPT"
    assert fixture.engine.calls == 1


@pytest.mark.parametrize("target", ["manifest.json", "observations.json", "frames.json", "ocr-state.json"])
@pytest.mark.parametrize("invalid", ["duplicate_key", "nan"])
def test_strict_json_rejects_duplicate_keys_and_nonfinite_literals_even_after_resealing(fixture, target, invalid):
    result = run(fixture, "seed")

    def corrupt(raw):
        text = raw.decode()
        if invalid == "duplicate_key":
            # The last value remains the valid original. A permissive JSON
            # parser would otherwise accept this byte sequence unchanged.
            marker = '"schema_version":' if target == "manifest.json" else '"frame_id":'
            if target == "ocr-state.json":
                marker = '"frames":'
                return text.replace(marker, '"frames": [], ' + marker, 1).encode()
            return text.replace(marker, marker + '"shadowed", ' + marker, 1).encode()
        marker = '"schema_version":' if target == "manifest.json" else '"timestamp_ms":'
        changed, count = re.subn(re.escape(marker) + r"\s*-?\d+", marker + "NaN", text, count=1)
        assert count == 1
        return changed.encode()

    if target == "manifest.json":
        path = entry(fixture, result) / target
        path.write_bytes(corrupt(path.read_bytes()))
    else:
        change_artifact(fixture, result, "/" + target, corrupt)
    with pytest.raises(VisionError) as failure:
        run(fixture, "strict-reader")
    assert failure.value.code == "OCR_CACHE_CORRUPT"
    assert fixture.engine.calls == 3


@pytest.mark.parametrize("kind", ["frame_time", "requested_time", "observation_time", "observation_frame",
                                       "observation_key", "observation_extra", "frame_order", "coverage",
                                       "source_pts", "time_base"])
def test_semantic_frame_observation_correspondence_is_validated_beyond_hashes(fixture, kind):
    result = run(fixture, "seed")
    if kind == "frame_time":
        suffix, mutate = "/frames.json", lambda value: value[0].update(timestamp_ms=1200)
    elif kind == "requested_time":
        suffix, mutate = "/frames.json", lambda value: value[0].update(requested_timestamp_ms=7)
    elif kind == "observation_time":
        suffix, mutate = "/observations.json", lambda value: value[0].update(timestamp_ms=4)
    elif kind == "observation_frame":
        suffix, mutate = "/observations.json", lambda value: value[0].update(frame_id="ocr_000002")
    elif kind == "observation_key":
        suffix, mutate = "/observations.json", lambda value: value[0].update(object_key="../other.jpg")
    elif kind == "observation_extra":
        suffix, mutate = "/observations.json", lambda value: value[0].update(private_token="must-fail")
    elif kind == "frame_order":
        suffix, mutate = "/frames.json", lambda value: value.reverse()
    elif kind == "source_pts":
        suffix, mutate = "/frames.json", lambda value: value[0].update(source_pts=-3)
    elif kind == "time_base":
        suffix, mutate = "/frames.json", lambda value: value[0].update(source_time_base="1/1")
    else:
        suffix, mutate = "/ocr-state.json", lambda value: value.update(coverage_truncated=True)
    change_artifact(fixture, result, suffix, json_mutation(mutate))
    if suffix in {"/frames.json", "/observations.json"}:
        # Keep the duplicate state copy consistent so failure must come from
        # sampling/schema/frame-observation validation rather than stale copies.
        values = json.loads(artifact(fixture, result, suffix).read_bytes())
        key = "frames" if suffix == "/frames.json" else "observations"
        change_artifact(fixture, result, "/ocr-state.json",
                        json_mutation(lambda state: state.update({key: values})))
    with pytest.raises(VisionError) as failure:
        run(fixture, "semantic-reader")
    assert failure.value.code == "OCR_CACHE_CORRUPT"
    assert fixture.engine.calls == 3


@pytest.mark.parametrize("kind", ["same_size", "missing_frame", "missing_manifest", "extra_file"])
def test_hit_verifies_every_evidence_byte_and_the_exact_file_set(fixture, kind):
    result = run(fixture, "seed")
    frame = entry(fixture, result) / "evidence/frames/ocr_000003.jpg"
    if kind == "same_size":
        original = frame.read_bytes()
        frame.write_bytes(original[:5] + bytes([original[5] ^ 1]) + original[6:])
        assert frame.stat().st_size == len(original)
    elif kind == "missing_frame":
        frame.unlink()
    elif kind == "missing_manifest":
        (entry(fixture, result) / "manifest.json").unlink()
    else:
        (entry(fixture, result) / "evidence/unlisted.txt").write_text("unexpected fixture")
    with pytest.raises(VisionError) as failure:
        run(fixture, "must-not-hide-corruption")
    assert failure.value.code == "OCR_CACHE_CORRUPT"
    assert fixture.engine.calls == 3 and fixture.extractor.calls == [0, 500, 1000]


def test_materialization_copies_bytes_without_hardlinks_or_overwriting_a_prior_attempt(fixture):
    result = run(fixture, "old-attempt")
    run(fixture, "new-attempt")
    cached = entry(fixture, result) / "evidence/frames/ocr_000001.jpg"
    old = fixture.root / "old-attempt/frames/ocr_000001.jpg"
    new = fixture.root / "new-attempt/frames/ocr_000001.jpg"
    assert not os.path.samefile(cached, old)
    assert not os.path.samefile(cached, new)
    assert not os.path.samefile(old, new)
    expected = cached.read_bytes()
    changed = b"X" + expected[1:]
    old.write_bytes(changed)
    assert cached.read_bytes() == expected and new.read_bytes() == expected
    with pytest.raises(VisionError):
        run(fixture, "old-attempt")
    assert old.read_bytes() == changed and cached.read_bytes() == expected


def test_interrupted_generation_is_not_published_and_retry_remains_within_frame_budget(fixture):
    fixture.engine.fail_at = 2
    with pytest.raises(RuntimeError, match="synthetic interrupted"):
        run(fixture, "interrupted")
    assert published(fixture) == []
    assert not (fixture.root / "interrupted/observations.json").exists()
    fixture.engine.fail_at = None
    result = run(fixture, "resumed")
    assert result.provenance["cache_hit"] is False
    assert len(result.observations) == 3
    assert len({item["frame_id"] for item in result.observations}) == 3
    assert fixture.engine.calls <= 6 and len(fixture.extractor.calls) <= 6
    assert len(published(fixture)) == 1


class SyntheticLeaseLost(RuntimeError):
    pass


def test_lost_guard_aborts_generation_and_hit_without_publishing_partial_attempts(fixture):
    failure = SyntheticLeaseLost("synthetic lease revoked")

    def guard():
        if fixture.engine.calls:
            raise failure

    with pytest.raises(SyntheticLeaseLost) as lost:
        run(fixture, "revoked-build", guard=guard)
    assert lost.value is failure and published(fixture) == []
    ready = run(fixture, "valid-build")
    calls = fixture.engine.calls
    snapshot = {path: digest(path) for path in entry(fixture, ready).rglob("*") if path.is_file()}

    def lost_on_hit():
        raise failure

    with pytest.raises(SyntheticLeaseLost) as lost:
        run(fixture, "revoked-hit", guard=lost_on_hit)
    assert lost.value is failure
    assert fixture.engine.calls == calls and snapshot == {path: digest(path) for path in snapshot}
    assert not (fixture.root / "revoked-hit/observations.json").exists()


def test_entry_and_byte_limits_bound_new_work_but_do_not_disable_verified_hits(fixture):
    with pytest.raises(VisionError) as exhausted:
        run(fixture, "no-capacity", max_cache_bytes=1)
    assert exhausted.value.code == "OCR_CACHE_CAPACITY_EXCEEDED"
    assert fixture.engine.calls == 0 and fixture.extractor.calls == []
    first = run(fixture, "first", max_entries=2)
    run(fixture, "second", engine_identity="synthetic-engine-v2", max_entries=2)
    calls = fixture.engine.calls
    with pytest.raises(VisionError) as exhausted:
        run(fixture, "third", engine_identity="synthetic-engine-v3", max_entries=2)
    assert exhausted.value.code == "OCR_CACHE_CAPACITY_EXCEEDED"
    assert len(published(fixture)) == 2 and fixture.engine.calls == calls
    hit = run(fixture, "existing-still-readable", max_cache_bytes=1, max_entries=1)
    assert hit.provenance["cache_hit"] is True
    assert hit.provenance["cache_key"] == first.provenance["cache_key"]


def test_sampling_and_resume_never_exceed_the_explicit_frame_cap(fixture):
    result = run(fixture, "truncated", duration_ms=999999, interval_ms=100, max_frames=2)
    assert result.provenance["coverage_truncated"] is True
    assert result.provenance["frame_count"] == 2
    assert fixture.extractor.calls == [0, 100] and fixture.engine.calls == 2
    resumed = run(fixture, "truncated-next", duration_ms=999999, interval_ms=100, max_frames=2)
    assert resumed.provenance["cache_hit"] is True and fixture.engine.calls == 2


def link_directory(link, target):
    try:
        link.symlink_to(target, target_is_directory=True)
    except OSError:
        if os.name != "nt":
            pytest.skip("directory links unavailable")
        process = subprocess.run([
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "New-Item -ItemType Junction -Path $env:LIVECUT_TEST_LINK "
            "-Target $env:LIVECUT_TEST_TARGET -ErrorAction Stop | Out-Null"],
            env={**os.environ, "LIVECUT_TEST_LINK": str(link), "LIVECUT_TEST_TARGET": str(target)},
            capture_output=True, timeout=10, creationflags=subprocess.CREATE_NO_WINDOW)
        if process.returncode:
            pytest.skip("directory links unavailable")


@pytest.mark.parametrize("part", ["frames", "entry", "output"])
def test_symlink_or_junction_aliases_cannot_supply_or_overwrite_shared_evidence(fixture, part):
    result = run(fixture, "seed")
    outside = fixture.root / "outside"
    if part == "output":
        outside.mkdir()
        (outside / "sentinel").write_bytes(b"do not modify")
        link_directory(fixture.root / "linked-output", outside)
    else:
        target = entry(fixture, result) if part == "entry" else entry(fixture, result) / "evidence/frames"
        target.rename(outside)
        link_directory(target, outside)
    original = {path: path.read_bytes() for path in outside.rglob("*") if path.is_file()}
    with pytest.raises(VisionError):
        run(fixture, "linked-output" if part == "output" else "reject-alias")
    assert original == {path: path.read_bytes() for path in original}
    assert fixture.engine.calls == 3


_PRODUCER = r'''
import json, os, sys
from pathlib import Path
from types import SimpleNamespace
from livecut.ocr_cache import cached_observe_video_ocr

source, target, cache, ffmpeg, counter = map(Path, sys.argv[1:])
calls = 0
def extract(source, target, requested_ms, *, ffmpeg_path):
    target.write_bytes(b"\xff\xd8" + f"synthetic-frame-{requested_ms:08d}".encode() + b"\xff\xd9")
    return {"timestamp_ms": requested_ms + 3, "source_pts": requested_ms + 3,
            "source_time_base": "1/1000"}
def engine(frame):
    global calls
    calls += 1
    with counter.open("a", encoding="utf-8") as output:
        output.write("call\n")
    if calls == 1:
        print("READY", os.getpid(), flush=True)
        sys.stdin.readline()
    return SimpleNamespace(txts=["合成垃圾袋字幕"], scores=[0.91])
result = cached_observe_video_ocr(source, target, cache_root=cache,
    duration_ms=1200, interval_ms=500, max_frames=3, ffmpeg_path=str(ffmpeg),
    engine=engine, engine_identity="synthetic-engine-weights-v1",
    extractor=extract, extractor_identity="synthetic-extractor-v1")
print(json.dumps(result.provenance), flush=True)
'''


def start_producer(fixture):
    counter = fixture.root / "producer-calls.txt"
    process = subprocess.Popen([sys.executable, "-c", _PRODUCER, str(fixture.source),
                                str(fixture.root / "child-attempt"), str(fixture.cache),
                                str(fixture.ffmpeg), str(counter)],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True, encoding="utf-8")
    ready = queue.Queue()
    thread = threading.Thread(target=lambda: ready.put(process.stdout.readline()), daemon=True)
    thread.start()
    try:
        line = ready.get(timeout=10)
        marker, owner_pid = line.split()
        assert marker == "READY"
        return process, int(owner_pid), counter
    except BaseException:
        process.kill()
        process.communicate(timeout=5)
        raise


def stop_producer(process, owner_pid):
    # Windows venv python.exe can redirect to a child; kill the actual lock
    # owner announced by the fixture, not only the launcher waiting for it.
    if process.poll() is None:
        try:
            os.kill(owner_pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate(timeout=5)


def test_cross_process_generation_has_one_owner_then_replays_the_completed_bundle(fixture):
    process, owner_pid, counter = start_producer(fixture)
    try:
        with pytest.raises(VisionError) as busy:
            run(fixture, "contender", lock_timeout_seconds=0)
        assert busy.value.code == "OCR_CACHE_BUSY"
        assert fixture.engine.calls == 0 and fixture.extractor.calls == []
        assert published(fixture) == []
        stdout, stderr = process.communicate(input="release\n", timeout=15)
        assert process.returncode == 0, stderr
        provenance = json.loads(stdout.strip().splitlines()[-1])
        assert provenance["cache_hit"] is False
        hit = run(fixture, "after-owner", lock_timeout_seconds=0)
        assert hit.provenance["cache_hit"] is True
        assert hit.provenance["cache_key"] == provenance["cache_key"]
        assert fixture.engine.calls == 0 and fixture.extractor.calls == []
        assert counter.read_text().splitlines() == ["call"] * 3
    finally:
        stop_producer(process, owner_pid)


def test_crashed_producer_cannot_publish_a_valid_bundle_or_strand_the_os_lock(fixture):
    process, owner_pid, _ = start_producer(fixture)
    try:
        assert published(fixture) == []
        builds = list(fixture.cache.glob(".build-*"))
        assert len(builds) == 1
        expected_key = builds[0].name.removeprefix(".build-")
        stop_producer(process, owner_pid)
        result = run(fixture, "after-crash", lock_timeout_seconds=0)
        assert result.provenance["cache_hit"] is False
        assert result.provenance["cache_key"] == expected_key
        assert len(result.observations) == 3 and fixture.engine.calls <= 3
        assert len(published(fixture)) == 1
    finally:
        stop_producer(process, owner_pid)


def test_lock_wait_checks_the_lease_guard_instead_of_waiting_after_revocation(fixture):
    process, owner_pid, _ = start_producer(fixture)
    started = time.monotonic()
    failure = SyntheticLeaseLost("revoked while waiting for cache lock")
    checks = []

    def guard():
        elapsed = time.monotonic() - started
        checks.append(elapsed)
        if elapsed >= 0.08:
            raise failure

    try:
        with pytest.raises(SyntheticLeaseLost) as lost:
            run(fixture, "revoked-contender", guard=guard, lock_timeout_seconds=2)
        assert lost.value is failure
        assert len(checks) >= 2 and time.monotonic() - started < 1.5
        assert fixture.engine.calls == 0 and fixture.extractor.calls == []
        assert published(fixture) == []
    finally:
        stop_producer(process, owner_pid)
