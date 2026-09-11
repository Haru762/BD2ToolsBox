# -*- coding: utf-8 -*-
"""check_scan_needed 的待扫名单判定。

历史 bug：catalog 预筛只保留地址含 illust/skeletondata 的 bundle，而 spine
资产可能装在 timeline / storypack / localpacktitle 这类地址不含关键词的
bundle 里（m_Name 才是真相，catalog 地址看不到）。游戏更新后 bundle 哈希
变化、重扫被预筛剔除，识别从 KNOWN 退化成 UNKNOWN。

修复：默认全量扫描（full_scan=True），预筛仅在显式要求时启用。
"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python"))

import local_bundle_indexer


def _write_catalog(output_dir, bundle_to_keys):
    """落一份最小 catalog_{画质}_{版本}.json，形状与 _parse_catalog_data 消费的一致。"""
    content = {"_test_bundle_to_keys": bundle_to_keys}
    # _parse_catalog_data 走 catalog_parser.Catalog；这里直接构造它产出的中间形态
    # —— 测试里用 monkeypatch 替换 _parse_catalog_data 更直接。
    path = os.path.join(output_dir, "catalog_HD_20260101000000.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(content, f)
    return path


class CheckScanNeededDefaultTest(unittest.TestCase):
    """默认（不传 full_scan）必须全量：预筛说无关的 bundle 也要进待扫名单。"""

    def setUp(self):
        self._tmp = tempfile.mkdtemp()
        # 预筛数据源：一个 bundle 地址含 illust（预筛保留），一个只有 signal（预筛跳过）。
        # 另造 20 个 filler illust bundle：_relevant_bundles_from_catalog 有
        # 「条目 < 20 视为 catalog 解析出错、不裁剪」的护栏，不垫够数预筛会整个失效。
        self._orig_parse = local_bundle_indexer._parse_catalog_data
        bundle_to_keys = {
            "bundle_illust": ["ui/prefabs/spine/illustspecial/illust_dating11.prefab"],
            "bundle_timeline": ["cinematool2005/quest_main_01/1_loop.signal"],
            "bundle_sound": ["sound/bgm/main_theme.ogg"],
        }
        for i in range(20):
            bundle_to_keys["bundle_filler_%02d" % i] = ["ui/illust_filler_%d.prefab" % i]
        local_bundle_indexer._parse_catalog_data = lambda output_dir: ({}, bundle_to_keys)

    def tearDown(self):
        local_bundle_indexer._parse_catalog_data = self._orig_parse
        local_bundle_indexer._scan_state = None
        for name in os.listdir(self._tmp):
            os.unlink(os.path.join(self._tmp, name))
        os.rmdir(self._tmp)

    def _bundle_list(self, with_fillers=False):
        bundles = [
            {"name": "bundle_illust", "hash": "h1"},
            {"name": "bundle_timeline", "hash": "h2"},
            {"name": "bundle_sound", "hash": "h3"},
        ]
        if with_fillers:
            bundles.extend({"name": "bundle_filler_%02d" % i, "hash": "f"}
                           for i in range(20))
        return json.dumps(bundles)

    def test_default_scans_all_bundles(self):
        """默认全量：三个 bundle（含预筛会跳过的 timeline/sound）都在待扫名单。"""
        needs = json.loads(
            local_bundle_indexer.check_scan_needed(self._tmp, self._bundle_list()))
        self.assertEqual(
            {"bundle_illust", "bundle_timeline", "bundle_sound"}, set(needs))

    def test_prefilter_still_available_when_requested(self):
        """显式 full_scan=False 时预筛照旧生效（行为没删，只是不再是默认）。"""
        needs = json.loads(
            local_bundle_indexer.check_scan_needed(
                self._tmp, self._bundle_list(with_fillers=True), full_scan=False))
        self.assertEqual(
            {"bundle_illust"} | {"bundle_filler_%02d" % i for i in range(20)},
            set(needs))

    def test_cached_bundle_not_rescanned(self):
        """全量默认下缓存照旧：哈希没变的 bundle 不重扫（首次 19GB 只付一次）。"""
        cached = {"bundle_illust": {"hash": "h1", "assets": ["illust_dating11.skel"]}}
        with open(os.path.join(self._tmp, "local_bundle_index.json"), "w",
                  encoding="utf-8") as f:
            json.dump({"schemaVersion": local_bundle_indexer.INDEX_SCHEMA_VERSION,
                       "scannedBundles": cached}, f)
        needs = json.loads(
            local_bundle_indexer.check_scan_needed(self._tmp, self._bundle_list()))
        # 缓存命中的不扫，其余全要（包括预筛跳过的）
        self.assertEqual({"bundle_timeline", "bundle_sound"}, set(needs))


if __name__ == "__main__":
    unittest.main()
