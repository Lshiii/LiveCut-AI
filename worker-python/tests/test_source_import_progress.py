import errno
import json

import pytest
from livecut import source_import_download as dl


def test_progress_replace_one_windows_lock_then_success(tmp_path, monkeypatch):
    original, calls, sleeps = dl.os.replace, [], []
    def replace(source, target):
        calls.append(1)
        if len(calls) == 1:
            raise PermissionError(errno.EACCES, "synthetic transient lock")
        original(source, target)
    monkeypatch.setattr(dl, "_WINDOWS", True)
    monkeypatch.setattr(dl.os, "replace", replace)
    monkeypatch.setattr(dl.time, "sleep", sleeps.append)
    path = tmp_path / "download-progress.json"
    dl._atomic(path, {"downloaded_bytes": 123})
    assert json.loads(path.read_text()) == {"downloaded_bytes": 123}
    assert len(calls) == 2 and sleeps == [0.025]


def test_progress_permanent_windows_lock_stays_failure(tmp_path, monkeypatch):
    calls, sleeps = [], []
    def locked(*args):
        calls.append(1)
        raise PermissionError(errno.EACCES, "synthetic permanent lock")
    monkeypatch.setattr(dl, "_WINDOWS", True)
    monkeypatch.setattr(dl.os, "replace", locked)
    monkeypatch.setattr(dl.time, "sleep", sleeps.append)
    path = tmp_path / "download-progress.json"
    with pytest.raises(PermissionError):
        dl._atomic(path, {"downloaded_bytes": 123})
    assert not path.exists()
    assert len(calls) == 5 and sum(sleeps) < 1


def test_non_windows_permission_error_is_not_retried(tmp_path, monkeypatch):
    monkeypatch.setattr(dl, "_WINDOWS", False)
    def denied(*args):
        raise PermissionError(errno.EACCES, "synthetic permission denial")
    monkeypatch.setattr(dl.os, "replace", denied)
    monkeypatch.setattr(dl.time, "sleep", lambda _: pytest.fail("must not retry"))
    with pytest.raises(PermissionError):
        dl._atomic(tmp_path / "download-progress.json", {"downloaded_bytes": 1})


def test_progress_throttles_writes_but_final_snapshot_has_exact_bytes(tmp_path, monkeypatch):
    observed, now = [], [0.0]
    monkeypatch.setattr(dl, "_atomic", lambda path, value: observed.append(value))
    progress = dl._DownloadProgress(tmp_path / "progress.json", clock=lambda: now[0])
    progress.update(65536)
    for milliseconds in (1, 50, 100, 200, 249):
        now[0] = milliseconds / 1000
        progress.update(65536 + milliseconds)
    now[0] = 0.25
    progress.update(10 * 65536)
    now[0] = 0.26
    progress.update(10 * 65536 + 123, final=True)
    assert observed == [{"downloaded_bytes": 65536}, {"downloaded_bytes": 10 * 65536},
                        {"downloaded_bytes": 10 * 65536 + 123}]
