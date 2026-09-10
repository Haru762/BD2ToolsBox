"""Kotlin ↔ Python 的接口层：Chaquopy 调用的全部入口都在这里。

命名规则：本模块公开函数的名字、参数与返回形态是 Kotlin 侧
（ModdingService 等）的硬契约，改动必须两侧同步；内部实现随便换。

分组：
  角色数据    update_character_data / get_bundle_meta / get_bundle_hints
  本地扫描    check_scan_needed / scan_single_bundle / finalize_scan
  mod 解析    ensure_asset_index / resolve_mod_files / resolve_mod_batch
  下载与转换  download_bundle / unpack_bundle / restore_bundle / main
  自救工具    merge_spine_assets
  装入校验    validate_bundle
"""
import json
import os
import struct
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
# 换安装批次（cache_key 变化）即清空；键是 (画质, 版本)。跨批次的复用靠磁盘
# 缓存（download_catalog 自己管），这里只管「一次批次内别重复下载 65MB」。
# 键里必须带画质：HD/SD 的版本号今天恰好不同，一旦撞车，只按版本号存的
# 内存缓存会把第一档的内容端给第二档，与磁盘缓存那颗雷是同一个。
catalog_cache = {}
catalog_cache_lock = threading.Lock()
current_cache_key = None


def _prune_catalog_cache(keep_key=None):
    for key in [k for k in catalog_cache if k != keep_key]:
        catalog_cache.pop(key, None)


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
            _prune_catalog_cache(cdn_downloader.catalog_cache_key(quality, latest_version))
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

    离线降级：CDN 版本号查不到（网络不通）时不再直接判失败 —— 磁盘上
    有缓存的 catalog/bundle_meta 就用缓存那份（可能略旧，但「过没过期」
    的判定用它远好于不判）。缓存里的表对不上才作废（比如游戏刚更新、
    缓存还是上个版本时，查表会把新旧 mod 全判 STALE —— 宁可判「没表」
    也别把整列表标红）。返回时附上降级标记，Kotlin 侧据此亮未校验标识。
    """
    report = _reporter(progress_callback)
    try:
        # 归一化只在 cdn_downloader.normalize_quality 一处做（FHD → HD），
        # 这里手写就重演过「版本映射了、路径没映射 → 404 → 干净检测全空」的事故
        quality = cdn_downloader.normalize_quality(quality)
        version = cdn_downloader.get_cdn_version(quality)
        if not version:
            degraded, meta = _cached_table_from_disk(output_dir, "bundle_meta_", "bundleMeta", quality)
            if degraded and meta:
                report("CDN 版本查询失败，使用磁盘缓存的元数据（可能不是最新版）。")
                return True, "degraded", meta
            return False, "无法获取 CDN 版本号（可能离线），请检查网络后重试。", None

        # 派生表已经在盘上就到这儿为止：65MB 的 catalog 一行都不读。
        # 内存里的 catalog_cache 按 (画质, 版本) 存、且只留一个键，HD 与 SD
        # 版本号不同 → 干净检测/画质自检两档交替取表时会互相驱逐，每次刷新
        # 都要从磁盘 json.loads 两遍 65MB。版本查询照发（要判断表是不是当前
        # 版本），只省掉 catalog 加载。
        cached = catalog_indexer.load_cached_bundle_meta(output_dir, quality, version)
        if cached is not None:
            report(f"Bundle metadata ready: {len(cached)} bundles "
                   f"(version {version}, from disk cache).")
            return True, version, cached

        with catalog_cache_lock:
            _prune_catalog_cache(cdn_downloader.catalog_cache_key(quality, version))
        catalog_content, error = cdn_downloader.download_catalog(
            output_dir, quality, version, catalog_cache, catalog_cache_lock, progress_callback)
        if error:
            degraded, meta = _cached_table_from_disk(output_dir, "bundle_meta_", "bundleMeta", quality)
            if degraded and meta:
                report("catalog 下载失败，使用磁盘缓存的元数据（可能不是最新版）。")
                return True, "degraded", meta
            return False, error, None

        meta = catalog_indexer.load_or_build_bundle_meta(output_dir, quality, version, catalog_content)
        report(f"Bundle metadata ready: {len(meta)} bundles (version {version}).")
        return True, version, meta
    except Exception:
        error_message = _fail(None)[1]
        report(f"Error building bundle metadata: {error_message}")
        return False, error_message, None


def get_bundle_hints(output_dir, quality="HD", progress_callback=None):
    """Kotlin 入口：取 UnityCache 的 32 位 hex 目录名 → [角色 file_id, 槽位]。

    已转换的安卓产物只有 `00044c1c0b4b673e271e…` 这种目录名，而 catalog 的
    download_key 里带着角色号（isolated-cutscene000707-group_…），所以
    只靠一份 CDN catalog 就能把它们还原成「char000707 / cutscene」。这条
    路不依赖游戏目录、也不依赖「扫描游戏资源」，与 characters.json 的
    hashed_name 那条路互补（那条只覆盖 characters.json 生成时的那批）。

    与 get_bundle_meta 同一套降级与缓存前置检查：查不到版本号 / catalog
    下不来时退回磁盘上现成的表；表已在盘上时连 catalog 都不读。
    """
    report = _reporter(progress_callback)
    try:
        quality = cdn_downloader.normalize_quality(quality)
        version = cdn_downloader.get_cdn_version(quality)
        if not version:
            degraded, hints = _cached_table_from_disk(output_dir, "bundle_hints_", "bundleHints", quality)
            if degraded and hints:
                report("CDN 版本查询失败，使用磁盘缓存的 bundle 提示表（可能不是最新版）。")
                return True, "degraded", hints
            return False, "无法获取 CDN 版本号（可能离线），请检查网络后重试。", None

        cached = catalog_indexer.load_cached_bundle_hints(output_dir, quality, version)
        if cached is not None:
            report(f"Bundle hints ready: {len(cached)} bundles "
                   f"(version {version}, from disk cache).")
            return True, version, cached

        with catalog_cache_lock:
            _prune_catalog_cache(cdn_downloader.catalog_cache_key(quality, version))
        catalog_content, error = cdn_downloader.download_catalog(
            output_dir, quality, version, catalog_cache, catalog_cache_lock, progress_callback)
        if error:
            degraded, hints = _cached_table_from_disk(output_dir, "bundle_hints_", "bundleHints", quality)
            if degraded and hints:
                report("catalog 下载失败，使用磁盘缓存的 bundle 提示表（可能不是最新版）。")
                return True, "degraded", hints
            return False, error, None

        hints = catalog_indexer.load_or_build_bundle_hints(output_dir, quality, version, catalog_content)
        report(f"Bundle hints ready: {len(hints)} bundles (version {version}).")
        return True, version, hints
    except Exception:
        error_message = _fail(None)[1]
        report(f"Error building bundle hints: {error_message}")
        return False, error_message, None


def _cached_table_from_disk(output_dir, cache_prefix, payload_key, quality):
    """离线降级：直接读磁盘上现成的派生表，不再下载。

    返回 (可用, 表)。离线时拿不到版本号，只找同画质的
    {cache_prefix}{画质}_{版本}.json 里最新的一份。**不会去读别的画质** ——
    跨档的字节数/哈希对不上，用它查表会把整列表误判「待更新」，正是要避免
    的静默错档；同画质一份都没有时也不用老命名兜底（只有一种例外：盘上还
    没有任何带画质的表，说明用户还没走过 0.2.2 的迁移，那份老命名就是唯一
    的家底，离线有表总比没表强）。

    不解析 catalog（65MB 那份磁盘上当然也有，但几百 KB 的派生表就够查了）。
    """
    candidates = []     # 本画质：版本号
    legacy = []         # 0.2.2 之前不带画质的老命名
    has_quality_tables = False
    quality_prefix = f"{cache_prefix}{quality}_"
    try:
        for name in os.listdir(output_dir):
            if not name.startswith(cache_prefix) or not name.endswith(".json"):
                continue
            if catalog_indexer.is_legacy_cache_name(name, cache_prefix):
                legacy.append(name)
                continue
            has_quality_tables = True
            if name.startswith(quality_prefix):
                # 文件名是 {cache_prefix}{画质}_{版本}.json，按版本号新→旧排
                candidates.append(name[len(quality_prefix):-len(".json")])
    except OSError:
        return False, None

    candidates.sort(reverse=True)   # 版本号形如 20260825131421，字典序即时间序
    paths = [os.path.join(output_dir, f"{quality_prefix}{stem}.json") for stem in candidates]
    if not candidates and not has_quality_tables:
        paths += [os.path.join(output_dir, name) for name in sorted(legacy, reverse=True)]

    for path in paths:
        try:
            with open(path, 'r', encoding='utf-8') as f:
                root = json.loads(f.read())
            payload = root.get(payload_key) if isinstance(root, dict) else None
            if isinstance(payload, dict) and payload:
                return True, payload
        except Exception:
            continue
    return False, None


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
# mod 解析 —— 本地扫描索引为主，没有时退回 catalog 资源地址
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


def _latest_disk_catalog(output_dir, quality):
    """磁盘上最新的 catalog 内容；没有或读坏了返回 None。

    离线（拿不到 CDN 版本号）时唯一的 catalog 来源，等价于
    local_bundle_indexer._parse_catalog_data 的选法。优先本画质的
    catalog_{画质}_{版本}.json，没有才退回任意一份（含不带画质的老命名）
    —— 解析 mod 只用得上 bundle 名与资源地址，这两样与画质无关，
    退而求其次不会给出跨档的错误目标。
    """
    try:
        names = [n for n in os.listdir(output_dir)
                 if n.startswith("catalog_") and n.endswith(".json")]
    except OSError:
        return None
    if not names:
        return None
    preferred = [n for n in names if n.startswith(f"catalog_{quality}_")]
    for name in sorted(preferred or names,
                       key=lambda n: os.path.getmtime(os.path.join(output_dir, n)),
                       reverse=True):
        try:
            with open(os.path.join(output_dir, name), 'r', encoding='utf-8') as f:
                return json.load(f)
        except Exception:
            continue
    return None


def _resolution_index(output_dir, quality, file_names, report):
    """解析 mod 用的查表索引。返回 (index, error)，index 为 None 时 error 是原因。

    优先本地扫描索引（最准，含 bundle 内全部 m_Name）；没有（新装 app、
    没跑过「扫描游戏资源」、清过数据）就现场用 catalog 的资源地址建一份
    够用的——只保留这批 mod 会查的那些键，几十 KB 而不是 20MB。

    「索引在盘上但资产表是空的」也要走 catalog：finalize_scan 扫到 0 个
    bundle 也会落盘一份「空但有效」的索引（用户中途放弃扫描、预筛全跳过），
    只判 `index is not None` 会让兜底永远不触发，表现为整列表 unknown。
    """
    index = local_bundle_indexer.load_local_index(output_dir)
    if index is not None and index.get("assetToBundles"):
        return index, None

    # 先算出这批 mod 真正会查的键：一个都没有就没必要下 60MB catalog
    # （空 mod 列表、只有一个预览图之类的场合）
    wanted = resolver.candidate_keys(file_names)
    if not wanted:
        # 空索引原样返回（好过 None：调用方不用区分「没索引」与「没资产」）
        return index or {"assetToBundles": {}, "catalogAssetToBundle": {}}, None

    quality = cdn_downloader.normalize_quality(quality)
    version = cdn_downloader.get_cdn_version(quality)
    catalog_content = None
    if version:
        with catalog_cache_lock:
            _prune_catalog_cache(cdn_downloader.catalog_cache_key(quality, version))
        catalog_content, error = cdn_downloader.download_catalog(
            output_dir, quality, version, catalog_cache, catalog_cache_lock, None)
        if error:
            catalog_content = None

    if catalog_content is None:
        # 离线降级：磁盘上那份 catalog 对不上版本也比没有强
        catalog_content = _latest_disk_catalog(output_dir, quality)

    if not catalog_content:
        # catalog 也拿不到时，盘上那份空索引仍旧是「什么都没匹配上」的
        # 正确来源（硬判成「请先扫描」会误导：用户可能已经扫过了）
        if index is not None:
            report("No catalog available; falling back to the local bundle index.")
            return index, None
        return None, ("Local bundle index not found and no catalog available. "
                      "Please scan local bundles first.")

    assets = catalog_indexer.build_catalog_asset_index(catalog_content, wanted)
    reason = ("Local bundle index has no assets" if index is not None
              else "Local bundle index not found")
    report(f"{reason}; using catalog addresses instead "
           f"({len(assets)} candidate assets matched).")
    # assetToBundles 留空是给 resolver 的信号：走 catalog 主路径而不是
    # 「扫描为主 + catalog 收窄」。返回结构与扫描索引同一份契约，
    # ModRepository / resolve_mod_folder 都不用改。
    return {"assetToBundles": {}, "catalogAssetToBundle": assets}, None


def resolve_mod_files(file_names_json, output_dir, quality="HD", progress_callback=None):
    """解析一组 mod 文件名。返回 (成功, 结果 JSON 串或错误)。"""
    report = _reporter(progress_callback)
    try:
        file_names = json.loads(file_names_json) if isinstance(file_names_json, str) else file_names_json
        index, error = _resolution_index(output_dir, quality, file_names, report)
        if index is None:
            return False, error
        return True, json.dumps(resolver.resolve_mod_folder(file_names, index))
    except Exception:
        return _fail(None)


def resolve_mod_batch(mods_json, output_dir, quality="HD", progress_callback=None):
    """批量解析（每个 mod 带 id 与 fileNames）。返回 (成功, 结果数组 JSON 串)。"""
    report = _reporter(progress_callback)
    try:
        mods = json.loads(mods_json) if isinstance(mods_json, str) else mods_json
        mods = mods or []
        if not mods:
            return True, json.dumps([])
        # 索引按整批的文件名建一次（每个 mod 各建一份会把 catalog 反复扫）
        all_names = [name for mod in mods for name in (mod.get("fileNames") or [])]
        index, error = _resolution_index(output_dir, quality, all_names, report)
        if index is None:
            return False, error
        results = [{"id": mod.get("id"),
                    "result": resolver.resolve_mod_folder(mod.get("fileNames") or [], index)}
                   for mod in mods]
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
            _prune_catalog_cache(cdn_downloader.catalog_cache_key(download_quality, version))
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


# ---------------------------------------------------------------------------
# 装入校验 —— 外来预转换产物（别人分享的 <bundle>/<hash>/__data）装入前校验
# ---------------------------------------------------------------------------

# 头部快检与 repacker 侧同名私有实现同构（契约层自包含，允许这份少量重复）：
# UnityFS 头布局 = 8B 签名 "UnityFS\x00" + 格式版本 int32 大端 + unity 版本串
# （null 结尾）+ revision 串（null 结尾）+ size int64 大端（声明为 bundle
# 文件总长，实测与 getsize 完全一致）。格式版本值只按布局消费，不参与判断。
_UNITYFS_MAGIC = b"UnityFS\x00"
_HEADER_PEEK = 256      # 头部快检只读前 ~256 字节，不整读
_HEADER_MIN_SIZE = 64   # 连头部都容不下的文件直接判过小


def _brief(e):
    """异常 → 单行简短文案：压平空白并截断到 ~300 字符（Kotlin 展示用）。"""
    text = " ".join(str(e).split())
    if not text:
        text = type(e).__name__
    return text[:300] + ("…" if len(text) > 300 else "")


def _read_cstr(buf, pos):
    """从 pos 起读 null 结尾串；peek 范围内找不到结尾时返回 (None, pos)。"""
    end = buf.find(b"\x00", pos)
    if end < 0:
        return None, pos
    return buf[pos:end].decode("utf-8", "replace"), end + 1


def _peek_bundle_header(bundle_path):
    """UnityFS 头部快检（不整读）：签名合法 + 声明的 bundle 总长不超过实际字节数。

    比对只拒「声明 > 实际」（写了一半的截断文件）。反方向（声明 < 实际）实测
    存在于外部 repacker 的产物里且游戏照常加载 —— 那种不判坏，否则会把能用的
    好 mod 误报成损坏。深层内容校验由 validate_bundle 的 UnityPy 完整加载层负责。

    返回 (True, None) 或 (False, 中文原因)。
    """
    if not os.path.isfile(bundle_path):
        return False, "文件不存在或过小"
    try:
        actual = os.path.getsize(bundle_path)
    except OSError:
        return False, "文件不存在或过小"
    if actual < _HEADER_MIN_SIZE:
        return False, "文件不存在或过小"
    with open(bundle_path, "rb") as f:
        head = f.read(_HEADER_PEEK)
    if not head.startswith(_UNITYFS_MAGIC):
        return False, "不是有效的 UnityFS bundle（头部签名不符，可能不是 Unity 资源）"
    pos = 8 + 4  # 签名 + 格式版本 int32 大端
    unity_version, pos = _read_cstr(head, pos)
    if unity_version is None:
        return False, "UnityFS 头部损坏（unity 版本串缺失）"
    revision, pos = _read_cstr(head, pos)
    if revision is None:
        return False, "UnityFS 头部损坏（revision 串缺失）"
    if pos + 8 > len(head):
        return False, "UnityFS 头部损坏（size 字段缺失）"
    declared = struct.unpack_from(">q", head, pos)[0]
    if declared > actual:
        return False, f"头部声明大小 {declared} B 超过实际 {actual} B（文件被截断）"
    return True, None


def validate_bundle(bundle_path: str):
    """Kotlin 入口：外来预转换产物装入前完整加载校验。

    分两层：先做头部快检（只读前 256 字节 + 取文件大小，不整读），再交给
    UnityPy 完整加载（触发块解压与结构解析），随后遍历一次 env.objects
    （触发对象表解析，只拿对象计数）—— 不深读 typetree、不导出任何数据。
    返回 (True, "ok") 或 (False, 中文原因消息)。
    """
    ok, reason = _peek_bundle_header(bundle_path)
    if not ok:
        return False, reason

    env = None
    try:
        try:
            # vendor 路径已在模块顶部入 sys.path；与 restore_bundle 一样延迟导入
            import UnityPy
            env = UnityPy.load(bundle_path)
        except Exception as e:
            import traceback
            print(f"validate_bundle failed to load: {e}")
            print(traceback.format_exc())
            return False, f"bundle 加载失败：{_brief(e)}"
        try:
            object_count = sum(1 for _ in env.objects)
        except Exception as e:
            import traceback
            print(f"validate_bundle failed to parse objects: {e}")
            print(traceback.format_exc())
            return False, f"bundle 对象表解析失败：{_brief(e)}"
        print(f"validate_bundle ok: {object_count} objects")
        return True, "ok"
    finally:
        del env
        import gc
        gc.collect()
