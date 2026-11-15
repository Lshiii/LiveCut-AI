from __future__ import annotations

import hashlib
import json
import shutil
import socket
import subprocess
import time
from datetime import datetime, timezone
from uuid import uuid4

import httpx
import pytest
from livecut import source_import as imp
from livecut import source_import_download as dl
from livecut.media import _require_aligned_timeline, probe_media
from livecut.worker_client import WorkerClient, WorkerClientError


def instant(value):
    return datetime.fromtimestamp(value, timezone.utc).isoformat().replace("+00:00", "Z")


@pytest.fixture
def claim():
    now = time.time()
    return {"id": str(uuid4()), "url": "https://www.youtube.com/watch?v=BaW_jenozKc",
            "attempt": 1, "run_token": str(uuid4()), "lease_until": instant(now + 90),
            "hard_deadline_at": instant(now + 3600), "max_bytes": 8 * 1024 * 1024}


def snapshot(claim, action, body):
    return {"id": claim["id"], "url": claim["url"], "status": "COMPLETED" if action == "complete" else "FAILED",
            "phase": "VERIFYING", "progress_percent": None, "downloaded_bytes": 123,
            "video_id": str(uuid4()) if action == "complete" else None,
            "error_code": body.get("error_code"), "attempt": claim["attempt"],
            "created_at": instant(time.time()), "updated_at": instant(time.time())}


def fake_client(claim, *, lose_complete=False, stale=False):
    calls = []
    def handle(request):
        assert request.headers["X-Worker-Key"] == "local-test-key"
        body = json.loads(request.content)
        calls.append((request.url.path, body))
        if request.url.path.endswith("/claim"):
            return httpx.Response(200, json={"claim": claim})
        action = request.url.path.rsplit("/", 1)[1]
        assert body["attempt"] == claim["attempt"] and body["run_token"] == claim["run_token"]
        if stale:
            return httpx.Response(409, json={"error_code": "STALE_IMPORT_ATTEMPT"})
        if action == "heartbeat":
            return httpx.Response(200, json={k: claim[k] for k in ("lease_until", "hard_deadline_at")})
        if lose_complete and action == "complete" and sum(p.endswith("complete") for p, _ in calls) == 1:
            raise httpx.ReadError("lost acknowledgement")
        return httpx.Response(200, json=snapshot(claim, action, body))
    client = WorkerClient("http://127.0.0.1:8080", "local-test-key", transport=httpx.MockTransport(handle))
    return imp.SourceImportClient(client), calls


@pytest.mark.parametrize("url", [
    "http://www.youtube.com/watch?v=BaW_jenozKc", "https://127.0.0.1/watch?v=BaW_jenozKc",
    "https://www.youtube.com@127.0.0.1/watch?v=BaW_jenozKc", "file:///etc/passwd",
    "https://www.youtube.com/watch?v=BaW_jenozKc&list=abc", "https://www.youtube.com/playlist?list=x",
    "https://www.bilibili.com/video/BV1xx411c7mD?p=2", "https://www.bilibili.com/bangumi/play/ss1",
    "https://www.youtube.com:8443/watch?v=BaW_jenozKc", "https://www.youtube.com/watch?v=x",
])
def test_url_rejects_noncanonical_or_playlist(url):
    with pytest.raises(dl.ImportDownloadError, match="INVALID_URL"):
        dl.validate_url(url)


@pytest.mark.parametrize("url,extractor", [
    ("https://www.youtube.com/watch?v=BaW_jenozKc", "Youtube"),
    ("https://www.bilibili.com/video/BV1xx411c7mD", "BiliBili"),
    ("https://www.bilibili.com/video/av123?p=1", "BiliBili"),
])
def test_canonical_url(url, extractor):
    assert dl.validate_url(url) == extractor


@pytest.mark.parametrize("ip", ["127.0.0.1", "::1", "10.2.3.4", "192.168.1.1", "169.254.169.254",
    "100.64.0.1", "0.0.0.0", "224.0.0.1", "::ffff:127.0.0.1", "fe80::1", "fc00::1", "192.0.2.1"])
def test_nonpublic_addresses(ip):
    assert not dl._public_ip(ip)


def test_dns_and_direct_connect_guard_cover_redirect_rebinding(monkeypatch):
    original_resolve, original_connect = socket.getaddrinfo, socket.socket.connect
    original_connect_ex = socket.socket.connect_ex
    connected = []
    monkeypatch.setattr(socket, "getaddrinfo", lambda *a, **k: [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("8.8.8.8", 443))])
    monkeypatch.setattr(socket.socket, "connect", lambda s, a: connected.append(a))
    monkeypatch.setattr(socket.socket, "connect_ex", lambda s, a: connected.append(a) or 0)
    try:
        dl.install_network_guard()
        with socket.socket() as sock:
            sock.connect(("example.org", 443))
            assert connected == [("8.8.8.8", 443)]
            for address in [("127.0.0.1", 443), ("8.8.8.8", 80)]:
                with pytest.raises(OSError, match="NETWORK_DENIED"):
                    sock.connect(address)
                with pytest.raises(OSError, match="NETWORK_DENIED"):
                    sock.connect_ex(address)
    finally:
        socket.getaddrinfo, socket.socket.connect, socket.socket.connect_ex = original_resolve, original_connect, original_connect_ex


def test_mixed_dns_returns_only_public_addresses(monkeypatch):
    original_resolve, original_connect = socket.getaddrinfo, socket.socket.connect
    original_connect_ex = socket.socket.connect_ex
    monkeypatch.setattr(socket, "getaddrinfo", lambda *a, **k: [
        (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("8.8.8.8", 443)),
        (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("10.0.0.1", 443))])
    try:
        dl.install_network_guard()
        rows = socket.getaddrinfo("rebound.example", 443)
        assert [row[4][0] for row in rows] == ["8.8.8.8"]
    finally:
        socket.getaddrinfo, socket.socket.connect, socket.socket.connect_ex = original_resolve, original_connect, original_connect_ex


def test_private_or_fake_dns_without_public_address_is_rejected(monkeypatch):
    original_resolve, original_connect = socket.getaddrinfo, socket.socket.connect
    original_connect_ex = socket.socket.connect_ex
    monkeypatch.setattr(socket, "getaddrinfo", lambda *a, **k: [
        (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("198.18.0.23", 443)),
        (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("10.0.0.1", 443))])
    try:
        dl.install_network_guard()
        with pytest.raises(OSError, match="NETWORK_DENIED"):
            socket.getaddrinfo("only-internal.example", 443)
    finally:
        socket.getaddrinfo, socket.socket.connect, socket.socket.connect_ex = original_resolve, original_connect, original_connect_ex


def test_wrapped_network_denial_keeps_fixed_code_without_raw_provider_message():
    original = OSError("SOURCE_IMPORT_NETWORK_DENIED")
    middle, outer = RuntimeError("private provider response"), RuntimeError("signed URL must stay private")
    middle.cause = original
    outer.exc_info = (RuntimeError, middle, None)
    assert dl._failure_code(outer) == "SOURCE_IMPORT_NETWORK_DENIED"
    assert dl._failure_code(RuntimeError("remote text SOURCE_IMPORT_NETWORK_DENIED")) == "SOURCE_IMPORT_DOWNLOAD_FAILED"


@pytest.mark.parametrize("info", [{"_type": "playlist"}, {"_type": "multi_video"},
    {"entries": []}, {"is_live": True}, {"was_live": True}, {"live_status": "is_upcoming"}])
def test_metadata_refuses_playlist_or_live(info):
    with pytest.raises(dl.ImportDownloadError, match="UNSUPPORTED_SOURCE"):
        dl._streams(info)


def test_metadata_prefers_bounded_direct_combined_and_requires_audio():
    rows = [{"protocol": "https", "vcodec": "avc1", "acodec": "aac", "height": h} for h in [360, 720, 1080]]
    assert dl._streams({"formats": rows})[0]["height"] == 720
    with pytest.raises(dl.ImportDownloadError, match="NO_HTTPS_AUDIO_VIDEO"):
        dl._streams({"formats": [{"protocol": "https", "vcodec": "avc1", "acodec": "none"}]})


def test_metadata_downgrades_to_fit_total_input_budget():
    rows = [{"protocol": "https", "vcodec": "avc1", "acodec": "aac", "height": h, "filesize": size}
            for h, size in [(360, 100), (720, 1000)]]
    assert dl._streams({"formats": rows}, 200)[0]["height"] == 360


def test_complete_lost_ack_replays_exact_body_without_redownload(tmp_path, claim):
    client, calls = fake_client(claim, lose_complete=True)
    downloads = []
    def download(*args):
        downloads.append(1)
        return {}
    body = {"attempt": claim["attempt"], "run_token": claim["run_token"], "sha256": "a" * 64, "size_bytes": 123}
    def normalize(result, parsed, directory, guard):
        (directory / "source.mp4").write_bytes(b"verified-final")
        (directory / "stream-0.media").write_bytes(b"temporary")
        return body
    worker = imp.SourceImportWorker(client, tmp_path / "storage", tmp_path / "journal",
        downloader=download, normalizer=normalize)
    with pytest.raises(WorkerClientError, match="JAVA_UNAVAILABLE"):
        worker.run_once()
    assert (tmp_path / "journal/pending-callback.json").exists()
    assert worker.run_once() == "callback_replayed"
    assert len(downloads) == 1
    complete = [b for p, b in calls if p.endswith("/complete")]
    assert complete == [body, body]
    assert not (tmp_path / "journal/pending-callback.json").exists()
    final = tmp_path / "storage/imports" / claim["id"] / "attempts/1" / claim["run_token"] / "source.mp4"
    assert final.read_bytes() == b"verified-final"
    assert not final.with_name("stream-0.media").exists()


def test_unknown_claim_same_session_reuses_key_but_new_session_does_not(tmp_path, claim):
    calls = []
    class Client:
        def claim(self, body):
            calls.append(body)
            raise WorkerClientError("JAVA_UNAVAILABLE", retryable=True)
    worker = imp.SourceImportWorker(Client(), tmp_path / "s", tmp_path / "j")
    for _ in range(2):
        with pytest.raises(WorkerClientError):
            worker.run_once()
    assert calls[0] == calls[1]
    other = imp.SourceImportWorker(Client(), tmp_path / "s", tmp_path / "j")
    with pytest.raises(WorkerClientError):
        other.run_once()
    assert calls[2]["claim_request_id"] != calls[0]["claim_request_id"]
    assert len(list((tmp_path / "j").glob("abandoned-claim-*.json"))) == 1


def test_cancelled_or_stale_guard_never_downloads_or_completes(tmp_path, claim):
    client, calls = fake_client(claim, stale=True)
    worker = imp.SourceImportWorker(client, tmp_path / "s", tmp_path / "j",
        downloader=lambda *a: pytest.fail("must not download"))
    assert worker.run_once() == "stale"
    assert not any(p.endswith("/complete") for p, _ in calls)


def test_guard_stop_deadline_and_staging_bound(tmp_path, claim):
    client, _ = fake_client(claim)
    parsed = imp.ImportClaim.parse(claim)
    guard = imp.ImportGuard(client, parsed, tmp_path, cancelled=lambda: True)
    with pytest.raises(imp.SourceImportError, match="STOPPED"):
        guard.check()
    guard.cancelled = lambda: False
    guard.clock = lambda: parsed.hard_deadline_at + 1
    with pytest.raises(imp.SourceImportError, match="TIMEOUT"):
        guard.check()
    guard.clock = time.time
    parsed.max_bytes = 1
    (tmp_path / "oversized").write_bytes(b"x" * (256 * 1024 + 3))
    with pytest.raises(imp.SourceImportError, match="STAGING_LIMIT"):
        guard.check()


def test_expired_callback_only_reconciles_exact_completion_after_new_process(tmp_path, claim):
    client, calls = fake_client(claim)
    worker = imp.SourceImportWorker(client, tmp_path / "s", tmp_path / "j")
    imp._atomic(tmp_path / "j/pending-callback.json", {"id": claim["id"], "action": "complete",
        "body": {"attempt": 1, "run_token": claim["run_token"], "sha256": "a" * 64, "size_bytes": 1},
        "lease_until": time.time() - 1, "hard_deadline_at": time.time() + 90})
    assert worker.run_once() == "callback_replayed"
    assert len(calls) == 1 and calls[0][0].endswith("/complete")


def test_failure_only_reports_safe_code(tmp_path, claim):
    client, calls = fake_client(claim)
    def download(*args):
        (args[1] / "stream-0.media").write_bytes(b"partial")
        (args[1] / "normalized.mp4").write_bytes(b"partial")
        raise imp.SourceImportError("SOURCE_IMPORT_TOO_LARGE")
    worker = imp.SourceImportWorker(client, tmp_path / "s", tmp_path / "j", downloader=download)
    assert worker.run_once() == "failed"
    assert calls[-1][1]["error_code"] == "SOURCE_IMPORT_TOO_LARGE"
    assert not list((tmp_path / "s").rglob("*.media"))
    assert not list((tmp_path / "s").rglob("normalized.mp4"))


def test_complete_malformed_snapshot_keeps_pending_callback(tmp_path, claim):
    def handle(request):
        body = json.loads(request.content)
        if request.url.path.endswith("/claim"):
            return httpx.Response(200, json={"claim": claim})
        if request.url.path.endswith("/heartbeat"):
            return httpx.Response(200, json={k: claim[k] for k in ("lease_until", "hard_deadline_at")})
        value = snapshot(claim, "complete", body)
        value["id"] = str(uuid4())
        return httpx.Response(200, json=value)
    client = imp.SourceImportClient(WorkerClient("http://127.0.0.1:8080", "key", transport=httpx.MockTransport(handle)))
    worker = imp.SourceImportWorker(client, tmp_path / "s", tmp_path / "j", downloader=lambda *a: {},
        normalizer=lambda *a: {"attempt": 1, "run_token": claim["run_token"], "sha256": "a" * 64, "size_bytes": 1})
    with pytest.raises(WorkerClientError, match="INVALID_JAVA_RESPONSE"):
        worker.run_once()
    assert (tmp_path / "j/pending-callback.json").exists()


def test_downloader_cancel_kills_only_child_and_strips_credentials(tmp_path, claim, monkeypatch):
    original = subprocess.Popen
    processes, environments = [], []
    def spawn(command, **kwargs):
        assert command[1:3] == ["-I", "-m"]
        environments.append(kwargs["env"])
        process = original([imp.sys.executable, "-c", "import time;time.sleep(30)"], **kwargs)
        processes.append(process)
        return process
    monkeypatch.setattr(imp.subprocess, "Popen", spawn)
    monkeypatch.setenv("DASHSCOPE_API_KEY", "secret-should-never-reach-downloader")
    monkeypatch.setenv("LIVECUT_WORKER_API_KEY", "control-key")
    client, _ = fake_client(claim)
    parsed = imp.ImportClaim.parse(claim)
    checks = []
    guard = imp.ImportGuard(client, parsed, tmp_path, cancelled=lambda: len(checks) > 1)
    original_check = guard.check
    def check():
        checks.append(1)
        original_check()
    guard.check = check
    started = time.monotonic()
    with pytest.raises(imp.SourceImportError, match="STOPPED"):
        imp._download_child(parsed, tmp_path, guard)
    assert time.monotonic() - started < 5
    assert processes[0].poll() is not None
    assert "DASHSCOPE_API_KEY" not in environments[0]
    assert "LIVECUT_WORKER_API_KEY" not in environments[0]


def test_external_downloader_and_js_processes_are_denied():
    with pytest.raises(FileNotFoundError, match="EXTERNAL_PROCESS_DENIED"):
        dl._no_external_execution("subprocess.Popen", ("curl",))


def test_download_hard_timeout_reaps_child(tmp_path, claim, monkeypatch):
    original, processes = subprocess.Popen, []
    def spawn(command, **kwargs):
        process = original([imp.sys.executable, "-c", "import time;time.sleep(30)"], **kwargs)
        processes.append(process)
        return process
    monkeypatch.setattr(imp.subprocess, "Popen", spawn)
    parsed = imp.ImportClaim.parse(claim)
    client, _ = fake_client(claim)
    started = time.monotonic()
    with pytest.raises(imp.SourceImportError, match="TIMEOUT"):
        imp._download_child(parsed, tmp_path, imp.ImportGuard(client, parsed, tmp_path), timeout_seconds=0.1)
    assert time.monotonic() - started < 5
    assert processes[0].poll() is not None


FFMPEG, FFPROBE = shutil.which("ffmpeg"), shutil.which("ffprobe")


@pytest.mark.skipif(not (FFMPEG and FFPROBE), reason="real FFmpeg required")
def test_real_ffmpeg_normalizes_video_audio_and_hash(tmp_path, claim):
    source = tmp_path / "stream-0.media"
    subprocess.run([FFMPEG, "-nostdin", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=10",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "1.2", "-c:v", "mpeg4",
        "-c:a", "pcm_s16le", "-f", "matroska", str(source)], check=True, capture_output=True, timeout=15)
    client, _ = fake_client(claim)
    parsed = imp.ImportClaim.parse(claim)
    guard = imp.ImportGuard(client, parsed, tmp_path)
    result = imp.normalize_download({"files": [source.name]}, parsed, tmp_path, guard,
        ffmpeg_path=FFMPEG, ffprobe_path=FFPROBE)
    target = tmp_path / "source.mp4"
    info = probe_media(target, ffprobe_path=FFPROBE)
    _require_aligned_timeline(info)
    assert (info.video_codec, info.audio_codec) == ("h264", "aac")
    assert result["sha256"] == hashlib.sha256(target.read_bytes()).hexdigest()
    assert result["size_bytes"] == target.stat().st_size
    assert not source.exists()


@pytest.mark.skipif(not (FFMPEG and FFPROBE), reason="real FFmpeg required")
def test_real_ffmpeg_rejects_silent_video(tmp_path, claim):
    source = tmp_path / "stream-0.media"
    subprocess.run([FFMPEG, "-nostdin", "-v", "error", "-f", "lavfi", "-i", "color=size=64x64:rate=5",
        "-t", "1", "-c:v", "mpeg4", "-f", "matroska", str(source)], check=True, capture_output=True, timeout=15)
    client, _ = fake_client(claim)
    parsed = imp.ImportClaim.parse(claim)
    with pytest.raises(imp.SourceImportError, match="AUDIO_REQUIRED"):
        imp.normalize_download({"files": [source.name]}, parsed, tmp_path,
            imp.ImportGuard(client, parsed, tmp_path), ffmpeg_path=FFMPEG, ffprobe_path=FFPROBE)
    assert not (tmp_path / "source.mp4").exists()


@pytest.mark.skipif(not (FFMPEG and FFPROBE), reason="real FFmpeg required")
def test_real_ffmpeg_preserves_delayed_video_by_padding_black(tmp_path, claim):
    source = tmp_path / "stream-0.media"
    subprocess.run([FFMPEG, "-nostdin", "-v", "error", "-itsoffset", "0.2", "-f", "lavfi",
        "-i", "color=color=white:size=64x64:rate=10", "-f", "lavfi", "-i", "sine=sample_rate=48000",
        "-t", "1.2", "-c:v", "mpeg4", "-c:a", "pcm_s16le", "-f", "matroska", str(source)],
        check=True, capture_output=True, timeout=15)
    client, _ = fake_client(claim)
    parsed = imp.ImportClaim.parse(claim)
    imp.normalize_download({"files": [source.name]}, parsed, tmp_path, imp.ImportGuard(client, parsed, tmp_path),
        ffmpeg_path=FFMPEG, ffprobe_path=FFPROBE)
    target = tmp_path / "source.mp4"
    _require_aligned_timeline(probe_media(target, ffprobe_path=FFPROBE))
    def pixel_at(position):
        value = subprocess.run([FFMPEG, "-nostdin", "-v", "error", "-ss", str(position), "-i", str(target),
            "-frames:v", "1", "-vf", "scale=1:1", "-pix_fmt", "gray", "-f", "rawvideo", "pipe:1"],
            check=True, capture_output=True, timeout=10)
        return value.stdout[0]
    assert pixel_at(0) < 30
    assert pixel_at(0.4) > 220


def test_truncated_output_duration_rejected(tmp_path, claim, monkeypatch):
    from dataclasses import replace
    (tmp_path / "stream-0.media").write_bytes(b"source")
    client, _ = fake_client(claim)
    parsed = imp.ImportClaim.parse(claim)
    monkeypatch.setattr(imp, "_input_timing", lambda *a: {"duration": 20, "video_delay": 0, "origin": 0})
    def run(command, **kwargs):
        (tmp_path / "normalized.mp4").write_bytes(b"encoded")
    monkeypatch.setattr(imp, "_run_process", run)
    from livecut.media import MediaInfo
    info = MediaInfo(tmp_path / "normalized.mp4", 1000, 7, "mp4", "h264", "aac", 64, 64, 10, 0,
                     0.0, 0.0, 0.0, 48000, 2)
    monkeypatch.setattr(imp, "probe_media", lambda *a, **k: replace(info))
    with pytest.raises(imp.SourceImportError, match="DURATION_MISMATCH"):
        imp.normalize_download({"files": ["stream-0.media"]}, parsed, tmp_path, imp.ImportGuard(client, parsed, tmp_path))
    assert not (tmp_path / "source.mp4").exists()
