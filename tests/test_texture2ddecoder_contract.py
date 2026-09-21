"""Return-value contract of the real ctypes wrapper, without loading a host DLL."""
import ctypes
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import MagicMock, patch

WRAPPER = Path(__file__).resolve().parents[1] / "app/src/main/python/texture2ddecoder.py"


def load_wrapper(library):
    spec = importlib.util.spec_from_file_location("bd2_texture_contract_fixture", WRAPPER)
    module = importlib.util.module_from_spec(spec)
    with patch("ctypes.cdll.LoadLibrary", return_value=library):
        spec.loader.exec_module(module)
    return module


class TestTexture2DDecoderContract(unittest.TestCase):
    def setUp(self):
        self.library = MagicMock()
        self.wrapper = load_wrapper(self.library)

    def test_generic_decoder_success(self):
        self.library.decode_bc1.return_value = 1
        self.assertEqual(self.wrapper.decode_bc1(bytes(8), 4, 4), bytes(64))
        self.library.decode_bc1.assert_called_once()

    def test_generic_decoder_failure(self):
        for result in (0, -1, 2):
            with self.subTest(result=result):
                self.library.decode_bc1.return_value = result
                with self.assertRaisesRegex(RuntimeError, f"return code {result}"):
                    self.wrapper.decode_bc1(bytes(8), 4, 4)

    def test_astc_decoder_success(self):
        self.library.decode_astc.return_value = 1
        self.assertEqual(self.wrapper.decode_astc(bytes(16), 4, 4, 4, 4), bytes(64))
        self.library.decode_astc.assert_called_once()

    def test_astc_decoder_failure(self):
        for result in (0, -1, 2):
            with self.subTest(result=result):
                self.library.decode_astc.return_value = result
                with self.assertRaisesRegex(RuntimeError, f"return code {result}"):
                    self.wrapper.decode_astc(bytes(16), 4, 4, 4, 4)

    def test_bgra_to_rgba_channel_swap(self):
        def fill(*args):
            # Generic output pointer is fourth; ASTC output pointer is last.
            out = ctypes.cast(args[-1], ctypes.POINTER(ctypes.c_ubyte))
            for i, value in enumerate((1, 2, 3, 4)):
                out[i] = value
            return 1
        self.library.decode_bc1.side_effect = fill
        self.library.decode_astc.side_effect = fill
        self.assertEqual(self.wrapper.decode_bc1(bytes(8), 1, 1), bytes((3, 2, 1, 4)))
        self.assertEqual(self.wrapper.decode_astc(bytes(16), 1, 1, 4, 4), bytes((3, 2, 1, 4)))

    def test_missing_library(self):
        spec = importlib.util.spec_from_file_location("bd2_missing_texture_fixture", WRAPPER)
        wrapper = importlib.util.module_from_spec(spec)
        with patch("ctypes.cdll.LoadLibrary", side_effect=OSError("missing")):
            spec.loader.exec_module(wrapper)
        self.assertIsNone(wrapper._lib)
        for call in (
            lambda: wrapper.decode_bc1(bytes(8), 4, 4),
            lambda: wrapper.decode_astc(bytes(16), 4, 4, 4, 4),
            lambda: wrapper.unpack_crunch(b"fixture"),
            lambda: wrapper.unpack_unity_crunch(b"fixture"),
        ):
            with self.assertRaisesRegex(RuntimeError, "not available on this device"):
                call()

    def test_crunch_success_uses_boolean_and_returns_bytes(self):
        payload = b"decoded fixture"
        buffer = ctypes.create_string_buffer(payload)
        def unpack(data, size, level, out, out_size):
            ctypes.cast(out, ctypes.POINTER(ctypes.c_void_p))[0] = ctypes.addressof(buffer)
            ctypes.cast(out_size, ctypes.POINTER(ctypes.c_uint))[0] = len(payload)
            return True
        for symbol, function in (
            ("crunch_unpack_level", self.wrapper.unpack_crunch),
            ("unity_crunch_unpack_level", self.wrapper.unpack_unity_crunch),
        ):
            with self.subTest(symbol=symbol):
                getattr(self.library, symbol).side_effect = unpack
                self.assertEqual(function(b"fixture"), payload)

    def test_crunch_failure_does_not_read_output(self):
        for symbol, function in (
            ("crunch_unpack_level", self.wrapper.unpack_crunch),
            ("unity_crunch_unpack_level", self.wrapper.unpack_unity_crunch),
        ):
            with self.subTest(symbol=symbol):
                getattr(self.library, symbol).return_value = False
                with patch("ctypes.string_at") as read_output:
                    with self.assertRaisesRegex(RuntimeError, "failed"):
                        function(b"fixture")
                    read_output.assert_not_called()


if __name__ == "__main__":
    unittest.main()
