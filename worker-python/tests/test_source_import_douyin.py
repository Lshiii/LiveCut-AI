from __future__ import annotations

import io
import json
import urllib.error
import urllib.request
from types import SimpleNamespace

import pytest
from livecut import source_import_download as dl

VIDEO_ID = "6961737553342991651"
VIDEO = "https://www.douyin.com/video/" + VIDEO_ID
SHORT = "https://v.douyin.com/uD7JHhJ-0oo/"


@pytest.mark.parametrize("url", [VIDEO, VIDEO + "/", SHORT, SHORT.rstrip("/")])
def test_douyin_canonical_claim_urls(url):
    assert dl.validate_url(url) == "Douyin"


@pytest.mark.parametrize("url", [
    "http://www.douyin.com/video/" + VIDEO_ID,
    "https://www.douyin.com/video/123",
    "https://www.douyin.com/video/" + "1" * 23,
    "https://www.douyin.com/user/" + VIDEO_ID,
    "https://live.douyin.com/" + VIDEO_ID,
    "https://www.douyin.com/note/" + VIDEO_ID,
    VIDEO + "?modal_id=" + VIDEO_ID,
    VIDEO + "?tracking=x",
    "https://www.v.douyin.com/code/",
    "https://v.douyin.com/",
    "https://v.douyin.com/" + "a" * 65 + "/",
    "https://v.douyin.com/a/b/",
    "https://v.douyin.com@127.0.0.1/abc/",
])
def test_douyin_claim_rejects_noncanonical_or_nonvideo(url):
    with pytest.raises(dl.ImportDownloadError, match="INVALID_URL"):
        dl.validate_url(url)


@pytest.mark.parametrize("url", [
    VIDEO + "?enter_from=share&timestamp=1",
    "https://douyin.com/video/" + VIDEO_ID,
    "https://m.douyin.com/video/" + VIDEO_ID,
    "https://www.iesdouyin.com/share/video/" + VIDEO_ID + "/?region=CN",
])
def test_douyin_only_official_single_video_landings(url):
    assert dl._douyin_redirect_target(url) == (VIDEO, VIDEO_ID)


@pytest.mark.parametrize("url", [
    "https://example.org/video/" + VIDEO_ID,
    "https://127.0.0.1/video/" + VIDEO_ID,
    "http://www.douyin.com/video/" + VIDEO_ID,
    "https://www.douyin.com/user/" + VIDEO_ID,
    "https://www.iesdouyin.com/share/user/" + VIDEO_ID,
    VIDEO + "?aweme_id=" + VIDEO_ID,
    VIDEO + "?item_ids=" + VIDEO_ID,
    VIDEO + "?list=x",
    VIDEO + "#x",
])
def test_douyin_redirect_cannot_expand_source_scope(url):
    with pytest.raises(dl.ImportDownloadError, match="UNSUPPORTED_SOURCE"):
        dl._douyin_redirect_target(url)


def test_short_link_resolves_only_to_video_and_keeps_original_input():
    class Response:
        url = "https://www.iesdouyin.com/share/video/" + VIDEO_ID + "/?region=CN"
        def __enter__(self):
            return self
        def __exit__(self, *_):
            pass
    seen = []
    class Opener:
        def open(self, request, timeout):
            seen.append((request.full_url, timeout))
            return Response()
    assert dl._resolve_douyin_short(SHORT, opener=Opener()) == VIDEO
    assert seen == [(SHORT, 8)]


def test_douyin_redirect_count_and_single_identity_are_bounded():
    redirects = dl._DouyinRedirects()
    request = urllib.request.Request(SHORT)
    for _ in range(5):
        redirects.redirect_request(request, None, 302, "Found", {}, VIDEO)
    with pytest.raises(dl.ImportDownloadError, match="REDIRECT_LIMIT"):
        redirects.redirect_request(request, None, 302, "Found", {}, VIDEO)
    other = dl._DouyinRedirects()
    other.observe("https://www.iesdouyin.com/share/video/" + VIDEO_ID + "/")
    with pytest.raises(dl.ImportDownloadError, match="UNSUPPORTED_SOURCE"):
        other.observe("https://www.douyin.com/video/6963263655114722595")


def test_short_link_rejects_nonofficial_redirect_before_request():
    redirects = dl._DouyinRedirects()
    with pytest.raises(dl.ImportDownloadError, match="UNSUPPORTED_SOURCE"):
        redirects.redirect_request(urllib.request.Request(SHORT), None, 302, "Found", {},
                                   "https://127.0.0.1/metadata")


def page(item=None, *, status=0):
    if item is None:
        item = {"aweme_id": VIDEO_ID, "video": {"duration": 19000}, "desc": "synthetic SSR fixture"}
    data = {"loaderData": {"video_(id)/page": {"videoInfoRes": {
        "status_code": status, "item_list": [item]}}}}
    return "<html><script>window._ROUTER_DATA = " + json.dumps(data) + ";</script></html>"


def test_public_ssr_only_reads_actual_same_video_json():
    item = dl._public_douyin_detail(page(), VIDEO_ID)
    assert item["aweme_id"] == VIDEO_ID
    assert item["video"]["duration"] == 19000


@pytest.mark.parametrize("html", [
    "<html>Login required<script>solveCaptcha()</script></html>",
    "<script>window._ROUTER_DATA={};</script>",
    page({"aweme_id": "6963263655114722595", "video": {"duration": 15000}}),
    page({"aweme_id": VIDEO_ID, "video": {}, "images": [{"url": "x"}]}),
    page({"aweme_id": VIDEO_ID, "video": {}, "is_live": True}),
    page(status=1),
    page() + page(),
    page().replace(";</script>", ";run_challenge();</script>"),
    page().replace("<script>", '<script src="https://example.org/script">'),
])
def test_public_ssr_missing_challenge_conflicting_or_nonvideo_is_not_fabricated(html):
    with pytest.raises(dl.ImportDownloadError, match="AUTH_REQUIRED"):
        dl._public_douyin_detail(html, VIDEO_ID)


def test_urllib_network_denial_reason_has_fixed_public_code():
    error = urllib.error.URLError(OSError("SOURCE_IMPORT_NETWORK_DENIED"))
    assert dl._failure_code(error) == "SOURCE_IMPORT_NETWORK_DENIED"


def test_anonymous_guest_cookies_remain_in_memory_and_retry_is_finite():
    calls = []
    class Response(io.BytesIO):
        url = "https://www.douyin.com/"
    class Ydl:
        cookiejar = []
        def extract_info(self, url, **kwargs):
            calls.append(("metadata", url))
            if sum(k == "metadata" for k, _ in calls) == 1:
                raise RuntimeError("Fresh cookies (not necessarily logged in) are needed")
            return {"id": VIDEO_ID, "extractor_key": "Douyin"}
        def urlopen(self, request):
            calls.append(("guest", request.url))
            self.cookiejar = [SimpleNamespace(domain=".douyin.com", name="guest", value="fixture")]
            return Response(b"ordinary public home page")
    def request_type(url, **kwargs):
        return SimpleNamespace(url=url, **kwargs)
    assert dl._extract_douyin(Ydl(), None, VIDEO, request_type)["id"] == VIDEO_ID
    assert [kind for kind, _ in calls] == ["metadata", "guest", "metadata"]


def test_public_html_without_metadata_returns_auth_error_without_solving_challenge():
    calls = []
    class Response(io.BytesIO):
        url = VIDEO
    class Ydl:
        cookiejar = []
        def extract_info(self, url, **kwargs):
            calls.append("metadata")
            raise RuntimeError("Fresh cookies (not necessarily logged in) are needed")
        def urlopen(self, request):
            calls.append(request.url)
            return Response(b"<script>challenge()</script>")
    with pytest.raises(dl.ImportDownloadError, match="AUTH_REQUIRED"):
        dl._extract_douyin(Ydl(), None, VIDEO, lambda url, **kwargs: SimpleNamespace(url=url, **kwargs))
    assert calls == ["metadata", "https://www.douyin.com/",
                     "https://www.iesdouyin.com/share/video/" + VIDEO_ID + "/"]


def test_public_ssr_parser_is_used_only_after_same_source_binding():
    captured = []
    class Response(io.BytesIO):
        url = VIDEO
    class Extractor:
        def _parse_aweme_video_app(self, detail):
            captured.append(detail)
            return {"id": detail["aweme_id"], "formats": []}
    class Ydl:
        cookiejar = []
        def extract_info(self, url, **kwargs):
            raise RuntimeError("Fresh cookies (not necessarily logged in) are needed")
        def urlopen(self, request):
            return Response(page().encode() if "iesdouyin" in request.url else b"public guest page")
        def process_ie_result(self, result, **kwargs):
            assert kwargs == {"download": False}
            return result
    result = dl._extract_douyin(Ydl(), Extractor(), VIDEO,
        lambda url, **kwargs: SimpleNamespace(url=url, **kwargs))
    assert result["id"] == VIDEO_ID
    assert result["extractor_key"] == "Douyin"
    assert captured[0]["aweme_id"] == VIDEO_ID


def test_http_douyin_media_is_not_upgraded_or_downloaded():
    info = {"formats": [{"protocol": "http", "url": "http://example.org/video.mp4",
                         "vcodec": "h264", "acodec": "aac", "height": 720}]}
    with pytest.raises(dl.ImportDownloadError, match="NO_HTTPS_AUDIO_VIDEO"):
        dl._streams(info, 1024 * 1024)
    assert info["formats"][0]["url"] == "http://example.org/video.mp4"
