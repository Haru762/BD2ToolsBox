# ---------------------------------------------------------------------------
# 来源与许可
#
# 本文件的纹理 ASTC 编码调用方式（libastcenc 的 ctypes 结构布局与调用序列）
# 与 Spine 资产合并的输出格式对齐 Arm astc-encoder 与 Spine atlas 的公开规格。
# 历史上参考过 kxdekxde/browndust2-repacker-android（GPL-3.0）项目的做法，
# 本文件为全新实现：模块结构、文件索引、合并计划、压缩管线与内存管理均为
# 本项目原创，不再保留其任何代码。
# 本项目整体按 GPL-3.0 分发（见仓库根 LICENSE）。
# ---------------------------------------------------------------------------
"""Unity bundle 重打包器：把 mod 的资产替换进原版 bundle。

管线（repack_bundle）：
  1. Spine 预处理 —— mod 贴图页多于原版时先两两横向拼接对齐页数
  2. 文件分类 —— json（转 skel）/ png（ASTC 或 RGBA32）/ 其它文本
  3. 逐类替换 —— 写回 Texture2D / TextAsset 对象
  4. lz4 保存

内存是这个模块的第一约束：一个 bundle 里可能有一百多张贴图，逐张
处理完成即写回即释放，峰值压在几张图的量级。
"""
import ctypes
import gc
import glob
import os
import re
import shutil
import sys
import tempfile
from ctypes import (POINTER, Structure, byref, c_float, c_int, c_size_t,
                    c_ubyte, c_uint, c_void_p)
from concurrent.futures import ThreadPoolExecutor, as_completed

from PIL import Image, ImageFile

from .json_to_skel import json_to_skel
from utils.atlas_operations import parse_atlas_file
from utils.file_operations import find_file_case_insensitive  # noqa: F401 兼容导入

# 并行压缩的工人数：贴图解码吃内存，压到 CPU 数的一半、上限 4
MAX_PARALLEL_TEXTURES = min(4, max(1, (os.cpu_count() or 4) // 2))

# Chaquopy 环境没有进程 fork，Android 上线程池代替进程池
IS_ANDROID = hasattr(sys, 'getandroidapilevel') or 'ANDROID_ROOT' in os.environ

from UnityPy.helpers import TypeTreeHelper
TypeTreeHelper.read_typetree_boost = False
import UnityPy
UnityPy.config.FALLBACK_UNITY_VERSION = '2022.3.22f1'

# Unity 的纹理格式枚举值（写回 Texture2D 用）
TEXTURE_FORMAT_ASTC_RGB_4X4 = 48
TEXTURE_FORMAT_RGBA32 = 4


# ---------------------------------------------------------------------------
# libastcenc 的 ctypes 绑定
# ---------------------------------------------------------------------------

_astcenc = None          # 加载成功 = 库句柄；加载失败 = False（只试一次）
_astcenc_types = {}


def _load_astcenc():
    """懒加载 libastcenc.so 并声明用到的 C 接口。失败记 False 不重试。"""
    global _astcenc
    if _astcenc is not None:
        return _astcenc or None
    try:
        lib = ctypes.cdll.LoadLibrary("libastcenc.so")
    except OSError as e:
        print(f"FATAL: Could not load libastcenc.so. Make sure it's in jniLibs. Error: {e}")
        _astcenc = False
        return None

    t = _astcenc_types
    t['SUCCESS'] = 0
    t['PRF_LDR_SRGB'] = 0
    t['PRE_MEDIUM'] = 60.0
    t['TYPE_U8'] = 0
    t['FLG_USE_DECODE_UNORM8'] = 1 << 1

    class Swizzle(Structure):
        _fields_ = [("r", c_uint), ("g", c_uint), ("b", c_uint), ("a", c_uint)]
    class Config(Structure):
        # 布局是 astc-encoder 4.x 的公开 ABI；字段名照上游头文件
        _fields_ = [
            ("profile", c_uint), ("flags", c_uint), ("block_x", c_uint),
            ("block_y", c_uint), ("block_z", c_uint), ("cw_r_weight", c_float),
            ("cw_g_weight", c_float), ("cw_b_weight", c_float), ("cw_a_weight", c_float),
            ("a_scale_radius", c_uint), ("rgbm_m_scale", c_float),
            ("tune_partition_count_limit", c_uint), ("tune_2partition_index_limit", c_uint),
            ("tune_3partition_index_limit", c_uint), ("tune_4partition_index_limit", c_uint),
            ("tune_block_mode_limit", c_uint), ("tune_refinement_limit", c_uint),
            ("tune_candidate_limit", c_uint), ("tune_2partitioning_candidate_limit", c_uint),
            ("tune_3partitioning_candidate_limit", c_uint), ("tune_4partitioning_candidate_limit", c_uint),
            ("tune_db_limit", c_float), ("tune_mse_overshoot", c_float),
            ("tune_2partition_early_out_limit_factor", c_float),
            ("tune_3partition_early_out_limit_factor", c_float),
            ("tune_2plane_early_out_limit_correlation", c_float),
            ("tune_search_mode0_enable", c_float),
            ("progress_callback", c_void_p),
        ]
    class Image(Structure):
        _fields_ = [
            ("dim_x", c_uint), ("dim_y", c_uint), ("dim_z", c_uint),
            ("data_type", c_uint), ("data", POINTER(c_void_p)),
        ]
    t['Config'], t['Image'], t['Swizzle'] = Config, Image, Swizzle

    lib.astcenc_config_init.argtypes = [c_uint, c_uint, c_uint, c_uint, c_float, c_uint, POINTER(Config)]
    lib.astcenc_config_init.restype = c_int
    lib.astcenc_context_alloc.argtypes = [POINTER(Config), c_uint, POINTER(c_void_p)]
    lib.astcenc_context_alloc.restype = c_int
    lib.astcenc_compress_image.argtypes = [c_void_p, POINTER(Image), POINTER(Swizzle), POINTER(c_ubyte), c_size_t, c_uint]
    lib.astcenc_compress_image.restype = c_int
    lib.astcenc_context_free.argtypes = [c_void_p]
    lib.astcenc_context_free.restype = None
    lib.astcenc_get_error_string.argtypes = [c_int]
    lib.astcenc_get_error_string.restype = ctypes.c_char_p

    _astcenc = lib
    return lib


def _astcenc_error(lib, status):
    return lib.astcenc_get_error_string(status).decode('utf-8')


def compress_image_astc(image_bytes, width, height, block_x, block_y):
    """把 RGBA 字节压成 ASTC 块数据。返回 (数据, None) 或 (None, 错误)。

    用途：mod 的 PNG 转 ASTC 写回 Texture2D。图先上下翻转（Unity 纹理
    行序与 PNG 相反）由调用方做，这里只管压缩。
    """
    lib = _load_astcenc()
    if not lib:
        return None, "libastcenc.so could not be loaded."
    t = _astcenc_types

    config = t['Config']()
    status = lib.astcenc_config_init(
        t['PRF_LDR_SRGB'], block_x, block_y, 1, t['PRE_MEDIUM'],
        t['FLG_USE_DECODE_UNORM8'], byref(config))
    if status != t['SUCCESS']:
        return None, f"astcenc_config_init failed: {_astcenc_error(lib, status)}"

    context = c_void_p()
    threads = os.cpu_count() or 1
    status = lib.astcenc_context_alloc(byref(config), threads, byref(context))
    if status != t['SUCCESS']:
        return None, f"astcenc_context_alloc failed: {_astcenc_error(lib, status)}"

    data_ptr = (c_void_p * 1)()
    data_ptr[0] = ctypes.cast(image_bytes, c_void_p)
    image = t['Image']()
    image.dim_x, image.dim_y, image.dim_z = width, height, 1
    image.data_type = t['TYPE_U8']
    image.data = data_ptr

    swizzle = t['Swizzle'](r=0, g=1, b=2, a=3)

    blocks_x = (width + block_x - 1) // block_x
    blocks_y = (height + block_y - 1) // block_y
    buf_size = blocks_x * blocks_y * 16
    out = (c_ubyte * buf_size)()

    try:
        status = lib.astcenc_compress_image(context, byref(image), byref(swizzle),
                                            out, buf_size, 0)
    finally:
        lib.astcenc_context_free(context)

    if status != t['SUCCESS']:
        return None, f"astcenc_compress_image failed: {_astcenc_error(lib, status)}"
    return bytes(out), None


# ---------------------------------------------------------------------------
# 工作目录索引
# ---------------------------------------------------------------------------

def _build_file_index(working_dir):
    """一次遍历建好文件索引，取代反复 os.walk。

    返回 {'skel_json': {词干: (类型, 路径, 目录)}, 'all_files': [路径]}。
    .old 备份目录跳过。
    """
    index = {'skel_json': {}, 'all_files': []}
    for root, dirs, files in os.walk(working_dir):
        dirs[:] = [d for d in dirs if d != '.old']
        for f in files:
            filepath = os.path.join(root, f)
            index['all_files'].append(filepath)
            lowered = f.lower()
            if lowered.endswith(('.skel', '.json')):
                stem = os.path.splitext(f)[0]
                ftype = 'skel' if lowered.endswith('.skel') else 'json'
                index['skel_json'].setdefault(stem, (ftype, filepath, root))
    return index


def _asset_objects(asset_map, target_asset_name, type_name=None):
    """按名取对象列表，可再按类型过滤。"""
    objects = list(asset_map.get(target_asset_name, []))
    if type_name:
        objects = [obj for obj in objects if obj.type.name == type_name]
    return objects


# ---------------------------------------------------------------------------
# Spine 贴图合并（mod 页数 > 原版时压回原页数）
# ---------------------------------------------------------------------------

def _merge_spine_assets(mod_dir_path, base_name, target_count, report):
    """把 <base>_N.png 序列合并成 target_count 张，重写 .atlas 对齐。

    合并策略：按序轮流分桶（第 i 张进 i % target_count 桶），桶内横向
    拼接，第二张起 bounds 右移。返回 None 成功、字符串失败。
    """
    report(f"Starting Spine asset merge for '{base_name}' in temporary directory.")

    png_pattern = re.compile(rf'^{re.escape(base_name)}(_\d+)?\.png$', re.IGNORECASE)
    pngs = [os.path.join(mod_dir_path, f)
            for f in os.listdir(mod_dir_path) if png_pattern.match(f)]
    atlas_path = os.path.join(mod_dir_path, f"{base_name}.atlas")

    if not pngs or not os.path.exists(atlas_path):
        return "SKIPPING: Missing png or atlas files for {base} in the working directory.".replace("{base}", base_name)

    atlas_db, err = parse_atlas_file(atlas_path)
    if err:
        return f"FAILED: Could not parse atlas file: {err}"

    # _2 排在 _10 前面；主图（无后缀）排最前
    def page_order(path):
        name = os.path.basename(path)
        if name.lower() == f"{base_name.lower()}.png":
            return 0
        m = re.search(r'_(\d+)\.png$', name)
        return int(m.group(1)) if m else float('inf')
    pngs.sort(key=page_order)

    if len(pngs) <= target_count:
        return "Merge not needed, texture count is already at or below target."

    ImageFile.LOAD_TRUNCATED_IMAGES = True
    report(f"Merging {len(pngs)} textures down to {target_count}.")

    # 全部先解码（要拼的图必须在内存里凑齐；坏图尽早报）
    images = {}
    try:
        for p in pngs:
            img = Image.open(p)
            img.load()  # 立即读全，截断图在这里就炸
            images[os.path.basename(p)] = img
    except OSError as e:
        for img in images.values():
            img.close()
        return (f"FAILED: Could not decode image file '{os.path.basename(p)}'. "
                f"The PNG stream might be corrupted or in an unsupported format. Error: {e}")

    # 轮流分桶：桶 i 收第 i、i+target_count、… 张
    buckets = [[] for _ in range(target_count)]
    for i, p in enumerate(pngs):
        buckets[i % target_count].append(os.path.basename(p))

    merged_names = {f"{base_name}.png" if i == 0 else f"{base_name}_{i + 1}.png"
                    for i in range(target_count)}

    try:
        blocks = []
        for i, group in enumerate(buckets):
            if not group:
                continue
            out_name = f"{base_name}.png" if i == 0 else f"{base_name}_{i + 1}.png"

            base_img = images[group[0]].copy()
            offsets = {group[0]: (0, 0)}
            width, height = base_img.width, base_img.height
            for other in group[1:]:
                img = images[other]
                offsets[other] = (width, 0)
                width += img.width
                height = max(height, img.height)
                canvas = Image.new('RGBA', (width, height))
                canvas.paste(base_img, (0, 0))
                canvas.paste(img, offsets[other])
                base_img.close()
                base_img = canvas

            out_path = os.path.join(mod_dir_path, out_name)
            base_img.save(out_path)
            base_img.close()
            report(f"Saved merged image: {out_path}")

            # atlas 小节：桶里每张原图的精灵行拼一段，非首张的 bounds 右移
            sprite_parts = []
            filter_line = None
            for name in group:
                page = atlas_db.get(name)
                if not page:
                    report(f"WARNING: Missing atlas entry for {name}; skipping atlas block data during merge.")
                    continue
                if filter_line is None:
                    filter_line = page['filter_line']
                x_off, y_off = offsets[name]
                if x_off == 0 and y_off == 0:
                    sprite_parts.append(page['sprites'])
                else:
                    sprite_parts.append(_shift_bounds(page['sprites'], x_off, y_off))

            if filter_line is None:
                report(f"WARNING: No atlas blocks matched merged texture group {group}; skipping output atlas block for {out_name}.")
                continue
            blocks.append('\n'.join([
                out_name,
                f" size: {width},{height}",
                filter_line,
                '\n'.join(sprite_parts),
            ]))

        with open(atlas_path, 'w', encoding='utf-8') as f:
            f.write('\n\n'.join(blocks))
        report(f"Wrote final atlas file to: {atlas_path}")

        # 删掉没进合并产物的原图（产物名与原图名可能重合，只删多余的）
        report("Cleaning up original, unmerged texture files...")
        for p in pngs:
            if os.path.basename(p) not in merged_names:
                try:
                    os.remove(p)
                except OSError as e:
                    report(f"Could not delete old file {p}: {e}")
        return None
    finally:
        for img in images.values():
            img.close()
        del images


def _shift_bounds(sprites, dx, dy):
    """精灵块的 bounds 整体平移；其它行原样。解析失败的行也原样保留。"""
    moved = []
    for line in sprites.split('\n'):
        s = line.strip()
        if s.startswith('bounds:'):
            try:
                x, y, w, h = [int(c.strip()) for c in s.split(':', 1)[1].split(',')]
                moved.append(f" bounds: {x + dx},{y + dy},{w},{h}")
                continue
            except (ValueError, IndexError):
                pass
        moved.append(line)
    return '\n'.join(moved)


# ---------------------------------------------------------------------------
# 纹理压缩 worker（线程/进程池里跑）
# ---------------------------------------------------------------------------

def _compress_texture_worker(args):
    """解码一张 PNG → 上下翻转 → ASTC 压缩。

    args: (mod路径, 目标资产名, block_x, block_y)
    返回 {success, target_asset_name, mod_filepath, compressed_data, width, height, error}
    """
    mod_filepath, target_asset_name, block_x, block_y = args
    result = {
        'success': False, 'target_asset_name': target_asset_name,
        'mod_filepath': mod_filepath, 'compressed_data': None,
        'width': 0, 'height': 0, 'error': None,
    }
    img = flipped = None
    try:
        img = Image.open(mod_filepath).convert("RGBA")
        result['width'] = img.width
        result['height'] = img.height
        # Unity 纹理行序与 PNG 相反，压之前先翻
        flipped = img.transpose(Image.FLIP_TOP_BOTTOM)
        compressed, err = compress_image_astc(
            flipped.tobytes(), img.width, img.height, block_x, block_y)
        if err:
            result['error'] = err
        else:
            result['success'] = True
            result['compressed_data'] = compressed
    except Exception as e:
        result['error'] = str(e)
    finally:
        for x in (flipped, img):
            if x:
                x.close()
        gc.collect()
    return result


# ---------------------------------------------------------------------------
# 主入口
# ---------------------------------------------------------------------------

def _write_astc_texture(obj, compressed, width, height):
    """把 ASTC 数据写回一个 Texture2D 对象。"""
    data = obj.read()
    data.m_TextureFormat = TEXTURE_FORMAT_ASTC_RGB_4X4
    data.image_data = compressed
    data.m_CompleteImageSize = len(compressed)
    data.m_Width = width
    data.m_Height = height
    data.m_MipCount = 1
    _detach_stream(data)
    data.save()


def _write_rgba_texture(obj, image):
    """把 PIL 图写回一个 Texture2D 对象（RGBA32，未压缩）。"""
    data = obj.read()
    data.m_TextureFormat = TEXTURE_FORMAT_RGBA32
    data.image = image
    data.m_MipCount = 1
    _detach_stream(data)
    data.save()


def _write_text_asset(obj, script_text):
    """把文本写回一个 TextAsset 对象。"""
    data = obj.read()
    data.m_Script = script_text
    data.save()


def _detach_stream(data):
    """纹理数据改为内联后，流数据字段必须清零（否则读端还去找老偏移）。"""
    if hasattr(data, 'm_StreamData'):
        data.m_StreamData.offset = 0
        data.m_StreamData.size = 0
        data.m_StreamData.path = ""


def _replace_text_assets(items, asset_map, report, edited):
    """json→skel 与纯文本替换（都是写 TextAsset.m_Script）。

    items: [(mod路径, 目标资产名, is_json)]。返回 (成功数, 修改标志)。
    """
    succeeded = 0
    for mod_filepath, target_name, is_json in items:
        mod_filename = os.path.basename(mod_filepath)
        try:
            objects = _asset_objects(asset_map, target_name, "TextAsset")
            if is_json:
                with tempfile.NamedTemporaryFile(delete=False, suffix=".skel") as tmp:
                    temp_path = tmp.name
                try:
                    json_to_skel(mod_filepath, temp_path)
                    with open(temp_path, 'rb') as f:
                        script = f.read().decode("utf-8", "surrogateescape")
                finally:
                    if os.path.exists(temp_path):
                        os.remove(temp_path)
            else:
                with open(mod_filepath, "rb") as f:
                    script = f.read().decode("utf-8", "surrogateescape")
            for obj in objects:
                _write_text_asset(obj, script)
                edited = True
            succeeded += len(objects)
            report(f"Successfully replaced: {mod_filename} -> {len(objects)} object(s)")
        except Exception:
            import traceback
            report(f"Error processing {mod_filename}: {traceback.format_exc()}")
    return succeeded, edited


def _process_astc_textures(items, asset_map, report, edited):
    """并行压缩 PNG→ASTC，完成一张写回一张（内存峰值压在几张图量级）。

    Android 用线程池（Chaquopy 无进程 fork）；池子挂了退回串行。
    """
    total = len(items)
    workers = MAX_PARALLEL_TEXTURES
    executor_type = "Thread" if IS_ANDROID else "Process"
    report(f"Phase 3: Parallel ASTC compression ({total} textures, {workers} {executor_type} workers)...")

    block_x, block_y = 4, 4
    worker_args = [(p, t, block_x, block_y) for p, t in items]
    succeeded = failed = 0
    done_count = 0

    def handle(result):
        """一个压缩结果写回 bundle（失败则记数）。返回新的 edited 标志。"""
        nonlocal succeeded, failed
        if not result['success']:
            failed += 1
            report(f"  FAILED: {os.path.basename(result['mod_filepath'])} - {result['error']}")
            return False
        try:
            targets = _asset_objects(asset_map, result['target_asset_name'], "Texture2D")
            for obj in targets:
                _write_astc_texture(obj, result['compressed_data'],
                                    result['width'], result['height'])
            succeeded += len(targets)
            return True
        except Exception as e:
            failed += 1
            report(f"  Write error {os.path.basename(result['mod_filepath'])}: {e}")
            return False

    try:
        from concurrent.futures import ThreadPoolExecutor as _TPE
        from concurrent.futures import ProcessPoolExecutor as _PPE
        ExecutorClass = _TPE if IS_ANDROID else _PPE
        with ExecutorClass(max_workers=workers) as executor:
            futures = {executor.submit(_compress_texture_worker, a): a for a in worker_args}
            for future in as_completed(futures):
                done_count += 1
                result = future.result()
                if handle(result):
                    edited = True
                del result
                if total <= 10 or done_count == total or done_count % max(1, total // 4) == 0:
                    report(f"  Progress: {done_count}/{total} ({100 * done_count // total}%)")
                if done_count % 10 == 0:
                    gc.collect()
    except Exception as e:
        report(f"Executor failed, falling back to sequential: {e}")
        for a in worker_args:
            done_count += 1
            result = _compress_texture_worker(a)
            if handle(result):
                edited = True
            del result
            if done_count % 10 == 0:
                gc.collect()

    del worker_args
    gc.collect()
    report(f"  ASTC compression complete: {succeeded} success, {failed} failed")
    return edited


UNITYFS_SIGNATURE = b"UnityFS\x00"
_UNITYFS_HEAD_WINDOW = 256    # 头解析窗口：签名 + 版本串 + size 足够，不整读大文件


def _validate_unityfs_header(path):
    """校验 path 是结构完整的 UnityFS bundle（.part 落盘后的写前自检）。

    只读文件头（前 256 字节）与文件大小。按 UnityFS 头部布局解析：
    签名(8B "UnityFS\\x00") + format version(int32 大端) + unity version
    字符串(null 结尾) + revision 字符串(null 结尾) + size(int64 大端，
    即 bundle 总长度)，并断言 size == 文件实际字节数。进程中途被杀留下的
    半个文件必然缺尾部数据，size 对不上即可拦截，不会覆盖好文件。

    返回 (是否有效, 原因)：失败 (False, 中文原因)，成功 (True, "")。
    """
    try:
        actual_size = os.path.getsize(path)
    except OSError as e:
        return False, f"无法读取文件大小: {e}"
    try:
        with open(path, "rb") as f:
            head = f.read(_UNITYFS_HEAD_WINDOW)
    except OSError as e:
        return False, f"无法读取文件头: {e}"

    if head[:8] != UNITYFS_SIGNATURE:
        return False, '文件头签名不是 UnityFS（缺 b"UnityFS\\x00" 开头）'

    try:
        pos = 8
        int.from_bytes(head[pos:pos + 4], "big")     # format version，只步进不判定
        pos += 4
        for field in ("unity version", "unity revision"):
            end = head.find(b"\x00", pos)
            if end < 0:
                return False, (f"文件头中 {field} 字符串未在 "
                               f"{_UNITYFS_HEAD_WINDOW} 字节窗口内以 \\x00 结尾")
            pos = end + 1
        if pos + 8 > len(head):
            return False, "文件头截断，剩余字节不足 8 字节的 size 字段"
        declared_size = int.from_bytes(head[pos:pos + 8], "big")
    except (ValueError, IndexError) as e:
        return False, f"文件头解析失败: {e}"

    if declared_size != actual_size:
        return False, (f"UnityFS 头声明的 bundle 总长 {declared_size} 与文件实际"
                       f"字节数 {actual_size} 不一致，文件不完整")
    return True, ""


def _try_remove(path):
    """尽力删除残留临时文件；失败不影响主流程的报错信息。"""
    try:
        if os.path.exists(path):
            os.remove(path)
    except OSError:
        pass


def repack_bundle(original_bundle_path, modded_assets_folder, output_path,
                  use_astc, progress_callback=None):
    """把 mod 资产替换进原版 bundle，lz4 保存到 output_path。

    返回 (成功, 消息)。任何资产都没替换上按失败处理（多半是 mod 文件名
    与 bundle 内资产对不上）。
    """
    report = (lambda m: (progress_callback and progress_callback(m), print(m)))
    env = None
    try:
        working_dir = modded_assets_folder
        report(f"Using mod directory: {working_dir}")

        report("Loading original game file...")
        env = UnityPy.load(original_bundle_path)
        edited = False

        # ---- Spine 预处理：mod 页数超出原版时先合并 ----
        report("Building file index...")
        file_index = _build_file_index(working_dir)

        spine_stems = {stem: d for stem, (_t, _p, d) in file_index['skel_json'].items()}
        if spine_stems:
            report(f"Detected {len(spine_stems)} unique Spine mods for pre-processing: {list(spine_stems.keys())}")
            for stem, mod_dir in spine_stems.items():
                report(f"--- Processing: {stem} ---")
                name_pattern = re.compile(f"^{re.escape(stem)}(_\\d+)?$", re.IGNORECASE)

                original_pages = 0
                for obj in env.objects:
                    if obj.type.name == "Texture2D":
                        try:
                            if name_pattern.match(obj.read().m_Name):
                                original_pages += 1
                        except Exception as e:
                            report(f"Couldn't read asset name, skipping. Error: {e}")

                mod_png_pattern = re.compile(rf'^{re.escape(stem)}(_\d+)?\.png$', re.IGNORECASE)
                mod_pages = sum(1 for f in os.listdir(mod_dir) if mod_png_pattern.match(f))
                report(f"Found {original_pages} matching textures in the original game file for {stem}.")
                report(f"Mod has {mod_pages} textures for {stem}.")

                if mod_pages > original_pages > 0:
                    report("Mod texture count exceeds original, starting merge process into temp directory...")
                    merge_error = _merge_spine_assets(mod_dir, stem, original_pages, report)
                    if merge_error:
                        report(f"ERROR during merge for {stem}: {merge_error}.")
                    else:
                        report(f"Merge successful for {stem}.")
                        # 合并产生了新文件，索引重建
                        file_index = _build_file_index(working_dir)
                else:
                    report("Texture count matches or is lower, no merge needed.")

        # ---- bundle 内资产名索引 ----
        report("Scanning for moddable assets...")
        asset_map = {}
        for obj in env.objects:
            try:
                data = obj.read()
                name = getattr(data, 'm_Name', None)
                if name:
                    asset_map.setdefault(name.lower(), []).append(obj)
            except Exception:
                pass    # 读不出的对象跳过

        # ---- mod 文件分类 ----
        report("Phase 1: Categorizing mod files...")
        text_items = []      # (mod路径, 目标名, is_json)
        astc_items = []      # (mod路径, 目标名)
        rgba_items = []      # (mod路径, 目标名)

        for mod_filepath in file_index['all_files']:
            filename = os.path.basename(mod_filepath)
            lowered = filename.lower()
            if lowered.endswith('.json'):
                target = (os.path.splitext(filename)[0] + ".skel").lower()
                if _asset_objects(asset_map, target, "TextAsset"):
                    text_items.append((mod_filepath, target, True))
            elif lowered.endswith('.png'):
                target = os.path.splitext(filename)[0].lower()
                if _asset_objects(asset_map, target, "Texture2D"):
                    (astc_items if use_astc else rgba_items).append((mod_filepath, target))
            else:
                target = lowered
                if _asset_objects(asset_map, target, "TextAsset"):
                    text_items.append((mod_filepath, target, False))

        report(f"  - JSON animations: {sum(1 for i in text_items if i[2])}")
        report(f"  - ASTC textures: {len(astc_items)}")
        report(f"  - RGBA32 textures: {len(rgba_items)}")
        report(f"  - Text assets: {sum(1 for i in text_items if not i[2])}")

        # ---- 文本类替换（串行，快）----
        report("Phase 2: Processing JSON and TextAsset files...")
        _, edited = _replace_text_assets(text_items, asset_map, report, edited)

        # ---- ASTC 纹理（并行压缩，完成即写回）----
        if astc_items:
            edited = _process_astc_textures(astc_items, asset_map, report, edited)

        # ---- RGBA32 纹理（无需压缩，串行）----
        for mod_filepath, target in rgba_items:
            filename = os.path.basename(mod_filepath)
            img = None
            try:
                report(f"(RGBA) Processing: {filename}")
                img = Image.open(mod_filepath).convert("RGBA")
                for obj in _asset_objects(asset_map, target, "Texture2D"):
                    _write_rgba_texture(obj, img)
                    edited = True
            except Exception:
                import traceback
                report(f"Error processing {filename}: {traceback.format_exc()}")
            finally:
                if img:
                    img.close()

        del asset_map
        gc.collect()

        if not edited:
            msg = "No modifications were made. Check if your mod files match any assets in the bundle."
            report(msg)
            return False, msg

        report("Saving modified game file...")
        os.makedirs(os.path.dirname(output_path), exist_ok=True)
        # 先写 .part 临时文件并自校验，通过后才 os.replace 原子覆盖到最终路径：
        # 直接覆盖写中途被杀会留半个 __data，游戏加载即闪退。
        part_path = output_path + ".part"
        try:
            with open(part_path, "wb") as f:
                env.file.save(f, packer="lz4")
        except Exception as e:
            _try_remove(part_path)
            return False, f"Error saving bundle: {e}"
        valid, reason = _validate_unityfs_header(part_path)
        if not valid:
            _try_remove(part_path)
            report(f"  Saved bundle failed integrity check, discarded: {reason}")
            return False, f"Bundle validation failed after save: {reason}"
        try:
            os.replace(part_path, output_path)
        except OSError as e:
            _try_remove(part_path)
            return False, f"Error replacing bundle: {e}"
        report("Saved successfully!")
        return True, "Repack completed successfully."

    except Exception:
        import traceback
        error_message = traceback.format_exc()
        report(f"Error processing bundle: {error_message}")
        return False, error_message
    finally:
        if env is not None:
            del env
        gc.collect()
