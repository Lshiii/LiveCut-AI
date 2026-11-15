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
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


class ImportDownloadError(Exception):
    pass


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
    """Check every DNS result and connect destination, including redirects.

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
        results = original_resolve(host, port, *args, **kwargs)
        if not results or any(not _public_ip(item[4][0]) for item in results):
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
    formats = [item for item in info.get("formats", []) if isinstance(item, dict)
               and item.get("protocol") == "https" and not item.get("has_drm")]
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
    os.replace(temporary, path)


def download(url: str, directory: Path, max_bytes: int, *, metadata_only=False) -> dict:
    extractor = validate_url(url)
    from yt_dlp import YoutubeDL
    from yt_dlp.extractor.bilibili import BiliBiliIE
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
        ydl.add_info_extractor(YoutubeIE() if extractor == "Youtube" else BiliBiliIE())
        # Pin the only HTTP implementation: optional requests/curl/websocket
        # handlers and their native networking cannot bypass Python sockets.
        ydl._request_director = ydl.build_request_director([UrllibRH])
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
                    _atomic(directory / "download-progress.json", {"downloaded_bytes": total})
            if not target.stat().st_size:
                raise ImportDownloadError("SOURCE_IMPORT_EMPTY_DOWNLOAD")
            files.append(target.name)
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
        code = str(error) if isinstance(error, ImportDownloadError) else "SOURCE_IMPORT_DOWNLOAD_FAILED"
        print(json.dumps({"ok": False, "error_code": code}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
