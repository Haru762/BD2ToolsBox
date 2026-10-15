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
   有扫描索引时也**不是完全不看它**：扫描索引里已有的命中优先，但
   「这个资产本地没扫到」的候选名仍旧回退查这张表 —— 扫描索引常常只是
   部分结果（用户中途停了扫描、资源没下全、某个 bundle 解包失败），
   只认本地会让部分索引比没有索引还差。多命中时同样用它收窄。

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

# SpecialIllust_1 图包中，早期 64 张好感度/场景 CG 在 Addressables 目录里以
# `sepcialillust_*` 登记，但打包进 Unity AssetBundle 后的 Texture2D.m_Name
# 实为 `specialillust*`。提供双向桥接以便本地扫描索引（按 m_Name）与重打包均能命中。
SPECIAL_ILLUST_ADDRESS_TO_ASSET = {
    "sepcialillust_1": "specialillust3",
    "sepcialillust_6": "specialillust3-1",
    "sepcialillust_7": "specialillust11",
    "sepcialillust_8": "specialillust11-1",
    "sepcialillust_11": "specialillust5",
    "sepcialillust_15": "specialillust5-1",
    "sepcialillust_16": "specialillust5-2",
    "sepcialillust_19": "specialillust2",
    "sepcialillust_20": "specialillust4",
    "sepcialillust_21": "specialillust9",
    "sepcialillust_22": "specialillust10",
    "sepcialillust_23": "specialillust7",
    "sepcialillust_24": "specialillust6",
    "sepcialillust_25": "specialillust8",
    "sepcialillust_26_1": "specialillust4-1",
    "sepcialillust_26_2": "specialillust4-2",
    "sepcialillust_26_3": "specialillust4-3",
    "sepcialillust_26_4": "specialillust4-4",
    "sepcialillust_27": "specialillust14",
    "sepcialillust_28": "specialillust14-1",
    "sepcialillust_29": "specialillust15",
    "sepcialillust_30": "specialillust16",
    "sepcialillust_31_1": "specialillust17",
    "sepcialillust_31_3": "specialillust17-1",
    "sepcialillust_32_1": "specialillust17-2",
    "sepcialillust_32_2": "specialillust17-3",
    "sepcialillust_33": "specialillust18",
    "sepcialillust_34": "specialillust19",
    "sepcialillust_34_1": "specialillust19-1",
    "sepcialillust_35_1": "specialillust20",
    "sepcialillust_35_2": "specialillust20-1",
    "sepcialillust_35_3": "specialillust20-2",
    "sepcialillust_36_4": "specialillust21-1",
    "sepcialillust_37": "specialillust22-3",
    "sepcialillust_37_1": "specialillust22-5",
    "sepcialillust_37_2": "specialillust22-1",
    "sepcialillust_37_6": "specialillust22-4",
    "sepcialillust_38": "specialillust23",
    "sepcialillust_39": "specialillust24",
    "sepcialillust_40": "specialillust25",
    "sepcialillust_40_2": "specialillust25-1",
    "sepcialillust_41": "specialillust41",
    "sepcialillust_41_1": "specialillust41-1",
    "sepcialillust_42": "specialillust42",
    "sepcialillust_42_2": "specialillust42-1",
    "sepcialillust_42_3": "specialillust42-2",
    "sepcialillust_42_5": "specialillust42-5",
    "sepcialillust_42_6": "specialillust42-4",
    "sepcialillust_43": "specialillust43",
    "sepcialillust_43_1": "specialillust43-1",
    "sepcialillust_43_2": "specialillust43-2",
    "sepcialillust_43_3": "specialillust43-3",
    "sepcialillust_44": "specialillust51",
    "sepcialillust_45": "specialillust52",
    "sepcialillust_45-1": "specialillust52-1",
    "specialillust_46": "specialillust64",
    "sepcialillust_50-1": "specialillust60",
    "sepcialillust_95": "specialillust33",
    "sepcialillust_95-2": "specialillust34",
    "sepcialillust_95-3": "specialillust35",
    "sepcialillust_98": "specialillust26-1",
    "sepcialillust_98_1": "specialillust26",
    "sepcialillust_99_1": "specialillust28",
    "sepcialillust_99_2": "specialillust31-1",
    "sepcialillust_99_3": "specialillust30",
    "sepcialillust_99_4": "specialillust40",
    "sepcialillust_99_5": "specialillust31",
    "sepcialillust_99_6": "specialillust29",
    "sepcialillust_100": "specialillust44-4",
    "sepcialillust_100_1": "specialillust44-2",
    "sepcialillust_100_2": "specialillust44-3",
    "sepcialillust_100_3": "specialillust44",
    "sepcialillust_100_4": "specialillust44-1",
    "sepcialillust_101": "specialillust45",
    "sepcialillust_102": "specialillust46",
    "sepcialillust_103": "specialillust47",
    "sepcialillust_103_1": "specialillust48",
    "sepcialillust_104": "specialillust50",
    "sepcialillust_105": "specialillust53",
    "sepcialillust_105_1": "specialillust53-1",
    "sepcialillust_105_2": "specialillust53-2",
    "sepcialillust_105_3": "specialillust53-3",
    "sepcialillust_105_4": "specialillust53-4",
    "sepcialillust_105_5": "specialillust53-5",
    "sepcialillust_105_6": "specialillust53-6",
    "sepcialillust_106": "specialillust54",
    "sepcialillust_106_1": "specialillust56",
    "sepcialillust_106_3": "specialillust54-1",
    "sepcialillust_107_1": "specialillust57",
    "sepcialillust_107_2": "specialillust58",
    "sepcialillust_108": "specialillust61",
    "sepcialillust_108 1": "specialillust62",
    "sepcialillust_109": "specialillust63",
}


def _expand_candidates(base_name):
    """mod 文件名 → 索引里可能登记过的资产名列表（全部小写）。

    桥接基于「复合扩展名的首段」：char000104.atlas.txt 的复合扩展名是
    .atlas.txt，剥掉它得词干 char000104，加回首段 .atlas → char000104.atlas。

    PC mod 作者的文件名经常不带 cutscene_ 前缀（char066401.skel），而
    catalog 地址末段带前缀（cutscene_char066401.skel.bytes）。对没有
    前缀的候选补一个带前缀的变体，让这类 mod 也能在 catalog 里找到目标。
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
    # 前缀桥接：char066401.atlas → 也试 cutscene_char066401.atlas
    expanded = list(candidates)
    for c in candidates:
        if not c.startswith('cutscene_'):
            prefixed = 'cutscene_' + c
            if prefixed not in expanded:
                expanded.append(prefixed)
    # 约会 prefab 桥：illust_dating11.atlas → 也试 illust_dating11.prefab。
    # catalog 里约会 spine 只登记为 prefab 地址（char/datingillust/
    # illust_datingNN.prefab），atlas/skel/png 名一概没有 —— 没有本地扫描
    # 索引时（「建立文件索引」未完成）约会 mod 全落 UNKNOWN，UI 上表现为
    # 角色页的「心契之约」分区整体消失。限定 illust_dating 词干：通用的
    # {stem}.prefab 桥会把立绘 mod 错配到 UI prefab 包（char020801.prefab
    # 确实存在但属于立绘包，不是过场目标）。分页后缀（_2/_3）产生的
    # prefab 变体在 catalog 里不存在，查不到就留在 unresolved，无害。
    for c in candidates:
        stem = c.split('.', 1)[0]
        if stem.startswith('illust_dating'):
            prefab = stem + '.prefab'
            if prefab not in expanded:
                expanded.append(prefab)
        if c.endswith('.png') and stem in SPECIAL_ILLUST_ADDRESS_TO_ASSET:
            mapped = SPECIAL_ILLUST_ADDRESS_TO_ASSET[stem] + '.png'
            if mapped not in expanded:
                expanded.append(mapped)
    return expanded


def candidate_keys(file_names):
    """一组 mod 文件名 → 全部可能命中的资产名集合（含前缀桥接）。

    给「按需建 catalog 资产索引」用：全量索引太大，先算出这批 mod 真正
    会查的那些键（包括 cutscene_ 前缀变体），让 catalog_indexer 只留命中的。
    """
    keys = set()
    for name in file_names or []:
        keys.update(_expand_candidates(Path(name).name))
    return keys


def primary_candidate_keys(file_names):
    """一组 mod 文件名 → 主候选名集合（不含前缀桥接）。

    给 main_script._resolution_index 的覆盖判定用：本地索引存的是 bundle 内
    m_Name，覆盖判定只看原始候选名（mod 文件名本身展开的结果），前缀变体
    是 catalog 侧的查表需求，不算本地缺口。
    """
    keys = set()
    for name in file_names or []:
        lowered = (Path(name).name or "").strip().lower()
        if not lowered:
            continue
        keys.add(lowered)
        for suffix, targets in _EXTENSION_BRIDGES:
            if not lowered.endswith(suffix):
                continue
            stem = lowered[:-len(suffix)]
            for t in targets:
                bridged = stem + t
                if bridged:
                    keys.add(bridged)
        stem = lowered.split('.', 1)[0]
        if lowered.endswith('.png') and stem in SPECIAL_ILLUST_ADDRESS_TO_ASSET:
            keys.add(SPECIAL_ILLUST_ADDRESS_TO_ASSET[stem] + '.png')
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
    # 去掉页号后缀（_2/_3 是图集分页；保留 _1 以区分 cutscene_char004091_1 等多段骨骼）
    filename = re.sub(r'_(?:[2-9]|\d{2,})$', '', filename)
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

            # 多命中时用 catalog 权威映射收窄到其中一个。
            # 取到的值可能是裸字符串（扫描索引的消解表原生形状），也可能是列表
            # （catalog 兜底表 / 合并后的表）—— 一律先归成集合再与本地候选求交，
            # 直接拿列表做 `in set` 会抛 TypeError: unhashable type: 'list'，
            # 而那个异常会冒到 resolve_mod_batch 的兜底 except，整批 mod 一起塌。
            # 只有交集唯一时才收窄；交集为空或仍有多个都保持保守，不任选。
            if len(bundles) > 1:
                for candidate in candidates:
                    narrowed = bundles & set(
                        _as_bundle_names(catalog_asset_to_bundle.get(candidate)))
                    if len(narrowed) == 1:
                        bundles = narrowed
                        strategy = 'CATALOG_FILTERED'
                        break
                else:
                    strategy = 'LOCAL_SCAN'
            else:
                strategy = 'LOCAL_SCAN'

            # 本地一个都没命中 —— 索引是**部分**扫出来的（用户中途停了扫描 / 这份
            # 资源没下过 / 该 bundle 解包失败）。此时才回退 catalog 权威映射，
            # 且只补未命中：上面本地已有的单命中、多命中消歧结果原样不动。
            #
            # 此前这个分支里 catalog 只用于「收窄多命中」，未命中一律留 UNKNOWN ——
            # 结果是有本地索引反而更差：扫了一半的用户比完全没扫的识别得还少
            # （没索引时走 catalog_only 主路径反而是全的）。
            if not bundles:
                for candidate in candidates:
                    found = _as_bundle_names(catalog_asset_to_bundle.get(candidate))
                    if not found:
                        continue
                    if hit_candidate is None:
                        hit_candidate = candidate
                    bundles.update(found)
                if bundles:
                    strategy = 'CATALOG_ONLY'

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
        # 各文件没有公共包。**先别急着判 INVALID**：贴图分页（_2.png/_back1.png
        # 之类）可能被两种情况误伤 —— 前缀桥接把它指向过场包（游戏里该分页只有
        # cutscene 版），或多包同名消歧挑了贴图集包。装哪个包由骨架文件
        # （skel/atlas/prefab）说了算：以骨架文件的公共包为目标，与它不相交的
        # 贴图分页降级 unresolved —— 一个分页不该绑架整个 mod（真机案例：
        # char020801 立绘被 _2.png 拖成 INVALID、specialillust65 被 png 的
        # 四包歧义拖成 INVALID，其余文件全都能落到同一个包）。
        skeleton_matches = [m for m in matches
                            if _infer_asset_type(m['fileName']) in
                            ('TextAsset', 'JsonSkeleton')]
        base = skeleton_matches or matches
        base_intersection = set(base[0]['bundles'])
        for m in base[1:]:
            base_intersection &= m['bundles']

        if base_intersection:
            target_bundle = sorted(base_intersection)[0]
            # 与骨架公共包不相交的命中降级 unresolved：桥接误伤的分页、
            # 消歧到别的包的 png 都在这里出列。骨架文件必须自己命中目标包。
            demoted = []
            for m in matches:
                if target_bundle not in m['bundles']:
                    demoted.append(m)
                    unresolved.append(m['fileName'])
            for m in demoted:
                matches.remove(m)
        else:
            # 骨架文件自己也分家 —— 那是真的装不进一个目标
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
