import unittest
from copy import deepcopy
from pathlib import Path

from livecut.inputs import DatasetValidationError, validate_dataset_manifest


class DatasetTests(unittest.TestCase):
    def setUp(self):
        self.data = {"schema_version": "1.0", "data_kind": "real_video_manifest", "videos": [
            {"video_id": "v1", "original_video_id": "original1", "source_type": "public_video",
             "local_path": "missing.mp4", "source_url": "https://example.org/video", "split": "dev"}
        ]}

    def test_metadata_only_accepts_no_sku(self):
        validate_dataset_manifest(self.data, base_dir=Path.cwd(), check_files=False)

    def test_missing_video_is_not_silently_accepted(self):
        with self.assertRaises(DatasetValidationError):
            validate_dataset_manifest(self.data, base_dir=Path.cwd())

    def test_same_original_cannot_cross_splits(self):
        second = deepcopy(self.data["videos"][0])
        second.update(video_id="v2", split="test")
        self.data["videos"].append(second)
        with self.assertRaisesRegex(DatasetValidationError, "both dev and test"):
            validate_dataset_manifest(self.data, base_dir=Path.cwd(), check_files=False)

    def test_fixture_cannot_masquerade_as_real_video(self):
        self.data["videos"][0]["source_type"] = "synthetic_fixture"
        with self.assertRaises(DatasetValidationError):
            validate_dataset_manifest(self.data, base_dir=Path.cwd(), check_files=False)

    def test_boolean_is_not_a_millisecond_offset(self):
        self.data["videos"][0]["original_offset_ms"] = True
        with self.assertRaises(DatasetValidationError):
            validate_dataset_manifest(self.data, base_dir=Path.cwd(), check_files=False)
