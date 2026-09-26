"""T13 兼容探针的 Python 侧：只做确定性、离线的最小调用。"""
import sys


def run():
    import lz4.frame
    import PIL
    from PIL import Image

    payload = b"bd2-compat-probe" * 64
    assert lz4.frame.decompress(lz4.frame.compress(payload)) == payload

    img = Image.new("RGBA", (4, 4), (255, 255, 255, 255))
    assert img.tobytes() == b"\xff" * 64

    from java import jclass
    abi = jclass("com.chaquo.python.android.AndroidPlatform").ABI

    return "%d.%d|%s|lz4-ok|PIL-%s" % (sys.version_info[0], sys.version_info[1], abi, PIL.__version__)
