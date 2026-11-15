import http.cookiejar
import io
import json
import subprocess
import sys
from types import SimpleNamespace
from urllib.parse import parse_qs, urlsplit

import pytest
from livecut import source_import_douyin as adapter
from livecut.source_import_errors import ImportDownloadError

VIDEO_ID = "6961737553342991651"


def cookie(name, value, domain=".douyin.com"):
    return http.cookiejar.Cookie(0, name, value, None, False, domain, True, domain.startswith("."),
        "/", True, True, None, True, None, None, {}, False)


class Response(io.BytesIO):
    def __init__(self, body=b"", headers=None, url=adapter.HOME):
        super().__init__(body)
        self.headers, self.url = headers or {}, url


class FakeExtractor:
    def __init__(self, ydl):
        self.ydl = ydl

    def _get_cookies(self, url):
        return {c.name: c for c in self.ydl.cookiejar}

    def _set_cookie(self, domain, name, value):
        self.ydl.cookiejar.set_cookie(cookie(name, value, domain))

    def _parse_aweme_video_app(self, detail):
        assert not any(c.name == "sid_tt" for c in self.ydl.cookiejar)
        return {"id": detail["aweme_id"], "formats": []}


class FakeYdl:
    def __init__(self, *, visitor="fixture-visitor", response=None):
        self.cookiejar, self.requests, self.visitor = http.cookiejar.CookieJar(), [], visitor
        self.response = response or {"status_code": 0, "aweme_detail": {
            "aweme_id": VIDEO_ID, "video": {"duration": 19000}}}

    def urlopen(self, request):
        parsed = urlsplit(request.url)
        self.requests.append(request)
        if parsed.path == "/ttwid/register":
            assert json.loads(request.data) == {"aid": 6383, "service": "www.douyin.com"}
            self.cookiejar.set_cookie(cookie("ttwid", "actual-fixture-server-visitor"))
            return Response(b"{}")
        if parsed.path == "/":
            return Response(b"ordinary public page", {"x-tt-trace-host": self.visitor} if self.visitor else {})
        assert parsed.path == "/aweme/v1/web/aweme/detail/"
        query = parse_qs(parsed.query)
        assert query["aweme_id"] == [VIDEO_ID]
        assert len(query["x-secsdk-web-signature"][0]) == 32
        body = self.response if isinstance(self.response, bytes) else json.dumps(self.response).encode()
        return Response(body)

    def process_ie_result(self, result, **kwargs):
        assert kwargs == {"download": False}
        return result


def request_type(url, **kwargs):
    return SimpleNamespace(url=url, **kwargs)


def test_public_checksum_matches_pinned_upstream_wire_example():
    result = adapter.signed_public_query(VIDEO_ID, "fixture-visitor", 1700000000)
    assert result == {"aweme_id": VIDEO_ID, "aid": 6383, "uifid": "fixture-visitor",
        "timestamp": "1700000000", "x-secsdk-web-signature": "06c606e261c2aec3189e5e0d1ddca46a"}


@pytest.mark.parametrize("visitor", [None, "", "x\r\ny", "a" * 2049])
def test_missing_or_invalid_visitor_is_never_invented(visitor):
    with pytest.raises(ImportDownloadError, match="AUTH_REQUIRED"):
        adapter.signed_public_query(VIDEO_ID, visitor, 1700000000)


def test_anonymous_protocol_has_one_metadata_request_and_no_cookie_file_access(monkeypatch):
    ydl = FakeYdl()
    ydl.cookiejar.set_cookie(cookie("sid_tt", "must-not-leave-parser"))
    monkeypatch.setattr("builtins.open", lambda *a, **k: pytest.fail("must not read cookies/files"))
    result = adapter.anonymous_metadata(ydl, FakeExtractor(ydl), VIDEO_ID, request_type)
    assert result["id"] == VIDEO_ID
    assert result["http_headers"] == {"Referer": "https://www.douyin.com/"}
    assert [urlsplit(r.url).path for r in ydl.requests] == ["/ttwid/register", "/", "/aweme/v1/web/aweme/detail/"]
    assert not any(c.name == "sid_tt" for c in ydl.cookiejar)


def test_no_actual_visitor_stops_before_signed_metadata_request():
    ydl = FakeYdl(visitor=None)
    with pytest.raises(ImportDownloadError, match="AUTH_REQUIRED"):
        adapter.anonymous_metadata(ydl, FakeExtractor(ydl), VIDEO_ID, request_type)
    assert len(ydl.requests) == 2


@pytest.mark.parametrize("body,code", [
    (b"<html>Please complete captcha</html>", "AUTH_REQUIRED"),
    ({"status_code": 1}, "AUTH_REQUIRED"),
    ({"aweme_detail": {"aweme_id": "6963263655114722595", "video": {"duration": 15000}}}, "UNSUPPORTED_SOURCE"),
    ({"aweme_detail": {"aweme_id": VIDEO_ID, "video": {"duration": 19000}, "status": {"is_private": True}}}, "UNSUPPORTED_SOURCE"),
    ({"aweme_detail": {"aweme_id": VIDEO_ID, "video": {"duration": 19000}, "is_live": True}}, "UNSUPPORTED_SOURCE"),
])
def test_no_wrong_video_private_or_challenge_content_is_published(body, code):
    ydl = FakeYdl(response=body)
    with pytest.raises(ImportDownloadError, match=code):
        adapter.anonymous_metadata(ydl, FakeExtractor(ydl), VIDEO_ID, request_type)
    assert sum(urlsplit(r.url).path == "/aweme/v1/web/aweme/detail/" for r in ydl.requests) == 1


def test_non_https_site_response_is_rejected():
    class Ydl:
        def urlopen(self, request):
            return Response(url="http://www.douyin.com/")
    with pytest.raises(ImportDownloadError, match="UNSUPPORTED_SOURCE"):
        adapter._read_site(Ydl(), request_type(adapter.HOME), 1024)


def test_real_isolated_module_classifies_input_fault_without_network(tmp_path):
    payload = {"url": "https://www.douyin.com/video/123", "directory": str(tmp_path),
               "max_bytes": 1024, "metadata_only": True}
    result = subprocess.run([sys.executable, "-I", "-m", "livecut.source_import_download"],
        input=json.dumps(payload), capture_output=True, text=True, timeout=10)
    assert result.returncode == 1
    assert json.loads(result.stdout) == {"ok": False, "error_code": "SOURCE_IMPORT_INVALID_URL"}


def test_main_namespace_keeps_helper_error_identity_in_isolated_child(tmp_path):
    # runpy uses the same __main__ identity as -m; this reproduces the former
    # helper/canonical-module split without contacting any platform.
    code = r'''
import io,json,runpy,sys
import yt_dlp
from livecut import source_import_douyin as adapter
from livecut.source_import_errors import ImportDownloadError
def fail(*args): raise ImportDownloadError("SOURCE_IMPORT_UNSUPPORTED_SOURCE")
adapter.anonymous_metadata=fail
class Ydl:
 def __init__(self,*args,**kwargs):pass
 def __enter__(self):return self
 def __exit__(self,*args):pass
 def add_info_extractor(self,*args):pass
 def build_request_director(self,*args):return None
 def extract_info(self,*args,**kwargs):raise RuntimeError("Fresh cookies (not necessarily logged in) are needed")
yt_dlp.YoutubeDL=Ydl
sys.stdin=io.TextIOWrapper(io.BytesIO(sys.argv[1].encode()))
runpy.run_module("livecut.source_import_download",run_name="__main__")
'''
    payload = {"url": "https://www.douyin.com/video/" + VIDEO_ID, "directory": str(tmp_path),
               "max_bytes": 1024, "metadata_only": True}
    result = subprocess.run([sys.executable, "-I", "-c", code, json.dumps(payload)],
        capture_output=True, text=True, timeout=10)
    assert result.returncode == 1
    assert json.loads(result.stdout) == {"ok": False, "error_code": "SOURCE_IMPORT_UNSUPPORTED_SOURCE"}
