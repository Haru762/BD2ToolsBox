"""本地游戏 bundle 扫描索引：把「资产名 → bundle」的映射建起来。

为什么需要它：mod 的文件名要落到游戏 bundle 才能替换。catalog 里登记的
是资源地址（assets/asset/character/…），与 bundle 内对象的 m_Name 对不
上，所以得把游戏目录里的 bundle 逐个解开，读出每个对象的 m_Name。

三步式 API（为 Kotlin/Shizuku 协作设计——Kotlin 管文件搬运，Python 只管解析）：
  check_scan_needed()  对照缓存给出需要重扫的 bundle 清单
  scan_single_bundle() 扫一个临时路径上的 __data（Kotlin 经 Shizuku 拷出来）
  finalize_scan()      合并缓存 + 新扫结果，落盘最终索引

mod 能替换的只有骨架与立绘，其余 bundle（UI/音效/场景/字体）扫了也匹配
不上——catalog 预筛能把 1780 个 bundle 砍到 309 个（拷贝量 14.47GB →
4.19GB）。catalog 不可用时退回全量扫描，宁可慢也不能认不出 mod。
"""
import glob
import json
import os
import time

_UnityPy = None

INDEX_SCHEMA_VERSION = 3

# 只扫 m_Name 有意义的三类对象，跳过 Transform/Material/Shader 等的 obj.read()
SCAN_TYPES = frozenset({"Texture2D", "TextAsset", "Sprite"})

# Texture2D/Sprite 的 m_Name 无扩展名，补上 .png 对齐 unpacker 的导出习惯
_EXTENSION_MAP = {"Texture2D": ".png", "Sprite": ".png"}

# 一次扫描会话的状态：check_scan_needed 填充，scan_single_bundle 更新，
# finalize_scan 消费后清空
_scan_state = None


def _ensure_unitypy():
    """懒加载 UnityPy 并配置回退版本号（bundle 内无版本头时的兜底）。"""
    global _UnityPy
    if _UnityPy is not None:
        return _UnityPy
    from UnityPy.helpers import TypeTreeHelper
    TypeTreeHelper.read_typetree_boost = False
    import UnityPy
    UnityPy.config.FALLBACK_UNITY_VERSION = '2022.3.22f1'
    _UnityPy = UnityPy
    return _UnityPy


def _read_name_fast(obj):
    """直接从原始字节读 m_Name，跳过完整反序列化。

    Texture2D/TextAsset/Sprite 的 m_Name 都是第一个序列化字段，
    Unity 字符串布局是 [int32 长度][UTF-8][4 字节对齐填充]。
    只读名字就能跳过贴图数据、脚本文本、网格 —— 那是数据量的大头。
    """
    try:
        obj.reset()
        return obj.reader.read_aligned_string()
    except Exception:
        try:
            return getattr(obj.read(), "m_Name", None)
        except Exception:
            return None


def _scan_bundle_file(file_path):
    """解开一个 bundle，返回其中全部资产名（小写、去重、排序）。

    只遍历 SCAN_TYPES 里的对象类型，名字用快速读取。
    """
    env = _ensure_unitypy().load(file_path)
    names = set()
    for obj in env.objects:
        type_name = obj.type.name
        if type_name not in SCAN_TYPES:
            continue
        raw = _read_name_fast(obj)
        if not raw:
            continue
        name = raw.strip().lower()
        if not name:
            continue
        ext = _EXTENSION_MAP.get(type_name, "")
        if ext and not name.endswith(ext):
            name += ext
        names.add(name)
    return sorted(names)


def _load_existing_cache(cache_path):
    """读磁盘上的索引缓存；schema 不符/损坏返回 None。"""
    try:
        if os.path.isfile(cache_path):
            with open(cache_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            if data.get("schemaVersion") == INDEX_SCHEMA_VERSION:
                return data
    except Exception:
        pass
    return None


# ---------------------------------------------------------------------------
# catalog 交叉信息（复用 catalog_parser 的 Catalog）
# ---------------------------------------------------------------------------

def _parse_catalog_data(output_dir):
    """从盘上最新的 catalog 解出 (download_names, bundle → 资源地址列表)。

    给两处用：catalog 预筛（哪些 bundle 与 mod 相关）与歧义消解
    （同名资产出现在多个 bundle 时按 catalog 评分挑最像的）。
    catalog 文件不存在或解析失败返回 ({}, {})。
    """
    catalogs = glob.glob(os.path.join(output_dir, "catalog_*.json"))
    if not catalogs:
        return {}, {}
    catalog_path = max(catalogs, key=os.path.getmtime)
    try:
        with open(catalog_path, 'r', encoding='utf-8') as f:
            content = json.load(f)
    except Exception as e:
        print(f"Error parsing catalog data: {e}")
        return {}, {}

    try:
        from catalog_parser import Catalog
    except ImportError:
        return {}, {}

    catalog = Catalog(content)
    if not catalog.bundles:
        return {}, {}

    download_names = {}
    for info in catalog.bundles.values():
        name = info.get('bundle_name')
        if name:
            download_names[str(name)] = str(info.get('download_key') or '')

    # bundle → 资源地址列表（bundle entry 自身除外，按依赖链归属）
    bundle_to_keys = {}
    for i in range(len(catalog.entries)):
        if i in catalog.bundles:
            continue
        key = catalog.primary_key_of(i)
        if not isinstance(key, str):
            continue
        bundle = catalog.bundle_of(i)
        if bundle and bundle.get('bundle_name'):
            bundle_to_keys.setdefault(bundle['bundle_name'], []).append(key.lower())
    return download_names, bundle_to_keys


def _relevant_bundles_from_catalog(output_dir):
    """catalog 预筛：只留装着骨架/立绘的 bundle。catalog 不可用返回 None。

    **已知会误杀**：spine 资产装在哪只有 bundle 内 m_Name 说了算，
    timeline/storypack/localpacktitle 类 bundle 的 catalog 地址不含
    illust/skeletondata，预筛会把它们连同里面的 illust_special24.skel
    一起剔除。仅 check_scan_needed(full_scan=False) 时使用，不是默认。"""
    try:
        _, bundle_to_keys = _parse_catalog_data(output_dir)
    except Exception:
        return None
    if not bundle_to_keys:
        return None

    relevant = set()
    for bundle_name, keys in bundle_to_keys.items():
        if any(("skeletondata" in k or "illust" in k) for k in keys):
            relevant.add(bundle_name)
    # 条目太少说明 catalog 格式变了或解析出错，不敢据此裁剪
    if len(relevant) < 20:
        return None
    return relevant


# ---------------------------------------------------------------------------
# 三步式扫描 API
# ---------------------------------------------------------------------------

def check_scan_needed(output_dir, bundle_list_json, full_scan=True):
    """Step 1：对照缓存，给出需要重扫的 bundle 名单（JSON 串）。

    bundle_list_json 是 Kotlin 经 Shizuku 从游戏 Shared/ 拿到的目录清单：
    [{"name": bundle名, "hash": 哈希目录名}, ...]。哈希没变的直接复用缓存
    扫描结果。

    默认全量扫描（full_scan=True）。此前默认走 catalog 预筛（只扫地址含
    illust/skeletondata 的 bundle），但 spine 资产实际装在哪个 bundle 里
    只有 bundle 内 m_Name 说了算 —— timeline / storypack / localpacktitle
    这类 bundle 的 catalog 地址一个关键词都不含，预筛把它们剔除后索引里
    永远查不到这些名字（表现为剧情/好感类 mod 永远 UNKNOWN）。预筛的
    收益只是首次扫描少拷 ~14GB，代价是认不出用户的 mod，不值。

    预筛保留为 full_scan=False 的可选路径：扫描代价敏感、只装角色立绘类
    mod 的用户可以显式选择。缓存机制两种模式下照旧 —— 首次全量只付一次，
    之后游戏更新只重扫哈希变化的 bundle。
    """
    global _scan_state

    bundle_list = json.loads(bundle_list_json) if isinstance(bundle_list_json, str) else bundle_list_json

    cache_path = os.path.join(output_dir, "local_bundle_index.json")
    existing = _load_existing_cache(cache_path)
    cached_bundles = existing.get("scannedBundles", {}) if existing else {}

    relevant = None if full_scan else _relevant_bundles_from_catalog(output_dir)

    needs_scan = []
    still_valid = {}
    skipped_irrelevant = 0

    for item in bundle_list:
        name, hash_ = item["name"], item["hash"]
        cached = cached_bundles.get(name)
        if cached and cached.get("hash") == hash_:
            still_valid[name] = cached
        elif relevant is not None and name not in relevant:
            skipped_irrelevant += 1
        else:
            needs_scan.append(name)

    if skipped_irrelevant:
        print(f"catalog 预筛跳过 {skipped_irrelevant} 个与 mod 无关的 bundle，"
              f"待扫 {len(needs_scan)} 个")

    _scan_state = {
        "output_dir": output_dir,
        "all_bundle_hashes": {item["name"]: item["hash"] for item in bundle_list},
        "cached": still_valid,
        "scanned": {},
    }

    return json.dumps(needs_scan)


def scan_single_bundle(bundle_name, bundle_hash, temp_data_path, progress_callback=None):
    """Step 2：扫描一个临时路径上的 __data。返回 (成功, 资产数, 消息)。

    Kotlin 的职责：Shizuku 拷文件 → 调这里 → 删临时文件。失败也记进
    状态（error 字段），不中断整轮扫描。
    """
    global _scan_state
    if _scan_state is None:
        return False, 0, "No scan in progress. Call check_scan_needed first."

    try:
        assets = _scan_bundle_file(temp_data_path)
        _scan_state["scanned"][bundle_name] = {"hash": bundle_hash, "assets": assets}
        msg = f"OK: {len(assets)} assets"
        if progress_callback:
            progress_callback(f"Scanned {bundle_name}: {len(assets)} assets")
        return True, len(assets), msg
    except Exception as e:
        _scan_state["scanned"][bundle_name] = {"hash": bundle_hash, "assets": [], "error": str(e)}
        if progress_callback:
            progress_callback(f"Failed {bundle_name}: {e}")
        return False, 0, str(e)


def _score_bundle_for_asset(asset_name, catalog_keys):
    """按 catalog 资源地址给 bundle 打分（越高越像装着这个资产）。

    文件名精确命中 100 分；词干出现在文件名段 50+len；出现在整条地址里
    10+len。"""
    stem = asset_name.rsplit('.', 1)[0] if '.' in asset_name else asset_name
    best = 0
    for key in catalog_keys:
        filename = key.rsplit('/', 1)[-1]
        if filename == asset_name:
            return 100
        if stem in filename:
            best = max(best, 50 + len(stem))
        elif stem in key:
            best = max(best, 10 + len(stem))
    return best


def _get_latest_catalog_mtime(output_dir):
    """最新 catalog_*.json 的 mtime，没有则 0。"""
    catalogs = glob.glob(os.path.join(output_dir, "catalog_*.json"))
    if not catalogs:
        return 0
    try:
        return os.path.getmtime(max(catalogs, key=os.path.getmtime))
    except OSError:
        return 0


def _apply_catalog_disambiguation(index_data, output_dir):
    """（重）算 catalogAssetToBundle：多 bundle 命中的资产按 catalog 挑最像的。

    在加载时也重算一次，保证消解结果反映盘上最新的 catalog（即便扫描
    那会儿 catalog 还没下载）。顺带刷新 scannedBundles 里的 downloadName。
    返回 (歧义资产数, 消解数)。
    """
    asset_to_bundles = index_data.get("assetToBundles", {})
    download_names, bundle_to_keys = _parse_catalog_data(output_dir)

    # downloadName 无实际消费方（Kotlin 只读 hash/assets），填充只为对齐旧索引结构
    for bundle_name, info in index_data.get("scannedBundles", {}).items():
        info["downloadName"] = download_names.get(bundle_name, "")

    # bundle 名 → catalog keys 只对扫过的 bundle 有用，先按需建索引
    catalog_asset_to_bundle = {}
    ambiguous = resolved = 0
    for asset_name, bundle_list in asset_to_bundles.items():
        if len(bundle_list) <= 1:
            continue
        ambiguous += 1
        best_bundle, best_score = None, 0
        for bundle_name in bundle_list:
            score = _score_bundle_for_asset(asset_name, bundle_to_keys.get(bundle_name, []))
            if score > best_score:
                best_score, best_bundle = score, bundle_name
        if best_bundle:
            catalog_asset_to_bundle[asset_name] = best_bundle
            resolved += 1

    index_data["catalogAssetToBundle"] = catalog_asset_to_bundle
    return ambiguous, resolved


def finalize_scan(output_dir, progress_callback=None):
    """Step 3：合并缓存与新扫结果，建索引落盘。返回 (成功, 消息)。

    partial 结果也合法（用户中途停止扫描）。catalogAssetToBundle 不落盘，
    加载时从最新 catalog 重算（见 _apply_catalog_disambiguation）。
    """
    global _scan_state
    report = (lambda m: (progress_callback and progress_callback(m), print(m)))

    if _scan_state is None:
        return False, "No scan in progress. Call check_scan_needed first."

    try:
        all_bundles = dict(_scan_state["cached"])
        all_bundles.update(_scan_state["scanned"])
        cached_count = len(_scan_state["cached"])
        scanned_count = len(_scan_state["scanned"])
        failed_count = sum(1 for i in _scan_state["scanned"].values() if i.get("error"))

        # 资产名 → bundle 列表（扫描索引，同名资产可能出现在多个 bundle）
        asset_to_bundles = {}
        for bundle_name, info in all_bundles.items():
            for asset_name in info.get("assets", []):
                bundles = asset_to_bundles.setdefault(asset_name, [])
                if bundle_name not in bundles:
                    bundles.append(bundle_name)

        os.makedirs(output_dir, exist_ok=True)
        index = {
            "schemaVersion": INDEX_SCHEMA_VERSION,
            "scannedAt": int(time.time()),
            "bundleCount": len(all_bundles),
            "assetCount": len(asset_to_bundles),
            "assetToBundles": asset_to_bundles,
            "catalogAssetToBundle": {},
            "scannedBundles": all_bundles,
        }

        ambiguous, resolved = _apply_catalog_disambiguation(index, output_dir)
        report(f"Catalog disambiguation: {ambiguous} ambiguous assets, {resolved} resolved")

        with open(os.path.join(output_dir, "local_bundle_index.json"), "w",
                  encoding="utf-8") as f:
            json.dump(index, f, ensure_ascii=False, separators=(",", ":"))

        msg = (f"Index saved: {len(all_bundles)} bundles, {len(asset_to_bundles)} assets "
               f"(cached: {cached_count}, scanned: {scanned_count}, failed: {failed_count})")
        report(msg)
        return True, msg
    except Exception:
        import traceback
        error_msg = traceback.format_exc()
        report(f"Error finalizing scan: {error_msg}")
        return False, error_msg
    finally:
        _scan_state = None


# ---------------------------------------------------------------------------
# 索引加载（resolver 用）
# ---------------------------------------------------------------------------

# 内存缓存：这个 JSON 很大，别每次 resolve 都重读重解
_index_cache = None
_index_cache_mtime = 0
_catalog_cache_mtime = 0


def load_local_index(output_dir):
    """加载本地索引（带内存缓存）。

    catalogAssetToBundle 总是从盘上最新 catalog 重算，保证扫描时没有
    catalog 的场合也能正确消解。索引或 catalog 的 mtime 变了缓存失效。
    没有有效缓存返回 None。
    """
    global _index_cache, _index_cache_mtime, _catalog_cache_mtime

    cache_path = os.path.join(output_dir, "local_bundle_index.json")
    try:
        current_mtime = os.path.getmtime(cache_path)
    except OSError:
        return None

    current_catalog_mtime = _get_latest_catalog_mtime(output_dir)
    if (_index_cache is not None
            and current_mtime == _index_cache_mtime
            and current_catalog_mtime == _catalog_cache_mtime):
        return _index_cache

    data = _load_existing_cache(cache_path)
    if data is not None:
        _apply_catalog_disambiguation(data, output_dir)
        _index_cache = data
        _index_cache_mtime = current_mtime
        _catalog_cache_mtime = current_catalog_mtime
    return data
