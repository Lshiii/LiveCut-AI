"""A hard stop between dispatch and response must resume through the durable gate."""
import json

import pytest
from livecut.segmentation import _request
from test_text_resilience import setup


def test_logical_checkpoint_after_hard_stop_uses_a_new_bounded_slot(tmp_path):
    hits = []

    def provider(request):
        import httpx

        hits.append(request)
        if len(hits) == 1:
            raise KeyboardInterrupt()
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop",
            "message": {"content": '{"ok":true}'}}]})

    client, ledger, context = setup(tmp_path, provider)
    client.livecut_resumable_text = True
    state = {"calls": []}
    (tmp_path / "logical").mkdir()
    options = {"endpoint": "https://primary.example/chat/completions", "key": "synthetic-key",
        "model": "primary-model", "messages": [{"role": "user", "content": "JSON"}],
        "directory": tmp_path / "logical", "state": state, "window_index": 0, "attempt": 0, "max_calls": 1}
    with client, pytest.raises(KeyboardInterrupt):
        _request(client, **options)
    assert len(state["calls"]) == 1 and state["calls"][0]["status"] == "started"
    assert next(iter(ledger.calls.values()))["status"] == "DISPATCHING"

    resumed, _, _ = setup(tmp_path, provider, ledger=ledger, context=context)
    resumed.livecut_resumable_text = True
    with resumed:
        result = _request(resumed, **options)
        assert _request(resumed, **options) == result
    assert len(hits) == len(ledger.calls) == 2
    assert len(state["calls"]) == 1 and state["calls"][0]["status"] == "received"
    assert [call["status"] for call in ledger.calls.values()] == ["DISPATCHING", "SUCCEEDED"]
    assert json.loads((tmp_path / "logical" / state["calls"][0]["response_file"]).read_text()) == result
