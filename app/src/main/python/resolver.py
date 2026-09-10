"""mod 文件名 → 目标 bundle 的解析。

三条查表来源，按可用性自动选：

1. **本地扫描索引**（local_bundle_index.json 的 assetToBundles）—— 主路径。
   索引里资产按 bundle 内的 m_Name 登记（如 char000104.png），mod 文件名
   只需做少量扩展名换算就能直接查表：

       char000104.png      → 查 char000104.png
       char000104.json     → 另试 char000104.skel（json 动画 → 骨架 TextAsset）
       char000104.atlas.txt → 另试 char000104.atlas

2. **catalog 资源地址**（catalogAssetToBundle）—— 没有扫描索引时的兜底。
   新装 app / 没跑过「扫描游戏资源」的场合只有 CDN catalog 可用；catalog
   的资源地址末段与 bundle 内 m_Name 是同一套命名（见
   catalog_indexer.build_catalog_asset_index），所以同一套候选名直接查表。
   有扫描索引时不走这条路（扫描为主、catalog 只用来收窄多命中）。

3. 两条都没有 → 判 UNKNOWN，交给上层提示去扫描。

流程分三步：每文件查候选 → catalog 映射收窄多命中 → 全部文件求公共
bundle。返回结构保持与 Kotlin 层的契约不变。
"""
import re
from pathlib import Path

# 扩展名桥接表：输入 → 索引里可能登记过的资产名。
#   catalog 惯例：.skel.bytes/.atlas.txt 是 catalog 的写法，bundle m_Name 只带首段
#   json 动画：mod 提供的 .json 在 bundle 里是 .skel TextAsset
#   （.skel.txt 不是已知惯例，不桥接 —— 与老实现行为一致）
_EXTENSION_BRIDGES = (
    ('.json',       ('.skel',)),
    ('.skel.bytes', ('.skel',)),
    ('.atlas.txt',  ('.atlas',)),
)

_COMPOUND_EXTS = ('.skel.bytes', '.atlas.txt')


def _expand_candidates(base_name):
    """mod 文件名 → 索引里可能登记过的资产名列表（全部小写）。

    桥接基于「复合扩展名的首段」：char000104.atlas.txt 的复合扩展名是
    .atlas.txt，剥掉它得词干 char000104，加回首段 .atlas → char000104.atlas。
    """
    lowered = (base_name or "").strip().lower()
    if not lowered:
        return []
    candidates = [lowered]
    for suffix, targets in _EXTENSION_BRIDGES:
        if not lowered.endswith(suffix):
            continue
        stem = lowered[:-len(suffix)]
        for t in targets:
            bridged = stem + t
            if bridged and bridged not in candidates:
                candidates.append(bridged)
    return candidates


def candidate_keys(file_names):
    """一组 mod 文件名 → 可能命中的资产名集合（小写、去重）。

    给「按需建 catalog 资产索引」用：全量索引太大，先算出这批 mod 真正
    会查的那些键，让 catalog_indexer 只留命中的。
    """
    keys = set()
    for file_name in file_names or []:
        keys.update(_expand_candidates(Path(file_name).name))
    return keys


def _as_bundle_names(value):
    """catalogAssetToBundle 的一条值 → bundle 名列表。

    两个来源形状不同：扫描索引里它是单个字符串（歧义消解的结果），
    build_catalog_asset_index 给的是列表（同名资产可能落在多个 bundle）。
    """
    if not value:
        return ()
    if isinstance(value, str):
        return (value,)
    return tuple(value)


def _stem_of(filename):
    """去掉复合/单层扩展名的词干：char000104_2.skel.bytes → char000104。"""
    for ext in _COMPOUND_EXTS:
        if filename.endswith(ext):
            filename = filename[:-len(ext)]
            break
    else:
        filename = Path(filename).stem
    # 去掉页号后缀（_2/_3 是图集分页）
    filename = re.sub(r'_\d+$', '', filename)
    return filename or None


def _infer_asset_type(file_name):
    """按扩展名粗分资产类型，给 UI 展示用。"""
    lowered = (file_name or "").lower()
    if lowered.endswith('.png'):
        return 'Texture2D'
    if lowered.endswith('.json'):
        return 'JsonSkeleton'
    if any(lowered.endswith(ext) for ext in ('.atlas', '.atlas.txt', '.skel', '.skel.txt', '.skel.bytes')):
        return 'TextAsset'
    return 'Unknown'


def resolve_mod_folder(mod_file_names, local_index):
    """把一个 mod 的全部文件名解析到它们共同所属的 bundle。

    local_index 需含 assetToBundles（扫描得到）与 catalogAssetToBundle
    （catalog 权威映射），两者缺一即按另一条路走：

    - 有 assetToBundles：扫描为主（索引里是 bundle 内 m_Name，最准），
      多命中时用 catalogAssetToBundle 收窄——扫描索引分不出同名资产在
      不同 bundle 里的情况，catalog 能。
    - assetToBundles 空/缺失：catalogAssetToBundle 直接当主表用
      （catalog 地址末段 → bundle），策略标 CATALOG_ONLY。这条路不依赖
      游戏目录扫描，是「新装 app / 没扫过资源」时的兜底。

    返回结构（Kotlin 契约，勿改）：
      targetHash / resolvedFamilyKey / resolvedTargets / unresolvedFiles /
      resolutionState（KNOWN|UNKNOWN|INVALID）/ errorReason
    """
    asset_to_bundles = (local_index or {}).get("assetToBundles") or {}
    catalog_asset_to_bundle = (local_index or {}).get("catalogAssetToBundle") or {}
    catalog_only = not asset_to_bundles and bool(catalog_asset_to_bundle)

    matches = []      # [{fileName, candidates, candidate, bundles, strategy}]
    unresolved = []
    for file_name in mod_file_names or []:
        base_name = Path(file_name).name
        candidates = _expand_candidates(base_name)

        hit_candidate = None
        bundles = set()
        if catalog_only:
            # catalog 地址末段与 m_Name 同套命名，候选名可直接查表；
            # 地址是权威登记，同一个名字落在多个 bundle 时无法再收窄，
            # 全收下来交给下面的公共 bundle 求交集
            for candidate in candidates:
                found = _as_bundle_names(catalog_asset_to_bundle.get(candidate))
                if not found:
                    continue
                if hit_candidate is None:
                    hit_candidate = candidate
                bundles.update(found)
            strategy = 'CATALOG_ONLY'
        else:
            for candidate in candidates:
                found = asset_to_bundles.get(candidate)
                if found:
                    if hit_candidate is None:
                        hit_candidate = candidate
                    bundles.update(found)

            # 多命中时用 catalog 权威映射收窄到其中一个
            if len(bundles) > 1:
                for candidate in candidates:
                    catalog_bundle = catalog_asset_to_bundle.get(candidate)
                    if catalog_bundle and catalog_bundle in bundles:
                        bundles = {catalog_bundle}
                        strategy = 'CATALOG_FILTERED'
                        break
                else:
                    strategy = 'LOCAL_SCAN'
            else:
                strategy = 'LOCAL_SCAN'

        if hit_candidate and bundles:
            matches.append({
                'fileName': base_name,
                'candidates': candidates,
                'candidate': hit_candidate,
                'bundles': bundles,
                'strategy': strategy,
            })
        else:
            unresolved.append(base_name)

    if not matches:
        return {
            'targetHash': None,
            'resolvedFamilyKey': None,
            'resolvedTargets': [],
            'unresolvedFiles': unresolved,
            'resolutionState': 'UNKNOWN',
            'errorReason': ('No matching bundle found in catalog'
                            if catalog_only else 'No matching bundle found in local index')
        }

    # 全部文件求公共 bundle；文件本身命中多个时按字典序取（确定性）
    intersection = set(matches[0]['bundles'])
    for m in matches[1:]:
        intersection &= m['bundles']
    union = set()
    for m in matches:
        union |= m['bundles']

    if len(intersection) == 1:
        target_bundle = next(iter(intersection))
    elif len(intersection) > 1:
        target_bundle = sorted(intersection)[0]
    elif len(union) == 1:
        target_bundle = next(iter(union))
    else:
        # 各文件指向不同 bundle，没法装进一个目标
        return {
            'targetHash': None,
            'resolvedFamilyKey': None,
            'resolvedTargets': [_target(m) for m in matches],
            'unresolvedFiles': unresolved,
            'resolutionState': 'INVALID',
            'errorReason': 'Mod files map to different bundles'
        }

    stems = {_stem_of(m['fileName']) for m in matches}
    stems.discard(None)
    family_key = next(iter(stems)) if len(stems) == 1 else None

    return {
        'targetHash': target_bundle,
        'resolvedFamilyKey': family_key,
        'resolvedTargets': [_target(m, target_bundle) for m in matches],
        'unresolvedFiles': unresolved,
        'resolutionState': 'KNOWN',
        'errorReason': None
    }


def _target(match, target_bundle=None):
    """一个文件条目的解析详情（resolvedTargets 里的元素）。"""
    bundle_name = target_bundle or (sorted(match['bundles'])[0] if match['bundles'] else None)
    return {
        'originalFileName': match['fileName'],
        'normalizedCandidates': match['candidates'],
        'resolvedAssetKey': match['candidate'],
        'resolvedBundleName': bundle_name,
        'assetType': _infer_asset_type(match['fileName']),
        'targetHash': bundle_name,
        'familyKey': _stem_of(match['fileName']),
        'matchStrategy': match['strategy'],
        'confidence': 1.0,
    }
