"""catalog → bundle 元数据表，带磁盘缓存。

bundle meta 用于「干净检测」：游戏目录里 Shared/<name>/<hash>/__data 的
目录名与字节数若同时等于 catalog 登记的内容哈希与原版大小，就说明这个
bundle 没被 mod 改过，可直接拿来当重打包基底（省一次 CDN 下载）；
大小不符说明里面装着 mod。

另有两张「不依赖本地游戏目录」的派生表，供新装 / 无游戏 / 没扫描过的
场合兜底（见各自 docstring）：
  build_bundle_hints        bundle 名(hex) → [角色 file_id, 槽位]
  build_catalog_asset_index 资产名 → [bundle 名]

耗时（Windows 桌面实测，63MB / 42 万条目的 HD catalog；安卓上按 2~3 倍估）：
`json.load` 0.27s，`Catalog` 构造 1.28s —— 构造里现在无条件解码全部 key
数组（26MB base64 / 34.8 万条），这是所有派生表共同的大头。所以
bundle meta ≈ 1.4s、hints ≈ 1.4s（都只多遍历一张小表，各自另加 ~0.1s），
asset 索引 ≈ 2.0~2.4s（还要按条目走依赖链读地址）——也是唯一按需裁剪的表。
"""
import json
import os
import re
from pathlib import Path

from catalog_parser import Catalog, read_int32

# bundle_meta 缓存的结构版本号；布局变了就 bump，老缓存自动作废重建
BUNDLE_META_SCHEMA_VERSION = 1

# bundle_hints 缓存的结构版本号，同上
BUNDLE_HINTS_SCHEMA_VERSION = 1


def build_bundle_meta(catalog_content):
    """catalog → {bundle 名: [原版字节数, 内容哈希]}。"""
    meta = {}
    catalog = Catalog(catalog_content)
    for info in catalog.bundles.values():
        name = info.get('bundle_name')
        size = info.get('bundle_size')
        bhash = info.get('bundle_hash')
        if name and size is not None and bhash:
            meta[name] = [size, bhash]
    return meta


def load_or_build_bundle_meta(output_dir, quality, version, catalog_content):
    """带磁盘缓存的 [build_bundle_meta]。缓存约 150KB，远快于重解析。"""
    return _load_or_build_cached(
        output_dir, quality, version, "bundle_meta_", "bundleMeta",
        BUNDLE_META_SCHEMA_VERSION,
        lambda: build_bundle_meta(catalog_content))


def load_cached_bundle_meta(output_dir, quality, version):
    """只读 [load_or_build_bundle_meta] 的磁盘缓存，**不加载 catalog**。

    调用方在下载/读取 65MB catalog 之前先探一次：命中就整个跳过 catalog。
    """
    return load_cached_table(output_dir, "bundle_meta_", quality, version,
                             "bundleMeta", BUNDLE_META_SCHEMA_VERSION)


# ---------------------------------------------------------------------------
# 派生表磁盘缓存（bundle_meta / bundle_hints 共用）
# ---------------------------------------------------------------------------

def cache_file_name(cache_prefix, quality, version):
    """派生表缓存文件名：{前缀}{画质}_{版本}.json。

    画质段不能省。HD/SD 的 CDN 版本号今天恰好不同，但一旦两档共用一个
    版本串，不带画质的文件名会让第二档直接命中第一档的表 —— 跨档产物的
    字节数/哈希全对不上，会被整片误判「待更新」并改名（静默错档）。
    与 cdn_downloader 的 catalog_{画质}_{版本}.json 同一套命名。
    """
    return f"{cache_prefix}{quality}_{version}.json"


def is_legacy_cache_name(name, cache_prefix):
    """是否是 0.2.2 之前那种不带画质的老命名 {前缀}{版本}.json。

    版本号是纯数字，所以 `bundle_meta_HD_2026….json` 不会被误判成老命名。
    """
    if not name.startswith(cache_prefix) or not name.endswith(".json"):
        return False
    return name[len(cache_prefix):-len(".json")].isdigit()


def load_cached_table(output_dir, cache_prefix, quality, version, payload_key,
                      schema_version):
    """只读一张派生表的磁盘缓存，**不加载 catalog**，不构建、不清理。

    没有 / schema 不符 / 读坏 / 载荷缺失一律返回 None；缓存有效但内容为空
    时返回的是空 dict，所以调用方要判 `is not None` 而不是真值。
    """
    path = Path(output_dir).joinpath(cache_file_name(cache_prefix, quality, version))
    try:
        cached = json.loads(path.read_text(encoding='utf-8'))
    except Exception:
        return None
    if not isinstance(cached, dict) or cached.get('schemaVersion') != schema_version:
        return None
    payload = cached.get(payload_key)
    return payload if isinstance(payload, dict) else None


def _prune_cache_files(output_dir, cache_prefix, quality, keep_name):
    """重建时清场：同前缀同画质的其他版本 + 老命名。

    只清同画质，**别的画质一律不动** —— 画质自检会同时用 HD 与 SD 两档，
    互删会让另一档每次重下 65MB（老实现那条「保留最近两份」的注释讲的就是
    这个坑）。老命名（不带画质）内容属于哪一档已无从考证，宁可让它按新名字
    重建（catalog 已在盘上，重建只要一两秒），也不能挂着错误画质的名字用。
    """
    try:
        names = os.listdir(output_dir)
    except OSError:
        return
    same_quality_prefix = f"{cache_prefix}{quality}_"
    for name in names:
        if name == keep_name:
            continue
        if not name.endswith(".json"):
            continue
        if name.startswith(same_quality_prefix) or is_legacy_cache_name(name, cache_prefix):
            try:
                os.remove(os.path.join(output_dir, name))
            except OSError:
                pass


def _load_or_build_cached(output_dir, quality, version, cache_prefix, payload_key,
                          schema_version, build):
    """派生表磁盘缓存的通用流程（bundle_meta / bundle_hints 共用）。

    命中且 schema 对得上就直接返回；schema 不符或读坏了删掉重建；重建时
    清掉同画质的旧版本与不带画质的老命名。
    """
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)

    cached = load_cached_table(output_dir, cache_prefix, quality, version,
                               payload_key, schema_version)
    if cached is not None:
        return cached

    file_name = cache_file_name(cache_prefix, quality, version)
    cache_path = output_path.joinpath(file_name)
    try:
        cache_path.unlink()      # schema 不符 / 读坏的那份，别留在盘上
    except OSError:
        pass
    _prune_cache_files(output_dir, cache_prefix, quality, file_name)

    payload = build()
    try:
        with open(cache_path, 'w', encoding='utf-8') as f:
            json.dump({'schemaVersion': schema_version, payload_key: payload},
                      f, ensure_ascii=False, separators=(',', ':'))
    except Exception:
        pass
    return payload


# ---------------------------------------------------------------------------
# bundle 名(hex) → [角色 file_id, 槽位]（已转换产物的命名兜底）
# ---------------------------------------------------------------------------

# CDN 下载文件名里唯一带得出角色的形态：按角色隔离的过场包
#   isolated-cutscene000707-group_assets_all_<hash>.bundle
# 全量盘点 2023 个 bundle：168 个 isolated-cutscene* 全部命中这条，
# 其余（common-char-atlas / common-specialillust_1 / packNNNN）都是多角色
# 共享包或剧情包，文件名里没有角色号 —— 一律跳过，不猜。
_CUTSCENE_IN_DOWNLOAD_KEY = re.compile(r'^isolated-cutscene(\d{6})-group_')

# 过场包在 characters.json 里的槽位名（与
# catalog_parser.parse_catalog_for_bundle_names 的槽位取值同源）
CUTSCENE_SLOT = "cutscene"


def build_bundle_hints(catalog_content):
    """catalog → {bundle 名(hex): [角色 file_id, 槽位]}。

    UnityCache 的目录名就是 catalog 登记的 bundle_name（32 位小写 hex，
    跨版本稳定），所以拿 hex 名可以直接查这张表。file_id 与槽位跟
    characters.json 是同一套写法（char000707 / cutscene），Kotlin 侧能
    直接拿去翻 characters.json 的 file_id 表，也能直接映射成中文标签。

    实测：2023 个 bundle 里 168 个能推出来，且与 catalog 地址
    （SkeletonData/cutscene_charNNNNNN/…）对得上 166/168，剩下 2 个是
    该过场包同时收了别的角色素材，文件名给的角色号仍然是对的。
    """
    hints = {}
    catalog = Catalog(catalog_content)
    for info in catalog.bundles.values():
        name = info.get('bundle_name')
        match = _CUTSCENE_IN_DOWNLOAD_KEY.match(info.get('download_key') or '')
        if name and match:
            hints[str(name)] = ["char" + match.group(1), CUTSCENE_SLOT]
    return hints


def load_or_build_bundle_hints(output_dir, quality, version, catalog_content):
    """带磁盘缓存的 [build_bundle_hints]。表约 10KB，远快于重解析 60MB catalog。"""
    return _load_or_build_cached(
        output_dir, quality, version, "bundle_hints_", "bundleHints",
        BUNDLE_HINTS_SCHEMA_VERSION,
        lambda: build_bundle_hints(catalog_content))


def load_cached_bundle_hints(output_dir, quality, version):
    """只读 [load_or_build_bundle_hints] 的磁盘缓存，**不加载 catalog**。"""
    return load_cached_table(output_dir, "bundle_hints_", quality, version,
                             "bundleHints", BUNDLE_HINTS_SCHEMA_VERSION)


# ---------------------------------------------------------------------------
# 资产名 → [bundle 名]（没有本地扫描索引时的解析兜底）
# ---------------------------------------------------------------------------

# catalog 登记的是资源地址（.skel.bytes / .atlas.txt），而 bundle 内资产的
# m_Name（也就是 mod 文件名）只到 .skel / .atlas —— 末段要桥接一次。
# 实测：SkeletonData/cutscene_char000707/cutscene_char000707.skel.bytes 所在
# 的 bundle 里，TextAsset 的 m_Name 正是 cutscene_char000707.skel。
_ADDRESS_EXT_ALIASES = (('.skel.bytes', '.skel'), ('.atlas.txt', '.atlas'))


def _address_aliases(segment):
    """地址末段 → 它在 assetToBundles 里可能出现的那几种键（全小写）。"""
    low = segment.lower()
    aliases = {low}
    for registered, asset_name in _ADDRESS_EXT_ALIASES:
        if low.endswith(registered):
            aliases.add(low[:-len(registered)] + asset_name)
    return aliases


def build_catalog_asset_index(catalog_content, wanted_keys=None):
    """catalog → {资产名: [bundle 名]}，形状与扫描索引的 assetToBundles 同套。

    wanted_keys 给定时只保留命中的键。必须给：全量索引实测 28 万个键、
    19.7MB JSON、构造期约 3 秒，安卓端内存扛不住；而一次解析最多关心
    几百个文件名。传 None 只用于离线核对/测试。

    同名资产可能落在多个 bundle（catalog 里地址唯一，但末段会重名），
    所以值是列表，交给调用方求交集收窄。
    """
    index = {}
    wanted = None if wanted_keys is None else set(wanted_keys)
    catalog = Catalog(catalog_content)
    for i in range(len(catalog.entries)):
        entry_index = i
        if entry_index in catalog.bundles:
            continue
        address = catalog.primary_key_of(entry_index)
        if not isinstance(address, str):
            continue
        bundle = catalog.bundle_of(entry_index)
        bundle_name = bundle and bundle.get('bundle_name')
        if not bundle_name:
            continue

        for alias in _address_aliases(address.rsplit('/', 1)[-1]):
            if wanted is not None and alias not in wanted:
                continue
            bundles = index.setdefault(alias, [])
            if bundle_name not in bundles:
                bundles.append(bundle_name)
    return index


# 保留导出：少数调用方（旧代码 / 测试）直接引 read_int32 做二进制探测
__all__ = ["build_bundle_meta", "load_or_build_bundle_meta", "load_cached_bundle_meta",
           "read_int32", "BUNDLE_META_SCHEMA_VERSION",
           "build_bundle_hints", "load_or_build_bundle_hints", "load_cached_bundle_hints",
           "BUNDLE_HINTS_SCHEMA_VERSION", "CUTSCENE_SLOT",
           "build_catalog_asset_index",
           "cache_file_name", "is_legacy_cache_name", "load_cached_table"]
