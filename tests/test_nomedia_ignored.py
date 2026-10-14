# -*- coding: utf-8 -*-
"""T62 — `.nomedia` 目录级媒体扫描隔离标记不参与 Python 侧资源枚举/索引/重打包输入。

被测对象是真实实现 `repacker.repacker.should_ignore_entry` 与 `_build_file_index`：
  · 标记**恰好**是 `.nomedia`（`.nomedia.txt` / `.nomedia.bin` 不算，与 Kotlin 侧
    `MediaExclusionManager.isMarkerName` 同口径）；
  · 标记不进入 `all_files`，也不被当成 skel/json 资源；
  · 只含标记的目录不会被识别出任何资源。

测试约定：只驱动真实实现；每个用例在系统临时目录里自建输入，不用夹具；
Python 3.8 兼容。
"""
import os
import shutil
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_PYTHON_ROOT = os.path.join(os.path.dirname(_HERE), "app", "src", "main", "python")
if _PYTHON_ROOT not in sys.path:
    sys.path.insert(0, _PYTHON_ROOT)

from repacker import repacker  # noqa: E402


class ShouldIgnoreEntryTest(unittest.TestCase):
    def test_nomedia_is_ignored(self):
        self.assertTrue(repacker.should_ignore_entry(".nomedia"))

    def test_modfile_is_ignored(self):
        self.assertTrue(repacker.should_ignore_entry(".modfile"))

    def test_renamed_marker_is_not_ignored(self):
        for name in (".nomedia.txt", ".nomedia.bin", "nomedia", ".nomedia2"):
            self.assertFalse(repacker.should_ignore_entry(name), name)

    def test_normal_assets_are_not_ignored(self):
        for name in ("char000104.dat", "skel.atlas", "a.png", "a.skel", "a.json"):
            self.assertFalse(repacker.should_ignore_entry(name), name)


class FileIndexTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="t62-nomedia-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def _write(self, rel, content=""):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(content)
        return path

    def test_marker_is_excluded_from_the_file_index(self):
        self._write(".nomedia")
        self._write("hero.skel", "x")
        self._write("hero.atlas", "y")
        index = repacker._build_file_index(self.root)
        joined = "\n".join(index["all_files"])
        self.assertNotIn(".nomedia", joined)
        self.assertIn("hero.skel", joined)
        self.assertIn("hero.atlas", joined)

    def test_marker_in_subdirectory_is_excluded(self):
        self._write(os.path.join("sub", ".nomedia"))
        self._write(os.path.join("sub", "a.skel"), "x")
        index = repacker._build_file_index(self.root)
        self.assertTrue(all(not p.endswith(".nomedia") for p in index["all_files"]))
        self.assertTrue(any(p.endswith("a.skel") for p in index["all_files"]))

    def test_marker_is_not_treated_as_a_skel_or_json_asset(self):
        # `.nomedia` 不是 skel/json；即便被误当成 json 也不该出现。
        self._write(".nomedia", "{}")
        index = repacker._build_file_index(self.root)
        self.assertEqual({}, index["skel_json"])

    def test_directory_with_only_a_marker_yields_no_resources(self):
        self._write(".nomedia")
        index = repacker._build_file_index(self.root)
        self.assertEqual([], index["all_files"])
        self.assertEqual({}, index["skel_json"])

    def test_renamed_marker_is_not_filtered(self):
        # `.nomedia.txt` 不是标记：不排除（与 Kotlin 侧 verifyProviderName 同口径）。
        self._write(".nomedia.txt", "not a marker")
        index = repacker._build_file_index(self.root)
        self.assertTrue(any(p.endswith(".nomedia.txt") for p in index["all_files"]))

    def test_uppercase_atlas_page_headers_and_merge_are_normalized_to_lowercase(self):
        raw_atlas = "\nChar000104.PNG\r\n size: 64,64\r\n filter: Linear, Linear\r\nhead\r\n bounds: 0,0,32,32\r\n\r\nChar000104_2.PNG\r\n size: 64,64\r\n filter: Linear, Linear\r\nbody\r\n bounds: 0,0,32,32\r\n"
        normalized = repacker._normalize_atlas_page_headers(raw_atlas)
        self.assertIn("char000104.png\r\n", normalized)
        self.assertIn("char000104_2.png\r\n", normalized)
        self.assertNotIn("Char000104.PNG", normalized)

        from PIL import Image
        Image.new("RGBA", (64, 64), (255, 0, 0, 255)).save(os.path.join(self.root, "Char000104.PNG"))
        Image.new("RGBA", (64, 64), (0, 255, 0, 255)).save(os.path.join(self.root, "Char000104_2.PNG"))
        atlas_file = self._write("Char000104.ATLAS", raw_atlas)
        err = repacker._merge_spine_assets(self.root, "Char000104", 1, lambda _m: None)
        self.assertIsNone(err)
        with open(atlas_file, "r", encoding="utf-8") as fh:
            merged_atlas = fh.read()
        self.assertTrue(merged_atlas.startswith("char000104.png\n"))
        self.assertIn(" size: 128,64", merged_atlas)


if __name__ == "__main__":
    unittest.main()
