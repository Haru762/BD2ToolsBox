# -*- coding: utf-8 -*-
"""T60：`tools/apk_identity.py` 的真实行为。

这个工具是「这次交付的是什么内容」的唯一判据，判错就会把两份不同的包说成同一份。
所以这里断言的是：它**忽略**签名块与压缩方式，但**不忽略**内容。
"""
import hashlib
import io
import json
import os
import shutil
import sys
import tempfile
import unittest
import zipfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(REPO_ROOT, "tools"))

import apk_identity  # noqa: E402


def make_apk(path, entries, compress=True, extra_tail=b""):
    """造一个最小 APK 形状的 zip；`extra_tail` 模拟签名块（在中央目录之前）。"""
    mode = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
    with zipfile.ZipFile(path, "w", mode) as z:
        for name, data in entries.items():
            z.writestr(name, data)
    if extra_tail:
        # zipfile 写完后追加的字节会落在中央目录**之后**；这里只用来验证
        # 「文件哈希会变、内容哈希不变」这一点，位置不影响结论。
        with open(path, "ab") as fh:
            fh.write(extra_tail)


class ApkIdentityTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="t60-apkid-")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _path(self, name):
        return os.path.join(self.tmp, name)

    def test_same_entries_same_content_hash(self):
        entries = {"classes.dex": b"dex-bytes", "res/raw/keep.xml": b"<x/>"}
        a, b = self._path("a.apk"), self._path("b.apk")
        make_apk(a, entries)
        make_apk(b, entries)
        self.assertEqual(apk_identity.content_sha256(a), apk_identity.content_sha256(b))

    def test_compression_level_does_not_change_content_hash(self):
        """压缩方式不同 → 文件字节不同，但内容哈希必须相同。"""
        entries = {"classes.dex": b"x" * 5000}
        a, b = self._path("a.apk"), self._path("b.apk")
        make_apk(a, entries, compress=True)
        make_apk(b, entries, compress=False)
        self.assertNotEqual(apk_identity._file_sha256(a), apk_identity._file_sha256(b))
        self.assertEqual(apk_identity.content_sha256(a), apk_identity.content_sha256(b))

    def test_trailing_signing_block_does_not_change_content_hash(self):
        """追加的字节（模拟签名块）改文件哈希，但不改内容哈希。"""
        entries = {"classes.dex": b"dex-bytes"}
        a, b = self._path("a.apk"), self._path("b.apk")
        make_apk(a, entries)
        make_apk(b, entries, extra_tail=b"APK Sig Block 42" + b"\x00" * 64)
        self.assertNotEqual(apk_identity._file_sha256(a), apk_identity._file_sha256(b))
        self.assertEqual(apk_identity.content_sha256(a), apk_identity.content_sha256(b))

    def test_changed_content_changes_the_hash(self):
        """内容变了必须变 —— 否则这个判据没有意义。"""
        a, b = self._path("a.apk"), self._path("b.apk")
        make_apk(a, {"classes.dex": b"one"})
        make_apk(b, {"classes.dex": b"two"})
        self.assertNotEqual(apk_identity.content_sha256(a), apk_identity.content_sha256(b))

    def test_entry_name_boundary_is_unambiguous(self):
        """`('ab','c')` 与 `('a','bc')` 不能撞哈希 —— 名字与内容之间必须有分隔。"""
        a, b = self._path("a.apk"), self._path("b.apk")
        make_apk(a, {"ab": b"c"})
        make_apk(b, {"a": b"bc"})
        self.assertNotEqual(apk_identity.content_sha256(a), apk_identity.content_sha256(b))

    def test_entry_order_does_not_matter(self):
        """ZIP 写入顺序不该影响内容哈希（按名字排序后算）。"""
        a, b = self._path("a.apk"), self._path("b.apk")
        with zipfile.ZipFile(a, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("b.txt", b"second")
            z.writestr("a.txt", b"first")
        with zipfile.ZipFile(b, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("a.txt", b"first")
            z.writestr("b.txt", b"second")
        self.assertEqual(apk_identity.content_sha256(a), apk_identity.content_sha256(b))

    def test_describe_reports_both_hashes_and_entry_count(self):
        p = self._path("a.apk")
        make_apk(p, {"a": b"1", "b": b"2"})
        info = apk_identity.describe(p)
        self.assertEqual(2, info["entryCount"])
        self.assertEqual(os.path.getsize(p), info["bytes"])
        self.assertEqual(64, len(info["fileSha256"]))
        self.assertEqual(64, len(info["contentSha256"]))
        self.assertNotEqual(info["fileSha256"], info["contentSha256"])

    def test_real_release_apks_match_the_recorded_content_hash(self):
        """本仓交付记录里的 contentSha256 必须与磁盘上的 release 产物一致。"""
        manifest = os.path.join(REPO_ROOT, "docs", "refactor", "artifact-manifest.json")
        with io.open(manifest, encoding="utf-8") as fh:
            delivery = json.load(fh)["t61Delivery"]["artifacts"]
        for abi, rel in (("arm64-v8a", "app/build/outputs/apk/arm64/release/app-arm64-release.apk"),
                         ("x86_64", "app/build/outputs/apk/x64/release/app-x64-release.apk")):
            full = os.path.join(REPO_ROOT, rel.replace("/", os.sep))
            if not os.path.isfile(full):
                self.skipTest("release APK 尚未构建：%s" % rel)
            self.assertEqual(
                delivery[abi]["contentSha256"],
                apk_identity.content_sha256(full),
                "%s 的内容哈希与交付记录不一致" % abi,
            )


if __name__ == "__main__":
    unittest.main()
