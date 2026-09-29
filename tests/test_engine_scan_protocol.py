"""The scan bridge must not turn malformed input into a successful empty worklist."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

PYTHON_ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "python"
sys.path.insert(0, str(PYTHON_ROOT))
sys.path.insert(0, str(PYTHON_ROOT / "vendor"))

import main_script
import local_bundle_indexer


class EngineScanProtocolTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.previous_scan = local_bundle_indexer._scan_state

    def tearDown(self):
        local_bundle_indexer._scan_state = self.previous_scan
        self.temp.cleanup()

    def test_success_keeps_the_single_element_tuple_protocol(self):
        result = main_script.check_scan_needed(
            self.temp.name, '[{"name":"bundle-a","hash":"hash-a"}]'
        )
        self.assertIsInstance(result, tuple)
        self.assertEqual(1, len(result))
        self.assertEqual(["bundle-a"], json.loads(result[0]))

    def test_real_empty_worklist_is_a_success(self):
        self.assertEqual(("[]",), main_script.check_scan_needed(self.temp.name, "[]"))

    def test_malformed_json_is_not_reported_as_no_work(self):
        with self.assertRaises(json.JSONDecodeError):
            main_script.check_scan_needed(self.temp.name, "[")

    def test_missing_hash_is_not_reported_as_no_work(self):
        with self.assertRaises(KeyError):
            main_script.check_scan_needed(self.temp.name, '[{"name":"bundle-a"}]')


if __name__ == "__main__":
    unittest.main()
