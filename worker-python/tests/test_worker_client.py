import httpx
import pytest
from livecut.cli import main
from livecut.worker_client import WorkerClient, WorkerClientError


def test_authenticated_exact_routes_and_payloads():
    requests = []

    def receive(request):
        requests.append(request)
        assert request.headers["X-Worker-Key"] == "private-worker-key"
        assert "Authorization" not in request.headers
        return httpx.Response(200, json={"outcome": "ACCEPTED"})

    client = WorkerClient("http://127.0.0.1:8080", "private-worker-key",
                          transport=httpx.MockTransport(receive))
    client.ready(stage_type="ASR", limit=1)
    client.claim("stage-1", "worker-1", "claim-1")
    client.callback("complete", "stage-1", {"attempt": 1})
    assert requests[0].url.path == "/internal/stages/ready"
    assert requests[0].url.params["stage_type"] == "ASR"
    assert requests[1].url.path == "/internal/stages/claim"
    assert requests[2].url.path == "/internal/stages/stage-1/complete"
    client.close()


def test_redirect_is_never_followed_and_body_is_not_exposed():
    def receive(request):
        return httpx.Response(302, headers={"Location": "https://untrusted.invalid"},
                              json={"message": "private-worker-key"})

    client = WorkerClient("http://localhost", "private-worker-key",
                          transport=httpx.MockTransport(receive))
    with pytest.raises(WorkerClientError) as caught:
        client.ready()
    assert str(caught.value) == "JAVA_HTTP_ERROR"
    client.close()


@pytest.mark.parametrize("url", ["http://external.example", "https://user:pass@example.com",
                                "https://example.com?key=secret", "file:///tmp/test"])
def test_invalid_or_unsafe_endpoint(url):
    with pytest.raises(WorkerClientError, match="INVALID_WORKER_CONFIGURATION"):
        WorkerClient(url, "secret")


def test_server_error_is_retryable_without_body_leak():
    client = WorkerClient("http://localhost", "secret", transport=httpx.MockTransport(
        lambda request: httpx.Response(503, text="sensitive details")))
    with pytest.raises(WorkerClientError) as caught:
        client.ready()
    assert caught.value.retryable
    assert "sensitive" not in str(caught.value)
    client.close()


def test_java_error_code_is_preserved_instead_of_generic_conflict():
    client = WorkerClient("http://localhost", "secret", transport=httpx.MockTransport(
        lambda request: httpx.Response(409, json={"error_code": "MODEL_BUDGET_EXHAUSTED"})))
    with pytest.raises(WorkerClientError, match="MODEL_BUDGET_EXHAUSTED"):
        client.ready()
    client.close()


def test_worker_cli_missing_key_returns_sanitized_error(tmp_path, monkeypatch, capsys):
    monkeypatch.delenv("LIVECUT_WORKER_API_KEY", raising=False)
    monkeypatch.delenv("LIVECUT_WORKER_KEY", raising=False)
    assert main(["worker", "--once", "--storage-root", str(tmp_path / "storage"),
                 "--journal-dir", str(tmp_path / "journal")]) == 2
    assert '"error_code": "INVALID_WORKER_CONFIGURATION"' in capsys.readouterr().out


def test_worker_cli_once_preserves_stage_filter_and_failure_exit(tmp_path, monkeypatch, capsys):
    observed = []

    class Client:
        def __init__(self, *args):
            pass

        def close(self):
            observed.append("closed")

    class Runner:
        def __init__(self, *args):
            pass

        def run_once(self, **kwargs):
            observed.append(kwargs)
            return "failed"

    monkeypatch.setenv("LIVECUT_WORKER_API_KEY", "unit-test-key")
    monkeypatch.setattr("livecut.worker_client.WorkerClient", Client)
    monkeypatch.setattr("livecut.worker.StageWorker", Runner)
    assert main(["worker", "--once", "--stage-type", "MEDIA", "--storage-root", str(tmp_path),
                 "--journal-dir", str(tmp_path / "journal")]) == 1
    assert observed == [{"stage_type": "MEDIA"}, "closed"]
    assert "unit-test-key" not in capsys.readouterr().out
