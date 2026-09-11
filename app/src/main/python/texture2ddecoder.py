"""libtexture2ddecoder 的 ctypes 绑定。

Chaquopy 编不了 K0lb3 官方 pip 包的 C 扩展，这里直接 ctypes 调 .so。
函数名是硬契约：vendored 的 UnityPy 按这些名字找解码器，一个都不能少、
也不能多（UnityPy 会对存在的函数尝试调用）。

绝大多数符号共用一个签名：`(data, width, height, out[, 额外参数...])`，
由 _make_decoder 统一生成；decode_astc 是例外（out 在最后），单独实现。

.so 懒加载：x86_64 模拟器镜像不带它，导入必须照常成功 —— repack/编码
路径不解码纹理，只有真正调用解码函数时才要求库存在。
"""
import ctypes

try:
    _lib = ctypes.cdll.LoadLibrary("libtexture2ddecoder.so")
except OSError:
    _lib = None


def _missing(name):
    raise RuntimeError(f"{name}: libtexture2ddecoder.so not available on this device")


# Python 标量 → ctypes 类型。调用方（vendored 的 UnityPy）传的是原生
# int/bool，而 ctypes 只认带 from_param 的类型，直接 type(a) 会 TypeError。
# bool 走 c_int 更稳：C 侧无论是 bool 还是 int，寄存器里放 0/1 都对。
_SCALAR_TO_CTYPES = {int: ctypes.c_int, bool: ctypes.c_int, float: ctypes.c_double}


def _argtype(value):
    """取一个额外参数该用的 ctypes 类型（已是 ctypes 对象的原样返回）。"""
    return _SCALAR_TO_CTYPES.get(type(value), type(value))


def _make_decoder(symbol):
    """生成一个 (data, width, height[, 额外参数...]) → RGBA bytes 的解码函数。

    C 侧签名统一为 (const void* data, long w, long h, void* out [, extra...])，
    out 缓冲按 w*h 个 uint32 申请。注意 decode_astc 不守这个约定，它单独实现。
    """
    def decode(data, width, height, *extra):
        if _lib is None:
            _missing(symbol)
        out = (ctypes.c_uint * (width * height))()
        fn = getattr(_lib, symbol)
        fn.argtypes = [
            ctypes.c_void_p, ctypes.c_long, ctypes.c_long, ctypes.c_void_p
        ] + [_argtype(a) for a in extra]
        fn.restype = ctypes.c_int
        ret = fn(
            ctypes.cast(data, ctypes.c_void_p), width, height,
            ctypes.cast(out, ctypes.c_void_p), *extra
        )
        if ret != 0:
            raise RuntimeError(f"{symbol} failed with return code {ret}")
        # 返回前做 BGRA→RGBA 通道交换：C 侧按 uint32 打包输出，小端机器
        # 上字节序是 B,G,R,A，而 PIL/UnityPy 期望 R,G,B,A。所有压缩格式
        # （ETC/BC/ATC/PVRTC）都有这个问题，不只是 ASTC。
        # bytearray 扩展切片在 C 层做，比逐像素快得多。
        _b = bytearray(out)
        _src = bytes(out)
        _b[0::4], _b[2::4] = _src[2::4], _src[0::4]   # swap R↔B
        return bytes(_b)
    return decode


def _make_crunch_unpacker(symbol):
    """生成一个 (data) → bytes 的 crunch 解包函数。

    C 侧签名 (const void* data, unsigned size, unsigned level,
    void** out, unsigned* out_size)，返回是否成功。
    """
    def unpack(data):
        if _lib is None:
            _missing(symbol)
        fn = getattr(_lib, symbol)
        fn.argtypes = [
            ctypes.c_void_p, ctypes.c_uint, ctypes.c_uint,
            ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(ctypes.c_uint)
        ]
        fn.restype = ctypes.c_bool
        out_ptr = ctypes.c_void_p()
        out_size = ctypes.c_uint()
        ok = fn(
            ctypes.cast(data, ctypes.c_void_p), len(data), 0,
            ctypes.byref(out_ptr), ctypes.byref(out_size)
        )
        if not ok:
            raise RuntimeError(f"{symbol} failed.")
        # C 侧没有暴露 free，返回的内存只能交给它自己管理
        return ctypes.string_at(out_ptr, out_size.value)
    return unpack


# 无额外参数的解码器清单 —— 逐个注册成模块级函数
for _symbol in (
    "decode_atc_rgb4", "decode_atc_rgba8",
    "decode_etc1", "decode_etc2", "decode_etc2a1", "decode_etc2a8",
    "decode_eacr", "decode_eacr_signed", "decode_eacrg", "decode_eacrg_signed",
    "decode_bc1", "decode_bc3", "decode_bc4", "decode_bc5",
    "decode_bc6", "decode_bc7",
):
    globals()[_symbol] = _make_decoder(_symbol)


def _decode_astc(data, width, height, block_x, block_y):
    """ASTC 解码：(data, w, h, block 宽, block 高) → RGBA bytes。

    这是唯一不守统一签名的解码器：C 侧是
    `int decode_astc(const uint8_t* data, long w, long h,
                     int bw, int bh, uint32_t* image)`
    —— out 缓冲排在最后，block 宽高插在中间，所以不能走 _make_decoder。
    （.so 是带 DWARF 的调试构建，参数表与序即以上原型，已在指令层核对过。）
    """
    if _lib is None:
        _missing("decode_astc")
    out = (ctypes.c_uint * (width * height))()
    fn = getattr(_lib, "decode_astc")
    fn.argtypes = [
        ctypes.c_void_p, ctypes.c_long, ctypes.c_long,
        ctypes.c_int, ctypes.c_int, ctypes.c_void_p,
    ]
    fn.restype = ctypes.c_int
    ret = fn(
        ctypes.cast(data, ctypes.c_void_p), width, height,
        block_x, block_y, ctypes.cast(out, ctypes.c_void_p)
    )
    if ret != 0:
        raise RuntimeError(f"decode_astc failed with return code {ret}")
    # 同 _make_decoder：BGRA→RGBA 通道交换
    _b = bytearray(out)
    _src = bytes(out)
    _b[0::4], _b[2::4] = _src[2::4], _src[0::4]
    return bytes(_b)


decode_astc = _decode_astc

# pvrtc 多一个格式参数
decode_pvrtc = _make_decoder("decode_pvrtc")

unpack_crunch = _make_crunch_unpacker("crunch_unpack_level")
unpack_unity_crunch = _make_crunch_unpacker("unity_crunch_unpack_level")
