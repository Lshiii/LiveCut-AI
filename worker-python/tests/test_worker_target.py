"""Explicit target jobs fence claims and local uncertain-operation journals."""

import json
from uuid import uuid4

import httpx
import pytest
from livecut.cli import main
from livecut.worker import StageWorker, WorkerError, _write
from livecut.worker_client import WorkerClient, WorkerClientError
from test_worker import FakeClient, claim, fake_executor


class TargetClient(FakeClient):
    def __init__(self, job_id):
        super().__init__()
        self.reply = claim(job_id=job_id)
        self.ready_job_id = job_id
        self.requests = []

    def ready(self, **kwargs):
        self.requests.append(("ready", kwargs))
        return {"items": [{"id": "stage-1", "job_id": self.ready_job_id}]}

    def claim(self, stage_id, worker_id, claim_request_id, *, job_id=None):
        self.requests.append(("claim", job_id))
        return super().claim(stage_id, worker_id, claim_request_id)


def worker(tmp_path, client, calls, job_id):
    return StageWorker(client, tmp_path / "storage", tmp_path / "journal", "worker-1",
                       fake_executor(calls), target_job_id=job_id)


def test_client_target_filter_claim_and_public_source_routes_are_explicit():
    target, source, requests = str(uuid4()), str(uuid4()), []

    def receive(request):
        requests.append(request)
        return httpx.Response(200, json={"id": source} if request.url.path.startswith("/api/jobs/")
                              else {"items": []})

    client = WorkerClient("http://localhost", "private", transport=httpx.MockTransport(receive))
    client.ready(stage_type="ASR", limit=1, job_id=target.upper())
    client.claim("stage-1", "worker-1", "claim-1", job_id=target)
    client.job(source.upper())
    client.close()
    assert requests[0].url.params["job_id"] == target
    assert json.loads(requests[1].content)["job_id"] == target
    assert requests[2].url.path == "/api/jobs/" + source
    assert all(request.headers["X-Worker-Key"] == "private" for request in requests)


def test_public_source_response_must_belong_to_requested_job():
    client = WorkerClient("http://localhost", "private", transport=httpx.MockTransport(
        lambda request: httpx.Response(200, json={"id": str(uuid4())})))
    with pytest.raises(WorkerClientError, match="INVALID_JAVA_RESPONSE"):
        client.job(str(uuid4()))
    client.close()


def test_target_completion_persists_job_and_replays_without_computation(tmp_path):
    job_id, calls = str(uuid4()), []
    client = TargetClient(job_id)
    client.fail_complete_once = True
    with pytest.raises(WorkerClientError):
        worker(tmp_path, client, calls, job_id.upper()).run_once(stage_type="MEDIA")
    pending = json.loads((tmp_path / "journal/pending-callback.json").read_bytes())
    assert pending["job_id"] == job_id
    result = next((tmp_path / "storage").rglob("worker-result.json"))
    assert json.loads(result.read_bytes()) == pending
    assert worker(tmp_path, client, calls, job_id).run_once() == "callback_replayed"
    assert calls == ["stage-1"]
    assert client.requests == [("ready", {"stage_type": "MEDIA", "limit": 1, "job_id": job_id}),
                               ("claim", job_id)]
    assert client.callbacks[0] == client.callbacks[1]


def test_uncertain_target_claim_keeps_job_and_request_id(tmp_path):
    job_id, calls = str(uuid4()), []
    client = TargetClient(job_id)
    client.fail_claim_once = True
    with pytest.raises(WorkerClientError):
        worker(tmp_path, client, calls, job_id).run_once()
    pending = json.loads((tmp_path / "journal/pending-claim.json").read_bytes())
    assert pending["job_id"] == job_id
    assert worker(tmp_path, client, calls, job_id).run_once() == "completed"
    assert client.claims[0] == client.claims[1]


@pytest.mark.parametrize("filename", ["pending-claim.json", "pending-callback.json"])
@pytest.mark.parametrize("identity", [None, "other"])
def test_legacy_or_other_job_pending_records_fail_before_http(tmp_path, filename, identity):
    job_id, calls = str(uuid4()), []
    client = TargetClient(job_id)
    runner = worker(tmp_path, client, calls, job_id)
    record = {"stage_id": "stage-1", "worker_id": "worker-1", "claim_request_id": "request-1",
              "action": "complete", "payload": {}}
    if identity:
        record["job_id"] = str(uuid4())
    _write(tmp_path / "journal" / filename, record)
    with pytest.raises(WorkerError, match="WORKER_JOB_TARGET_MISMATCH"):
        runner.run_once()
    assert client.requests == [] and client.callbacks == [] and calls == []


@pytest.mark.parametrize("identity", [None, "other"])
def test_stored_result_fails_after_target_claim_before_callback_or_provider(tmp_path, identity):
    # The attempt directory is known only after claiming. That read-only
    # inspection may follow ready/claim, but must precede every callback/model.
    job_id, calls = str(uuid4()), []
    client = TargetClient(job_id)
    runner = worker(tmp_path, client, calls, job_id)
    directory = tmp_path / f"storage/jobs/{job_id}/stages/stage-1/attempts/1/token-1"
    record = {"action": "complete", "stage_id": "stage-1", "payload": {}}
    if identity:
        record["job_id"] = str(uuid4())
    _write(directory / "worker-result.json", record)
    with pytest.raises(WorkerError, match="WORKER_JOB_TARGET_MISMATCH"):
        runner.run_once()
    assert len(client.requests) == 2 and client.callbacks == [] and calls == []
    assert not (tmp_path / "journal/pending-callback.json").exists()


@pytest.mark.parametrize("where", ["ready", "claim"])
def test_target_change_rejected_before_computation_or_callbacks(tmp_path, where):
    job_id, calls = str(uuid4()), []
    client = TargetClient(job_id)
    if where == "ready":
        client.ready_job_id = str(uuid4())
    else:
        client.reply["job_id"] = str(uuid4())
    with pytest.raises(WorkerError, match="CLAIM_TARGET_CHANGED"):
        worker(tmp_path, client, calls, job_id).run_once()
    assert not calls and not client.callbacks
    assert len(client.claims) == (where == "claim")


@pytest.mark.parametrize("options", [[], ["--once"], ["--once", "--stage-type", "ASR"],
                                    ["--once", "--stage-type", "MEDIA", "--job-id", str(uuid4())],
                                    ["--stage-type", "ASR", "--job-id", str(uuid4())]])
def test_cli_reuse_requires_once_asr_and_target_before_client(tmp_path, monkeypatch, capsys, options):
    def fail(*args, **kwargs):
        pytest.fail("invalid reuse options must fail before client construction")
    monkeypatch.setattr("livecut.worker_client.WorkerClient", fail)
    assert main(["worker", "--reuse-asr-job", str(uuid4()), "--storage-root", str(tmp_path),
                 "--journal-dir", str(tmp_path / "journal"), *options]) == 2
    assert "INVALID_ASR_REUSE_CONFIGURATION" in capsys.readouterr().out


def test_cli_reuse_loads_source_from_public_api_and_passes_target(tmp_path, monkeypatch):
    source_id, target_id, observed = str(uuid4()), str(uuid4()), []
    snapshot = {"id": source_id, "stages": []}

    class Client:
        def __init__(self, *args):
            pass

        def job(self, job_id):
            observed.append(("source", job_id))
            return snapshot

        def close(self):
            observed.append("closed")

    class Executor:
        def __init__(self, **kwargs):
            assert kwargs["asr_reuse_source"] is snapshot

    class Runner:
        def __init__(self, *args, **kwargs):
            assert kwargs["target_job_id"] == target_id

        def run_once(self, **kwargs):
            assert kwargs == {"stage_type": "ASR"}
            return "completed"

    monkeypatch.setattr("livecut.worker_client.WorkerClient", Client)
    monkeypatch.setattr("livecut.worker.AnalysisStageExecutor", Executor)
    monkeypatch.setattr("livecut.worker.StageWorker", Runner)
    assert main(["worker", "--reuse-asr-job", source_id, "--job-id", target_id,
                 "--stage-type", "ASR", "--once", "--storage-root", str(tmp_path),
                 "--journal-dir", str(tmp_path / "journal")]) == 0
    assert observed == [("source", source_id), "closed"]


def test_cli_interrupt_during_source_fetch_closes_client_before_runner_exists(tmp_path, monkeypatch, capsys):
    observed = []

    class Client:
        def __init__(self, *args):
            pass

        def job(self, job_id):
            raise KeyboardInterrupt

        def close(self):
            observed.append("closed")

    monkeypatch.setattr("livecut.worker_client.WorkerClient", Client)
    assert main(["worker", "--reuse-asr-job", str(uuid4()), "--job-id", str(uuid4()),
                 "--stage-type", "ASR", "--once", "--storage-root", str(tmp_path),
                 "--journal-dir", str(tmp_path / "journal")]) == 0
    assert observed == ["closed"]
    assert '"status": "stopped"' in capsys.readouterr().out
