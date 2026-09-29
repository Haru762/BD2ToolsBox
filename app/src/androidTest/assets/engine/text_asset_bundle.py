"""Deterministic, self-authored UnityFS fixture; no game data or UnityPy writer used.

UnityFS v6, one uncompressed block containing a v17 SerializedFile with one
TextAsset (class 49, path ID 1). The asset has a name and an aligned UTF-8 script.
Only the device test APK includes this helper.
"""
import struct


def _align(data, boundary):
    return data + b"\0" * (-len(data) % boundary)


def _string(data):
    return _align(struct.pack("<I", len(data)) + data, 4)


def build_bundle():
    payload = _string(b"t18-text.txt") + _string(b"T18 conversion fixture.\n")
    # Unity version, Android target, no embedded type tree, one TextAsset type.
    meta = b"2018.4.0f1\0" + struct.pack("<i?i", 13, False, 1)
    meta += struct.pack("<i?h", 49, False, -1) + b"\0" * 16
    meta += struct.pack("<i", 1)  # object count
    meta = _align(meta, 4)
    meta += struct.pack("<qIIi", 1, 0, len(payload), 0)
    meta += struct.pack("<ii", 0, 0) + b"\0"  # no scripts/externals, empty user info
    data_offset = (20 + len(meta) + 15) // 16 * 16
    serialized = struct.pack(">IIII", len(meta), data_offset + len(payload), 17, data_offset)
    serialized = _align(serialized + b"\0" * 4 + meta, 16) + payload

    # Uncompressed block and its one serialized-file directory entry.
    info = b"\0" * 16 + struct.pack(">IIIH", 1, len(serialized), len(serialized), 0)
    info += struct.pack(">IqqI", 1, 0, len(serialized), 4) + b"CAB-t18\0"
    prefix = b"UnityFS\0" + struct.pack(">I", 6) + b"5.x.x\0" + b"2018.4.0f1\0"
    size = len(prefix) + 20 + len(info) + len(serialized)
    return prefix + struct.pack(">qIII", size, len(info), len(info), 0x40) + info + serialized
