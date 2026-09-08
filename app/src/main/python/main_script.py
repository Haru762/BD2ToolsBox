"""Kotlin ↔ Python 的接口层：Chaquopy 调用的全部入口都在这里。

命名规则：本模块公开函数的名字、参数与返回形态是 Kotlin 侧
（ModdingService 等）的硬契约，改动必须两侧同步；内部实现随便换。

分组：
  角色数据    update_character_data / get_bundle_meta
  本地扫描    check_scan_needed / scan_single_bundle / finalize_scan
  mod 解析    ensure_asset_index / resolve_mod_files / resolve_mod_batch
  下载与转换  download_bundle / unpack_bundle / restore_bundle / main
  自救工具    merge_spine_assets
"""
import json
import os
import sys
import threading
from pathlib import Path

sys.path.append(os.path.join(os.path.dirname(__file__), "vendor"))

from repacker.repacker import repack_bundle
import character_scraper
import cdn_downloader
from unpacker import unpack_bundle as unpacker_main
import spine_merger
import resolver
import local_bundle_indexer
import catalog_indexer

# --- catalog 的进程内缓存 ---
# 换安装批次（cache_key 变化）即清空；键是版本号。跨批次的复用靠磁盘缓存
# （download_catalog 自己管），这里只管「一次批次内别重复下载 60MB」。
catalog_cache = {}
catalog_cache_lock = threading.Lock()
current_cache_key = None


def _prune_catalog_cache(keep_version=None):
    for version in [v for v in catalog_cache if v != keep_version]:
        catalog_cache.pop(version, None)


def _fail(message):
    """异常 → (False, traceback 全文)。Kotlin 侧把第二项当错误信息展示。"""
    import traceback
    return False, traceback.format_exc()


def _reporter(progress_callback):
    """统一的进度出口：有回调走回调，同时落 logcat（print）。"""
    if progress_callback:
        def report(message):
            progress_callback(message)
            print(message)
        return report
    return print


# ---------------------------------------------------------------------------
# 角色数据（characters.json）与 bundle 元数据 —— 仍走 CDN catalog
# ---------------------------------------------------------------------------

def _refresh_character_data(output_dir, quality="HD", progress_callback=None):
    """下载 catalog 并重建 characters.json（资产索引由本地扫描另行负责）。"""
    report = _reporter(progress_callback)
    try:
        Path(output_dir).mkdir(parents=True, exist_ok=True)

        report(f"Fetching CDN version for {quality} quality...")
        latest_version = cdn_downloader.get_cdn_version(quality)
        if not latest_version:
            return False, "Failed to get CDN version.", None

        characters_json = Path(output_dir) / "characters.json"
        stored_version = None
        if characters_json.exists():
            try:
                stored_version = json.loads(
                    characters_json.read_text(encoding='utf-8')).get("version")
            except (json.JSONDecodeError, KeyError, TypeError):
                stored_version = None

        if stored_version == latest_version and characters_json.exists():
            report(f"characters.json already up to date for version {latest_version}.")
            return True, "SKIPPED", latest_version

        report(f"Refreshing character data for version {latest_version}...")
        with catalog_cache_lock:
            _prune_catalog_cache(latest_version)
        catalog_content, error = cdn_downloader.download_catalog(
            output_dir, quality, latest_version, catalog_cache, catalog_cache_lock, progress_callback)
        if error:
            return False, error, None

        report("Rebuilding characters.json from catalog...")
        success, message = character_scraper.scrape_and_save_from_catalog(
            output_dir, latest_version, catalog_content)
        if not success:
            return False, message, None
        return True, "SUCCESS", latest_version
    except Exception:
        error_message = _fail(None)[1]
        report(f"Error refreshing character data: {error_message}")
        return False, error_message, None


def update_character_data(output_dir, quality="HD"):
    """Kotlin 入口：刷新 characters.json。返回 (状态, 消息)，状态 ∈ SUCCESS/SKIPPED/FAILED。"""
    try:
        success, status, version = _refresh_character_data(output_dir, quality)
        if success:
            if status == "SKIPPED":
                return "SKIPPED", "characters.json is already up to date."
            return "SUCCESS", f"Character data refreshed for version {version}."
        return "FAILED", status
    except Exception:
        error_message = _fail(None)[1]
        print(f"An error occurred during character data refresh: {error_message}")
        return "FAILED", error_message


def get_bundle_meta(output_dir, quality="HD", progress_callback=None):
    """Kotlin 入口：取 bundle 名 → [原版字节数, 内容哈希]（干净检测用）。

    与游戏本地 Shared/<name>/<hash>/__data 的目录名和大小比对，可客观
    判断 bundle 是否原版 —— 不依赖 app 记账，旧工具或手动装入的改动也
    能发现。catalog 走版本化磁盘缓存，常态下几乎零流量零耗时。
    """
    report = _reporter(progress_callback)
    try:
        # 归一化只在 cdn_downloader.normalize_quality 一处做（FHD → HD），
        # 这里手写就重演过「版本映射了、路径没映射 → 404 → 干净检测全空」的事故
        quality = cdn_downloader.normalize_quality(quality)
        version = cdn_downloader.get_cdn_version(quality)
        if not version:
            return False, "无法获取 CDN 版本号（可能离线），请检查网络后重试。", None

        with catalog_cache_lock:
            _prune_catalog_cache(version)
        catalog_content, error = cdn_downloader.download_catalog(
            output_dir, quality, version, catalog_cache, catalog_cache_lock, progress_callback)
        if error:
            return False, error, None

        meta = catalog_indexer.load_or_build_bundle_meta(output_dir, version, catalog_content)
        report(f"Bundle metadata ready: {len(meta)} bundles (version {version}).")
        return True, version, meta
    except Exception:
        error_message = _fail(None)[1]
        report(f"Error building bundle metadata: {error_message}")
        return False, error_message, None


# ---------------------------------------------------------------------------
# 本地 bundle 扫描 —— 三步 API（Kotlin 管 Shizuku 文件搬运）
# ---------------------------------------------------------------------------

def check_scan_needed(output_dir, bundle_list_json, progress_callback=None, full_scan=False):
    """Step 1：对照缓存给出待扫 bundle 名单（JSON 串）。异常时返回空名单。"""
    report = _reporter(progress_callback)
    try:
        result = local_bundle_indexer.check_scan_needed(
            output_dir, bundle_list_json, full_scan=full_scan)
        report(f"Scan check complete: {len(json.loads(result))} bundles need scanning.")
        return result
    except Exception:
        report(f"Error checking scan: {_fail(None)[1]}")
        return json.dumps([])


def scan_single_bundle(bundle_name, bundle_hash, temp_data_path, progress_callback=None):
    """Step 2：扫一个临时路径上的 __data。返回 (成功, 资产数, 消息)。"""
    return local_bundle_indexer.scan_single_bundle(
        bundle_name, bundle_hash, temp_data_path, progress_callback)


def finalize_scan(output_dir, progress_callback=None):
    """Step 3：合并缓存与新扫结果，索引落盘。返回 (成功, 消息)。"""
    return local_bundle_indexer.finalize_scan(output_dir, progress_callback)


# ---------------------------------------------------------------------------
# mod 解析 —— 基于本地 bundle 索引
# ---------------------------------------------------------------------------

def ensure_asset_index(output_dir, quality="HD", progress_callback=None):
    """加载本地 bundle 索引。返回 (成功, 消息, 索引 dict 或 None)。"""
    report = _reporter(progress_callback)
    try:
        index = local_bundle_indexer.load_local_index(output_dir)
        if index is None:
            return False, "Local bundle index not found. Please scan local bundles first.", None
        report(f"Local bundle index loaded: {index.get('bundleCount', 0)} bundles, "
               f"{index.get('assetCount', 0)} assets.")
        return True, "Local index loaded.", index
    except Exception:
        error_message = _fail(None)[1]
        report(f"Error loading local index: {error_message}")
        return False, error_message, None


def resolve_mod_files(file_names_json, output_dir, quality="HD", progress_callback=None):
    """解析一组 mod 文件名。返回 (成功, 结果 JSON 串或错误)。"""
    try:
        file_names = json.loads(file_names_json) if isinstance(file_names_json, str) else file_names_json
        success, version_or_error, index = ensure_asset_index(output_dir, quality, progress_callback)
        if not success:
            return False, version_or_error
        return True, json.dumps(resolver.resolve_mod_folder(file_names, index))
    except Exception:
        return _fail(None)


def resolve_mod_batch(mods_json, output_dir, quality="HD", progress_callback=None):
    """批量解析（每个 mod 带 id 与 fileNames）。返回 (成功, 结果数组 JSON 串)。"""
    try:
        mods = json.loads(mods_json) if isinstance(mods_json, str) else mods_json
        success, version_or_error, index = ensure_asset_index(output_dir, quality, progress_callback)
        if not success:
            return False, version_or_error
        results = [{"id": mod.get("id"),
                    "result": resolver.resolve_mod_folder(mod.get("fileNames") or [], index)}
                   for mod in mods or []]
        return True, json.dumps(results)
    except Exception:
        return _fail(None)


# ---------------------------------------------------------------------------
# 下载与转换
# ---------------------------------------------------------------------------

def download_bundle(hashed_name, quality, output_dir, cache_key, progress_callback=None):
    """Kotlin 入口：从 CDN 下载 bundle。返回 (成功, 产物路径或错误)。

    catalog 用进程内缓存（按 cache_key 分批）；换批自动清。
    """
    global current_cache_key
    # FHD 档的资源就是 HD（CDN 没有 FHD 路径）；归一化统一走 normalize_quality
    download_quality = cdn_downloader.normalize_quality(quality)
    report = _reporter(progress_callback)
    try:
        with catalog_cache_lock:
            if current_cache_key != cache_key:
                report("New batch installation detected, clearing catalog cache.")
                catalog_cache.clear()
                current_cache_key = cache_key

        report(f"Fetching CDN version for {download_quality} quality...")
        version = cdn_downloader.get_cdn_version(download_quality)
        if not version:
            return False, "Failed to get CDN version."

        report(f"Latest version is {version}. Checking catalog...")
        with catalog_cache_lock:
            _prune_catalog_cache(version)
        catalog_content, error = cdn_downloader.download_catalog(
            output_dir, download_quality, version, catalog_cache, catalog_cache_lock, progress_callback)
        if error:
            return False, error

        report(f"Searching for bundle {hashed_name} in catalog...")
        output_file_path, error = cdn_downloader.find_and_download_bundle(
            catalog_content=catalog_content, version=version, quality=download_quality,
            hashed_name=hashed_name, output_dir=output_dir, progress_callback=progress_callback)
        if error:
            return False, error
        return True, output_file_path
    except Exception:
        error_message = _fail(None)[1]
        report(f"A critical error occurred: {error_message}")
        return False, error_message


def unpack_bundle(bundle_path, output_dir, progress_callback=None, fast=False):
    """Kotlin 入口：解包 bundle。fast=True 为预览模式（只导 png/atlas/skel、PNG 低压缩）。"""
    try:
        success, message = unpacker_main(
            bundle_path=bundle_path, output_dir=output_dir,
            progress_callback=progress_callback, fast=fast)
        print(message)
        return success, message
    except Exception:
        error_message = _fail(None)[1]
        print(f"An error occurred during unpack: {error_message}")
        if progress_callback:
            progress_callback(f"An error occurred: {error_message}")
        return False, error_message


def restore_bundle(original_bundle_path, output_path, progress_callback=None):
    """CDN 原版 bundle → 游戏缓存格式（不做任何替换），卸载时盖回游戏目录用。

    为什么必须单独做：CDN 那份是压缩包（实测同一 bundle 6.2MB），而
    UnityCache 的 __data 是另一种编码（26MB），直接拷游戏读不了；而
    repack_bundle 在零替换时会判「No modifications」拒绝写出。所以这里
    走一遍 UnityPy 载入 + 原样保存，packer 与 repack 同为 lz4，保证编码同源。
    """
    try:
        import UnityPy
        if progress_callback:
            progress_callback("正在读取官方原版...")
        env = UnityPy.load(original_bundle_path)
        os.makedirs(os.path.dirname(output_path), exist_ok=True)
        if progress_callback:
            progress_callback("正在转换为游戏缓存格式...")
        with open(output_path, "wb") as f:
            env.file.save(f, packer="lz4")
        del env
        import gc
        gc.collect()
        size = os.path.getsize(output_path)
        if progress_callback:
            progress_callback("原版已就绪")
        return True, f"restored {size} bytes"
    except Exception as e:
        msg = _fail(None)[1]
        print(f"restore_bundle failed: {msg}")
        if progress_callback:
            progress_callback(f"还原失败: {e}")
        return False, msg


def main(original_bundle_path, modded_assets_folder, output_path, use_astc, progress_callback=None):
    """Kotlin 入口：重打包。返回 (成功, 消息)。"""
    try:
        success, message = repack_bundle(
            original_bundle_path=original_bundle_path,
            modded_assets_folder=modded_assets_folder,
            output_path=output_path,
            use_astc=use_astc,
            progress_callback=progress_callback)
        print(message)
        return success, message
    except Exception:
        error_message = _fail(None)[1]
        print(f"An error occurred: {error_message}")
        return False, error_message


# ---------------------------------------------------------------------------
# 自救工具
# ---------------------------------------------------------------------------

def merge_spine_assets(mod_dir_path, progress_callback=None):
    """Kotlin 入口：独立图集合并（UI 入口当前未挂，供高级菜单复用）。"""
    report = _reporter(progress_callback)
    try:
        message = spine_merger.run(mod_dir_path, report)
        report(message)
        return True, message
    except Exception:
        error_message = _fail(None)[1]
        report(f"An error occurred during spine merge: {error_message}")
        return False, error_message
