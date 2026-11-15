"""Isolated, public-network-only metadata and bounded native HTTPS downloads.

Only this child process patches sockets. No curl, FFmpeg, JS runtime, plugins,
browser cookies, playlist extractor or external downloader can bypass the guard.
"""

from __future__ import annotations

import ipaddress
import json
import os
import re
import socket
import sys
import time
import urllib.request
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from .source_import_errors import ImportDownloadError

_WINDOWS = os.name == "nt"


def validate_url(url: str) -> str:
    try:
        value = urlsplit(url)
        query = parse_qs(value.query, keep_blank_values=True, strict_parsing=True)
        if (value.scheme != "https" or value.username or value.password
                or value.port not in (None, 443) or value.fragment or len(url) > 2048):
            raise ValueError
        if value.hostname == "www.youtube.com" and value.path == "/watch":
            if set(query) != {"v"} or len(query["v"]) != 1 or not re.fullmatch(
                    r"[A-Za-z0-9_-]{11}", query["v"][0]):
                raise ValueError
            return "Youtube"
        if value.hostname == "www.bilibili.com" and re.fullmatch(
                r"/video/(?:BV[A-Za-z0-9]{10}|av[1-9][0-9]*)/?", value.path):
            if query and query != {"p": ["1"]}:
                raise ValueError
            return "BiliBili"
        if (not query and value.hostname == "www.douyin.com"
                and re.fullmatch(r"/video/[0-9]{15,22}/?", value.path)):
            return "Douyin"
        if (not query and value.hostname == "v.douyin.com"
                and re.fullmatch(r"/[A-Za-z0-9_-]{1,64}/?", value.path)):
            return "Douyin"
    except (ValueError, TypeError, AttributeError):
        pass
    raise ImportDownloadError("SOURCE_IMPORT_INVALID_URL")


def _public_ip(value: str) -> bool:
    try:
        address = ipaddress.ip_address(value.split("%", 1)[0])
        if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped:
            address = address.ipv4_mapped
        return address.is_global and not address.is_multicast
    except (ValueError, AttributeError):
        return False


def install_network_guard() -> None:
    """Filter every DNS result and check connect destinations, including redirects.

    Connecting the validated numeric address prevents a second DNS lookup from
    rebinding a public hostname to an internal address. TLS still uses the
    original hostname for SNI/certificate verification in the HTTP layer.
    """
    original_resolve = socket.getaddrinfo
    original_connect = socket.socket.connect
    original_connect_ex = socket.socket.connect_ex

    def resolve(host, port, *args, **kwargs):
        if not isinstance(host, str) or port not in (443, "443"):
            raise OSError("SOURCE_IMPORT_NETWORK_DENIED")
        results = [item for item in original_resolve(host, port, *args, **kwargs)
                   if _public_ip(item[4][0])]
        if not results:
            raise OSError("SOURCE_IMPORT_NETWORK_DENIED")
        return results

    def target(sock, address):
        if sock.family not in (socket.AF_INET, socket.AF_INET6) or not isinstance(address, tuple):
            raise OSError("SOURCE_IMPORT_NETWORK_DENIED")
        host, port = address[:2]
        if port != 443:
            raise OSError("SOURCE_IMPORT_NETWORK_DENIED")
        try:
            ipaddress.ip_address(host.split("%", 1)[0])
        except (ValueError, AttributeError):
            rows = resolve(host, port, sock.family, socket.SOCK_STREAM)
            return rows[0][4]
        if not _public_ip(host):
            raise OSError("SOURCE_IMPORT_NETWORK_DENIED")
        return address

    def connect(sock, address):
        return original_connect(sock, target(sock, address))

    def connect_ex(sock, address):
        return original_connect_ex(sock, target(sock, address))

    socket.getaddrinfo = resolve
    socket.socket.connect = connect
    socket.socket.connect_ex = connect_ex


def _no_external_execution(event, args):
    if event in {"subprocess.Popen", "os.system", "os.exec", "os.posix_spawn", "os.spawn"}:
        # yt-dlp probes optional ffmpeg/OS helpers even in metadata mode. An
        # unavailable executable is its normal supported no-helper condition.
        raise FileNotFoundError("SOURCE_IMPORT_EXTERNAL_PROCESS_DENIED")


class _SilentLogger:
    def debug(self, *_):
        pass

    info = warning = error = debug


def _https(value):
    parsed = urlsplit(value) if isinstance(value, str) else None
    if (parsed is None or parsed.scheme != "https" or not parsed.hostname
            or parsed.username or parsed.password or parsed.port not in (None, 443)):
        raise ImportDownloadError("SOURCE_IMPORT_NETWORK_DENIED")
    return value


def _streams(info: dict, max_bytes: int | None = None) -> list[dict]:
    if (not isinstance(info, dict) or info.get("_type", "video") != "video"
            or "entries" in info or info.get("is_live") or info.get("was_live")
            or info.get("live_status") not in (None, "not_live")):
        raise ImportDownloadError("SOURCE_IMPORT_UNSUPPORTED_SOURCE")
    if info.get("availability") not in (None, "public", "unlisted"):
        raise ImportDownloadError("SOURCE_IMPORT_AUTH_REQUIRED")
    formats, blocked = [], False
    for item in info.get("formats", []):
        if not isinstance(item, dict) or item.get("protocol") != "https" or item.get("has_drm"):
            continue
        try:
            _https(item.get("url"))
            formats.append(item)
        except ImportDownloadError:
            blocked = True
    if not formats and blocked:
        raise ImportDownloadError("SOURCE_IMPORT_NETWORK_DENIED")
    def video(row):
        return row.get("vcodec") not in (None, "none")
    def audio(row):
        return row.get("acodec") not in (None, "none")
    def rank(row):
        height = row.get("height") or 0
        return (height <= 720, min(height, 720), row.get("tbr") or 0)
    def estimated(row):
        value = row.get("filesize") or row.get("filesize_approx")
        if isinstance(value, (int, float)) and value > 0:
            return value
        bitrate, duration = row.get("tbr"), info.get("duration")
        return bitrate * duration * 1000 / 8 if bitrate and duration else 0
    def fits(rows):
        return max_bytes is None or sum(estimated(row) for row in rows) <= max_bytes
    combined = [item for item in formats if video(item) and audio(item)]
    bounded = [item for item in combined if fits([item])]
    if bounded:
        return [max(bounded, key=rank)]
    videos = [item for item in formats if video(item) and not audio(item)]
    audios = [item for item in formats if audio(item) and not video(item)]
    if not videos or not audios:
        raise ImportDownloadError("SOURCE_IMPORT_TOO_LARGE" if combined else "SOURCE_IMPORT_NO_HTTPS_AUDIO_VIDEO")
    audio_row = max(audios, key=lambda r: r.get("abr") or r.get("tbr") or 0)
    videos = [row for row in videos if fits([row, audio_row])]
    if not videos:
        raise ImportDownloadError("SOURCE_IMPORT_TOO_LARGE")
    return [max(videos, key=rank), audio_row]


def _atomic(path: Path, value) -> None:
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=True), encoding="utf-8")
    # Windows readers/antivirus may briefly hold an otherwise valid destination.
    # Retry only those local sharing/access errors, never hide a permanent write
    # failure or retry a platform request. Total extra waiting is at most 375ms.
    for delay in (0.025, 0.05, 0.1, 0.2, None):
        try:
            os.replace(temporary, path)
            return
        except PermissionError as error:
            transient = (_WINDOWS and (getattr(error, "winerror", None) in {5, 32, 33}
                         or getattr(error, "winerror", None) is None and error.errno == 13))
            if not transient or delay is None:
                raise
            time.sleep(delay)


class _DownloadProgress:
    def __init__(self, path, *, interval=0.25, clock=time.monotonic):
        self.path, self.interval, self.clock, self.last = path, interval, clock, None

    def update(self, total, *, final=False):
        now = self.clock()
        if final or self.last is None or now - self.last >= self.interval:
            _atomic(self.path, {"downloaded_bytes": total})
            self.last = now


def _bili_https_play_info(play_info):
    """Select an actual supplied HTTPS:443 CDN URL before yt-dlp drops backups.

    The locked Bili extractor keeps only baseUrl/base_url. No hostname, port,
    query or signature is rewritten, and there is no extra platform request.
    """
    if not isinstance(play_info, dict):
        return play_info
    result = dict(play_info)
    def rows(items):
        if not isinstance(items, list):
            return items
        converted = []
        for item in items:
            if not isinstance(item, dict):
                converted.append(item)
                continue
            value = dict(item)
            candidates = [item.get(key) for key in ("baseUrl", "base_url", "url")]
            for key in ("backupUrl", "backup_url"):
                if isinstance(item.get(key), list):
                    candidates.extend(item[key])
            for candidate in candidates:
                try:
                    selected = _https(candidate)
                except (ImportDownloadError, ValueError):
                    continue
                for key in ("baseUrl", "base_url", "url"):
                    if key in item:
                        value[key] = selected
                break
            converted.append(value)
        return converted
    if isinstance(play_info.get("dash"), dict):
        result["dash"] = dict(play_info["dash"])
        for key in ("audio", "video"):
            if key in play_info["dash"]:
                result["dash"][key] = rows(play_info["dash"][key])
    if "durl" in play_info:
        result["durl"] = rows(play_info["durl"])
    return result


def _bili_https_extractor(base_type):
    class HttpsBiliExtractor(base_type):
        @classmethod
        def ie_key(cls):
            return "BiliBili"

        def extract_formats(self, play_info):
            return super().extract_formats(_bili_https_play_info(play_info))
    return HttpsBiliExtractor()


def _failure_code(error: BaseException) -> str:
    """Preserve only fixed guard codes through yt-dlp's exception wrappers.

    Exception messages, URLs and headers are never published. A remote error
    mentioning the code does not qualify: it must be our literal OSError or an
    ImportDownloadError, possibly in a bounded cause/exception-info chain.
    """
    queue, seen = [error], set()
    for _ in range(32):
        if not queue:
            break
        current = queue.pop()
        if not isinstance(current, BaseException) or id(current) in seen:
            continue
        seen.add(id(current))
        if isinstance(current, ImportDownloadError) and re.fullmatch(
                r"SOURCE_IMPORT_[A-Z_]{1,49}", str(current)):
            return str(current)
        if isinstance(current, OSError) and current.args == ("SOURCE_IMPORT_NETWORK_DENIED",):
            return "SOURCE_IMPORT_NETWORK_DENIED"
        queue.extend([current.__cause__, current.__context__, getattr(current, "cause", None),
                      getattr(current, "reason", None)])
        info = getattr(current, "exc_info", None)
        if isinstance(info, tuple) and len(info) == 3:
            queue.append(info[1])
    return "SOURCE_IMPORT_DOWNLOAD_FAILED"


_DOUYIN_AMBIGUOUS_QUERY = {"list", "playlist", "index", "modal_id", "aweme_id", "item_id",
                          "item_ids", "video_id"}
_DOUYIN_MOBILE_UA = ("Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
                    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 "
                    "Mobile/15E148 Safari/604.1")


def _douyin_redirect_target(url: str, *, allow_short: bool = False) -> tuple[str, str | None]:
    """Allow only official single-video landing routes, never arbitrary pages."""
    try:
        _https(url)
        value = urlsplit(url)
        query = parse_qs(value.query, keep_blank_values=True, strict_parsing=True)
        if value.fragment or len(url) > 8192 or _DOUYIN_AMBIGUOUS_QUERY.intersection(query):
            raise ValueError
        if (allow_short and value.hostname == "v.douyin.com" and not query
                and re.fullmatch(r"/[A-Za-z0-9_-]{1,64}/?", value.path)):
            return url, None
        if value.hostname in {"douyin.com", "www.douyin.com", "m.douyin.com"}:
            found = re.fullmatch(r"/video/([0-9]{15,22})/?", value.path)
        elif value.hostname == "www.iesdouyin.com":
            found = re.fullmatch(r"/share/video/([0-9]{15,22})/?", value.path)
        else:
            found = None
        if found:
            video_id = found[1]
            return f"https://www.douyin.com/video/{video_id}", video_id
    except (ValueError, TypeError, AttributeError, ImportDownloadError):
        pass
    raise ImportDownloadError("SOURCE_IMPORT_UNSUPPORTED_SOURCE")


class _DouyinRedirects(urllib.request.HTTPRedirectHandler):
    def __init__(self):
        super().__init__()
        self.count, self.video_id = 0, None
        self.deadline = time.monotonic() + 30

    def observe(self, url):
        canonical, video_id = _douyin_redirect_target(url, allow_short=True)
        if video_id is not None:
            if self.video_id is not None and video_id != self.video_id:
                raise ImportDownloadError("SOURCE_IMPORT_UNSUPPORTED_SOURCE")
            self.video_id = video_id
        return canonical, video_id

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        self.count += 1
        if self.count > 5 or time.monotonic() >= self.deadline:
            raise ImportDownloadError("SOURCE_IMPORT_REDIRECT_LIMIT")
        self.observe(newurl)
        req.timeout = max(0.001, min(8, self.deadline - time.monotonic()))
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _resolve_douyin_short(url, *, opener=None):
    redirects = _DouyinRedirects()
    if opener is None:
        # Proxy discovery is disabled; the same process's socket guard checks
        # each redirect's DNS results and numeric connection destination.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), redirects)
    request = urllib.request.Request(url, headers={"User-Agent": _DOUYIN_MOBILE_UA})
    with opener.open(request, timeout=8) as response:
        canonical, video_id = redirects.observe(response.url)
        if video_id is None:
            raise ImportDownloadError("SOURCE_IMPORT_UNSUPPORTED_SOURCE")
        return canonical


class _InlineScripts(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.active, self.current, self.scripts = False, [], []

    def handle_starttag(self, tag, attrs):
        if tag.lower() == "script":
            self.active = not any(key.lower() == "src" for key, _ in attrs)
            self.current = []

    def handle_data(self, data):
        if self.active:
            self.current.append(data)

    def handle_endtag(self, tag):
        if tag.lower() == "script" and self.active:
            self.scripts.append("".join(self.current))
            self.active = False


def _unique_json_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate key")
        result[key] = value
    return result


def _public_douyin_detail(html, video_id):
    """Read actually served SSR JSON only. Never execute challenge/javascript."""
    if not isinstance(html, str) or len(html.encode("utf-8")) > 2 * 1024 * 1024:
        raise ImportDownloadError("SOURCE_IMPORT_AUTH_REQUIRED")
    parser = _InlineScripts()
    parser.feed(html)
    candidates = []
    for script in parser.scripts:
        marker = re.match(r"\s*(?:window\.)?_ROUTER_DATA\s*=\s*", script)
        if not marker:
            continue
        try:
            value, end = json.JSONDecoder(object_pairs_hook=_unique_json_pairs).raw_decode(script[marker.end():])
            if script[marker.end()+end:].strip() not in {"", ";"}:
                raise ValueError
            response = value["loaderData"]["video_(id)/page"]["videoInfoRes"]
            if response.get("status_code", 0) != 0 or not isinstance(response.get("item_list"), list):
                raise ValueError
            items = response["item_list"]
            if len(items) != 1 or not isinstance(items[0], dict):
                raise ValueError
            item = items[0]
            if (str(item.get("aweme_id")) != video_id or not isinstance(item.get("video"), dict)
                    or item.get("images") or item.get("image_post_info") or item.get("is_live")
                    or item.get("live_info") or item.get("room_id") or item.get("is_private")):
                raise ValueError
            candidates.append(item)
        except (ValueError, TypeError, KeyError, AttributeError):
            raise ImportDownloadError("SOURCE_IMPORT_AUTH_REQUIRED") from None
    if len(candidates) != 1:
        raise ImportDownloadError("SOURCE_IMPORT_AUTH_REQUIRED")
    return candidates[0]


def _extract_douyin(ydl, extractor, url, request_type):
    video_id = _douyin_redirect_target(url)[1]
    try:
        return ydl.extract_info(url, download=False, ie_key="Douyin")
    except Exception as error:
        if _failure_code(error) == "SOURCE_IMPORT_NETWORK_DENIED":
            raise
        if "fresh cookies" not in str(error).lower():
            raise
    from .source_import_douyin import anonymous_metadata

    try:
        return anonymous_metadata(ydl, extractor, video_id, request_type)
    except ImportDownloadError as error:
        if str(error) != "SOURCE_IMPORT_AUTH_REQUIRED":
            raise
    share = f"https://www.iesdouyin.com/share/video/{video_id}/"
    with ydl.urlopen(request_type(share, headers={"User-Agent": _DOUYIN_MOBILE_UA})) as response:
        if _douyin_redirect_target(response.url)[1] != video_id:
            raise ImportDownloadError("SOURCE_IMPORT_UNSUPPORTED_SOURCE")
        content = response.read(2 * 1024 * 1024 + 1)
    if len(content) > 2 * 1024 * 1024:
        raise ImportDownloadError("SOURCE_IMPORT_AUTH_REQUIRED")
    detail = _public_douyin_detail(content.decode("utf-8", "replace"), video_id)
    result = extractor._parse_aweme_video_app(detail)
    result.update(extractor="douyin", extractor_key="Douyin", webpage_url=url, original_url=url)
    return ydl.process_ie_result(result, download=False)


def download(url: str, directory: Path, max_bytes: int, *, metadata_only=False) -> dict:
    extractor = validate_url(url)
    from yt_dlp import YoutubeDL
    from yt_dlp.extractor.bilibili import BiliBiliIE
    from yt_dlp.extractor.tiktok import DouyinIE
    from yt_dlp.extractor.youtube import YoutubeIE
    from yt_dlp.networking import Request
    from yt_dlp.networking._urllib import UrllibRH

    options = {"quiet": True, "no_warnings": True, "logger": _SilentLogger(),
               "noplaylist": True, "extract_flat": False, "skip_download": True,
               "cachedir": False, "cookiefile": None, "cookiesfrombrowser": None,
               "usenetrc": False, "proxy": "", "socket_timeout": 15,
               "retries": 0, "extractor_retries": 0, "fragment_retries": 0,
               "js_runtimes": {}, "remote_components": set(), "plugin_dirs": [],
               "enable_file_urls": False, "geo_bypass": False}
    install_network_guard()
    sys.addaudithook(_no_external_execution)
    directory.mkdir(parents=True, exist_ok=True)
    with YoutubeDL(options, auto_init=False) as ydl:
        selected = (_bili_https_extractor(BiliBiliIE) if extractor == "BiliBili"
                    else {"Youtube": YoutubeIE, "Douyin": DouyinIE}[extractor]())
        ydl.add_info_extractor(selected)
        # Pin the only HTTP implementation: optional requests/curl/websocket
        # handlers and their native networking cannot bypass Python sockets.
        ydl._request_director = ydl.build_request_director([UrllibRH])
        if extractor == "Douyin":
            resolved = _resolve_douyin_short(url) if urlsplit(url).hostname == "v.douyin.com" else url
            info = _extract_douyin(ydl, selected, resolved, Request)
            if str(info.get("id")) != _douyin_redirect_target(resolved)[1]:
                raise ImportDownloadError("SOURCE_IMPORT_UNSUPPORTED_SOURCE")
        else:
            info = ydl.extract_info(url, download=False, ie_key=extractor)
        if info.get("extractor_key") != extractor:
            raise ImportDownloadError("SOURCE_IMPORT_UNSUPPORTED_SOURCE")
        streams = _streams(info, max_bytes)
        duration = info.get("duration")
        if not isinstance(duration, (int, float)) or duration <= 0 or duration > 4 * 3600:
            raise ImportDownloadError("SOURCE_IMPORT_DURATION_LIMIT")
        if metadata_only:
            return {"extractor": extractor, "duration_seconds": duration, "streams": len(streams)}
        total = 0
        files = []
        progress = _DownloadProgress(directory / "download-progress.json")
        for number, stream in enumerate(streams):
            known = stream.get("filesize")
            if known is not None and (type(known) is not int or known <= 0 or total + known > max_bytes):
                raise ImportDownloadError("SOURCE_IMPORT_TOO_LARGE")
            stream_url = _https(stream.get("url"))
            headers = dict(info.get("http_headers") or {})
            headers.update(stream.get("http_headers") or {})
            # Extractor metadata never supplies credentials or a filesystem path.
            headers = {k: v for k, v in headers.items() if k.lower() not in {"authorization", "cookie", "host"}}
            target = directory / f"stream-{number}.media"
            with ydl.urlopen(Request(stream_url, headers=headers)) as response, target.open("xb") as output:
                _https(response.url)
                content_length = response.headers.get("Content-Length")
                if content_length and (not content_length.isdigit() or total + int(content_length) > max_bytes):
                    raise ImportDownloadError("SOURCE_IMPORT_TOO_LARGE")
                while chunk := response.read(64 * 1024):
                    if total + len(chunk) > max_bytes:
                        raise ImportDownloadError("SOURCE_IMPORT_TOO_LARGE")
                    output.write(chunk)
                    total += len(chunk)
                    progress.update(total)
            if not target.stat().st_size:
                raise ImportDownloadError("SOURCE_IMPORT_EMPTY_DOWNLOAD")
            files.append(target.name)
        progress.update(total, final=True)
        return {"files": files, "downloaded_bytes": total, "duration_seconds": duration}


def main() -> int:
    try:
        request = json.loads(sys.stdin.buffer.read(8193))
        if (set(request) != {"url", "directory", "max_bytes", "metadata_only"}
                or type(request["max_bytes"]) is not int or not 1 <= request["max_bytes"] <= 2**40
                or type(request["metadata_only"]) is not bool):
            raise ImportDownloadError("SOURCE_IMPORT_INVALID_INPUT")
        result = download(request["url"], Path(request["directory"]), request["max_bytes"],
                          metadata_only=request["metadata_only"])
        print(json.dumps({"ok": True, "result": result}, ensure_ascii=True))
        return 0
    except BaseException as error:
        code = _failure_code(error)
        print(json.dumps({"ok": False, "error_code": code}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
