import copy

import pytest
from livecut import source_import_download as dl


def test_bili_supplied_443_backups_keep_track_metadata_and_original_object():
    raw = {"dash": {"audio": [{"id": 30280, "baseUrl": "https://cdn.example:8082/audio?token=fixture",
        "backup_url": ["https://cdn.example:4483/audio?token=fixture", "https://backup.example/audio?token=fixture"],
        "mimeType": "audio/mp4", "codecs": "mp4a.40.2", "bandwidth": 128000}],
        "video": [{"id": 80, "base_url": "https://cdn.example:4483/video?token=fixture",
        "backupUrl": ["http://not-secure.example/video", "https://backup.example/video?token=fixture"],
        "height": 720, "width": 1280, "codecs": "avc1.64001F"}]}, "duration": 10}
    before = copy.deepcopy(raw)
    selected = dl._bili_https_play_info(raw)
    assert selected["dash"]["audio"][0]["baseUrl"] == "https://backup.example/audio?token=fixture"
    assert selected["dash"]["video"][0]["base_url"] == "https://backup.example/video?token=fixture"
    assert selected["dash"]["audio"][0]["codecs"] == "mp4a.40.2"
    assert selected["dash"]["video"][0]["height"] == 720
    assert raw == before


def test_bili_valid_primary_remains_preferred_in_original_order():
    primary = "https://primary.example/audio?signature=fixture"
    raw = {"dash": {"audio": [{"baseUrl": primary, "backup_url": ["https://backup.example/audio"]}]}}
    assert dl._bili_https_play_info(raw)["dash"]["audio"][0]["baseUrl"] == primary


def test_stream_selection_drops_unsupported_ports_before_ranking():
    rows = [{"protocol": "https", "url": "https://cdn.example:4483/video", "vcodec": "h264",
             "acodec": "aac", "height": 720, "tbr": 999},
            {"protocol": "https", "url": "https://cdn.example/video", "vcodec": "h264",
             "acodec": "aac", "height": 720, "tbr": 100}]
    assert dl._streams({"formats": rows}) == [rows[1]]


def test_bili_no_valid_443_backup_is_not_rewritten_or_accepted():
    raw = {"dash": {"audio": [{"baseUrl": "https://cdn.example:8082/audio",
        "backup_url": ["https://cdn.example:4483/audio", "http://cdn.example/audio"]}]}}
    selected = dl._bili_https_play_info(raw)
    assert selected == raw
    with pytest.raises(dl.ImportDownloadError, match="NETWORK_DENIED"):
        dl._streams({"formats": [{"url": selected["dash"]["audio"][0]["baseUrl"],
                                  "protocol": "https", "vcodec": "none", "acodec": "aac"}]})


def test_bili_local_adapter_preserves_upstream_identity_and_uses_normalized_raw():
    class Base:
        def extract_formats(self, value):
            return value["dash"]["audio"]
    extractor = dl._bili_https_extractor(Base)
    assert extractor.ie_key() == "BiliBili"
    result = extractor.extract_formats({"dash": {"audio": [{"baseUrl": "https://bad.example:8082/a",
        "backup_url": ["https://good.example/a"], "id": 30280}]}})
    assert result[0]["baseUrl"] == "https://good.example/a" and result[0]["id"] == 30280
