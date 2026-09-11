# -*- coding: utf-8 -*-
"""mod 解析链：本地扫描索引与 catalog 兜底 的边界回归。

覆盖两条已证实的缺陷（见 README/审计结论）：

1. resolver.resolve_mod_folder 在**有本地索引**的分支里只查 assetToBundles，
   catalog 权威映射仅用于「同名多 bundle」的收窄，**从不用于补齐未命中**。
   于是「索引非空但没覆盖到这个资产」的 mod 一律 UNKNOWN —— 哪怕
   catalogAssetToBundle 里就躺着正确答案。
2. main_script._resolution_index 只判 `assetToBundles` **非空**就提前返回，
   部分索引因此把整条 catalog 兜底短路；而 catalogAssetToBundle 那张全量表
   连带不会被构建。

测试约定：
  · 只 mock IO（load_local_index / get_cdn_version / download_catalog /
    _latest_disk_catalog），resolver 与 catalog_indexer 一律跑真实实现，
    断言真实输出 —— 不 mock 被测逻辑本身。
  · 真实兼容性断言只用仓库里那份真实 addressables catalog 快照
    （测试用/…/catalog_alpha.json），合成 fixture 只用于隔离 IO 的形状测试。
  · Python 3.8 兼容（无 walrus / 无 dict 并集运算符 / 无 f-string '='）。
"""
import contextlib
import io
import json
import os
import sys
import unittest
from unittest import mock

_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.dirname(_HERE)
_PY_DIR = os.path.join(_REPO, "app", "src", "main", "python")
if _PY_DIR not in sys.path:
    sys.path.insert(0, _PY_DIR)

import resolver          # noqa: E402
import catalog_indexer   # noqa: E402
import main_script       # noqa: E402


# --------------------------------------------------------------------------
# 真实 catalog 快照
# --------------------------------------------------------------------------

_REAL_CATALOG_PATH = os.path.join(
    os.path.dirname(_REPO), u"测试用", u"com.neowizgames.game.browndust2",
    u"files", u"com.unity.addressables", u"catalog_alpha.json")

# 这两个常量本身由 test_real_catalog_maps_cutscene_asset 对着真实 catalog 断言，
# 免得快照换了之后下面的用例变成「对着自己的假设自证」。
REAL_ASSET = u"cutscene_char000707.skel"
REAL_BUNDLE = u"00044c1c0b4b673e127e271e219f70b2"

_real_catalog_cache = {}


def load_real_catalog():
    """真实 catalog 内容（进程内只读一次）。文件不在时返回 None。"""
    if "content" not in _real_catalog_cache:
        content = None
        if os.path.exists(_REAL_CATALOG_PATH):
            with io.open(_REAL_CATALOG_PATH, "r", encoding="utf-8") as f:
                content = json.load(f)
        _real_catalog_cache["content"] = content
    return _real_catalog_cache["content"]


def real_catalog_asset_index(wanted):
    """用真实 catalog 现场建 wanted 定向索引（真实实现，非 mock）。"""
    content = load_real_catalog()
    if content is None:
        return None
    return catalog_indexer.build_catalog_asset_index(content, set(wanted))


def _unrelated_partial_index():
    """一份「非空但没覆盖到目标资产」的本地索引 —— 缺陷的触发条件。"""
    return {
        "bundleCount": 1,
        "assetCount": 1,
        "assetToBundles": {u"some_unrelated_asset.png": [u"deadbeefdeadbeefdeadbeefdeadbeef"]},
        "catalogAssetToBundle": {},
        "scannedBundles": {},
    }


@contextlib.contextmanager
def quiet():
    """吞掉 _reporter 的 print。

    _reporter 是「有回调走回调、同时 print 落 logcat」的出口（main_script:53），
    无论如何都会打印。这里只截 stdout，不替换 _reporter 本身 —— 被测逻辑照跑。
    """
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        yield buf


# ==========================================================================
# 一、resolver 逐候选回退
# ==========================================================================

class ResolverLocalVsCatalogTest(unittest.TestCase):
    """本地命中优先、未命中回退 catalog，且不改变既有消歧行为。"""

    HEX_LOCAL = u"11111111111111111111111111111111"
    HEX_A = u"22222222222222222222222222222222"
    HEX_B = u"33333333333333333333333333333333"

    def test_local_single_hit_preserved(self):
        """本地单命中：结果与策略都不变（LOCAL_SCAN）。"""
        index = {"assetToBundles": {u"char_a.skel": [self.HEX_LOCAL]},
                 "catalogAssetToBundle": {}}
        r = resolver.resolve_mod_folder([u"char_a.skel"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(self.HEX_LOCAL, r["targetHash"])
        self.assertEqual("LOCAL_SCAN", r["resolvedTargets"][0]["matchStrategy"])

    def test_local_multi_hit_disambiguated_by_catalog(self):
        """同名多 bundle：catalog 有意见时必须采纳，不许按本地顺序瞎选。"""
        index = {"assetToBundles": {u"char_a.skel": [self.HEX_A, self.HEX_B]},
                 "catalogAssetToBundle": {u"char_a.skel": self.HEX_B}}
        r = resolver.resolve_mod_folder([u"char_a.skel"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(self.HEX_B, r["targetHash"])
        self.assertEqual("CATALOG_FILTERED", r["resolvedTargets"][0]["matchStrategy"])

    def test_local_multi_hit_deterministic_without_catalog_opinion(self):
        """catalog 没意见时保持原有确定性（取字典序首个），不静默改成别的。"""
        index = {"assetToBundles": {u"char_a.skel": [self.HEX_B, self.HEX_A]},
                 "catalogAssetToBundle": {}}
        r = resolver.resolve_mod_folder([u"char_a.skel"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(self.HEX_A, r["targetHash"])
        self.assertEqual("LOCAL_SCAN", r["resolvedTargets"][0]["matchStrategy"])

    def test_local_hit_wins_over_catalog(self):
        """本地命中处不被 catalog 覆盖 —— 回退只补未命中。"""
        index = {"assetToBundles": {u"char_a.skel": [self.HEX_LOCAL]},
                 "catalogAssetToBundle": {u"char_a.skel": [self.HEX_A]}}
        r = resolver.resolve_mod_folder([u"char_a.skel"], index)
        self.assertEqual(self.HEX_LOCAL, r["targetHash"])

    def test_partial_index_falls_back_to_catalog_for_missing_candidate(self):
        """核心回归：索引非空但未覆盖该资产 → 仍应用 catalog 映射。

        修前：UNKNOWN（catalog 表就在同一个 dict 里也视而不见）。
        """
        index = {"assetToBundles": {u"some_unrelated_asset.png": [self.HEX_LOCAL]},
                 "catalogAssetToBundle": {u"char_a.skel": [self.HEX_A]}}
        r = resolver.resolve_mod_folder([u"char_a.skel"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(self.HEX_A, r["targetHash"])
        self.assertEqual("CATALOG_ONLY", r["resolvedTargets"][0]["matchStrategy"])

    def test_partial_index_missing_candidate_catalog_value_may_be_list(self):
        """扫描索引里的 catalogAssetToBundle 是裸字符串、兜底表是列表 —— 两种都要认。"""
        index = {"assetToBundles": {u"unrelated.png": [self.HEX_LOCAL]},
                 "catalogAssetToBundle": {u"char_a.skel": self.HEX_A}}
        r = resolver.resolve_mod_folder([u"char_a.skel"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(self.HEX_A, r["targetHash"])

    def test_catalog_only_index_still_primary(self):
        """assetToBundles 为空 = catalog 主路径，行为不变。"""
        index = {"assetToBundles": {},
                 "catalogAssetToBundle": {u"char_a.skel": [self.HEX_A]}}
        r = resolver.resolve_mod_folder([u"char_a.skel"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(self.HEX_A, r["targetHash"])
        self.assertEqual("CATALOG_ONLY", r["resolvedTargets"][0]["matchStrategy"])

    def test_unresolved_files_preserved(self):
        """未命中「其余」不退化：认出的照样认出，认不出的照旧进 unresolvedFiles。"""
        index = {"assetToBundles": {u"unrelated.png": [self.HEX_LOCAL]},
                 "catalogAssetToBundle": {u"char_a.skel": [self.HEX_A]}}
        r = resolver.resolve_mod_folder([u"char_a.skel", u"mystery_thing.xyz"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(self.HEX_A, r["targetHash"])
        self.assertIn(u"mystery_thing.xyz", r["unresolvedFiles"])
        self.assertNotIn(u"char_a.skel", r["unresolvedFiles"])

    def test_all_missing_stays_unknown(self):
        """两张表都没有 → 仍旧 UNKNOWN，不因新增回退而误判。"""
        index = {"assetToBundles": {u"unrelated.png": [self.HEX_LOCAL]},
                 "catalogAssetToBundle": {}}
        r = resolver.resolve_mod_folder([u"char_a.skel"], index)
        self.assertEqual("UNKNOWN", r["resolutionState"])
        self.assertIsNone(r["targetHash"])
        self.assertIn(u"char_a.skel", r["unresolvedFiles"])

    def test_multi_file_common_bundle_uses_catalog_fallback(self):
        """回退来的映射与本地命中一起参与「公共 bundle」求交集。"""
        index = {"assetToBundles": {u"char_a.atlas": [self.HEX_A]},
                 "catalogAssetToBundle": {u"char_a.skel": [self.HEX_A]}}
        r = resolver.resolve_mod_folder([u"char_a.skel", u"char_a.atlas"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(self.HEX_A, r["targetHash"])

    # ---- 收窄阶段必须同时吃下「字符串值」与「列表值」----

    HEX_C = u"66666666666666666666666666666666"

    def test_disambiguation_accepts_list_valued_catalog_entry(self):
        """补表是列表值，扫描索引是字符串值 —— 两种都不能让收窄炸掉。

        可达路径：本地某候选名多命中 + 补表刚好补了同一个文件名的桥接别名
        （char_a.atlas.txt 的候选是 [char_a.atlas.txt, char_a.atlas]）。
        修前：`['..'] in {..}` → TypeError: unhashable type: 'list' —— 异常会
        冒到 resolve_mod_batch 的兜底 except，整批 mod 一起塌成 UNKNOWN。
        """
        index = {"assetToBundles": {u"char_a.atlas": [self.HEX_A, self.HEX_B]},
                 "catalogAssetToBundle": {u"char_a.atlas.txt": [self.HEX_A]}}
        r = resolver.resolve_mod_folder([u"char_a.atlas.txt"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(self.HEX_A, r["targetHash"])
        self.assertEqual("CATALOG_FILTERED", r["resolvedTargets"][0]["matchStrategy"])

    def test_disambiguation_list_value_intersecting_to_one_narrows(self):
        """列表值里只有一个与本地候选相交 → 收窄到它。"""
        index = {"assetToBundles": {u"char_a.atlas": [self.HEX_A, self.HEX_B]},
                 "catalogAssetToBundle": {u"char_a.atlas.txt": [self.HEX_A, self.HEX_C]}}
        r = resolver.resolve_mod_folder([u"char_a.atlas.txt"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(self.HEX_A, r["targetHash"])
        self.assertEqual("CATALOG_FILTERED", r["resolvedTargets"][0]["matchStrategy"])

    def test_disambiguation_list_value_intersecting_to_many_stays_conservative(self):
        """列表值与本地候选相交有多个 → 保守，不许任选一个冒充确定答案。"""
        index = {"assetToBundles": {u"char_a.atlas": [self.HEX_A, self.HEX_B]},
                 "catalogAssetToBundle": {u"char_a.atlas.txt": [self.HEX_A, self.HEX_B]}}
        r = resolver.resolve_mod_folder([u"char_a.atlas.txt"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        # 不收窄：策略仍是本地链路，目标沿用既有确定性规则（字典序首个）
        self.assertNotEqual("CATALOG_FILTERED", r["resolvedTargets"][0]["matchStrategy"])
        self.assertEqual("LOCAL_SCAN", r["resolvedTargets"][0]["matchStrategy"])
        self.assertEqual(self.HEX_A, r["targetHash"])

    def test_disambiguation_string_value_still_narrows(self):
        """字符串值（扫描索引消解表的原生形状）行为不变。"""
        index = {"assetToBundles": {u"char_a.atlas": [self.HEX_A, self.HEX_B]},
                 "catalogAssetToBundle": {u"char_a.atlas.txt": self.HEX_B}}
        r = resolver.resolve_mod_folder([u"char_a.atlas.txt"], index)
        self.assertEqual(self.HEX_B, r["targetHash"])
        self.assertEqual("CATALOG_FILTERED", r["resolvedTargets"][0]["matchStrategy"])

    def test_disambiguation_empty_value_does_not_narrow(self):
        """空值/缺失值不参与收窄（原 `if catalog_bundle` 的短路语义保持）。"""
        index = {"assetToBundles": {u"char_a.atlas": [self.HEX_A, self.HEX_B]},
                 "catalogAssetToBundle": {u"char_a.atlas.txt": []}}
        r = resolver.resolve_mod_folder([u"char_a.atlas.txt"], index)
        self.assertEqual("LOCAL_SCAN", r["resolvedTargets"][0]["matchStrategy"])


# ==========================================================================
# 二、_resolution_index 的四种索引形态
# ==========================================================================

class ResolutionIndexShapesTest(unittest.TestCase):
    """无 / 空 / 部分 / 完整 四种索引，外加离线与空输入。"""

    HEX_LOCAL = u"11111111111111111111111111111111"

    def _call(self, file_names, local_index, version=u"testver",
              download=(None, None), latest_disk=None, build=None):
        """调用真实 _resolution_index，只替换 IO 出口。返回 (结果, 探针)。"""
        probes = {}
        patches = [
            mock.patch.object(main_script.local_bundle_indexer,
                              "load_local_index", return_value=local_index),
            mock.patch.object(main_script.cdn_downloader,
                              "get_cdn_version", return_value=version),
            mock.patch.object(main_script.cdn_downloader,
                              "download_catalog", return_value=download),
            mock.patch.object(main_script, "_latest_disk_catalog",
                              return_value=latest_disk),
        ]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        probes["get_cdn_version"] = main_script.cdn_downloader.get_cdn_version
        probes["download_catalog"] = main_script.cdn_downloader.download_catalog

        if build is not None:
            bp = mock.patch.object(main_script.catalog_indexer,
                                   "build_catalog_asset_index", side_effect=build)
            bp.start()
            self.addCleanup(bp.stop)

        index, error = main_script._resolution_index(
            "/tmp/does-not-matter", "HD", file_names, lambda m: None)
        return index, error, probes

    def test_empty_input_makes_no_network_call(self):
        """空输入（空 mod 列表 / 只有预览图）不该触发任何网络调用。"""
        index, error, probes = self._call([], None)
        self.assertIsNone(error)
        self.assertEqual({}, index.get("assetToBundles"))
        self.assertEqual({}, index.get("catalogAssetToBundle"))
        probes["get_cdn_version"].assert_not_called()
        probes["download_catalog"].assert_not_called()

    def test_full_local_coverage_makes_no_network_call(self):
        """本地索引完整覆盖这批候选 → 一个字节都不该下载。"""
        wanted = [u"char_a.skel", u"char_a.atlas"]
        local = {"assetToBundles": {u"char_a.skel": [self.HEX_LOCAL],
                                    u"char_a.atlas": [self.HEX_LOCAL]},
                 "catalogAssetToBundle": {}, "scannedBundles": {}}
        index, error, probes = self._call(wanted, local)
        self.assertIsNone(error)
        self.assertEqual([self.HEX_LOCAL], index["assetToBundles"][u"char_a.skel"])
        probes["get_cdn_version"].assert_not_called()
        probes["download_catalog"].assert_not_called()

    def test_catalog_only_table_reused_without_network(self):
        """盘上已有能覆盖这批候选的 catalog-only 表 → 直接可用，不再联网。"""
        local = {"assetToBundles": {},
                 "catalogAssetToBundle": {u"char_a.skel": [self.HEX_LOCAL]},
                 "scannedBundles": {}}
        index, error, probes = self._call([u"char_a.skel"], local)
        self.assertIsNone(error)
        self.assertEqual([self.HEX_LOCAL], index["catalogAssetToBundle"][u"char_a.skel"])
        probes["get_cdn_version"].assert_not_called()
        probes["download_catalog"].assert_not_called()
        # 且这张表能直接喂给真实 resolver
        r = resolver.resolve_mod_folder([u"char_a.skel"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(self.HEX_LOCAL, r["targetHash"])

    def test_offline_keeps_local_results(self):
        """离线（版本号与磁盘 catalog 都拿不到）→ 保留本地结果，绝不清空整批。"""
        local = _unrelated_partial_index()
        index, error, probes = self._call(
            [u"char_a.skel"], local, version=None, latest_disk=None)
        self.assertIsNone(error)
        self.assertIsNotNone(index)
        self.assertEqual(local["assetToBundles"], index["assetToBundles"])
        probes["download_catalog"].assert_not_called()

    def test_no_index_and_offline_reports_error(self):
        """没有本地索引又没有 catalog → 明确报错（调用方据此提示去扫描）。"""
        index, error, _ = self._call([u"char_a.skel"], None,
                                     version=None, latest_disk=None)
        self.assertIsNone(index)
        self.assertIsNotNone(error)

    def test_empty_index_uses_catalog(self):
        """空索引（扫到 0 个 bundle 也照样落盘）→ 走 catalog 填表。"""
        local = {"assetToBundles": {}, "catalogAssetToBundle": {},
                 "scannedBundles": {}}

        def spy(content, wanted_keys=None):
            return {u"char_b.skel": [u"55555555555555555555555555555555"]}

        index, error, _ = self._call(
            [u"char_b.skel"], local,
            download=({"fake": "catalog"}, None), build=spy)
        self.assertIsNone(error)
        self.assertEqual({}, index["assetToBundles"])
        self.assertEqual([u"55555555555555555555555555555555"],
                         index["catalogAssetToBundle"][u"char_b.skel"])
        r = resolver.resolve_mod_folder([u"char_b.skel"], index)
        self.assertEqual("KNOWN", r["resolutionState"])

    def test_empty_index_offline_keeps_index_without_error(self):
        """空索引 + 离线：原样返回空索引、不硬判「请先扫描」（用户确实扫过了）。"""
        local = {"assetToBundles": {}, "catalogAssetToBundle": {},
                 "scannedBundles": {}}
        index, error, _ = self._call(
            [u"char_b.skel"], local, version=None, latest_disk=None)
        self.assertIsNone(error)
        self.assertEqual({}, index["assetToBundles"])

    def test_local_key_case_mismatch_does_not_falsely_claim_coverage(self):
        """本地键大小写与 resolver 实际查表不一致时，不许声称「已覆盖」而跳过补表。

        修前：local_keys 做了 lower 归一，于是「表里是 Char_A.Skel、候选是小写
        char_a.skel」被判成已覆盖 → 不联网直接返回；而 resolver 原样用小写键查
        大写键的本地表必然落空 → UNKNOWN。声称覆盖、实际漏命中 —— 比不判覆盖
        更糟，因为它连 catalog 兜底的机会都掐掉了。
        """
        local = {"assetToBundles": {u"Char_A.Skel": [self.HEX_LOCAL]},
                 "catalogAssetToBundle": {}, "scannedBundles": {}}

        def spy(content, wanted_keys=None):
            return {u"char_a.skel": [self.HEX_LOCAL]}

        index, error, probes = self._call(
            [u"char_a.skel"], local,
            download=({"fake": "catalog"}, None), build=spy)
        self.assertIsNone(error)
        # 真话必须兑现：能返回就说明 resolver 真查得到
        r = resolver.resolve_mod_folder([u"char_a.skel"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(self.HEX_LOCAL, r["targetHash"])
        # 且确实没走那条假短路（要补表就说明网络该被尝试）
        probes["download_catalog"].assert_called_once()

    def test_local_key_case_mismatch_offline_keeps_local_index(self):
        """上一条的离线版：不许崩、不许清空，原样返回本地索引待恢复。"""
        local = {"assetToBundles": {u"Char_A.Skel": [self.HEX_LOCAL]},
                 "catalogAssetToBundle": {}, "scannedBundles": {}}
        index, error, _ = self._call(
            [u"char_a.skel"], local, version=None, latest_disk=None)
        self.assertIsNone(error)
        self.assertEqual(local["assetToBundles"], index["assetToBundles"])

    def test_matching_lowercase_local_key_still_short_circuits(self):
        """对照组：大小写本来就一致时，覆盖判定照旧成立、不联网。"""
        local = {"assetToBundles": {u"char_a.skel": [self.HEX_LOCAL]},
                 "catalogAssetToBundle": {}, "scannedBundles": {}}
        index, error, probes = self._call([u"char_a.skel"], local)
        self.assertIsNone(error)
        probes["get_cdn_version"].assert_not_called()
        r = resolver.resolve_mod_folder([u"char_a.skel"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(self.HEX_LOCAL, r["targetHash"])

    def test_partial_index_builds_catalog_table_for_missing_keys_only(self):
        """只对缺失候选建 wanted 定向 catalog 表；本地命中的不入那次建表请求。

        缺失的主候选要带上它的 cutscene_ 前缀变体：PC mod 文件名常不带前缀
        （char066401.skel），catalog 地址末段却带（cutscene_char066401.skel.bytes），
        建表请求漏掉变体会让这类 mod 在 catalog 里也找不到。
        """
        local = {"assetToBundles": {u"char_a.skel": [self.HEX_LOCAL]},
                 "catalogAssetToBundle": {}, "scannedBundles": {}}
        captured = {}

        def spy(content, wanted_keys=None):
            captured["wanted"] = set(wanted_keys or [])
            # 补出来的映射与本地那份指向同一个 bundle —— 一个 mod 的多个文件
            # 必须落在同一个 bundle，否则会被判 INVALID（那是正确行为）
            return {u"char_b.skel": [self.HEX_LOCAL]}

        index, error, _ = self._call(
            [u"char_a.skel", u"char_b.skel"], local,
            download=({"fake": "catalog"}, None), build=spy)
        self.assertIsNone(error)
        self.assertEqual({u"char_b.skel", u"cutscene_char_b.skel"},
                         captured["wanted"])
        # 本地 assetToBundles 原样保留（本地命中优先）
        self.assertEqual([self.HEX_LOCAL], index["assetToBundles"][u"char_a.skel"])
        # 缺失的补进了 catalog 表，且真实 resolver 能把两者都用上
        self.assertEqual([self.HEX_LOCAL],
                         index["catalogAssetToBundle"][u"char_b.skel"])
        r = resolver.resolve_mod_folder([u"char_a.skel", u"char_b.skel"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(self.HEX_LOCAL, r["targetHash"])

    def test_partial_index_files_to_different_bundles_still_invalid(self):
        """回退来的映射若与本地命中指向不同 bundle，仍须判 INVALID（不放宽）。"""
        local = {"assetToBundles": {u"char_a.skel": [self.HEX_LOCAL]},
                 "catalogAssetToBundle": {}, "scannedBundles": {}}

        def spy(content, wanted_keys=None):
            return {u"char_b.skel": [u"99999999999999999999999999999999"]}

        index, _, _ = self._call(
            [u"char_a.skel", u"char_b.skel"], local,
            download=({"fake": "catalog"}, None), build=spy)
        r = resolver.resolve_mod_folder([u"char_a.skel", u"char_b.skel"], index)
        self.assertEqual("INVALID", r["resolutionState"])
        self.assertIsNone(r["targetHash"])


# ==========================================================================
# 四、补表链异常：不许把整批（含本地已 KNOWN 的）打成失败
# ==========================================================================

class CatalogFallbackFailureTest(unittest.TestCase):
    """catalog 兜底链上任何一步抛异常，都只能降级到本地已有结果。

    这些异常若冒到 resolve_mod_batch 的外层 except，整批会返回
    resolverFallback(null) —— 连本地索引已经解析出来的 KNOWN 条目一起丢掉，
    表现为「扫过资源、明明认得出的 mod 全变未识别」。比不补表还糟。
    """

    HEX_LOCAL = u"11111111111111111111111111111111"
    HEX_ELSE = u"77777777777777777777777777777777"

    def _local_with_mapping(self):
        """部分本地索引：char_a.skel 有本地命中，另带一张已有的 catalog 映射。"""
        return {
            "assetToBundles": {u"char_a.skel": [self.HEX_LOCAL]},
            "catalogAssetToBundle": {u"char_x.skel": [self.HEX_ELSE]},
            "scannedBundles": {},
        }

    def _run(self, local, file_names, **overrides):
        """跑真实 _resolution_index，异常注入由 overrides 指定。

        version 既可以给值（当 return_value），也可以给 mock kwargs
        （如 {"side_effect": OSError(...)}）来模拟版本查询本身炸掉。
        """
        version_spec = overrides.get("version", u"testver")
        if not isinstance(version_spec, dict):
            version_spec = {"return_value": version_spec}
        patches = [
            mock.patch.object(main_script.local_bundle_indexer,
                              "load_local_index", return_value=local),
            mock.patch.object(main_script.cdn_downloader, "get_cdn_version",
                              **version_spec),
            mock.patch.object(main_script.cdn_downloader, "download_catalog",
                              **overrides.get("download",
                                              {"return_value": (None, None)})),
            mock.patch.object(main_script, "_latest_disk_catalog",
                              return_value=overrides.get("latest_disk")),
        ]
        if "build" in overrides:
            patches.append(mock.patch.object(
                main_script.catalog_indexer, "build_catalog_asset_index",
                **overrides["build"]))
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        return main_script._resolution_index(
            "/tmp/does-not-matter", "HD", file_names, lambda m: None)

    def _assert_local_survives(self, index, error, local):
        """公共断言：不报错、原索引（含已有 catalog 映射）原样保留、不被改动。"""
        self.assertIsNone(error)
        self.assertEqual(local["assetToBundles"], index["assetToBundles"])
        self.assertEqual({u"char_x.skel": [self.HEX_ELSE]},
                         index["catalogAssetToBundle"])
        # 调用方复用的是 load_local_index 的内存缓存对象，绝不能被就地改写
        self.assertEqual({u"char_x.skel": [self.HEX_ELSE]},
                         local["catalogAssetToBundle"])

    def test_download_raises_oserror_keeps_local(self):
        """download_catalog 抛 OSError（不是返回 error 元组）。"""
        local = self._local_with_mapping()
        index, error = self._run(
            local, [u"char_a.skel", u"char_b.skel"],
            download={"side_effect": OSError("network on fire")},
            latest_disk=None)
        self._assert_local_survives(index, error, local)
        # 本地命中照常解析，缺失那条老实进 unresolvedFiles
        r = resolver.resolve_mod_folder([u"char_a.skel", u"char_b.skel"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(self.HEX_LOCAL, r["targetHash"])
        self.assertIn(u"char_b.skel", r["unresolvedFiles"])

    def test_get_version_raises_keeps_local(self):
        """get_cdn_version 抛异常（版本查询失败）→ 退磁盘、仍坏则保本地。"""
        local = self._local_with_mapping()
        index, error = self._run(
            local, [u"char_a.skel", u"char_b.skel"],
            version={"side_effect": OSError("version lookup failed")},
            download={"side_effect": AssertionError("不该走到下载")},
            build={"side_effect": AssertionError("不该走到建表")},
            latest_disk=None)
        self._assert_local_survives(index, error, local)

    def test_bad_catalog_structure_keeps_local(self):
        """catalog 内容结构坏 → 建表抛异常。"""
        local = self._local_with_mapping()

        def boom(content, wanted_keys=None):
            raise KeyError("m_InternalIds")

        index, error = self._run(
            local, [u"char_a.skel", u"char_b.skel"],
            download={"return_value": ({"malformed": True}, None)},
            build={"side_effect": boom},
            latest_disk=None)
        self._assert_local_survives(index, error, local)
        r = resolver.resolve_mod_folder([u"char_a.skel", u"char_b.skel"], index)
        self.assertEqual("KNOWN", r["resolutionState"])

    def test_builder_raises_generic_exception_keeps_local(self):
        """建表抛任意异常（不限 KeyError/ValueError）。"""
        local = self._local_with_mapping()
        index, error = self._run(
            local, [u"char_a.skel", u"char_b.skel"],
            download={"return_value": ({"anything": True}, None)},
            build={"side_effect": RuntimeError("builder exploded")},
            latest_disk=None)
        self._assert_local_survives(index, error, local)

    def test_disk_catalog_used_after_download_failure(self):
        """下载炸了 → 先试磁盘上那份（只读盘、不重复发请求），用得上就用。"""
        local = self._local_with_mapping()
        disk = {"used": "disk"}
        index, error = self._run(
            local, [u"char_a.skel", u"char_b.skel"],
            download={"side_effect": OSError("network on fire")},
            latest_disk=disk,
            build={"return_value": {u"char_b.skel": [self.HEX_LOCAL]}})
        self.assertIsNone(error)
        # 缺失项靠磁盘 catalog 补上了，本地命中不变
        self.assertEqual([self.HEX_LOCAL], index["assetToBundles"][u"char_a.skel"])
        self.assertEqual([self.HEX_LOCAL], index["catalogAssetToBundle"][u"char_b.skel"])
        self.assertEqual([self.HEX_ELSE],
                         index["catalogAssetToBundle"][u"char_x.skel"])
        r = resolver.resolve_mod_folder([u"char_a.skel", u"char_b.skel"], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(self.HEX_LOCAL, r["targetHash"])

    # ---- 真入口：整批不许塌 ----

    def test_batch_survives_download_failure(self):
        """外层真入口 resolve_mod_batch：补表炸了也不许整批 UNKNOWN。"""
        local = self._local_with_mapping()
        mods = json.dumps([
            {"id": 0, "fileNames": [u"char_a.skel"]},
            {"id": 1, "fileNames": [u"char_a.skel", u"char_b.skel"]},
        ])
        patches = [
            mock.patch.object(main_script.local_bundle_indexer,
                              "load_local_index", return_value=local),
            mock.patch.object(main_script.cdn_downloader, "get_cdn_version",
                              return_value=u"testver"),
            mock.patch.object(main_script.cdn_downloader, "download_catalog",
                              side_effect=OSError("network on fire")),
            mock.patch.object(main_script, "_latest_disk_catalog",
                              return_value=None),
        ]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        with quiet():
            ok, payload = main_script.resolve_mod_batch(mods, "/tmp/does-not-matter", "HD")
        self.assertTrue(ok, u"整批不该失败：%s" % (payload,))
        by_id = dict((int(r["id"]), r["result"]) for r in json.loads(payload))
        # 本地认得出的那条照旧 KNOWN —— 修前整批走 resolverFallback → 全 UNKNOWN
        self.assertEqual("KNOWN", by_id[0]["resolutionState"])
        self.assertEqual(self.HEX_LOCAL, by_id[0]["targetHash"])
        self.assertEqual("KNOWN", by_id[1]["resolutionState"])

    def test_batch_survives_bad_catalog_structure(self):
        """外层真入口：catalog 结构坏（建表抛）同样不许整批塌。

        必须带一个本地查不到的候选，否则 covered 判定会提前返回、根本走不到
        建表那步 —— 那样测的就不是补表链了。
        """
        local = self._local_with_mapping()
        mods = json.dumps([
            {"id": 0, "fileNames": [u"char_a.skel", u"char_b.skel"]},
        ])

        def boom(content, wanted_keys=None):
            raise ValueError("bad catalog")

        patches = [
            mock.patch.object(main_script.local_bundle_indexer,
                              "load_local_index", return_value=local),
            mock.patch.object(main_script.cdn_downloader, "get_cdn_version",
                              return_value=u"testver"),
            mock.patch.object(main_script.cdn_downloader, "download_catalog",
                              return_value=({"malformed": True}, None)),
            mock.patch.object(main_script, "_latest_disk_catalog",
                              return_value=None),
            mock.patch.object(main_script.catalog_indexer,
                              "build_catalog_asset_index", side_effect=boom),
        ]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        with quiet():
            ok, payload = main_script.resolve_mod_batch(mods, "/tmp/does-not-matter", "HD")
        self.assertTrue(ok, u"整批不该失败：%s" % (payload,))
        result = json.loads(payload)[0]["result"]
        self.assertEqual("KNOWN", result["resolutionState"])
        self.assertEqual(self.HEX_LOCAL, result["targetHash"])
        self.assertIn(u"char_b.skel", result["unresolvedFiles"])

    # ---- 没有本地索引时：给错误，但绝不谎报成功 ----

    def test_no_local_index_reports_error_on_failure(self):
        """没有本地索引 + 补表抛异常 → 如实返回错误，不谎报成功。"""
        index, error = self._run(
            None, [u"char_a.skel"],
            download={"side_effect": OSError("network on fire")},
            latest_disk=None)
        self.assertIsNone(index)
        self.assertIsNotNone(error)

    def test_no_local_index_reports_error_on_bad_catalog(self):
        """没有本地索引 + catalog 结构坏 → 同样如实报错。"""
        local = None
        index, error = self._run(
            local, [u"char_a.skel"],
            download={"return_value": ({"malformed": True}, None)},
            build={"side_effect": ValueError("bad catalog")},
            latest_disk=None)
        self.assertIsNone(index)
        self.assertIsNotNone(error)

    def test_no_local_index_batch_reports_failure_not_success(self):
        """外层真入口：无本地索引 + 故障 → ok=False（不把 false 打成成功）。"""
        patches = [
            mock.patch.object(main_script.local_bundle_indexer,
                              "load_local_index", return_value=None),
            mock.patch.object(main_script.cdn_downloader, "get_cdn_version",
                              return_value=u"testver"),
            mock.patch.object(main_script.cdn_downloader, "download_catalog",
                              side_effect=OSError("network on fire")),
            mock.patch.object(main_script, "_latest_disk_catalog",
                              return_value=None),
        ]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        with quiet():
            ok, payload = main_script.resolve_mod_batch(
                json.dumps([{"id": 0, "fileNames": [u"char_a.skel"]}]),
                "/tmp/does-not-matter", "HD")
        self.assertFalse(ok)
        self.assertTrue(payload)

    # ---- KeyboardInterrupt / SystemExit 不许被吞 ----

    def test_keyboard_interrupt_not_swallowed(self):
        """KeyboardInterrupt 是中断信号，不是「坏数据」，必须继续上抛。"""
        local = self._local_with_mapping()
        with self.assertRaises(KeyboardInterrupt):
            self._run(local, [u"char_a.skel", u"char_b.skel"],
                      download={"side_effect": KeyboardInterrupt()},
                      latest_disk=None)

    def test_system_exit_not_swallowed(self):
        """SystemExit 同理。"""
        local = self._local_with_mapping()
        with self.assertRaises(SystemExit):
            self._run(local, [u"char_a.skel", u"char_b.skel"],
                      download={"side_effect": SystemExit(2)},
                      latest_disk=None)


# ==========================================================================
# 三、真实 catalog 兼容性（非合成 fixture）
# ==========================================================================

@unittest.skipUnless(os.path.exists(_REAL_CATALOG_PATH),
                     u"缺少真实 catalog 快照：%s" % _REAL_CATALOG_PATH)
class RealCatalogCompatTest(unittest.TestCase):
    """用仓库里那份真实 addressables catalog 快照做端到端断言。"""

    def test_real_catalog_maps_cutscene_asset(self):
        """先锚定常量来自真实 catalog，而不是本文件的假设。"""
        idx = real_catalog_asset_index([REAL_ASSET])
        self.assertIn(REAL_ASSET, idx)
        self.assertIn(REAL_BUNDLE, idx[REAL_ASSET])

    def test_real_catalog_does_not_map_pc_only_asset(self):
        """真实 catalog 里确实没有的资产（PC 立绘骨架）→ 建表后仍不出现。"""
        idx = real_catalog_asset_index([u"char060302.skel"])
        self.assertNotIn(u"char060302.skel", idx)

    def test_partial_index_with_real_catalog_resolves_end_to_end(self):
        """真实输入端到端：部分本地索引 + 真实 catalog → 真 resolver 判 KNOWN。"""
        real_content = load_real_catalog()
        local = {"assetToBundles": {u"some_unrelated_asset.png": [u"deadbeef"]},
                 "catalogAssetToBundle": {}, "scannedBundles": {}}
        index, error, _ = self._call_via_helper(
            [REAL_ASSET], local, version=u"testver",
            download=(real_content, None))
        self.assertIsNone(error)
        self.assertIn(REAL_BUNDLE, index["catalogAssetToBundle"][REAL_ASSET])
        r = resolver.resolve_mod_folder([REAL_ASSET], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(REAL_BUNDLE, r["targetHash"])

    def test_no_index_with_real_catalog_resolves_end_to_end(self):
        """无索引 + 真实 catalog → 新装/清数据场景照旧可解析。"""
        real_content = load_real_catalog()
        index, error, _ = self._call_via_helper(
            [REAL_ASSET], None, version=u"testver",
            download=(real_content, None))
        self.assertIsNone(error)
        self.assertEqual({}, index["assetToBundles"])
        r = resolver.resolve_mod_folder([REAL_ASSET], index)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual(REAL_BUNDLE, r["targetHash"])

    def _call_via_helper(self, file_names, local_index, version, download):
        patches = [
            mock.patch.object(main_script.local_bundle_indexer,
                              "load_local_index", return_value=local_index),
            mock.patch.object(main_script.cdn_downloader,
                              "get_cdn_version", return_value=version),
            mock.patch.object(main_script.cdn_downloader,
                              "download_catalog", return_value=download),
        ]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        index, error = main_script._resolution_index(
            "/tmp/does-not-matter", "HD", file_names, lambda m: None)
        return index, error, None


if __name__ == "__main__":
    unittest.main(verbosity=2)
