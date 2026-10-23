import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from livecut.cli import main


class CliTests(unittest.TestCase):
    def test_invalid_result_returns_error_status(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.json"
            path.write_text("{}", encoding="utf-8")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                result = main(["validate-result", str(path)])
            self.assertEqual(result, 2)
            self.assertEqual(json.loads(output.getvalue())["status"], "error")

    def test_doctor_does_not_disclose_secret_or_claim_verified(self):
        output = io.StringIO()
        with patch.dict("os.environ", {"DASHSCOPE_API_KEY": "never-print-this-secret"}):
            with contextlib.redirect_stdout(output):
                self.assertEqual(main(["doctor"]), 0)
        self.assertNotIn("never-print-this-secret", output.getvalue())
        self.assertTrue(json.loads(output.getvalue())["dashscope_key_configured"])
        self.assertFalse(json.loads(output.getvalue())["credentials_verified"])
