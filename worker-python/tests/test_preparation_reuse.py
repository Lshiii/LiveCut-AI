"""Synthetic published preparation copies exercise fencing without any providers."""

import json
import os
import threading
from copy import deepcopy
from uuid import uuid4

import pytest
from livecut.worker import (
    AnalysisStageExecutor,
    Lease,
    LeaseGuard,
    StageContext,
    WorkerError,
    _native_path,
    _write,
    sha256,
)
from test_asr_reuse import link_directory
from test_worker import claim

SOURCE_TOKEN = "source-token-001"
TARGET_TOKEN = "target-token-001"


def never(*args, **kwargs):
    pytest.fail("published preparation copies must never probe, extract, upload or call a provider")


def source_fixture(tmp_path, monkeypatch, *, empty=False):
    for name in ("probe_media", "extract_audio", "transcribe_file"):
        monkeypatch.setattr("livecut.worker." + name, never)
    video = tmp_path / "videos/video.mp4"
    video.parent.mkdir()
    video.write_bytes(b"synthetic source video; not playable")
    job_id, video_id = str(uuid4()), str(uuid4())
    spec = {"schema_version": 1, "source_job_id": job_id, "video_id": video_id,
            "source_generation": 7, "source_hash": sha256(video), "asr_model": "paraformer-v2"}
    transcript = [] if empty else [
        {"utterance_id": "original-custom-u1", "start_ms": 20, "end_ms": 700,
         "text": "合成语音内容", "source_chunk_id": "original-chunk"},
        {"utterance_id": "original-custom-u2", "start_ms": 730, "end_ms": 1000,
         "text": "保留原有时间和标识", "source_chunk_id": "original-chunk"},
    ]
    for kind, files in (
        ("MEDIA", {"audio": ("audio.wav", b"synthetic WAV"),
                   "media_info": ("media-info.json", b'{"duration_ms":1000}')}),
        ("ASR", {"transcript": ("asr/transcript.json", json.dumps(transcript, ensure_ascii=False).encode())}),
    ):
        stage_id = str(uuid4())
        prefix = f"jobs/{job_id}/stages/{stage_id}/attempts/3/{SOURCE_TOKEN}"
        directory = tmp_path / prefix
        directory.mkdir(parents=True)
        artifacts = []
        for file_kind, (name, content) in files.items():
            path = directory / name
            path.parent.mkdir(exist_ok=True)
            path.write_bytes(content)
            artifacts.append({"kind": file_kind, "key": prefix + "/" + name, "sha256": sha256(path)})
        manifest = {"schema_version": 1, "job_id": job_id, "video_id": video_id,
                    "analysis_generation": 7, "stage_id": stage_id, "stage_type": kind,
                    "attempt": 3, "run_token": SOURCE_TOKEN, "input_sha256": spec["source_hash"],
                    "data_kind": "model_result", "review_status": "DRAFT", "artifacts": artifacts}
        _write(directory / "manifest.json", manifest)
        spec[kind.lower()] = {"id": stage_id, "attempt": 3, "manifest_key": prefix + "/manifest.json",
                              "manifest_hash": sha256(directory / "manifest.json")}
    return spec, transcript


def context(tmp_path, spec, *, stage_type="MEDIA", job_id=None, predecessors=None):
    lease = Lease.parse(claim(job_id=job_id or str(uuid4()), video_id=spec["video_id"],
        stage_id=str(uuid4()), stage_type=stage_type, source_hash=spec["source_hash"], attempt=2,
        run_token=TARGET_TOKEN, config={"analysis_mode": "topics", "visual_provider": "none",
            "asr_model": spec["asr_model"], "max_asr_submissions": 0, "preparation_reuse": deepcopy(spec)},
        artifacts=predecessors or []))
    directory = tmp_path / lease.attempt_key
    directory.mkdir(parents=True)
    return StageContext(lease, tmp_path, directory, LeaseGuard(lease, threading.Event()))


def execute(ctx):
    return AnalysisStageExecutor(transport_factory=never)(ctx)


def publish(ctx, manifest):
    path = ctx.directory / "manifest.json"
    _write(path, manifest)
    return {"stage_type": ctx.lease.stage_type, "stage_id": ctx.lease.stage_id,
            "manifest_key": path.relative_to(ctx.storage_root).as_posix(), "manifest_hash": sha256(path)}


def flow(tmp_path, spec):
    media = context(tmp_path, spec)
    media_manifest = execute(media)
    reference = publish(media, media_manifest)
    asr = context(tmp_path, spec, stage_type="ASR", job_id=media.lease.job_id, predecessors=[reference])
    asr_manifest = execute(asr)
    publish(asr, asr_manifest)
    return media, asr, media_manifest, asr_manifest


def artifact_path(ctx, manifest, kind):
    return _native_path(ctx.storage_root / next(
        item["key"] for item in manifest["artifacts"] if item["kind"] == kind))


def manifest_file(tmp_path, spec, kind):
    return tmp_path / spec[kind]["manifest_key"]


def change_manifest(tmp_path, spec, kind, mutate):
    path = manifest_file(tmp_path, spec, kind)
    value = json.loads(path.read_bytes())
    mutate(value)
    _write(path, value)
    spec[kind]["manifest_hash"] = sha256(path)


@pytest.mark.parametrize("empty", [False, True])
def test_two_stages_copy_only_published_bytes_and_publish_current_identity(tmp_path, monkeypatch, empty):
    spec, transcript = source_fixture(tmp_path, monkeypatch, empty=empty)
    before = {path: sha256(path) for path in tmp_path.rglob("*") if path.is_file()}
    media, asr, media_manifest, asr_manifest = flow(tmp_path, spec)
    assert {path: sha256(path) for path in before} == before
    assert asr.provider_input_hash == sha256(artifact_path(media, media_manifest, "audio"))
    assert json.loads(artifact_path(asr, asr_manifest, "transcript").read_bytes()) == transcript
    source_transcript = manifest_file(tmp_path, spec, "asr").parent / "asr/transcript.json"
    assert artifact_path(asr, asr_manifest, "transcript").read_bytes() == source_transcript.read_bytes()
    for ctx, manifest, kinds in ((media, media_manifest, {"audio", "media_info", "cache_provenance"}),
                                 (asr, asr_manifest, {"transcript", "cache_provenance"})):
        lease = ctx.lease
        assert manifest["job_id"] == lease.job_id != spec["source_job_id"]
        assert manifest["stage_id"] == lease.stage_id
        assert manifest["analysis_generation"] == lease.generation != spec["source_generation"]
        assert manifest["attempt"] == 2 and manifest["run_token"] == TARGET_TOKEN
        assert {item["kind"] for item in manifest["artifacts"]} == kinds
        provenance = json.loads(artifact_path(ctx, manifest, "cache_provenance").read_bytes())
        assert provenance["reuse_mode"] == "published_preparation_copy"
        assert provenance["provider_submissions"] == 0
        assert "verified_raw_sha256" not in provenance
        assert provenance["source_job_id"] == spec["source_job_id"]
        assert execute(ctx) == manifest  # fresh executor, immutable same-attempt replay
    # Neither raw result nor checkpoint exists anywhere in the fixture.
    assert not list(tmp_path.rglob("raw-result.json")) and not list(tmp_path.rglob("checkpoint.json"))


def test_recovered_job_can_itself_be_the_next_source(tmp_path, monkeypatch):
    spec, transcript = source_fixture(tmp_path, monkeypatch)
    media, asr, media_manifest, asr_manifest = flow(tmp_path, spec)
    chained = {**spec, "source_job_id": media.lease.job_id, "source_generation": media.lease.generation}
    for kind, ctx in (("media", media), ("asr", asr)):
        path = ctx.directory / "manifest.json"
        chained[kind] = {"id": ctx.lease.stage_id, "attempt": ctx.lease.attempt,
                         "manifest_key": path.relative_to(tmp_path).as_posix(), "manifest_hash": sha256(path)}
    _, again, _, again_manifest = flow(tmp_path, chained)
    assert json.loads(artifact_path(again, again_manifest, "transcript").read_bytes()) == transcript
    assert artifact_path(asr, asr_manifest, "transcript").read_bytes() == artifact_path(
        again, again_manifest, "transcript").read_bytes()
    assert media_manifest["artifacts"]


def test_each_copy_stage_hashes_source_video_once_with_the_guard(tmp_path, monkeypatch):
    spec, _ = source_fixture(tmp_path, monkeypatch)
    from livecut import preparation_reuse, worker
    stream = preparation_reuse._stream
    checked = []

    def tracked(context, path, *, sink=None, limit=None):
        if path == tmp_path / "videos/video.mp4":
            checked.append(context.lease.stage_type)
        return stream(context, path, sink=sink, limit=limit)

    def no_duplicate_video_hash(path, **kwargs):
        if path == tmp_path / "videos/video.mp4":
            pytest.fail("the generic executor must not hash the source a second time")
        return sha256(path, **kwargs)

    monkeypatch.setattr(preparation_reuse, "_stream", tracked)
    monkeypatch.setattr(worker, "sha256", no_duplicate_video_hash)
    flow(tmp_path, spec)
    assert checked == ["MEDIA", "ASR"]


@pytest.mark.parametrize("length,accepted", [(15, False), (16, True), (64, True), (65, False)])
def test_source_manifest_token_matches_shared_contract_boundaries(tmp_path, monkeypatch, length, accepted):
    spec, _ = source_fixture(tmp_path, monkeypatch)
    old_path = manifest_file(tmp_path, spec, "asr")
    token = "t" * length
    new_directory = old_path.parent.with_name(token)
    _native_path(old_path.parent).rename(_native_path(new_directory))
    new_path = new_directory / "manifest.json"
    manifest = json.loads(_native_path(new_path).read_bytes())
    manifest["run_token"] = token
    for artifact in manifest["artifacts"]:
        artifact["key"] = artifact["key"].replace(SOURCE_TOKEN, token)
    _write(new_path, manifest)
    spec["asr"].update(manifest_key=new_path.relative_to(tmp_path).as_posix(), manifest_hash=sha256(new_path))
    ctx = context(tmp_path, spec)
    if accepted:
        assert execute(ctx)["artifacts"]
    else:
        with pytest.raises(WorkerError, match="PREPARATION_REUSE_MANIFEST_MISMATCH"):
            execute(ctx)
        assert not (ctx.directory / "preparation").exists()


@pytest.mark.parametrize("mutation", [
    "video", "hash", "model", "schema_bool", "generation_bool", "attempt_bool", "extra_field",
    "source_same_job", "source_missing", "bad_key", "bad_hash", "manifest_identity", "manifest_bool",
    "manifest_token", "manifest_draft", "duplicate_kind", "duplicate_key", "outside_artifact",
    "transcript_bytes", "audio_bytes", "missing_audio", "transcript_content", "duplicate_json",
])
def test_mismatch_never_falls_back_or_creates_output(tmp_path, monkeypatch, mutation):
    spec, _ = source_fixture(tmp_path, monkeypatch)
    ctx = context(tmp_path, spec)
    selected = ctx.lease.config["preparation_reuse"]
    if mutation == "video":
        selected["video_id"] = str(uuid4())
    elif mutation == "hash":
        selected["source_hash"] = "a" * 64
    elif mutation == "model":
        selected["asr_model"] = "other-model"
    elif mutation in {"schema_bool", "generation_bool"}:
        selected["schema_version" if mutation == "schema_bool" else "source_generation"] = True
    elif mutation == "attempt_bool":
        selected["asr"]["attempt"] = True
    elif mutation == "extra_field":
        selected["unexpected"] = "value"
    elif mutation == "source_same_job":
        selected["source_job_id"] = ctx.lease.job_id
    elif mutation == "source_missing":
        (manifest_file(tmp_path, selected, "asr").parent / "asr/transcript.json").unlink()
    elif mutation == "bad_key":
        selected["asr"]["manifest_key"] = selected["asr"]["manifest_key"].replace(SOURCE_TOKEN, "../escape")
    elif mutation == "bad_hash":
        selected["asr"]["manifest_hash"] = "b" * 64
    elif mutation in {"transcript_bytes", "audio_bytes"}:
        path = (manifest_file(tmp_path, selected, "asr").parent / "asr/transcript.json"
                if mutation == "transcript_bytes" else manifest_file(tmp_path, selected, "media").parent / "audio.wav")
        path.write_bytes(b"tampered")
    elif mutation in {"transcript_content", "duplicate_json"}:
        path = manifest_file(tmp_path, selected, "asr").parent / "asr/transcript.json"
        if mutation == "duplicate_json":
            path.write_bytes(b'[{"utterance_id":"u1","utterance_id":"u2"}]')
        else:
            items = json.loads(path.read_bytes())
            items[0]["end_ms"] = 1001
            _write(path, items)
        change_manifest(tmp_path, selected, "asr", lambda value: value["artifacts"][0].update(sha256=sha256(path)))
    else:
        def mutate(value):
            if mutation == "manifest_identity":
                value["video_id"] = str(uuid4())
            elif mutation == "manifest_bool":
                value["analysis_generation"] = True
            elif mutation == "manifest_token":
                value["run_token"] = "wrong-token"
            elif mutation == "manifest_draft":
                value["review_status"] = "CONFIRMED"
            elif mutation == "duplicate_kind":
                value["artifacts"].append(dict(value["artifacts"][0]))
            elif mutation == "duplicate_key":
                value["artifacts"].append(dict(value["artifacts"][0], kind="other"))
            elif mutation == "missing_audio":
                value["artifacts"] = [item for item in value["artifacts"] if item["kind"] != "audio"]
            elif mutation == "outside_artifact":
                value["artifacts"][0]["key"] = "videos/video.mp4"
        change_manifest(tmp_path, selected, "media" if mutation == "missing_audio" else "asr", mutate)
    with pytest.raises(WorkerError, match="PREPARATION_REUSE_"):
        execute(ctx)
    assert not (ctx.directory / "preparation").exists()


def test_cli_source_and_claim_preparation_source_conflict(tmp_path, monkeypatch):
    spec, _ = source_fixture(tmp_path, monkeypatch)
    ctx = context(tmp_path, spec)
    with pytest.raises(WorkerError, match="PREPARATION_REUSE_CONFLICT"):
        AnalysisStageExecutor(transport_factory=never, asr_reuse_source={})(ctx)


def test_asr_revalidates_source_and_current_media_before_copy(tmp_path, monkeypatch):
    spec, _ = source_fixture(tmp_path, monkeypatch)
    media = context(tmp_path, spec)
    manifest = execute(media)
    reference = publish(media, manifest)
    asr = context(tmp_path, spec, stage_type="ASR", job_id=media.lease.job_id, predecessors=[reference])
    audio = artifact_path(media, manifest, "audio")
    audio.write_bytes(b"different published audio")
    for item in manifest["artifacts"]:
        if item["kind"] == "audio":
            item["sha256"] = sha256(audio)
    asr.lease.artifacts[0].update(publish(media, manifest))
    with pytest.raises(WorkerError, match="PREPARATION_REUSE_AUDIO_MISMATCH"):
        execute(asr)
    assert not (asr.directory / "preparation").exists()


@pytest.mark.parametrize("mutation", ["source_transcript", "source_manifest", "source_audio",
                                      "current_identity", "current_provenance", "current_info"])
def test_asr_rechecks_both_published_sources_after_media_has_completed(tmp_path, monkeypatch, mutation):
    spec, _ = source_fixture(tmp_path, monkeypatch)
    media = context(tmp_path, spec)
    manifest = execute(media)
    reference = publish(media, manifest)
    asr = context(tmp_path, spec, stage_type="ASR", job_id=media.lease.job_id, predecessors=[reference])
    if mutation == "source_transcript":
        (manifest_file(tmp_path, spec, "asr").parent / "asr/transcript.json").unlink()
    elif mutation == "source_manifest":
        manifest_file(tmp_path, spec, "asr").write_bytes(b'{}')
    elif mutation == "source_audio":
        (manifest_file(tmp_path, spec, "media").parent / "audio.wav").write_bytes(b"tampered audio")
    elif mutation == "current_identity":
        manifest["run_token"] = "wrong-token"
        asr.lease.artifacts[0].update(publish(media, manifest))
    else:
        kind = "cache_provenance" if mutation == "current_provenance" else "media_info"
        path = artifact_path(media, manifest, kind)
        value = json.loads(path.read_bytes())
        value["source_job_id" if mutation == "current_provenance" else "duration_ms"] = (
            str(uuid4()) if mutation == "current_provenance" else 1001)
        _write(path, value)
        for artifact in manifest["artifacts"]:
            if artifact["kind"] == kind:
                artifact["sha256"] = sha256(path)
        asr.lease.artifacts[0].update(publish(media, manifest))
    with pytest.raises(WorkerError, match="PREPARATION_REUSE_"):
        execute(asr)
    assert not (asr.directory / "preparation").exists()


def test_tampered_existing_output_is_never_overwritten(tmp_path, monkeypatch):
    spec, _ = source_fixture(tmp_path, monkeypatch)
    ctx = context(tmp_path, spec)
    manifest = execute(ctx)
    audio = artifact_path(ctx, manifest, "audio")
    audio.write_bytes(b"do not overwrite")
    with pytest.raises(WorkerError, match="PREPARATION_REUSE_OUTPUT_MISMATCH"):
        execute(ctx)
    assert audio.read_bytes() == b"do not overwrite"


@pytest.mark.parametrize("kind", ["source", "output", "artifact", "hardlink"])
def test_links_and_reparse_points_are_rejected(tmp_path, monkeypatch, kind):
    spec, _ = source_fixture(tmp_path, monkeypatch)
    ctx = context(tmp_path, spec)
    asr_dir = manifest_file(tmp_path, spec, "asr").parent
    if kind == "source":
        original = asr_dir / "asr"
        original.rename(asr_dir / "original-asr")
        link_directory(original, asr_dir / "original-asr")
    elif kind == "output":
        link_directory(ctx.directory / "preparation", asr_dir)
    elif kind == "hardlink":
        os.link(asr_dir / "asr/transcript.json", asr_dir / "same-transcript.json")
    else:
        link_directory(asr_dir / "linked", asr_dir / "asr")
        selected = ctx.lease.config["preparation_reuse"]
        change_manifest(tmp_path, selected, "asr", lambda value: value["artifacts"][0].update(
            key=(asr_dir / "linked/transcript.json").relative_to(tmp_path).as_posix()))
    with pytest.raises(WorkerError, match="PREPARATION_REUSE_UNSAFE_PATH"):
        execute(ctx)


def test_cancel_during_copy_does_not_publish_or_continue(tmp_path, monkeypatch):
    spec, _ = source_fixture(tmp_path, monkeypatch)
    ctx = context(tmp_path, spec)
    from livecut import preparation_reuse
    original_stream = preparation_reuse._stream
    checks = []

    def stop_when_copying(context, path, *, sink=None, limit=None):
        if sink is not None:
            checks.append("copy")
            context.guard._stop.set()
        return original_stream(context, path, sink=sink, limit=limit)

    monkeypatch.setattr(preparation_reuse, "_stream", stop_when_copying)
    with pytest.raises(WorkerError, match="WORKER_STOPPED"):
        execute(ctx)
    assert checks == ["copy"]
    assert not (ctx.directory / "preparation").exists()
    assert not list(ctx.directory.glob(".preparation-*"))


def test_source_video_hash_is_checked_even_before_media_reuse(tmp_path, monkeypatch):
    spec, _ = source_fixture(tmp_path, monkeypatch)
    ctx = context(tmp_path, spec)
    (tmp_path / ctx.lease.source_key).write_bytes(b"changed video")
    with pytest.raises(WorkerError, match="PREPARATION_REUSE_INPUT_MISMATCH"):
        execute(ctx)


def test_missing_asr_predecessor_is_not_regenerated(tmp_path, monkeypatch):
    spec, _ = source_fixture(tmp_path, monkeypatch)
    ctx = context(tmp_path, spec, stage_type="ASR")
    with pytest.raises(WorkerError, match="PREPARATION_REUSE_MISSING_MEDIA"):
        execute(ctx)


def test_missing_published_source_has_controlled_diagnostic(tmp_path, monkeypatch):
    spec, _ = source_fixture(tmp_path, monkeypatch)
    ctx = context(tmp_path, spec)
    (manifest_file(tmp_path, spec, "asr").parent / "asr/transcript.json").unlink()
    with pytest.raises(WorkerError, match="PREPARATION_REUSE_MISSING_SOURCE"):
        execute(ctx)


def test_null_reuse_config_fails_instead_of_silently_recomputing(tmp_path, monkeypatch):
    spec, _ = source_fixture(tmp_path, monkeypatch)
    ctx = context(tmp_path, spec)
    ctx.lease.config["preparation_reuse"] = None
    with pytest.raises(WorkerError, match="PREPARATION_REUSE_INVALID_SOURCE"):
        execute(ctx)
