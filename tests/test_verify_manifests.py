# -*- coding: utf-8 -*-
"""T60 — `tools/verify_manifests.py` 的真实行为。

只读工具，但它的判定必须能**抓到**哈希不符，否则「16 条全 OK」没有意义。
合成清单建在临时目录里，用完即删。
"""
import hashlib
import io
import json
import os
import shutil
import sys
import tempfile
import unittest

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(REPO_ROOT, "tools"))

import verify_manifests as vm  # noqa: E402


class VerifyManifestsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="t60-")
        os.makedirs(os.path.join(self.tmp, "docs", "refactor"))
        # 工具会同时读两份清单；另一份写成空的，免得「清单文件缺失」混进 missing 计数
        self._write("docs/refactor/fixture-manifest.json", '{"fixtures": []}')

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write(self, rel, data):
        path = os.path.join(self.tmp, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        mode = "wb" if isinstance(data, bytes) else "w"
        if mode == "wb":
            with open(path, "wb") as fh:
                fh.write(data)
        else:
            with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(data)
        return path

    def _manifest(self, name, payload):
        self._write("docs/refactor/%s.json" % name, json.dumps(payload))

    def _sha(self, rel):
        with open(os.path.join(self.tmp, rel.replace("/", os.sep)), "rb") as fh:
            return hashlib.sha256(fh.read()).hexdigest()

    def test_matching_hashes_pass(self):
        self._write("local/keep/a.apk", b"payload-a")
        self._manifest("artifact-manifest", {
            "categories": [{
                "id": "keep", "localDir": "local/keep",
                "items": [{"file": "a.apk", "bytes": 9, "sha256": self._sha("local/keep/a.apk")}],
            }]
        })
        report = vm.verify(self.tmp)
        self.assertEqual(1, report["summary"]["checked"])
        self.assertEqual(0, report["summary"]["mismatched"])

    def test_changed_bytes_are_caught(self):
        self._write("local/keep/a.apk", b"payload-a")
        self._manifest("artifact-manifest", {
            "categories": [{
                "id": "keep", "localDir": "local/keep",
                "items": [{"file": "a.apk", "sha256": "0" * 64}],
            }]
        })
        report = vm.verify(self.tmp)
        self.assertEqual(1, report["summary"]["mismatched"])
        self.assertIn("sha256 mismatch", report["mismatched"][0]["reason"])

    def test_byte_size_mismatch_is_caught_before_hashing(self):
        self._write("local/keep/a.apk", b"payload-a")
        self._manifest("artifact-manifest", {
            "categories": [{
                "id": "keep", "localDir": "local/keep",
                "items": [{"file": "a.apk", "bytes": 999, "sha256": self._sha("local/keep/a.apk")}],
            }]
        })
        report = vm.verify(self.tmp)
        self.assertEqual(1, report["summary"]["mismatched"])
        self.assertIn("byte size mismatch", report["mismatched"][0]["reason"])

    def test_missing_file_is_reported_not_silently_skipped(self):
        self._manifest("artifact-manifest", {
            "categories": [{
                "id": "keep", "localDir": "local/keep",
                "items": [{"file": "gone.apk", "sha256": "0" * 64}],
            }]
        })
        report = vm.verify(self.tmp)
        self.assertEqual(0, report["summary"]["checked"])
        self.assertEqual(1, report["summary"]["missing"])

    def test_tool_run_hash_belongs_to_output_not_to_the_audited_apk(self):
        # 回归：t48Freeze.toolRun 同时有 apk 与 output，而 sha256 指的是**输出报告**。
        # 先取 apk 会拿 APK 的字节去比报告的哈希，报出假的「哈希不符」。
        self._write("local/keep/a.apk", b"apk-bytes")
        self._write("docs/out.json", "report")
        self._manifest("artifact-manifest", {
            "t48Freeze": {"toolRun": [{
                "apk": "local/keep/a.apk",
                "output": "docs/out.json",
                "sha256": self._sha("docs/out.json"),
            }]}
        })
        report = vm.verify(self.tmp)
        self.assertEqual(0, report["summary"]["mismatched"], report["mismatched"])
        self.assertEqual(1, report["summary"]["checked"])
        self.assertEqual("docs/out.json", report["checked"][0]["path"])

    def test_cli_exit_codes(self):
        self._write("local/keep/a.apk", b"payload-a")
        self._manifest("artifact-manifest", {
            "categories": [{
                "id": "keep", "localDir": "local/keep",
                "items": [{"file": "a.apk", "sha256": self._sha("local/keep/a.apk")}],
            }]
        })
        self.assertEqual(0, vm.main(["--root", self.tmp, "--quiet"]))
        self._manifest("artifact-manifest", {
            "categories": [{
                "id": "keep", "localDir": "local/keep",
                "items": [{"file": "a.apk", "sha256": "f" * 64}],
            }]
        })
        self.assertEqual(1, vm.main(["--root", self.tmp, "--quiet"]))

    def test_real_repository_baselines_are_intact(self):
        report = vm.verify(REPO_ROOT)
        self.assertGreaterEqual(report["summary"]["checked"], 12)
        self.assertEqual([], report["mismatched"])

    # ---- T61：交付记录与冻结基线分开对待 ----

    def _delivery_manifest(self, sha):
        self._manifest("artifact-manifest", {
            "t61Delivery": {
                "artifacts": {
                    "x86_64": {"path": "app/build/outputs/apk/x64/release/app.apk", "sha256": sha},
                }
            }
        })

    def test_delivery_records_are_skipped_by_default(self):
        """交付记录记的是「当前构建」的哈希，改代码重建后理应过期；
        本地验证默认不该因为它失败（那会把人训练成忽略这个检查）。"""
        self._write("app/build/outputs/apk/x64/release/app.apk", b"built-later")
        self._delivery_manifest("f" * 64)  # 故意过期
        report = vm.verify(self.tmp)
        self.assertEqual([], report["mismatched"])
        self.assertFalse(report["deliveryChecked"])
        self.assertEqual(1, report["summary"]["skippedDelivery"])
        self.assertEqual(0, vm.main(["--root", self.tmp, "--quiet"]))

    def test_delivery_records_are_checked_when_asked(self):
        """发版前显式核对时必须能抓到过期的交付记录。"""
        self._write("app/build/outputs/apk/x64/release/app.apk", b"built-later")
        self._delivery_manifest("f" * 64)
        report = vm.verify(self.tmp, check_delivery=True)
        self.assertTrue(report["deliveryChecked"])
        self.assertEqual(1, len(report["mismatched"]))
        self.assertEqual(0, report["summary"]["skippedDelivery"])
        self.assertEqual(1, vm.main(["--root", self.tmp, "--quiet", "--check-delivery"]))

    def test_delivery_records_pass_when_fresh(self):
        self._write("app/build/outputs/apk/x64/release/app.apk", b"built-later")
        self._delivery_manifest(
            self._sha("app/build/outputs/apk/x64/release/app.apk"))
        report = vm.verify(self.tmp, check_delivery=True)
        self.assertEqual([], report["mismatched"])
        self.assertEqual(1, report["summary"]["checked"])

    def test_real_repository_delivery_record_matches_current_build(self):
        """本仓交付记录必须与当前两个 ABI 的 release 产物一致。

        与 `test_real_repository_baselines_are_intact` 的区别：那条核冻结基线，
        这条核交付记录 —— 发版前必须两者都绿。
        """
        report = vm.verify(REPO_ROOT, check_delivery=True)
        self.assertTrue(report["deliveryChecked"])
        self.assertEqual([], report["mismatched"])
        self.assertGreaterEqual(report["summary"]["checked"], 18)


if __name__ == "__main__":
    unittest.main()
