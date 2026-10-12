# -*- coding: utf-8 -*-
"""T48 — `tools/audit_apk_size.py` 的真实行为。

测试约定（沿用 tests/test_safe_archive.py）：
  · 只驱动真实实现，不 mock 被测逻辑；
  · 每个用例在系统临时目录里**自建合成 APK/IMY**，不使用夹具，因此不注册
    docs/refactor/fixture-manifest.json；
  · 所有合成输入都在 `tempfile.mkdtemp()` 下，测试结束即删。

覆盖计划验收项：
  · 大小合计、ABI 分类与 ZIP 工具（`zipfile`）交叉一致；
  · STORED native 不被漏算；
  · 同文件重复运行输出一致；
  · 归档内危险路径**不落盘**、不执行；
  · 未知平台文件单独列出；
  · 重复条目 / 畸形 ELF 明确失败（退出码 1）。
"""
import io
import json
import os
import shutil
import struct
import sys
import tempfile
import unittest
import zipfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(REPO_ROOT, "tools"))

import audit_apk_size as audit  # noqa: E402


def elf_bytes(machine, elf_class=2, endian=1):
    """合成一个只有 ELF 头的字节串（足够 parse_elf_header 判定）。"""
    ident = b"\x7fELF" + bytes([elf_class, endian, 1, 0]) + b"\x00" * 8
    rest = struct.pack(("<" if endian == 1 else ">") + "HH", 2, machine)
    return ident + rest + b"\x00" * 32


def make_imy(entries):
    """合成一个 IMY（就是 ZIP）。entries: list[(name, bytes)]。"""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, data in entries:
            zf.writestr(name, data)
    return buf.getvalue()


def make_apk(path, entries, compression=zipfile.ZIP_DEFLATED):
    """合成 APK。entries: list[(name, bytes)]。"""
    with zipfile.ZipFile(path, "w", compression) as zf:
        for name, data in entries:
            zf.writestr(name, data)
    return path


class AuditApkSizeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="t48-")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _apk(self, entries, name="app.apk", compression=zipfile.ZIP_DEFLATED):
        return make_apk(os.path.join(self.tmp, name), entries, compression)

    # ---------------------------------------------------------------- 分类

    def test_categories_match_zip_compress_size(self):
        apk = self._apk([
            ("classes.dex", b"D" * 1000),
            ("classes2.dex", b"D" * 500),
            ("resources.arsc", b"R" * 300),
            ("res/drawable/ic.png", b"P" * 200),
            ("AndroidManifest.xml", b"M" * 50),
            ("lib/arm64-v8a/libfoo.so", elf_bytes(183)),
            ("assets/chaquopy/stdlib-arm64-v8a.imy", make_imy([("a.py", b"x" * 10)])),
            ("META-INF/MANIFEST.MF", b"X" * 40),
        ])
        report = audit.audit(apk)
        with zipfile.ZipFile(apk) as zf:
            sizes = {i.filename: i.compress_size for i in zf.infolist()}

        self.assertEqual(sizes["classes.dex"] + sizes["classes2.dex"],
                         report["categories"]["dex"]["compressedBytes"])
        self.assertEqual(
            sizes["resources.arsc"] + sizes["res/drawable/ic.png"]
            + sizes["AndroidManifest.xml"],
            report["categories"]["androidResources"]["compressedBytes"])
        self.assertEqual(sizes["lib/arm64-v8a/libfoo.so"],
                         report["categories"]["native"]["compressedBytes"])
        self.assertEqual(sizes["assets/chaquopy/stdlib-arm64-v8a.imy"],
                         report["categories"]["chaquopy"]["compressedBytes"])
        self.assertEqual(sizes["META-INF/MANIFEST.MF"],
                         report["categories"]["other"]["compressedBytes"])

    def test_total_equals_file_size(self):
        apk = self._apk([("classes.dex", b"D" * 4096), ("lib/x86_64/liba.so", elf_bytes(62))])
        report = audit.audit(apk)
        self.assertEqual([], audit._check_invariants(report))
        self.assertEqual(os.path.getsize(apk), report["categoryBytesPlusOverhead"])
        self.assertGreaterEqual(report["containerOverhead"]["bytes"], 0)

    def test_stored_native_is_counted_at_file_size(self):
        # STORED 时 compress_size == file_size，绝不能漏算
        payload = elf_bytes(183) + b"\x00" * 4096
        apk = self._apk([("lib/arm64-v8a/libstored.so", payload)],
                        compression=zipfile.ZIP_STORED)
        report = audit.audit(apk)
        self.assertEqual(len(payload), report["categories"]["native"]["compressedBytes"])
        with zipfile.ZipFile(apk) as zf:
            info = zf.getinfo("lib/arm64-v8a/libstored.so")
            self.assertEqual(zipfile.ZIP_STORED, info.compress_type)
            self.assertEqual(info.compress_size, info.file_size)

    # ---------------------------------------------------------------- ABI

    def test_native_by_abi_dir_and_elf_machine_agree(self):
        apk = self._apk([
            ("lib/arm64-v8a/liba.so", elf_bytes(183)),
            ("lib/arm64-v8a/libb.so", elf_bytes(183)),
            ("lib/x86_64/libc.so", elf_bytes(62)),
        ])
        report = audit.audit(apk)
        self.assertEqual(2, report["nativeByAbiDir"]["arm64-v8a"]["files"])
        self.assertEqual(1, report["nativeByAbiDir"]["x86_64"]["files"])
        self.assertEqual([], report["elfProblems"])
        self.assertEqual([], report["findings"])

    def test_abi_dir_mismatch_is_reported(self):
        apk = self._apk([("lib/arm64-v8a/libwrong.so", elf_bytes(62))])
        report = audit.audit(apk)
        kinds = [f["kind"] for f in report["findings"]]
        self.assertIn("abi-mismatch", kinds)
        self.assertEqual(1, len(report["elfProblems"]))

    def test_unknown_platform_binary_is_listed_separately(self):
        # 未知 e_machine（例如 RISC-V 243）——单独列出，不混进已知 ABI
        apk = self._apk([("lib/arm64-v8a/libweird.so", elf_bytes(243))])
        report = audit.audit(apk)
        self.assertTrue(any("unknown machine" in p["reason"] for p in report["elfProblems"]))
        self.assertEqual([], report["findings"])  # 未知平台不是「失败」，只是列出

    # ---------------------------------------------------------------- IMY

    def test_nested_imy_reports_inner_abi(self):
        imy = make_imy([
            ("chaquopy/libarm64.so", elf_bytes(183)),
            ("chaquopy/module.py", b"print(1)"),
        ])
        apk = self._apk([("assets/chaquopy/requirements-arm64-v8a.imy", imy)])
        report = audit.audit(apk)
        self.assertEqual(1, len(report["nestedImy"]))
        inner = report["nestedImy"][0]
        self.assertEqual(2, inner["entries"])
        self.assertEqual(1, len(inner["soFiles"]))
        self.assertEqual("arm64-v8a", inner["soFiles"][0]["abi"])

    def test_nested_imy_malformed_fails(self):
        apk = self._apk([("assets/chaquopy/broken.imy", b"not a zip at all")])
        report = audit.audit(apk)
        kinds = [f["kind"] for f in report["findings"]]
        self.assertIn("malformed-nested-archive", kinds)

    # ---------------------------------------------------------------- 安全

    def test_dangerous_paths_are_recorded_and_never_extracted(self):
        apk = self._apk([
            ("../../evil.sh", b"#!/bin/sh\nrm -rf /\n"),
            ("/abs/evil.so", elf_bytes(183)),
            ("C:/evil.dll", b"MZ"),
        ])
        report = audit.audit(apk)
        reasons = sorted(d["reason"] for d in report["dangerousEntries"])
        self.assertEqual(["absolute path", "drive-letter path", "parent traversal"], reasons)
        # 只记录，不落盘：临时目录里除 APK 外没有新文件
        self.assertEqual(["app.apk"], sorted(os.listdir(self.tmp)))

    def test_duplicate_entries_are_flagged(self):
        path = os.path.join(self.tmp, "dup.apk")
        # zipfile 允许写重名条目
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
            zf.writestr("classes.dex", b"A" * 100)
            zf.writestr("classes.dex", b"B" * 200)
        report = audit.audit(path)
        self.assertTrue(any(f["kind"] == "duplicate-entry" for f in report["findings"]))

    def test_malformed_elf_is_flagged(self):
        apk = self._apk([("lib/arm64-v8a/libbad.so", b"not an elf")])
        report = audit.audit(apk)
        self.assertTrue(any(f["kind"] == "malformed-elf" for f in report["findings"]))
        self.assertEqual(1, len(report["elfProblems"]))

    # ---------------------------------------------------------------- 可重复

    def test_same_input_gives_identical_output(self):
        apk = self._apk([
            ("classes.dex", b"D" * 777),
            ("lib/x86_64/liba.so", elf_bytes(62)),
            ("assets/chaquopy/x.imy", make_imy([("m.py", b"y" * 5)])),
        ])
        first = json.dumps(audit.audit(apk), sort_keys=True)
        second = json.dumps(audit.audit(apk), sort_keys=True)
        self.assertEqual(first, second)
        # 输出里不含时间戳/绝对临时路径这类不确定内容
        self.assertNotIn(self.tmp.replace("\\", "/"), first)

    # ---------------------------------------------------------------- CLI

    def test_cli_exit_codes(self):
        ok = self._apk([("classes.dex", b"D" * 100)], name="ok.apk")
        self.assertEqual(0, audit.main(["--apk", ok, "--quiet"]))
        bad = self._apk([("lib/arm64-v8a/libbad.so", b"nope")], name="bad.apk")
        self.assertEqual(1, audit.main(["--apk", bad, "--quiet"]))
        self.assertEqual(2, audit.main(["--apk", os.path.join(self.tmp, "missing.apk"),
                                        "--quiet"]))

    def test_cli_writes_json_output(self):
        apk = self._apk([("classes.dex", b"D" * 100)])
        out = os.path.join(self.tmp, "report.json")
        self.assertEqual(0, audit.main(["--apk", apk, "--output", out, "--quiet"]))
        with open(out, encoding="utf-8") as fh:
            data = json.load(fh)
        self.assertEqual(1, data["schemaVersion"])
        self.assertIn("categories", data)


    # ---------------------------------------------------------------- T49 单 ABI

    def test_expected_abi_accepts_single_abi_package(self):
        apk = self._apk([
            ("lib/arm64-v8a/liba.so", elf_bytes(183)),
            ("assets/chaquopy/stdlib-arm64-v8a.imy",
             make_imy([("chaquopy/libx.so", elf_bytes(183))])),
        ])
        report = audit.audit(apk)
        self.assertEqual([], audit.check_expected_abi(report, "arm64-v8a"))

    def test_expected_abi_rejects_foreign_lib_dir(self):
        apk = self._apk([
            ("lib/arm64-v8a/liba.so", elf_bytes(183)),
            ("lib/x86_64/libb.so", elf_bytes(62)),
        ])
        report = audit.audit(apk)
        problems = audit.check_expected_abi(report, "arm64-v8a")
        self.assertTrue(any(p["kind"] == "foreign-lib-abi-dir" for p in problems))

    def test_expected_abi_rejects_foreign_content_inside_imy(self):
        # lib/ 全对，但 Chaquopy 归档里藏着另一套架构的原生库 —— 只看 lib/ 会漏
        apk = self._apk([
            ("lib/arm64-v8a/liba.so", elf_bytes(183)),
            ("assets/chaquopy/requirements-arm64-v8a.imy",
             make_imy([("chaquopy/libx.so", elf_bytes(62))])),
        ])
        report = audit.audit(apk)
        problems = audit.check_expected_abi(report, "arm64-v8a")
        self.assertTrue(any(p["kind"] == "foreign-imy-native" for p in problems))

    def test_expected_abi_rejects_foreign_imy_name(self):
        apk = self._apk([
            ("lib/arm64-v8a/liba.so", elf_bytes(183)),
            ("assets/chaquopy/stdlib-x86_64.imy", make_imy([("m.py", b"x")])),
        ])
        report = audit.audit(apk)
        problems = audit.check_expected_abi(report, "arm64-v8a")
        self.assertTrue(any(p["kind"] == "foreign-imy-name" for p in problems))

    def test_expected_abi_rejects_unknown_platform_native(self):
        apk = self._apk([
            ("lib/arm64-v8a/liba.so", elf_bytes(183)),
            ("assets/chaquopy/x-arm64-v8a.imy",
             make_imy([("chaquopy/libw.so", elf_bytes(243))])),
        ])
        report = audit.audit(apk)
        problems = audit.check_expected_abi(report, "arm64-v8a")
        self.assertTrue(any(p["kind"] == "unknown-imy-native" for p in problems))

    def test_cli_expected_abi_exit_code(self):
        ok = self._apk([("lib/arm64-v8a/liba.so", elf_bytes(183))], name="abi-ok.apk")
        self.assertEqual(0, audit.main(["--apk", ok, "--expected-abi", "arm64-v8a",
                                        "--quiet"]))
        self.assertEqual(1, audit.main(["--apk", ok, "--expected-abi", "x86_64",
                                        "--quiet"]))


    def test_expected_abi_token_matching_does_not_confuse_x86_with_x86_64(self):
        # 回归：stdlib-x86_64.imy 不能被当成「含 x86 架构内容」
        apk = self._apk([
            ("lib/x86_64/liba.so", elf_bytes(62)),
            ("assets/chaquopy/stdlib-x86_64.imy", make_imy([("m.py", b"x")])),
            ("assets/chaquopy/requirements-x86_64.imy", make_imy([("m.py", b"x")])),
        ])
        report = audit.audit(apk)
        self.assertEqual([], audit.check_expected_abi(report, "x86_64"))

    def test_expected_abi_token_matching_still_catches_real_foreign_names(self):
        apk = self._apk([
            ("lib/arm64-v8a/liba.so", elf_bytes(183)),
            ("assets/chaquopy/stdlib-x86_64.imy", make_imy([("m.py", b"x")])),
        ])
        report = audit.audit(apk)
        problems = audit.check_expected_abi(report, "arm64-v8a")
        self.assertTrue(any(p["kind"] == "foreign-imy-name" for p in problems))

    def test_token_matching_is_boundary_aware(self):
        self.assertTrue(audit._token_matches("stdlib-arm64-v8a.imy", "arm64-v8a"))
        self.assertTrue(audit._token_matches("app-x86_64-release.apk", "x86_64"))
        self.assertFalse(audit._token_matches("stdlib-x86_64.imy", "x86"))
        self.assertFalse(audit._token_matches("stdlib-arm64v8a.imy", "arm64"))
        self.assertFalse(audit._token_matches("libfmod.so", "x86"))
        self.assertTrue(audit._token_matches("lib/arm64/libfmod.so", "arm64"))
        self.assertTrue(audit._token_matches("stdlib-arm64-v8a.imy", "arm64"))


    # ---------------------------------------------------------------- T53 资源保留

    def test_required_resource_name_present(self):
        apk = self._apk([("resources.arsc", b"\x02\x00\x0c\x00\x61\x62\x6f\x75\x74\x6c\x69\x62\x72\x61\x72\x69\x65\x73\x00\x72\x61\x77\x00")])
        report = audit.audit(apk)
        self.assertEqual([], audit.check_required_resource_names(apk, ["raw/aboutlibraries"]))

    def test_required_resource_name_missing(self):
        apk = self._apk([("resources.arsc", b"\x02\x00\x0c\x00\x73\x6f\x6d\x65\x74\x68\x69\x6e\x67\x65\x6c\x73\x65\x00\x72\x61\x77\x00")])
        problems = audit.check_required_resource_names(apk, ["raw/aboutlibraries"])
        self.assertEqual(1, len(problems))
        self.assertEqual("missing-resource-name", problems[0]["kind"])

    def test_missing_arsc_is_reported(self):
        apk = self._apk([("classes.dex", b"D" * 10)])
        problems = audit.check_required_resource_names(apk, ["raw/aboutlibraries"])
        self.assertEqual("missing-resources-arsc", problems[0]["kind"])

    def test_cli_require_resource_name_exit_code(self):
        ok = self._apk([("resources.arsc", b"aboutlibraries")], name="res-ok.apk")
        self.assertEqual(0, audit.main(["--apk", ok, "--require-resource-name",
                                        "raw/aboutlibraries", "--quiet"]))
        self.assertEqual(1, audit.main(["--apk", ok, "--require-resource-name",
                                        "raw/nope", "--quiet"]))


    # ---------------------------------------------------------------- T54 对照

    def test_compare_reports_per_category_deltas(self):
        before = self._apk([("classes.dex", b"D" * 4000),
                            ("lib/arm64-v8a/liba.so", elf_bytes(183) + b"\x00" * 4000)],
                           name="cmp-before.apk")
        after = self._apk([("classes.dex", b"D" * 2000),
                           ("lib/arm64-v8a/liba.so", elf_bytes(183) + b"\x00" * 1000)],
                          name="cmp-after.apk")
        rb = audit.audit(before)
        ra = audit.audit(after)
        report = audit.compare_reports(rb, ra)
        self.assertEqual(rb["apk"]["bytes"], report["apk"]["bytesBefore"])
        self.assertEqual(ra["apk"]["bytes"], report["apk"]["bytesAfter"])
        self.assertLess(report["apk"]["deltaBytes"], 0)
        self.assertLess(report["categories"]["dex"]["deltaBytes"], 0)
        self.assertLess(report["categories"]["native"]["deltaBytes"], 0)
        # 分类差额与总量差额自洽
        total = sum(v["deltaBytes"] for v in report["categories"].values())
        total += report["containerOverhead"]["bytesAfter"] - report["containerOverhead"]["bytesBefore"]
        self.assertEqual(report["apk"]["deltaBytes"], total)

    def test_compare_cli_writes_output_and_ignores_apk(self):
        before = self._apk([("classes.dex", b"D" * 300)], name="cli-before.apk")
        after = self._apk([("classes.dex", b"D" * 100)], name="cli-after.apk")
        rb = os.path.join(self.tmp, "rb.json")
        ra = os.path.join(self.tmp, "ra.json")
        self.assertEqual(0, audit.main(["--apk", before, "--output", rb, "--quiet"]))
        self.assertEqual(0, audit.main(["--apk", after, "--output", ra, "--quiet"]))
        out = os.path.join(self.tmp, "cmp.json")
        self.assertEqual(0, audit.main(["--compare", rb, ra, "--output", out, "--quiet"]))
        with open(out, encoding="utf-8") as fh:
            data = json.load(fh)
        self.assertEqual(1, data["schemaVersion"])
        self.assertIn("categories", data)
        # 缺 --apk 也没关系（--compare 不需要 APK）
        self.assertEqual(0, audit.main(["--compare", rb, ra, "--quiet"]))
        # 两者都缺 → 用法错误
        self.assertEqual(2, audit.main(["--quiet"]))
        # 文件不存在 → IO 错误
        self.assertEqual(2, audit.main(["--compare", rb, os.path.join(self.tmp, "no.json"),
                                        "--quiet"]))


    # ---------------------------------------------------------------- T35/T36 DEX 键名

    def test_required_dex_string_present(self):
        apk = self._apk([("classes.dex", b"dexmagic" + b"schemaVersion")])
        self.assertEqual([], audit.check_required_dex_strings(apk, ["schemaVersion"]))

    def test_required_dex_string_missing(self):
        apk = self._apk([("classes.dex", b"somethingelse")])
        problems = audit.check_required_dex_strings(apk, ["schemaVersion"])
        self.assertEqual("missing-dex-string", problems[0]["kind"])

    def test_missing_dex_is_reported(self):
        apk = self._apk([("resources.arsc", b"x")])
        problems = audit.check_required_dex_strings(apk, ["schemaVersion"])
        self.assertEqual("missing-dex", problems[0]["kind"])

    def test_cli_require_dex_string_exit_code(self):
        ok = self._apk([("classes.dex", b"savedAt")], name="dex-ok.apk")
        self.assertEqual(0, audit.main(["--apk", ok, "--require-dex-string", "savedAt",
                                        "--quiet"]))
        self.assertEqual(1, audit.main(["--apk", ok, "--require-dex-string", "nope",
                                        "--quiet"]))


if __name__ == "__main__":
    unittest.main()
