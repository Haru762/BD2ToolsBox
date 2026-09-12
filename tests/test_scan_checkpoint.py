# -*- coding: utf-8 -*-
"""扫描的断点续扫：checkpoint_scan 落盘 + 原子写入。

真机上扫描进程会被系统杀（LMK/厂商省电）。此前已扫结果只在
_scan_state 内存里，finalize_scan 前不落盘 —— 进程一死全部归零，
重扫又从第一个 bundle 开始，永远卡在同一处（用户实测：总是回到
开始那二十几个包）。

checkpoint_scan 把 cached+scanned 原子写入 local_bundle_index.json；
下次 check_scan_needed 按哈希缓存跳过已扫的，从断点继续。
"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python"))

import local_bundle_indexer


class CheckpointScanTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.mkdtemp()
        local_bundle_indexer._scan_state = None

    def tearDown(self):
        local_bundle_indexer._scan_state = None
        for name in os.listdir(self._tmp):
            p = os.path.join(self._tmp, name)
            os.unlink(p)
        os.rmdir(self._tmp)

    def _start_scan(self, bundles):
        """走一遍 check_scan_needed 建好 _scan_state，再逐个扫 given 名单。"""
        needs = json.loads(local_bundle_indexer.check_scan_needed(
            self._tmp, json.dumps(bundles)))
        hashmap = {b["name"]: b["hash"] for b in bundles}
        for name in needs:
            local_bundle_indexer._scan_state["scanned"][name] = {
                "hash": hashmap[name], "assets": [name + ".skel"]}

    def test_checkpoint_writes_progress_and_resume_skips_scanned(self):
        """扫了 10 个 → checkpoint → 进程死了 → 重来时 check 只列剩下的。"""
        bundles = [{"name": "b%03d" % i, "hash": "h_b%03d" % i} for i in range(30)]
        self._start_scan(bundles[:10])

        ok, msg = local_bundle_indexer.checkpoint_scan(self._tmp)
        self.assertTrue(ok, msg)

        # 「进程死亡」：丢掉内存状态，只留盘上的索引
        local_bundle_indexer._scan_state = None

        needs = json.loads(local_bundle_indexer.check_scan_needed(
            self._tmp, json.dumps(bundles)))
        self.assertEqual(
            {"b%03d" % i for i in range(10, 30)}, set(needs),
            "已 checkpoint 的 10 个必须按哈希缓存跳过，只扫剩下 20 个")

    def test_checkpoint_index_has_valid_schema(self):
        """checkpoint 落盘的索引必须是 _load_existing_cache 认可的 schema，
        否则下次启动整份作废、退回全量。"""
        bundles = [{"name": "a", "hash": "ha"}, {"name": "b", "hash": "hb"}]
        self._start_scan(bundles)
        self.assertTrue(local_bundle_indexer.checkpoint_scan(self._tmp)[0])

        cached = local_bundle_indexer._load_existing_cache(
            os.path.join(self._tmp, "local_bundle_index.json"))
        self.assertIsNotNone(cached, "checkpoint 的索引必须能被 _load_existing_cache 读回")
        self.assertEqual(
            {"ha": "a", "hb": "b"},
            {v["hash"]: k for k, v in cached["scannedBundles"].items()})

    def test_checkpoint_without_scan_state_is_noop(self):
        """没有在途扫描时 checkpoint 不是错误（Kotlin 侧不必判断时机）。"""
        ok, msg = local_bundle_indexer.checkpoint_scan(self._tmp)
        self.assertTrue(ok)

    def test_finalize_write_is_atomic(self):
        """finalize 的最终落盘也必须原子：写一半被杀不能留下半截 JSON
        （否则 _load_existing_cache 判废，一次完整扫描的成果全丢）。"""
        bundles = [{"name": "a", "hash": "ha"}]
        self._start_scan(bundles)
        self.assertTrue(local_bundle_indexer.finalize_scan(self._tmp)[0])
        # 原子写入的痕迹：不留临时文件
        leftovers = [f for f in os.listdir(self._tmp)
                     if f.startswith("local_bundle_index") and f != "local_bundle_index.json"]
        self.assertEqual([], leftovers)

    def test_checkpoint_write_is_atomic(self):
        """checkpoint 同理不留临时文件。"""
        bundles = [{"name": "a", "hash": "ha"}]
        self._start_scan(bundles)
        self.assertTrue(local_bundle_indexer.checkpoint_scan(self._tmp)[0])
        leftovers = [f for f in os.listdir(self._tmp)
                     if f.startswith("local_bundle_index") and f != "local_bundle_index.json"]
        self.assertEqual([], leftovers)


if __name__ == "__main__":
    unittest.main()
