# -*- coding: utf-8 -*-
"""resolver 的跨包误伤防护（真机回归）。

两个真机 INVALID 案例（0.2.14 全量索引下）：
  1. 前缀桥接误伤：立绘 mod 的 char020801_2.png 本地索引不存在（游戏里该分页
     只有 cutscene 版），桥接候选命中过场包 → 整个 mod 被一个贴图分页拖成
     INVALID。mod 其余文件都能落到公共包，正确结果是 KNOWN + 该文件 unresolved。
  2. 多包歧义：specialillust65.png 命中 4 个包，本地索引消歧挑了贴图集包
     而不是「与兄弟文件同在」的 UI prefab 包 → 整包 INVALID。正确结果 KNOWN。

原则：少数文件指向别的包时不能绑架整个 mod——装包以主文件（skel/atlas/
prefab 骨架类）为准，贴图分页缺失或歧义就让它 unresolved。
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python"))

import resolver


class PrefixBridgeCrossBundleTest(unittest.TestCase):
    """案例 1：桥接候选把贴图分页指向过场包，不得拖垮整包。"""

    def test_bridged_texture_page_not_drags_whole_mod_invalid(self):
        # 布局还原自真机：char020801 立绘主文件在包 A；_back 分页在过场包 C；
        # char020801_2.png 游戏里只有 cutscene_ 版（包 C），mod 自带 _2.png
        local = {
            "assetToBundles": {
                "char020801.png": ["A"],
                "char020801.atlas": ["A"],
                "char020801.skel": ["A"],
                "char020801_back1.png": ["C"],
            },
            # catalog 兜底表里有 cutscene_char020801_2.png（过场包）
            "catalogAssetToBundle": {
                "cutscene_char020801_2.png": ["C"],
            },
        }
        files = ["char020801.png", "char020801.atlas", "char020801.skel",
                 "char020801_back1.png", "char020801_2.png"]
        r = resolver.resolve_mod_folder(files, local)
        self.assertEqual("KNOWN", r["resolutionState"],
                         "贴图分页被桥接到过场包不该拖垮立绘 mod: %s" % r["errorReason"])
        self.assertEqual("A", r["targetHash"])
        self.assertIn("char020801_2.png", r["unresolvedFiles"])

    def test_bridge_still_saves_cutscene_mod_when_it_is_the_mod(self):
        """对照：整包都是无前缀的过场 mod（0.2.12 修的场景）必须仍能被救。"""
        local = {
            "assetToBundles": {},   # 本地没扫到 → catalog 兜底
            "catalogAssetToBundle": {
                "cutscene_char066401.atlas": ["CUT"],
                "cutscene_char066401.skel": ["CUT"],
                "cutscene_char066401.png": ["CUT"],
            },
        }
        files = ["char066401.atlas", "char066401.skel", "char066401.png"]
        r = resolver.resolve_mod_folder(files, local)
        self.assertEqual("KNOWN", r["resolutionState"])
        self.assertEqual("CUT", r["targetHash"])


class MultiBundleDisambiguationTest(unittest.TestCase):
    """案例 2：同名资产多包，优先「与兄弟文件同在」的包。"""

    def test_multi_hit_prefers_bundle_shared_with_siblings(self):
        # 还原自真机：specialillust65 的 atlas/skel(_2/_3 分页) 在包 A，
        # specialillust65.png 同名落在 A/B/...4 个包里
        local = {
            "assetToBundles": {
                "specialillust65.atlas": ["A"],
                "specialillust65.skel": ["A"],
                "specialillust65_2.png": ["A"],
                "specialillust65_3.png": ["A"],
                # png 命中 4 个包（A、B1、B2、B3）
                "specialillust65.png": ["A", "B1", "B2", "B3"],
            },
            "catalogAssetToBundle": {},
        }
        files = ["specialillust65.atlas", "specialillust65.skel",
                 "specialillust65.png", "specialillust65_2.png",
                 "specialillust65_3.png"]
        r = resolver.resolve_mod_folder(files, local)
        self.assertEqual("KNOWN", r["resolutionState"],
                         "同名多包应收窄到与兄弟文件同在的包: %s" % r["errorReason"])
        self.assertEqual("A", r["targetHash"])

    def test_true_multi_bundle_mod_still_invalid(self):
        """对照：主文件（skel/atlas）真的分家时仍须 INVALID，不许放宽。"""
        local = {
            "assetToBundles": {
                "charx.skel": ["P"],
                "charx.atlas": ["Q"],
                "charx.png": ["P", "Q"],
            },
            "catalogAssetToBundle": {},
        }
        r = resolver.resolve_mod_folder(["charx.skel", "charx.atlas", "charx.png"], local)
        self.assertEqual("INVALID", r["resolutionState"])


if __name__ == "__main__":
    unittest.main()
