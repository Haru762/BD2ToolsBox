"""bundle 解包器：把游戏资源导出成通用文件（PNG / atlas / skel）。

解压解码全部交给 UnityPy（ASTC 解码落到 vendored 的
texture2ddecoder 绑定），本模块只负责遍历对象与落盘。预览模式
（fast=True）走两处捷径：只处理三类预览用得上的对象、PNG 用
最低压缩写盘 —— 预览文件看完即删，换速度划算。

本模块同时是 vendored UnityPy 的 ASTC 解码入口 decompress_astc_ctypes
的提供方，它必须定义在 `import UnityPy` 之前：Texture2DConverter 在模块
顶层就 `from unpacker import decompress_astc_ctypes`，定义晚了会走它自己的
except 分支 —— 之后每张 ASTC 贴图都静默变成纯黑占位图。
"""
import gc
import os
import sys

# 加载 vendored UnityPy
vendor_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vendor")
sys.path.insert(0, vendor_path)

# 解码失败要点名：ASTC 解码发生在 vendored UnityPy 内部，那里拿不到当前
# 资源名与 UI 进度回调，只能靠遍历时记在这两个模块级变量里。
_current_asset_name = None
_progress_callback = print


class UnpackCancelled(Exception):
    """进度回调要求取消（返回真）。解开到一半的目录由调用方清理。"""


def _report(message):
    """警告双写：stdout（logcat）与 UI 进度 —— 解码失败不能再静默。"""
    print(message)
    callback = _progress_callback
    if callback is not None:
        try:
            callback(message)
        except Exception:
            pass


def decompress_astc_ctypes(data, width, height, block_x, block_y):
    """vendored Texture2DConverter 约定的 ASTC 解码入口：(RGBA bytes, err)。

    err 为假表示成功；失败时返回 (b"", 原因)，调用方会退化成黑占位图 ——
    所以原因必须写进日志，否则又是一次「预览全黑、日志什么也没有」。
    """
    try:
        # 尺寸帽与数据量核对：损坏对象可能带着垃圾尺寸（169KB 的包声称
        # 16384x16384 → 一次分配 1GB → 进程被系统直接杀掉，预解包「无声
        # 消失」就死在这里）。声称的分辨率先过合理性检查，数据长度也要
        # 够铺满 block 数（ASTC 每 block 16 字节，减 3 字节头）——两道
        # 都不过就按解码失败处理，黑图 + 日志，进程继续活。
        pixels = width * height
        if pixels <= 0 or pixels > MAX_DECODE_PIXELS:
            raise ValueError(f"implausible dimensions {width}x{height}")
        if block_x <= 0 or block_y <= 0 or block_x > 16 or block_y > 16:
            raise ValueError(f"implausible block size {block_x}x{block_y}")
        blocks = ((width + block_x - 1) // block_x) * ((height + block_y - 1) // block_y)
        if len(data) < blocks * 16 // 2:  # 宽松一半，正常 ASTC 是 16B/block
            raise ValueError(f"data too short: {len(data)}B for {blocks} blocks")
        import texture2ddecoder
        result = texture2ddecoder.decode_astc(
            bytes(data), width, height, block_x, block_y)
        # result 可能是 ctypes 数组（安卓 .so 路径）或 bytes（PC pip 顶替）。
        # ctypes 数组不能直接做字节级切片——它的切片返回 uint 值不是字节，
        # 拿去赋值 bytearray 会产出垃圾（表现就是黑图）。必须先转 bytes。
        raw = result if isinstance(result, bytes) else bytes(result)
        return raw, None
    except Exception as e:
        _report(f"WARNING: ASTC decode failed for '{_current_asset_name or '?'}' "
                f"({width}x{height}, block {block_x}x{block_y}): {e}")
        return b"", str(e)


try:
    import UnityPy
    from UnityPy.helpers import TypeTreeHelper
except ImportError:
    print(f"Error: Could not import UnityPy. Make sure it exists in '{vendor_path}'")
    sys.exit(1)

# 预览模式只处理这三类（预览 = png + atlas + skel）
PREVIEW_TYPES = ("Texture2D", "TextAsset", "MonoBehaviour")

# 对象循环里的 gc 间隔。历史值是 10（为贴图解码内存设的），但贴图解码
# 已挪到 _export_textures 的独立批量路径（那里按张回收）—— 循环里只剩
# TextAsset/MonoBehaviour 这类小对象，每 10 个收一次全代回收纯属缴税：
# 实测 3218 对象的包 322 次 gc.collect() 吃掉 13s，改 500 后同包 2.3s。
GC_INTERVAL = 500

# 单张贴图解码的像素帽（16384x16384 = 上限；正常 spine 页最大 4096²）
MAX_DECODE_PIXELS = 16384 * 16384

# 单个 bundle 的体量门槛：超过则拒绝解码（加载本身就要吃等量内存，
# 448MB 的特殊插画合集在手机上会把进程直接顶爆——预解包队列死在它身上、
# 整个 app 无声消失。跳过只影响「预览」，安装是整文件拷贝不受影响）。
# 2026-09 门槛从 350MB 抬到 500MB：UnityPy 加了磁盘溢出（解压块落盘、
# .resS 按需读），422MB 约会大包的解包峰值从 1.66GB 降到 372MB ——
# 约会/好感剧情类 mod 的目标包正是它，350 的旧帽子把它们的预览全灭了。
MAX_BUNDLE_BYTES = 500 * 1024 * 1024


def _safe_export_name(base_name):
    """把**外部派生**的资产名规范化成不含任何路径分隔符的文件名片段。

    `base_name` 的来源是 Unity 对象的 `m_Name`（见 `_export_objects`），
    也就是 MOD 作者可以影响的输入。

    改动前这里是 `(base_name or 'unnamed').replace('/', '_')` —— 只消掉了 `/`。
    后果按平台分开说，不含糊（实测见 docs/refactor/evidence/T06.md §3）：

    · **POSIX（生产：Android）**：`/` 已消掉，`..` 与 `\\` 都只是普通字符，
      **没有任何可达的越界**。这一层是**纵深防御**，不是已证实的生产越界。
    · **Windows 语义**：`\\` 是分隔符，`..\\..\\evil` 会真的落到 output_dir 之外；
      `C:\\evil` 会被 `os.path.join` 当成绝对路径而**整段丢掉 output_dir**。

    这里把两种分隔符一起消掉，使两端行为一致（同一份代码在哪个平台跑都不越界）。
    另外把 NUL 换成 `_`：NUL 在任何平台上都会让 `open()` 抛
    `ValueError: embedded null character`，把该资产的导出整个打断。

    正常语义一字未动：中文、`#path_id` 去重后缀、大小写敏感性都由调用方保留。
    """
    name = base_name or 'unnamed'
    name = name.replace('/', '_').replace('\\', '_').replace('\x00', '_')
    # 盘符前缀（`C:evil`）：Windows 上 os.path.join(out, 'C:evil') 会返回
    # 驱动器相对路径、把 out 丢掉。把冒号挤掉，两端行为一致。
    if len(name) >= 2 and name[1] == ':':
        name = name[0] + '_' + name[2:]
    return name or 'unnamed'


def _require_within(output_dir, dest_path):
    """纵深防御闸门：`dest_path` 必须**严格落在** `output_dir` 之内，否则抛 ValueError。

    为什么要单独一层，而不只靠 `_safe_export_name`（如实写清，不夸大这一层的作用）：
    规范化之后，**单靠 `base_name` 已经到不了这里**。这一层守的是同一次拼接里的
    另外两条输入 —— `output_dir` 本身，以及 `extension` / `path_id`（今天三个调用点
    传的是字面量 `''`、`'.png'` 和 Unity 对象自带的 path_id，都是**未经规范化**直接
    拼进来的字符串）。它们是"今天安全、将来改调用点就可能不安全"的位置，
    所以在写盘接口上放一道与 `base_name` 无关的判定。

    判定用 `os.path.realpath` + `os.path.commonpath`，**不用纯字符串前缀**：
    实测 `'out-evil/x'.startswith('out')` 返回 True，会被同前缀兄弟目录骗过。
    """
    root = os.path.realpath(output_dir)
    real = os.path.realpath(dest_path)
    try:
        common = os.path.commonpath([root, real])
    except ValueError:
        # 不同驱动器（Windows）或绝对/相对混用：commonpath 直接抛
        raise ValueError(
            "导出路径与输出目录不同根，拒绝: {0!r}（输出目录 {1!r}）".format(dest_path, output_dir)
        )
    if common != root or real == root:
        raise ValueError(
            "导出路径越出输出目录，拒绝: {0!r}（输出目录 {1!r}）".format(dest_path, output_dir)
        )
    return dest_path


def _unique_export_path(output_dir, base_name, extension, path_id):
    """导出路径；同名冲突时追加 #path_id（Unity 对象的 path_id 天然唯一）。

    T06 加固：`base_name` 先过 `_safe_export_name`（消掉 `/`、`\\`、NUL 与盘符前缀），
    结果再过 `_require_within`（包含性判定）才返回。
    文件名的其余语义不变：扩展名照旧拼在末尾、去重后缀仍是 ` #<path_id>`。
    """
    safe_name = _safe_export_name(base_name)
    dest = os.path.join(output_dir, f"{safe_name}{extension}")
    if not os.path.exists(dest):
        return _require_within(output_dir, dest)
    return _require_within(
        output_dir, os.path.join(output_dir, f"{safe_name} #{path_id}{extension}")
    )


def _export_texture(data, dest_path, fast):
    img = data.image
    if fast:
        img.save(dest_path, compress_level=1)
    else:
        img.save(dest_path)


def _export_text(data, dest_path):
    content = data.m_Script
    if isinstance(content, str):
        content = content.encode('utf-8', 'surrogateescape')
    with open(dest_path, "wb") as f:
        f.write(content)


def _is_atlas_asset(name):
    lowered = (name or "").lower()
    return lowered.endswith(".atlas") or lowered.endswith(".atlas.txt")


def _atlas_page_stems(content):
    """atlas 文本里的登记页名（去掉 .png）：顶格、以 .png 结尾的行。"""
    stems = []
    for line in content.splitlines():
        entry = line.strip()
        if entry and not line[:1].isspace() and entry.lower().endswith(".png"):
            stems.append(entry[:-4])
    return stems


def _collect_atlas_pages(env):
    """预扫 atlas：返回 (页名小写 → 登记名, path_id → 已读的 TextAsset)。

    Android 的文件系统区分大小写：atlas 登记 "CutScene_Char000203.png" 而包里
    的 Texture2D 对象叫 "cutscene_char000203" 时，导出文件名差一个字母，Spine
    就找不到页文件 —— 预览一片空白。这里先把登记名收下来，导出贴图时按登记名
    落盘；读过的 atlas 对象顺手留给正式遍历复用，不重复读第二遍。
    """
    pages = {}
    cached = {}
    for obj in env.objects:
        if obj.type.name != "TextAsset":
            continue
        try:
            if not _is_atlas_asset(obj.peek_name()):
                continue
            data = obj.read()
            content = data.m_Script
            if isinstance(content, bytes):
                content = content.decode("utf-8", "surrogateescape")
            for stem in _atlas_page_stems(content):
                pages[stem.lower()] = stem
            cached[obj.path_id] = data
        except Exception:
            continue
    return pages, cached


def unpack_bundle(bundle_path, output_dir, progress_callback=print, fast=False):
    """把 bundle 里的资源导出到 output_dir。返回 (成功, 消息)。

    fast=True 是预览模式：只处理 Texture2D/TextAsset/MonoBehaviour（其余
    类型连 obj.read() 都不做——完整反序列化对用不上的对象是纯浪费），
    PNG 用 compress_level=1 换写盘速度，且**只解码 atlas 登记过的页**
    （表情差分/小图标预览用不上，解码占实测耗时的 8 成）。
    """
    # 体量门槛（见 MAX_BUNDLE_BYTES 注释）：宁可明说「预览不了」，
    # 也不能让一个巨包把整个预解包队列连带 app 进程一起带走。
    try:
        if os.path.getsize(bundle_path) > MAX_BUNDLE_BYTES:
            msg = (f"bundle too large to decode: {os.path.getsize(bundle_path) // 1048576} MB "
                   f"(cap {MAX_BUNDLE_BYTES // 1048576} MB) — 安装不受影响，仅无法预览")
            progress_callback(msg)
            return False, msg
    except OSError:
        pass

    global _current_asset_name, _progress_callback
    _progress_callback = progress_callback or print
    progress_callback(f"Starting to unpack '{os.path.basename(bundle_path)}'...")
    if not os.path.exists(bundle_path):
        return False, f"Bundle file not found at '{bundle_path}'"

    os.makedirs(output_dir, exist_ok=True)
    progress_callback(f"Output directory '{output_dir}' is ready.")

    TypeTreeHelper.read_typetree_boost = False
    UnityPy.config.FALLBACK_UNITY_VERSION = '2022.3.22f1'

    env = None
    try:
        env = UnityPy.load(bundle_path)
        _export_objects(env, output_dir, progress_callback, fast)
        progress_callback("Unpacking complete.")
        return True, "Unpacking complete."
    except UnpackCancelled:
        progress_callback("Unpacking cancelled.")
        return False, "cancelled"
    except Exception as e:
        import traceback
        error_message = f"Failed to load bundle: {e}"
        progress_callback(error_message)
        print(traceback.format_exc())
        return False, error_message
    finally:
        del env
        gc.collect()


# fast 模式下贴图解码的并行度。解码走 C 扩展（libtexture2ddecoder），
# 调用期间释放 GIL，多线程是真并行；但每张 2048x2048 RGBA 占 ~32MB，
# 并发数必须压着内存来 —— 3 是「8 核手机吃得下且峰值可控」的折中。
# min(3, ...) 保守对待核数低的设备。非 fast（正式解包给 repacker 用）
# 保持串行：那条路贴图还要走替换/写回，并行只会放大内存峰值。
FAST_DECODE_WORKERS = min(3, (os.cpu_count() or 2))

# 有界队列的容量（背压）：主线程读出的原始字节最多压 workers*2 张，
# 喂不进去就阻塞 —— 否则 170 张 × 4MB 的包会一次性攒出 700MB 内存。
_DECODE_QUEUE_DEPTH = FAST_DECODE_WORKERS * 2


def _read_texture_bytes(tex):
    """主线程：取贴图原始编码字节。

    UnityPy 的资源 reader（CAB 归档）是共享对象，读靠 Position 读写
    （helpers/ResourceReader._get_resource_data），两个线程同时取流
    数据会互相踩位置、静默产出坏图 —— 这一步绝不能进工作线程。
    """
    return tex.get_image_data()


def _decode_texture(tex, raw):
    """工作线程：原始字节 → PIL 图。纯计算（C 解码 + frombytes），线程安全。

    与 Texture2DConverter.get_image_from_texture2d 等价，只是拆开了
    get_image_data（必须串行）这一步。"""
    from UnityPy.export.Texture2DConverter import parse_image_data
    from UnityPy.enums import BuildTarget
    return parse_image_data(
        raw, tex.m_Width, tex.m_Height, tex.m_TextureFormat,
        getattr(tex.object_reader, "version", (0, 0, 0, 0)),
        getattr(tex.object_reader, "platform", BuildTarget.UnknownPlatform),
        getattr(tex, "m_PlatformBlob", None),
    )


def _export_objects(env, output_dir, progress_callback, fast):
    """遍历 env.objects 导出。fast 模式两件事省时间（见 unpack_bundle 文档）：
    跳过非预览类型、只解码 atlas 登记页，贴图并行解码。"""
    global _current_asset_name
    total = len(env.objects)
    progress_callback(f"Successfully loaded bundle. Found {total} assets.")

    pages_by_lower, atlas_cache = _collect_atlas_pages(env)

    # fast 模式的贴图先攒名单：读字节留主线程（串行），解码+落盘丢线程池
    # （C 扩展释放 GIL）。其余类型（atlas/skel 文本）串行导出 —— 毫秒级，
    # 并行没意义还要抢队列。文本先走，贴图最后并行。
    texture_jobs = []

    for i, obj in enumerate(env.objects):
        data = None
        try:
            if fast and obj.type.name not in PREVIEW_TYPES:
                continue
            if obj.type.name == "TextAsset" and obj.path_id in atlas_cache:
                data = atlas_cache[obj.path_id]  # 预扫 atlas 时已读过
            else:
                data = obj.read()
            name = getattr(data, 'm_Name', None)
            if not name:
                continue
            dest_name = name.replace('/', '_')
            if obj.type.name == "Texture2D":
                # fast 模式只解 atlas 登记页：预览 = spine 渲染，没登记的页
                # （表情差分/小图标/背景碎片）解了也白解 —— 解码占实测
                # 耗时 8 成，这一刀对 spine 类大包立竿见影。
                # atlas 为空的包（纯立绘合集）保持全解：没有登记表可对照。
                if fast and pages_by_lower and name.lower() not in pages_by_lower:
                    continue
                # 贴图名与 atlas 登记名只差大小写时，按登记名落盘（Android
                # 区分大小写，差一个字母 Spine 就找不到页文件）
                registered = pages_by_lower.get(name.lower())
                if registered:
                    dest_name = registered.replace('/', '_')
                path_id = getattr(obj, 'path_id', 'dup')
                _current_asset_name = dest_name
                if progress_callback(f"Processing asset {i + 1}/{total}: {dest_name}"):
                    raise UnpackCancelled()
                texture_jobs.append((data, dest_name, path_id))
                continue
            path_id = getattr(obj, 'path_id', 'dup')
            _current_asset_name = dest_name
            if progress_callback(f"Processing asset {i + 1}/{total}: {dest_name}"):
                raise UnpackCancelled()

            if obj.type.name in ("TextAsset", "MonoBehaviour"):
                # MonoBehaviour 只导 .skel（骨架存这类对象里）
                if obj.type.name == "MonoBehaviour" and ".skel" not in dest_name.lower():
                    continue
                _export_text(data, _unique_export_path(output_dir, dest_name, "", path_id))
        except UnpackCancelled:
            raise
        except Exception as e:
            import traceback
            asset_name = getattr(data, 'm_Name', None) or "Unknown"
            progress_callback(f"FAILED to export asset '{asset_name}': {e}")
            print(traceback.format_exc())
        finally:
            del data
            if (i + 1) % GC_INTERVAL == 0:
                gc.collect()

    if not texture_jobs:
        return
    _export_textures(texture_jobs, output_dir, progress_callback, fast)


def _export_textures(texture_jobs, output_dir, progress_callback, fast):
    """贴图批量导出。fast 走「主线程读字节 + 工作线程解码」的流水线，
    非 fast 保持老路径（串行 .image 直出，供 repacker 用）。"""
    if not fast:
        for data, dest_name, path_id in texture_jobs:
            _export_texture(data, _unique_export_path(output_dir, dest_name, ".png", path_id), fast)
            gc.collect()
        return

    def _decode_and_save(job):
        tex, raw, dest_name, path_id = job
        img = _decode_texture(tex, raw)
        img.save(_unique_export_path(output_dir, dest_name, ".png", path_id),
                 compress_level=1)

    use_pool = FAST_DECODE_WORKERS > 1 and len(texture_jobs) > 1
    if not use_pool:
        # 少量贴图开池不划算；主线程直接走同一条读+解路径
        for tex, dest_name, path_id in texture_jobs:
            try:
                raw = _read_texture_bytes(tex)
                _decode_and_save((tex, raw, dest_name, path_id))
            except Exception as e:
                progress_callback(f"FAILED to export asset '{dest_name}': {e}")
        gc.collect()
        return

    import queue
    import threading
    q = queue.Queue(maxsize=_DECODE_QUEUE_DEPTH)
    failures = []

    def worker():
        while True:
            job = q.get()
            try:
                if job is None:
                    return
                _decode_and_save(job)
            except Exception as e:
                failures.append((job[2], e))
            finally:
                q.task_done()

    threads = [threading.Thread(target=worker, daemon=True)
               for _ in range(FAST_DECODE_WORKERS)]
    for t in threads:
        t.start()
    try:
        n = len(texture_jobs)
        for idx, (tex, dest_name, path_id) in enumerate(texture_jobs):
            # 取消在喂料口检查：返回 True 就不再喂，工作线程把在手里的
            # 做完即收（与老行为「下一张贴图前停手」等价）
            if progress_callback(f"Decoding texture {idx + 1}/{n}: {dest_name}"):
                raise UnpackCancelled()
            try:
                raw = _read_texture_bytes(tex)
            except Exception as e:
                progress_callback(f"FAILED to export asset '{dest_name}': {e}")
                continue
            # 队列满时阻塞 —— 原始字节的驻留量被 _DECODE_QUEUE_DEPTH 封顶
            q.put((tex, raw, dest_name, path_id))
    finally:
        for _ in threads:
            q.put(None)
        for t in threads:
            t.join()
        gc.collect()

    for name, e in failures:
        progress_callback(f"FAILED to export asset '{name}': {e}")
