"""tools/audit_native_abi.py 的证伪测试：用真实 APK 构造错误变体，确认每类检查都会失败。

依赖已构建的 release APK（app/build/outputs/apk/release/app-<abi>-release.apk）；不存在时 skip，
并把 skip 如实计入报告（skip 不是 PASS）。
"""
import io
import os
import sys
import unittest
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import audit_native_abi as audit  # noqa: E402

APK = {abi: os.path.join(ROOT, "app", "build", "outputs", "apk", "release", f"app-{abi}-release.apk")
       for abi in ("arm64-v8a", "x86_64")}


def rewrite(src, dst, drop=(), replace=None):
    replace = replace or {}
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
        for info in zin.infolist():
            if info.filename in drop:
                continue
            data = replace.get(info.filename, zin.read(info.filename))
            zout.writestr(info, data, compress_type=info.compress_type)


@unittest.skipUnless(all(os.path.isfile(p) for p in APK.values()), "release APKs not built")
class AuditNativeAbiTest(unittest.TestCase):
    def setUp(self):
        self.tmp = os.path.join(ROOT, "local", "abi-audit", "falsify")
        os.makedirs(self.tmp, exist_ok=True)

    def run_audit(self, apk, abi):
        return audit.audit(apk, abi, require_16k=False)

    def test_real_apks_pass(self):
        for abi, apk in APK.items():
            r = self.run_audit(apk, abi)
            self.assertEqual("PASS", r["result"], r["failures"])
            self.assertEqual(20, r["libs"]["libtexture2ddecoder.so"]["requiredExportsPresent"])

    def test_wrong_abi_declared(self):
        r = self.run_audit(APK["arm64-v8a"], "x86_64")
        self.assertEqual("FAIL", r["result"])
        self.assertTrue(any("lib/x86_64/ missing" in f for f in r["failures"]))

    def test_wrong_arch_library_detected(self):
        with zipfile.ZipFile(APK["x86_64"]) as z:
            x64 = z.read("lib/x86_64/libtexture2ddecoder.so")
        dst = os.path.join(self.tmp, "wrong-arch.apk")
        rewrite(APK["arm64-v8a"], dst, replace={"lib/arm64-v8a/libtexture2ddecoder.so": x64})
        r = self.run_audit(dst, "arm64-v8a")
        self.assertTrue(any("libtexture2ddecoder.so: e_machine=62" in f for f in r["failures"]), r["failures"])

    def test_missing_required_library(self):
        dst = os.path.join(self.tmp, "missing-lib.apk")
        rewrite(APK["arm64-v8a"], dst, drop={"lib/arm64-v8a/libtexture2ddecoder.so"})
        r = self.run_audit(dst, "arm64-v8a")
        self.assertTrue(any("libtexture2ddecoder.so missing" in f for f in r["failures"]), r["failures"])

    def test_missing_export_detected(self):
        with zipfile.ZipFile(APK["arm64-v8a"]) as z:
            so = bytearray(z.read("lib/arm64-v8a/libtexture2ddecoder.so"))
        i = so.find(b"decode_bc1\0")
        self.assertGreater(i, 0)
        so[i:i + 10] = b"decode_xx1"
        dst = os.path.join(self.tmp, "missing-export.apk")
        rewrite(APK["arm64-v8a"], dst, replace={"lib/arm64-v8a/libtexture2ddecoder.so": bytes(so)})
        r = self.run_audit(dst, "arm64-v8a")
        self.assertTrue(any("missing exports ['decode_bc1']" in f for f in r["failures"]), r["failures"])

    def test_unresolved_needed_detected(self):
        dst = os.path.join(self.tmp, "missing-dep.apk")
        # Chaquopy 16：libcrypto_chaquopy.so 只是 stub，真正被 DT_NEEDED 的是 libcrypto_python.so。
        rewrite(APK["arm64-v8a"], dst, drop={"lib/arm64-v8a/libcrypto_python.so"})
        r = self.run_audit(dst, "arm64-v8a")
        self.assertTrue(any("DT_NEEDED libcrypto_python.so" in f for f in r["failures"]), r["failures"])

    def test_missing_chaquopy_asset(self):
        dst = os.path.join(self.tmp, "missing-stdlib.apk")
        rewrite(APK["arm64-v8a"], dst, drop={"assets/chaquopy/stdlib-arm64-v8a.imy"})
        r = self.run_audit(dst, "arm64-v8a")
        self.assertTrue(any("stdlib-arm64-v8a.imy missing" in f for f in r["failures"]), r["failures"])

    def test_require_16k_turns_warning_into_failure(self):
        r = audit.audit(APK["arm64-v8a"], "arm64-v8a", require_16k=True)
        self.assertEqual("FAIL", r["result"])
        self.assertTrue(any("< 16 KB" in f for f in r["failures"]))


if __name__ == "__main__":
    unittest.main()
