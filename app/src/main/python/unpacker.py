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
        import texture2ddecoder
        return texture2ddecoder.decode_astc(
            bytes(data), width, height, block_x, block_y), None
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

# 每 10 个对象做一次 gc：贴图解码的内存大头不进 gc 代际，得主动收
GC_INTERVAL = 10


def _unique_export_path(output_dir, base_name, extension, path_id):
    """导出路径；同名冲突时追加 #path_id（Unity 对象的 path_id 天然唯一）。"""
    safe_name = (base_name or 'unnamed').replace('/', '_')
    dest = os.path.join(output_dir, f"{safe_name}{extension}")
    if not os.path.exists(dest):
        return dest
    return os.path.join(output_dir, f"{safe_name} #{path_id}{extension}")


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
    PNG 用 compress_level=1 换写盘速度。
    """
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
        total = len(env.objects)
        progress_callback(f"Successfully loaded bundle. Found {total} assets.")

        pages_by_lower, atlas_cache = _collect_atlas_pages(env)

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
                    # 贴图名与 atlas 登记名只差大小写时，按登记名落盘（Android
                    # 区分大小写，差一个字母 Spine 就找不到页文件）
                    registered = pages_by_lower.get(name.lower())
                    if registered:
                        dest_name = registered.replace('/', '_')
                path_id = getattr(obj, 'path_id', 'dup')
                _current_asset_name = dest_name
                progress_callback(f"Processing asset {i + 1}/{total}: {dest_name}")

                if obj.type.name == "Texture2D":
                    _export_texture(data, _unique_export_path(output_dir, dest_name, ".png", path_id), fast)
                elif obj.type.name in ("TextAsset", "MonoBehaviour"):
                    # MonoBehaviour 只导 .skel（骨架存这类对象里）
                    if obj.type.name == "MonoBehaviour" and ".skel" not in dest_name.lower():
                        continue
                    _export_text(data, _unique_export_path(output_dir, dest_name, "", path_id))
            except Exception as e:
                import traceback
                asset_name = getattr(data, 'm_Name', None) or "Unknown"
                progress_callback(f"FAILED to export asset '{asset_name}': {e}")
                print(traceback.format_exc())
            finally:
                del data
                if (i + 1) % GC_INTERVAL == 0:
                    gc.collect()

        gc.collect()
        progress_callback("Unpacking complete.")
        return True, "Unpacking complete."
    except Exception as e:
        import traceback
        error_message = f"Failed to load bundle: {e}"
        progress_callback(error_message)
        print(traceback.format_exc())
        return False, error_message
    finally:
        del env
        gc.collect()
