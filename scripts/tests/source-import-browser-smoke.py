"""No-listener browser smoke: production UI, explicitly synthetic HTTP receipts.

Run after npm --prefix frontend run build:
uv run --with playwright python scripts/tests/source-import-browser-smoke.py
Uses installed Edge; never starts Java, downloads a video, or calls a model.
"""
import json
import mimetypes
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright

ROOT = Path(__file__).resolve().parents[2]
DIST = ROOT / "frontend" / "dist"
OUT = ROOT / "data" / "source-import-browser-smoke"
OUT.mkdir(parents=True, exist_ok=True)
ID = "10000000-0000-4000-8000-000000000001"
VIDEO = "10000000-0000-4000-8000-000000000002"
LINK = "https://www.youtube.com/watch?v=BaW_jenozKc"
STAMP = "2026-11-15T00:00:00Z"


def main():
    state = {"task": None, "posts": 0, "lost": True, "key": None}
    errors = []
    video = {"id": VIDEO, "original_filename": "synthetic-link-import.mp4",
             "content_hash": "a" * 64, "size_bytes": 1234,
             "content_url": f"/api/videos/{VIDEO}/content",
             "media_validation_status": "HEADER_CHECKED", "created_at": STAMP}

    def route(request):
        path = urlsplit(request.request.url).path
        method = request.request.method
        def reply(value, code=200):
            request.fulfill(status=code, content_type="application/json", body=json.dumps(value))
        if path == "/api/source-imports" and method == "POST":
            state["posts"] += 1
            state["key"] = request.request.headers["idempotency-key"]
            assert request.request.post_data_json == {"url": LINK}
            state["task"] = {"id": ID, "url": LINK, "status": "QUEUED", "phase": "QUEUED",
                "progress_percent": None, "downloaded_bytes": 0, "video_id": None,
                "error_code": None, "attempt": 0, "created_at": STAMP, "updated_at": STAMP}
            if state["lost"]:
                state["lost"] = False
                request.abort("failed")
            else:
                reply(state["task"])
        elif path == "/api/source-imports":
            reply({"items": [state["task"]] if state["task"] else []})
        elif path.startswith("/api/source-imports/by-key/"):
            assert path.endswith(state["key"])
            reply(state["task"])
        elif path == f"/api/videos/{VIDEO}":
            reply(video)
        elif path in ("/api/videos", "/api/jobs"):
            reply({"items": [video] if path.endswith("videos") and state["task"] and state["task"]["status"] == "COMPLETED" else [], "limit": 100, "offset": 0})
        elif path == "/actuator/health":
            reply({"status": "UP"})
        elif path.startswith("/api/"):
            reply({"items": []})
        else:
            file = (DIST / (path.lstrip("/") or "index.html")).resolve()
            assert file.is_relative_to(DIST.resolve())
            if not file.is_file():
                request.fulfill(status=404, body="missing")
                return
            content_type = "application/javascript" if file.suffix == ".js" else mimetypes.guess_type(file.name)[0] or "application/octet-stream"
            request.fulfill(status=200, content_type=content_type, body=file.read_bytes())

    with sync_playwright() as p:
        browser = p.chromium.launch(channel="msedge", headless=True)
        page = browser.new_page(viewport={"width": 1280, "height": 900})
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.route("**/*", route)
        page.goto("https://livecut.invalid/")
        page.get_by_role("button", name="从 YouTube / Bilibili 链接导入").click()
        page.get_by_label("视频链接", exact=True).fill(LINK)
        page.get_by_role("button", name="导入链接", exact=True).click()
        expect(page.get_by_role("button", name="核对原提交", exact=True)).to_be_enabled()
        page.get_by_role("button", name="核对原提交", exact=True).click()
        expect(page.get_by_role("button", name="取消导入", exact=True)).to_be_visible()
        assert state["posts"] == 1, "Lost receipt must not create a second task"
        state["task"].update(status="COMPLETED", phase="COMPLETED", attempt=1,
                            video_id=VIDEO, progress_percent=100, updated_at="2026-11-15T00:01:00Z")
        page.get_by_role("button", name="刷新进度", exact=True).click()
        expect(page.get_by_role("button", name="使用此视频", exact=True)).to_be_enabled()
        page.get_by_role("button", name="使用此视频", exact=True).click()
        expect(page.get_by_text("已选择导入的视频。请在创建分析中核对配置，再开始分析。", exact=True)).to_be_visible()
        expect(page.get_by_text("请等待当前操作完成，再新建或切换分析。", exact=True)).to_have_count(0)
        page.locator('.upload-host').evaluate("element => element.scrollTop = 0")
        page.screenshot(path=str(OUT / "desktop.png"), full_page=True)
        page.set_viewport_size({"width": 375, "height": 812})
        page.get_by_label("视频链接", exact=True).scroll_into_view_if_needed()
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "Horizontal overflow"
        page.screenshot(path=str(OUT / "mobile.png"), full_page=True)
        assert not errors, errors
        browser.close()
    print("Source import browser smoke passed: lost receipt recovered without POST replay; explicit video selection; 1280px/375px layout; no page errors. HTTP data is synthetic.")


if __name__ == "__main__":
    main()
