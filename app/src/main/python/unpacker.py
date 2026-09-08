"""bundle 解包器：把游戏资源导出成通用文件（PNG / atlas / skel）。

解压解码全部交给 UnityPy（ASTC 解码落到 vendored 的
texture2ddecoder 绑定），本模块只负责遍历对象与落盘。预览模式
（fast=True）走两处捷径：只处理三类预览用得上的对象、PNG 用
最低压缩写盘 —— 预览文件看完即删，换速度划算。
"""
import gc
import os
import sys

# 加载 vendored UnityPy
vendor_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vendor")
sys.path.insert(0, vendor_path)

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


def unpack_bundle(bundle_path, output_dir, progress_callback=print, fast=False):
    """把 bundle 里的资源导出到 output_dir。返回 (成功, 消息)。

    fast=True 是预览模式：只处理 Texture2D/TextAsset/MonoBehaviour（其余
    类型连 obj.read() 都不做——完整反序列化对用不上的对象是纯浪费），
    PNG 用 compress_level=1 换写盘速度。
    """
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

        for i, obj in enumerate(env.objects):
            data = None
            try:
                if fast and obj.type.name not in PREVIEW_TYPES:
                    continue
                data = obj.read()
                name = getattr(data, 'm_Name', None)
                if not name:
                    continue
                dest_name = name.replace('/', '_')
                path_id = getattr(obj, 'path_id', 'dup')
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
