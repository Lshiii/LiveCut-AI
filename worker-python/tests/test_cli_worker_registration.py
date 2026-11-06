"""Worker startup options retain JSON output and clean interruption behavior."""

import json
import threading

import pytest
from livecut.cli import main


def worker_args(tmp_path, *options):
    return ["worker", "--storage-root", str(tmp_path / "storage"),
            "--journal-dir", str(tmp_path / "journal"), *options]


@pytest.mark.parametrize("value", ["-1", "3600.01", "nan", "NaN", "inf", "-inf", "1e999",
                                  "private-invalid-input", "http://secret.example/private"])
def test_invalid_registration_wait_is_sanitized_before_client(tmp_path, monkeypatch, capsys, value):
    def fail(*args, **kwargs):
        pytest.fail("invalid registration wait must fail before client construction")

    monkeypatch.setattr("livecut.worker_client.WorkerClient", fail)
    assert main(worker_args(tmp_path, f"--registration-wait-seconds={value}")) == 2
    assert json.loads(capsys.readouterr().out) == {
        "status": "error", "error_code": "INVALID_REGISTRATION_WAIT_CONFIGURATION",
        "message": "INVALID_REGISTRATION_WAIT_CONFIGURATION",
    }
    assert not (tmp_path / "storage").exists()
    assert not (tmp_path / "journal").exists()


@pytest.mark.parametrize(("options", "expected"), [([], 45), (["--registration-wait-seconds", "0"], 0),
                                                   (["--registration-wait-seconds", "3600"], 3600),
                                                   (["--registration-wait-seconds", "1.25"], 1.25)])
def test_registration_wait_events_preserve_once_final_result(tmp_path, monkeypatch, capsys,
                                                            options, expected):
    observed = []

    class Client:
        def __init__(self, *args):
            pass

        def close(self):
            observed.append("closed")

    class Runner:
        def __init__(self, *args, **kwargs):
            assert kwargs["registration_wait_seconds"] == expected
            self.emit = kwargs["lifecycle_observer"]

        def run_once(self, **kwargs):
            assert kwargs == {"stage_type": "MEDIA"}
            self.emit({"status": "registration-wait", "worker_id": "worker-local",
                       "elapsed_seconds": 0, "remaining_seconds": expected})
            self.emit({"status": "registered", "worker_id": "worker-local"})
            return "failed"

    monkeypatch.setenv("LIVECUT_WORKER_API_KEY", "private-worker-key")
    monkeypatch.setattr("livecut.worker_client.WorkerClient", Client)
    monkeypatch.setattr("livecut.worker.StageWorker", Runner)
    assert main(worker_args(tmp_path, "--once", "--stage-type", "MEDIA", *options)) == 1
    assert observed == ["closed"]
    output = capsys.readouterr().out
    assert [json.loads(line) for line in output.splitlines()] == [
        {"status": "registration-wait", "worker_id": "worker-local",
         "elapsed_seconds": 0, "remaining_seconds": expected},
        {"status": "registered", "worker_id": "worker-local"},
        {"status": "failed", "worker_id": "worker-local"},
    ]
    assert "private-worker-key" not in output


@pytest.mark.parametrize("once", [False, True])
def test_interrupt_while_waiting_emits_stopped_and_closes_client(tmp_path, monkeypatch, capsys, once):
    stop = threading.Event()
    observed = []

    class Client:
        def __init__(self, *args):
            pass

        def close(self):
            observed.append("closed")

    class Runner:
        def __init__(self, *args, **kwargs):
            self.stop = stop
            self.emit = kwargs["lifecycle_observer"]

        def run_once(self, **kwargs):
            self.emit({"status": "registration-wait", "worker_id": "worker-local",
                       "elapsed_seconds": 0, "remaining_seconds": 45})
            raise KeyboardInterrupt

        def run(self, **kwargs):
            self.run_once()

    monkeypatch.setattr("livecut.worker_client.WorkerClient", Client)
    monkeypatch.setattr("livecut.worker.StageWorker", Runner)
    assert main(worker_args(tmp_path, *(["--once"] if once else []))) == 0
    assert stop.is_set()
    assert observed == ["closed"]
    assert [json.loads(line)["status"] for line in capsys.readouterr().out.splitlines()] == [
        "registration-wait", "stopped",
    ]
