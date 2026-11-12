"""Finite real-broker/real-API dispatch smoke test; no model provider is constructed.

Requires a fresh isolated API (Kafka mode), matching empty shared storage, and a
real Kafka broker. MEDIA uses FFmpeg; ASR deliberately publishes a synthetic
transcript fixture. This tests scheduling, not speech recognition or AI quality.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import threading
import time
from pathlib import Path
from uuid import uuid4

import httpx
from kafka import KafkaConsumer, KafkaProducer
from livecut.kafka_wakeup import KafkaWakeup
from livecut.worker import AnalysisStageExecutor, StageWorker
from livecut.worker_client import WorkerClient


def wait_for(predicate, errors, seconds=45):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if errors:
            raise RuntimeError("SMOKE_WORKER_FAILED") from errors[0]
        if predicate():
            return
        time.sleep(.05)
    raise RuntimeError("SMOKE_DEADLINE_EXCEEDED")


class ObservedClient(WorkerClient):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.ready_reads = []
        self.completions = []

    def ready(self, **kwargs):
        result = super().ready(**kwargs)
        self.ready_reads.append(time.monotonic())
        return result

    def callback(self, action, stage_id, payload):
        result = super().callback(action, stage_id, payload)
        if action == "complete":
            self.completions.append(stage_id)
        return result


def synthetic_asr(context):
    """A labeled, local scheduling fixture: never invokes real ASR or a model."""
    transcript = context.directory / "synthetic-transcript.json"
    transcript.write_text(json.dumps([
        {"utterance_id": "synthetic_u1", "start_ms": 0, "end_ms": 1000,
         "text": "Synthetic Kafka scheduling fixture; not recognized speech."}
    ]), encoding="utf-8")
    lease = context.lease
    return {"schema_version": 1, "data_kind": "model_result", "review_status": "DRAFT",
            "job_id": lease.job_id, "video_id": lease.video_id,
            "analysis_generation": lease.generation, "stage_id": lease.stage_id,
            "stage_type": lease.stage_type, "attempt": lease.attempt,
            "run_token": lease.run_token, "input_sha256": lease.source_hash,
            "artifacts": [context.artifact("transcript", transcript)]}


def run(args):
    key = os.environ.get("LIVECUT_WORKER_API_KEY")
    if not key:
        raise RuntimeError("SMOKE_WORKER_KEY_REQUIRED")
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    source = output / "synthetic.mp4"
    subprocess.run([args.ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
                    "-f", "lavfi", "-i", "color=c=blue:s=320x180:r=10:d=2",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
                    "-shortest", str(source)], check=True, timeout=30)
    clients, workers, threads, events, errors = [], [], [], {"MEDIA": [], "ASR": []}, []
    job_id = None
    started = time.monotonic()
    with httpx.Client(base_url=args.java_url, trust_env=False, timeout=15) as api:
        # Refuse to let broad stage workers process someone else's queued jobs.
        existing = api.get("/api/jobs?limit=100").raise_for_status().json()
        if existing.get("items"):
            raise RuntimeError("SMOKE_REQUIRES_ISOLATED_IDLE_API")
        try:
            for stage in ("MEDIA", "ASR"):
                client = ObservedClient(args.java_url, key)
                clients.append(client)
                worker_id = "kafka-smoke-" + stage.lower() + "-" + uuid4().hex[:12]
                runner = StageWorker(client, args.storage_root, output / stage, worker_id,
                                     AnalysisStageExecutor(ffmpeg_path=args.ffmpeg, ffprobe_path=args.ffprobe)
                                     if stage == "MEDIA" else synthetic_asr,
                                     registration_wait_seconds=15, reconnect_wait_seconds=15)
                workers.append(runner)
                wakeup = KafkaWakeup(args.bootstrap_servers, worker_id, topic=args.topic,
                                    reconnect_wait_seconds=15, observer=events[stage].append)

                def execute(runner=runner, stage=stage, wakeup=wakeup):
                    try:
                        runner.run(stage_type=stage, wakeup=wakeup, reconcile_seconds=600)
                    except BaseException as exc:
                        errors.append(exc)

                thread = threading.Thread(target=execute, daemon=True)
                threads.append(thread)
                thread.start()
            wait_for(lambda: all(c.ready_reads for c in clients), errors)
            # Initial subscription and catch-up are over. Idle must not poll at 3s.
            time.sleep(.3)
            initial = [len(c.ready_reads) for c in clients]
            time.sleep(4)
            idle_reads = [len(c.ready_reads) - n for c, n in zip(clients, initial)]
            assert idle_reads == [0, 0], "READY_POLLED_WITHOUT_EVENT"
            with source.open("rb") as video:
                uploaded = api.post("/api/videos", files={"file": (source.name, video, "video/mp4")})
            uploaded.raise_for_status()
            created = api.post("/api/jobs", headers={"Idempotency-Key": "kafka-smoke-" + uuid4().hex},
                               json={"video_id": uploaded.json()["id"], "config": {"analysis_mode": "topics"}})
            created.raise_for_status()
            job_id = created.json()["id"]
            wait_for(lambda: all(c.completions for c in clients), errors)
            result = api.get("/api/jobs/" + job_id).raise_for_status().json()
            stages = {s["stage_type"]: s for s in result["stages"]}
            assert stages["MEDIA"]["status"] == stages["ASR"]["status"] == "SUCCEEDED"
            assert stages["MEDIA"]["attempt"] == stages["ASR"]["attempt"] == 1
            for stage in ("MEDIA", "ASR"):
                assert any(e.get("status") == "dispatch-notified" and e.get("stage_id") == stages[stage]["id"]
                           and e.get("reason") == "READY" for e in events[stage]), "MISSING_BROKER_WAKEUP"
                assert not any(e.get("status") == "dispatch-reconcile" for e in events[stage])
            assert stages["SEGMENT"]["status"] == "READY"
            # Replay the exact broker record after success: offset delivery must
            # not turn a terminal stage into a second execution.
            records = KafkaConsumer(args.topic, bootstrap_servers=args.bootstrap_servers,
                                    enable_auto_commit=False, auto_offset_reset="earliest",
                                    consumer_timeout_ms=5000)
            try:
                original = next(record for record in records
                                if json.loads(record.value).get("stage_id") == stages["ASR"]["id"]
                                and json.loads(record.value).get("reason") == "READY")
            finally:
                records.close(autocommit=False, timeout_ms=1000)
            count_before = sum(e.get("event_id") == json.loads(original.value)["event_id"]
                               for e in events["ASR"])
            ready_before_duplicate = len(clients[1].ready_reads)
            producer = KafkaProducer(bootstrap_servers=args.bootstrap_servers, acks="all")
            try:
                for _ in range(3):
                    producer.send(args.topic, key=original.key, value=original.value).get(timeout=10)
            finally:
                producer.close(timeout=5)
            wait_for(lambda: sum(e.get("event_id") == json.loads(original.value)["event_id"]
                                 for e in events["ASR"]) >= count_before + 3, errors)
            wait_for(lambda: len(clients[1].ready_reads) > ready_before_duplicate, errors)
            assert all(len(c.completions) == 1 for c in clients)
            final = api.get("/api/jobs/" + job_id).raise_for_status().json()
            assert all(s["attempt"] == 1 and s["status"] == "SUCCEEDED"
                       for s in final["stages"] if s["stage_type"] in {"MEDIA", "ASR"})
            report = {"passed": True, "data_kind": "synthetic_dispatch_smoke", "job_id": job_id,
                      "transport": "real Kafka + Java API + two production StageWorkers",
                      "media": "real FFmpeg extraction", "asr": "local synthetic transcript fixture",
                      "new_model_requests": 0, "new_asr_requests": 0,
                      "idle_ready_reads_4_seconds": idle_reads,
                      "idle_probe_passed": True, "reconcile_seconds": 600,
                      "exact_duplicate_records_received": 3, "duplicate_execution": False,
                      "stages": {k: {"id": stages[k]["id"], "status": stages[k]["status"],
                                      "attempt": stages[k]["attempt"]} for k in ("MEDIA", "ASR")},
                      "events": events, "elapsed_seconds": round(time.monotonic() - started, 3)}
            (output / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            print(json.dumps({k: v for k, v in report.items() if k != "events"}))
        finally:
            for runner in workers:
                runner.stop.set()
            for thread in threads:
                thread.join(25)
            for client in clients:
                client.close()
            if job_id:
                api.post("/api/jobs/" + job_id + "/cancel").raise_for_status()
            if any(t.is_alive() for t in threads):
                raise RuntimeError("SMOKE_WORKER_DID_NOT_STOP")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-url", required=True)
    parser.add_argument("--storage-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--bootstrap-servers", default="127.0.0.1:19092")
    parser.add_argument("--topic", default="livecut.stage-wakeup.v1")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    run(parser.parse_args())
