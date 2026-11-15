"""Opt-in real browser import: live UI/API/worker/platform; no mock or interception.

Start Java, Vite and scripts/source-import.ps1 against the same private config.
uv run --with playwright python scripts/tests/source-import-live-smoke.py --url HTTPS_VIDEO_URL
Creates one real import and selects its video; never creates an analysis.
"""
import argparse
import hashlib
import json
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin, urlsplit

from playwright.sync_api import expect, sync_playwright


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--base-url", default="http://127.0.0.1:5173")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    if urlsplit(args.base_url).hostname not in {"127.0.0.1", "localhost"}:
        parser.error("Only a local workbench is accepted")
    args.output.mkdir(parents=True, exist_ok=False)
    evidence = {"started_at": datetime.now(timezone.utc).isoformat(), "url": args.url,
                "base_url": args.base_url, "mode": "live_no_mock", "snapshots": [],
                "page_errors": [], "analysis_posts": 0}
    current = {"id": None, "snapshot": None, "phase": None}

    def save():
        (args.output / "evidence.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 1000})
        page.on("pageerror", lambda error: evidence["page_errors"].append(str(error)))

        def requested(request):
            if request.method == "POST" and urlsplit(request.url).path == "/api/jobs":
                evidence["analysis_posts"] += 1

        def responded(response):
            if not current["id"] or response.request.method != "GET" or response.status != 200:
                return
            if urlsplit(response.url).path != "/api/source-imports":
                return
            for item in response.json().get("items", []):
                if item["id"] == current["id"]:
                    current["snapshot"] = item
                    if not evidence["snapshots"] or evidence["snapshots"][-1] != item:
                        evidence["snapshots"].append(item)

        page.on("request", requested)
        page.on("response", responded)
        try:
            page.goto(args.base_url, wait_until="networkidle")
            page.get_by_role("button", name="链接导入", exact=True).click()
            page.get_by_label("视频链接", exact=True).fill(args.url)
            page.screenshot(path=str(args.output / "01-pasted.png"), full_page=True)
            with page.expect_response(lambda r: urlsplit(r.url).path == "/api/source-imports"
                                      and r.request.method == "POST") as submitted:
                page.get_by_role("button", name="导入链接", exact=True).click()
            response = submitted.value
            assert 200 <= response.status < 300, f"Create returned HTTP {response.status}"
            created = response.json()
            current.update(id=created["id"], snapshot=created)
            evidence["snapshots"].append(created)
            print(json.dumps({"created_import": created["id"], "url": created["url"]}), flush=True)
            save()
            deadline = time.monotonic() + args.timeout
            while time.monotonic() < deadline:
                snapshot = current["snapshot"]
                if snapshot["phase"] != current["phase"]:
                    current["phase"] = snapshot["phase"]
                    print(json.dumps({"phase": snapshot["phase"], "downloaded_bytes": snapshot["downloaded_bytes"]}), flush=True)
                    page.screenshot(path=str(args.output / f"phase-{snapshot['phase'].lower()}.png"), full_page=True)
                    save()
                if snapshot["status"] in {"COMPLETED", "FAILED", "CANCELLED"}:
                    break
                page.wait_for_timeout(500)
            snapshot = current["snapshot"]
            assert snapshot["status"] == "COMPLETED", f"Import terminal: {snapshot['status']} / {snapshot['error_code']}"
            row = page.locator(".source-list li").filter(has_text=created["url"]).first
            expect(row.get_by_role("button", name="使用此视频", exact=True)).to_be_enabled()
            with page.expect_response(lambda r: urlsplit(r.url).path == f"/api/videos/{snapshot['video_id']}"
                                      and r.request.method == "GET") as adopted:
                row.get_by_role("button", name="使用此视频", exact=True).click()
            video = adopted.value.json()
            assert video["id"] == snapshot["video_id"]
            expect(page.get_by_text("已选择导入的视频。请在创建分析中核对配置，再开始分析。", exact=True)).to_be_visible()
            expect(page.get_by_text("请等待当前操作完成，再新建或切换分析。", exact=True)).to_have_count(0)
            page.wait_for_function("""() => [...document.querySelectorAll('video')].some(v =>
                v.readyState >= 2 && v.videoWidth > 0 && Number.isFinite(v.duration) && v.duration > 0)""", timeout=30000)
            player = page.locator("video").first
            evidence["player"] = player.evaluate("v => ({src:v.currentSrc,duration:v.duration,width:v.videoWidth,height:v.videoHeight,readyState:v.readyState,error:v.error?.code ?? null})")
            assert f"/api/videos/{video['id']}/content" in evidence["player"]["src"]
            player.evaluate("async v => {v.muted=true; await v.play()}")
            page.wait_for_function("() => document.querySelector('video').currentTime > 0.3", timeout=10000)
            evidence["playback_seconds"] = player.evaluate("v => {v.pause(); return v.currentTime}")
            assert video["content_url"] == f"/api/videos/{video['id']}/content"
            media = page.request.get(urljoin(args.base_url, video["content_url"]), timeout=60000)
            assert media.ok
            content = media.body()
            assert len(content) == video["size_bytes"]
            assert hashlib.sha256(content).hexdigest() == video["content_hash"]
            evidence["video"] = video
            evidence["served_media_sha256"] = hashlib.sha256(content).hexdigest()
            page.locator(".upload-host").evaluate("element => element.scrollTop = 0")
            page.screenshot(path=str(args.output / "02-selected-playing.png"), full_page=True)
            assert evidence["analysis_posts"] == 0
            assert not evidence["page_errors"], evidence["page_errors"]
            evidence["passed"] = True
            print(json.dumps({"passed": True, "import_id": created["id"], "video_id": video["id"],
                              "bytes": len(content), "duration_seconds": evidence["player"]["duration"]}), flush=True)
        except Exception as error:
            evidence["passed"] = False
            evidence["failure"] = str(error)[:2000]
            page.screenshot(path=str(args.output / "failure.png"), full_page=True)
            raise
        finally:
            save()
            browser.close()


if __name__ == "__main__":
    main()
