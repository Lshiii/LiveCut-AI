"""Anonymous public-video compatibility adapter; no user cookies or JS execution.

Protocol reference: yt-dlp/yt-dlp PR #17826, viking1304, public-domain contribution,
head 728fe46fbaa97d6f03efcaf9e766d4db63338b8d (reviewed 2026-11-15).
https://github.com/yt-dlp/yt-dlp/pull/17826

Only a locally generated CSRF nonce and actual site-issued visitor values are
used. This is a small adapter for the locked release, not execution of a fork.
"""

from __future__ import annotations

import hashlib
import json
import re
import time
from urllib.parse import urlencode, urlsplit
from uuid import uuid4

from .source_import_errors import ImportDownloadError

HOME = "https://www.douyin.com/"
AID = 6383
SEC_SALT = "A96D855A08C0A9707F8BEF0D9A527E4E"
UIFID_NAMES = ("uifid", "uifid_temp", "uifidtemp", "UIFID", "UIFID_TEMP", "UIFIDTEMP")


def signed_public_query(video_id: str, visitor: str, timestamp: int) -> dict:
    """Deterministic public request checksum; no account credential is involved."""
    if (not isinstance(video_id, str) or not re.fullmatch(r"[0-9]{15,22}", video_id)
            or not isinstance(visitor, str) or not re.fullmatch(r"[\x21-\x7e]{1,2048}", visitor)
            or type(timestamp) is not int or timestamp <= 0):
        raise ImportDownloadError("SOURCE_IMPORT_AUTH_REQUIRED")
    query = {"aweme_id": video_id, "aid": AID, "uifid": visitor, "timestamp": str(timestamp)}
    checksum = hashlib.md5(f"{visitor}_{timestamp}_{SEC_SALT}_{urlencode(query)}".encode()).hexdigest()
    return {**query, "x-secsdk-web-signature": checksum}


def _read_site(ydl, request, maximum):
    with ydl.urlopen(request) as response:
        parsed = urlsplit(response.url)
        if (parsed.scheme != "https" or parsed.hostname != "www.douyin.com"
                or parsed.port not in (None, 443) or parsed.username or parsed.password):
            raise ImportDownloadError("SOURCE_IMPORT_UNSUPPORTED_SOURCE")
        content = response.read(maximum + 1)
        if len(content) > maximum:
            raise ImportDownloadError("SOURCE_IMPORT_AUTH_REQUIRED")
        return content, response.headers


def _public_detail(value, video_id):
    if not isinstance(value, dict) or value.get("status_code", 0) != 0:
        raise ImportDownloadError("SOURCE_IMPORT_AUTH_REQUIRED")
    detail = value.get("aweme_detail")
    if not isinstance(detail, dict) or str(detail.get("aweme_id")) != video_id:
        raise ImportDownloadError("SOURCE_IMPORT_UNSUPPORTED_SOURCE")
    status, author = detail.get("status") or {}, detail.get("author") or {}
    if not isinstance(status, dict) or not isinstance(author, dict):
        raise ImportDownloadError("SOURCE_IMPORT_UNSUPPORTED_SOURCE")
    if (detail.get("is_private") or status.get("is_private") or status.get("is_prohibited")
            or status.get("allow_share") is False or author.get("secret")
            or detail.get("is_live") or detail.get("live_info")
            or detail.get("room_id") not in (None, 0, "0", "")
            or detail.get("images") or detail.get("image_post_info")
            or not isinstance(detail.get("video"), dict) or not detail["video"]):
        raise ImportDownloadError("SOURCE_IMPORT_UNSUPPORTED_SOURCE")
    return detail


def anonymous_metadata(ydl, extractor, video_id, request_type):
    """At most one guest registration, homepage read and signed metadata query.

    All I/O uses the caller's Urllib-only request director in the isolated child,
    so the existing DNS/connect guard, socket timeout and parent lease remain in
    force. No remote scripts, verification challenges or browser profiles run.
    """
    cookies = extractor._get_cookies(HOME)
    if not cookies.get("ttwid"):
        _read_site(ydl, request_type(HOME + "ttwid/register", data=json.dumps({
            "aid": AID, "service": "www.douyin.com"}).encode(), headers={
                "Content-Type": "application/json", "Referer": HOME}), 64 * 1024)
        cookies = extractor._get_cookies(HOME)
        if not cookies.get("ttwid"):
            raise ImportDownloadError("SOURCE_IMPORT_AUTH_REQUIRED")
    csrf = uuid4().hex
    extractor._set_cookie(".douyin.com", "passport_csrf_token", csrf)
    visitor = next((cookies[name].value for name in UIFID_NAMES if cookies.get(name)), None)
    if visitor is None:
        _, headers = _read_site(ydl, request_type(HOME, headers={"Referer": HOME}), 512 * 1024)
        cookies = extractor._get_cookies(HOME)
        visitor = next((cookies[name].value for name in UIFID_NAMES if cookies.get(name)), None)
        if visitor is None:
            visitor = headers.get("x-tt-trace-host")
    query = signed_public_query(video_id, visitor, int(time.time()))
    body, _ = _read_site(ydl, request_type(HOME + "aweme/v1/web/aweme/detail/?" + urlencode(query),
                                        headers={"Referer": HOME}), 2 * 1024 * 1024)
    try:
        raw = json.loads(body)
    except (ValueError, UnicodeError):
        raise ImportDownloadError("SOURCE_IMPORT_AUTH_REQUIRED") from None
    detail = _public_detail(raw, video_id)
    # The locked parser copies sid_tt to media-CDN domains. This anonymous route
    # has no need for an account session; discard any such cookie before parsing.
    for cookie in list(ydl.cookiejar):
        if cookie.name == "sid_tt":
            ydl.cookiejar.clear(cookie.domain, cookie.path, cookie.name)
    result = extractor._parse_aweme_video_app(detail)
    result.update(extractor="douyin", extractor_key="Douyin", webpage_url=HOME + "video/" + video_id,
                  original_url=HOME + "video/" + video_id, http_headers={"Referer": HOME})
    return ydl.process_ie_result(result, download=False)
