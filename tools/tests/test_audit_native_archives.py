"""Self-contained ABI archive checks; no APK build, device or external fixtures."""
import io
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import audit_native_abi as audit

DECODER_EXPORTS = [
    "decode_atc_rgb4", "decode_atc_rgba8", "decode_etc1", "decode_etc2",
    "decode_etc2a1", "decode_etc2a8", "decode_eacr", "decode_eacr_signed",
    "decode_eacrg", "decode_eacrg_signed", "decode_bc1", "decode_bc3", "decode_bc4",
    "decode_bc5", "decode_bc6", "decode_bc7", "decode_astc", "decode_pvrtc",
    "crunch_unpack_level", "unity_crunch_unpack_level",
]


def elf(machine=183, exports=()):
    """ELF metadata fixture: one PT_LOAD and a dynamic symbol table; never executed."""
    strings = bytearray(b"\0")
    symbols = bytearray()
    for name in exports:
        offset = len(strings)
        strings.extend(name.encode("ascii") + b"\0")
        symbols.extend(struct.pack("<IBBHQQ", offset, 0x12, 0, 1, 0, 0))
    string_offset = 120
    symbol_offset = string_offset + len(strings)
    section_offset = symbol_offset + len(symbols)
    size = section_offset + 128
    ident = b"\x7fELF\x02\x01\x01" + bytes(9)
    header = ident + struct.pack("<HHIQQQIHHHHHH", 3, machine, 1, 0, 64,
                                 section_offset, 0, 64, 56, 1, 64, 2, 0)
    load = struct.pack("<IIQQQQQQ", 1, 4, 0, 0, 0, size, size, 0x4000)
    string_section = struct.pack("<IIQQQQIIQQ", 0, 3, 0, 0, string_offset,
                                  len(strings), 0, 0, 1, 0)
    symbol_section = struct.pack("<IIQQQQIIQQ", 0, 11, 0, 0, symbol_offset,
                                  len(symbols), 0, 0, 8, 24)
    return header + load + strings + symbols + string_section + symbol_section


def archive(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as zipped:
        for name, content in entries.items():
            zipped.writestr(name, content)
    return buffer.getvalue()


class NativeArchiveAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="bd2-native-archive-")
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / "sample.apk"
        self.entries = {
            "lib/arm64-v8a/libpython3.8.so": elf(),
            "lib/arm64-v8a/libchaquopy_java.so": elf(exports=["JNI_OnLoad"]),
            "lib/arm64-v8a/libastcenc.so": elf(),
            "lib/arm64-v8a/libtexture2ddecoder.so": elf(exports=DECODER_EXPORTS),
            "assets/chaquopy/bootstrap-native/arm64-v8a/zlib.cpython-38.so": elf(),
            "assets/chaquopy/stdlib-arm64-v8a.imy": archive({"_asyncio.so": elf()}),
            "assets/chaquopy/requirements-arm64-v8a.imy": archive({"_brotli.so": elf()}),
            "assets/chaquopy/requirements-common.imy": archive({"package/__init__.py": b"pass\n"}),
            "assets/chaquopy/bootstrap.imy": archive({"bootstrap.py": b"pass\n"}),
            "assets/chaquopy/build.json": b"{}",
        }

    def inspect(self):
        self.apk.write_bytes(archive(self.entries))
        return audit.audit(str(self.apk), "arm64-v8a", require_16k=False)

    def assert_rejected(self, asset):
        result = self.inspect()
        self.assertEqual("FAIL", result["result"], result)
        self.assertTrue(any(asset in failure for failure in result["failures"]), result)

    def test_valid_archives_pass(self):
        result = self.inspect()
        self.assertEqual("PASS", result["result"], result["failures"])

    def test_single_abi_requirements_can_use_the_common_archive(self):
        specific = "assets/chaquopy/requirements-arm64-v8a.imy"
        common = "assets/chaquopy/requirements-common.imy"
        self.entries[specific] = archive({})
        self.entries[common] = archive({"_brotli.so": elf()})
        result = self.inspect()
        self.assertEqual("PASS", result["result"], result["failures"])
        self.assertEqual(0, result["chaquopy"]["archives"][specific]["files"])
        self.assertEqual(1, result["chaquopy"]["archives"][common]["nativeFiles"])

    def test_wrong_arch_inside_common_requirements_is_rejected(self):
        name = "assets/chaquopy/requirements-common.imy"
        self.entries[name] = archive({"_brotli.so": elf(machine=62)})
        self.assert_rejected(name)

    def test_missing_common_requirements_is_rejected(self):
        name = "assets/chaquopy/requirements-common.imy"
        del self.entries[name]
        self.assert_rejected(name)

    def test_corrupt_common_requirements_is_rejected(self):
        name = "assets/chaquopy/requirements-common.imy"
        self.entries[name] = b"not a zip archive"
        self.assert_rejected(name)

    def test_empty_requirements_pair_is_rejected(self):
        name = "assets/chaquopy/requirements-common.imy"
        self.entries[name] = archive({})
        self.entries["assets/chaquopy/requirements-arm64-v8a.imy"] = archive({})
        self.assert_rejected(name)

    def test_corrupt_requirements_is_rejected(self):
        name = "assets/chaquopy/requirements-arm64-v8a.imy"
        self.entries[name] = b"not a zip archive"
        self.assert_rejected(name)

    def test_empty_stdlib_is_rejected(self):
        name = "assets/chaquopy/stdlib-arm64-v8a.imy"
        self.entries[name] = archive({})
        self.assert_rejected(name)

    def test_wrong_arch_inside_requirements_is_rejected(self):
        name = "assets/chaquopy/requirements-arm64-v8a.imy"
        self.entries[name] = archive({"_brotli.so": elf(machine=62)})
        self.assert_rejected(name)

    def test_truncated_elf_inside_stdlib_is_a_reported_failure(self):
        name = "assets/chaquopy/stdlib-arm64-v8a.imy"
        self.entries[name] = archive({"_asyncio.so": b"\x7fELF"})
        self.assert_rejected(name)

    def test_corrupt_bootstrap_is_rejected(self):
        name = "assets/chaquopy/bootstrap.imy"
        self.entries[name] = b"broken"
        self.assert_rejected(name)

    def test_crc_failure_is_rejected_even_if_outer_entry_exists(self):
        name = "assets/chaquopy/requirements-arm64-v8a.imy"
        data = bytearray(self.entries[name])
        # Damage only compressed payload: entry names and ZIP directory stay intact.
        name_length, extra_length = struct.unpack_from("<HH", data, 26)
        data[30 + name_length + extra_length] ^= 0xFF
        self.entries[name] = bytes(data)
        self.assert_rejected(name)


if __name__ == "__main__":
    unittest.main()
