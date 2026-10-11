# -*- coding: utf-8 -*-
"""T50 — `tools/stage_android_python.py` 的真实行为。

测试约定（沿用 tests/test_apk_size_audit.py）：
  · 只驱动真实实现，不 mock 被测逻辑；
  · 合成源树建在 `tempfile.mkdtemp()` 下，测试结束即删；
  · 最后一个用例跑**真实仓库**的 `app/src/main/python` + `config/android-python-excludes.json`，
    断言排除前后的实际内容（不是「应该有」）。

覆盖计划验收项：
  · 清单内文件确实未进入暂存源，其余内容哈希/路径符合预期；
  · 重复构建无残留、无绝对路径或时间戳引起的非确定差异；
  · 清单条目未命中时明确失败（防清单腐烂）；
  · 源树不被修改。
"""
import hashlib
import io
import json
import os
import shutil
import sys
import tempfile
import time
import unittest

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(REPO_ROOT, "tools"))

import stage_android_python as staging  # noqa: E402


def sha256_file(path):
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()


def write(root, rel, data=b"x"):
    path = os.path.join(root, rel.replace("/", os.sep))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(data)
    return path


class StageAndroidPythonTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="t50-")
        self.source = os.path.join(self.tmp, "python")
        self.dest = os.path.join(self.tmp, "staged")
        write(self.source, "repacker/repacker.py", b"# repacker\n")
        write(self.source, "vendor/UnityPy/lib/FMOD/Darwin/libfmod.dylib", b"macho" * 100)
        write(self.source, "vendor/UnityPy/lib/FMOD/Windows/x64/fmod.dll", b"pe" * 100)
        write(self.source, "vendor/UnityPy/lib/FMOD/Linux/arm64/libfmod.so", b"elf" * 100)
        write(self.source, "vendor/UnityPy/lib/FMOD/__init__.py", b"")
        write(self.source, "vendor/UnityPy/lib/__init__.py", b"")
        write(self.source, "vendor/UnityPy/export/AudioClipConverter.py", b"# audio\n")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _config(self, excludes):
        path = os.path.join(self.tmp, "excludes.json")
        with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(json.dumps({"schemaVersion": 1, "excludes": excludes}))
        return path

    def _real_excludes(self):
        with io.open(os.path.join(REPO_ROOT, "config", "android-python-excludes.json"),
                     encoding="utf-8") as fh:
            return sorted(e["path"] for e in json.load(fh)["excludes"])

    # ---------------------------------------------------------------- 基本行为

    def test_excluded_files_are_absent_and_others_kept(self):
        excludes = self._real_excludes()
        report = staging.stage(self.source, self.dest, excludes)
        for rel in ("vendor/UnityPy/lib/FMOD/Darwin/libfmod.dylib",
                    "vendor/UnityPy/lib/FMOD/Windows/x64/fmod.dll",
                    "vendor/UnityPy/lib/FMOD/Linux/arm64/libfmod.so"):
            self.assertFalse(os.path.exists(os.path.join(self.dest, rel.replace("/", os.sep))),
                             "%s 必须被排除" % rel)
        for rel in ("repacker/repacker.py",
                    "vendor/UnityPy/lib/__init__.py",
                    "vendor/UnityPy/lib/FMOD/__init__.py",
                    "vendor/UnityPy/export/AudioClipConverter.py"):
            self.assertTrue(os.path.exists(os.path.join(self.dest, rel.replace("/", os.sep))),
                            "%s 必须保留" % rel)
        self.assertEqual(4, report["includedCount"])
        self.assertEqual(3, report["excludedFileCount"])

    def test_kept_files_match_source_hash(self):
        report = staging.stage(self.source, self.dest, self._real_excludes())
        for record in report["included"]:
            dst = os.path.join(self.dest, record["path"].replace("/", os.sep))
            self.assertEqual(sha256_file(dst), record["sha256"])
            self.assertEqual(os.path.getsize(dst), record["bytes"])

    def test_source_tree_is_never_modified(self):
        before = {}
        for dirpath, _, filenames in os.walk(self.source):
            for name in filenames:
                p = os.path.join(dirpath, name)
                before[p] = sha256_file(p)
        staging.stage(self.source, self.dest, self._real_excludes())
        after = {}
        for dirpath, _, filenames in os.walk(self.source):
            for name in filenames:
                p = os.path.join(dirpath, name)
                after[p] = sha256_file(p)
        self.assertEqual(before, after)
        self.assertEqual(7, len(after), "源树文件数必须不变")

    # ---------------------------------------------------------------- 确定性

    def test_rebuild_is_byte_identical_and_leaves_no_residue(self):
        excludes = self._real_excludes()
        first = staging.stage(self.source, self.dest, excludes)
        # 第二次前先塞一个垃圾文件，验证目标目录确实被清空
        stray = os.path.join(self.dest, "leftover.txt")
        write(self.dest, "leftover.txt", b"stale")
        self.assertTrue(os.path.exists(stray))
        second = staging.stage(self.source, self.dest, excludes)
        self.assertFalse(os.path.exists(stray), "残留文件必须被清掉")
        self.assertEqual(json.dumps(first, sort_keys=True), json.dumps(second, sort_keys=True))

    def test_manifest_has_no_absolute_paths_and_mtime_is_normalised(self):
        report = staging.stage(self.source, self.dest, self._real_excludes())
        blob = json.dumps(report, sort_keys=True)
        self.assertNotIn(self.tmp.replace("\\", "/"), blob)
        self.assertNotIn(self.tmp.replace("/", "\\\\"), blob)
        for record in report["included"]:
            dst = os.path.join(self.dest, record["path"].replace("/", os.sep))
            self.assertEqual(staging.FIXED_MTIME, int(os.stat(dst).st_mtime))

    # ---------------------------------------------------------------- 清单防腐烂

    def test_entry_matching_nothing_fails(self):
        with self.assertRaises(staging.StageLogicError) as ctx:
            staging.stage(self.source, self.dest,
                          ["vendor/UnityPy/lib/NoSuchPlatform"])
        self.assertIn("matched nothing", str(ctx.exception))

    def test_one_missing_entry_among_valid_ones_fails(self):
        excludes = self._real_excludes() + ["vendor/UnityPy/lib/NoSuchPlatform"]
        with self.assertRaises(staging.StageLogicError):
            staging.stage(self.source, self.dest, excludes)

    def test_directory_prefix_match_excludes_whole_subtree(self):
        report = staging.stage(self.source, self.dest, ["vendor/UnityPy/lib/FMOD/Linux"])
        self.assertFalse(os.path.exists(os.path.join(self.dest, "vendor", "UnityPy", "lib",
                                                     "FMOD", "Linux")))
        self.assertTrue(os.path.exists(os.path.join(self.dest, "vendor", "UnityPy", "lib",
                                                    "FMOD", "Darwin", "libfmod.dylib")))
        self.assertEqual(1, report["excludedFileCount"])

    def test_dest_inside_source_is_rejected(self):
        with self.assertRaises(staging.StageLogicError):
            staging.stage(self.source, os.path.join(self.source, "staged"), [])

    def test_dest_equal_to_source_is_rejected(self):
        with self.assertRaises(staging.StageLogicError):
            staging.stage(self.source, self.source, [])

    # ---------------------------------------------------------------- 配置校验

    def test_config_rejects_unsafe_and_duplicate_paths(self):
        unsafe = self._config([{"path": "../escape"}])
        with self.assertRaises(staging.StageError):
            staging.load_config(unsafe)
        dup = self._config([{"path": "a"}, {"path": "a"}])
        with self.assertRaises(staging.StageError):
            staging.load_config(dup)
        empty = self._config([{"reason": "no path"}])
        with self.assertRaises(staging.StageError):
            staging.load_config(empty)
        missing = os.path.join(self.tmp, "nope.json")
        with self.assertRaises(staging.StageError):
            staging.load_config(missing)

    # ---------------------------------------------------------------- CLI

    def test_cli_exit_codes_and_manifest(self):
        cfg = self._config([{"path": "vendor/UnityPy/lib/FMOD"}])
        manifest = os.path.join(self.tmp, "manifest.json")
        self.assertEqual(0, staging.main(["--source", self.source, "--dest", self.dest,
                                          "--config", cfg, "--manifest", manifest, "--quiet"]))
        with io.open(manifest, encoding="utf-8") as fh:
            data = json.load(fh)
        # FMOD 整棵树共 4 个文件（含空 __init__.py）
        self.assertEqual(4, data["excludedFileCount"])
        self.assertEqual(3, data["includedCount"])

        bad = self._config([{"path": "nope"}])
        self.assertEqual(1, staging.main(["--source", self.source, "--dest", self.dest,
                                          "--config", bad, "--quiet"]))
        self.assertEqual(2, staging.main(["--source", self.source, "--dest", self.dest,
                                          "--config", os.path.join(self.tmp, "gone.json"),
                                          "--quiet"]))
        self.assertEqual(2, staging.main(["--source", os.path.join(self.tmp, "nosrc"),
                                          "--dest", self.dest, "--config", cfg, "--quiet"]))

    # ---------------------------------------------------------------- 真实仓库

    def test_real_repo_staging_matches_the_shipped_exclude_list(self):
        source = os.path.join(REPO_ROOT, "app", "src", "main", "python")
        if not os.path.isdir(source):
            self.skipTest("real python source tree not present")
        dest = os.path.join(self.tmp, "real-staged")
        report = staging.stage(source, dest, self._real_excludes())

        # 三条清单路径覆盖的 18 个 FMOD 文件全部不在暂存源里
        self.assertEqual(18, report["excludedFileCount"])
        for platform in ("Darwin", "Windows", "Linux"):
            self.assertFalse(os.path.exists(os.path.join(dest, "vendor", "UnityPy", "lib",
                                                         "FMOD", platform)),
                             "FMOD/%s 必须整棵排除" % platform)
        # 保留必须能力：UnityPy 类、延迟导入、纹理/文本/骨骼相关模块
        for rel in ("vendor/UnityPy/export/AudioClipConverter.py",
                    "vendor/UnityPy/export/Texture2DConverter.py",
                    "vendor/UnityPy/export/MeshExporter.py",
                    "vendor/UnityPy/export/SpriteHelper.py",
                    "vendor/UnityPy/helpers/TypeTreeHelper.py",
                    "vendor/UnityPy/helpers/MeshHelper.py",
                    "vendor/UnityPy/__init__.py",
                    "repacker/repacker.py",
                    "texture2ddecoder.py",
                    "unpacker.py",
                    "resolver.py"):
            self.assertTrue(os.path.exists(os.path.join(dest, rel.replace("/", os.sep))),
                            "%s 必须保留" % rel)
        # 源树里被排除的原件仍然在（vendored 来源不被破坏）
        for platform in ("Darwin", "Windows", "Linux"):
            self.assertTrue(os.path.isdir(os.path.join(source, "vendor", "UnityPy", "lib",
                                                       "FMOD", platform)))
        # 体积确实降下来了（未压缩字节）
        staged_bytes = report["includedBytes"]
        self.assertLess(staged_bytes, 6 * 1024 * 1024)


if __name__ == "__main__":
    unittest.main()
