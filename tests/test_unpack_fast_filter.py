# -*- coding: utf-8 -*-
"""预解包加速：只解 atlas 登记页 + 并行解码。

实测（421MB dating 包，PC）：贴图解码占 81%（170 张 × 0.11s），
其中 atlas 用到的只有少数。两个优化：
  1. fast 模式下 Texture2D 只解 atlas 登记过的页（表情差分/小图标解了也不用于预览）
  2. fast 模式贴图并行解码：主线程读原始字节（UnityPy 的资源 reader 是共享
     对象，Position 读写非线程安全，绝不能进工作线程），工作线程解码+落盘
     （C 扩展释放 GIL，纯计算线程安全）；有界队列背压防内存暴涨。
     非 fast（正式解包给 repacker）保持串行 + 全量。
"""
import os
import sys
import tempfile
import threading
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python"))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "vendor"))

import unpacker


class FakeImage:
    def save(self, path, compress_level=None):
        pass


class FakeTexture:
    """假装 read() 出来的 Texture2D。image 走老路径（非 fast）；fast 路径
    只碰 get_image_data / m_Width 等，不触 .image。"""
    def __init__(self, name, script=None):
        self.m_Name = name
        self.m_Script = script
        self.image_read_count = 0

    @property
    def image(self):
        self.image_read_count += 1
        return FakeImage()


class FakeObj:
    def __init__(self, type_name, name, path_id, data):
        import types
        self.type = types.SimpleNamespace(name=type_name)
        self._name = name
        self.path_id = path_id
        self._data = data

    def read(self):
        return self._data

    def peek_name(self):
        return self._name


class FakeEnv:
    def __init__(self, objects):
        self.objects = objects


ATLAS_CONTENT = """illust_x.png
size: 4,4
format: RGBA8888
illust_x
  rotate: false
  xy: 0, 0
  size: 4, 4
"""


def _patch_seams(reads=None, decodes=None, fail_names=()):
    """把读字节/解码两个接缝换成可观测的假实现，不触真实 UnityPy。"""
    def fake_read(tex):
        if reads is not None:
            reads.append(tex.m_Name)
        return b"raw:" + tex.m_Name.encode()

    def fake_decode(tex, raw):
        if decodes is not None:
            decodes.append((threading.current_thread().ident, tex.m_Name))
        if tex.m_Name in fail_names:
            raise RuntimeError("decode boom")
        return FakeImage()

    return mock.patch.multiple(
        unpacker, _read_texture_bytes=fake_read, _decode_texture=fake_decode)


class FastModePageFilterTest(unittest.TestCase):
    """fast 模式只解 atlas 登记页（方案 2）。"""

    def _make_env(self):
        atlas = FakeTexture("illust_x.atlas", script=ATLAS_CONTENT)
        page = FakeTexture("illust_x")
        unused_1 = FakeTexture("illust_x_face01")   # 表情差分：atlas 没登记
        unused_2 = FakeTexture("icon_badge")        # 小图标：atlas 没登记
        objs = [
            FakeObj("TextAsset", "illust_x.atlas", 1, atlas),
            FakeObj("Texture2D", "illust_x", 2, page),
            FakeObj("Texture2D", "illust_x_face01", 3, unused_1),
            FakeObj("Texture2D", "icon_badge", 4, unused_2),
        ]
        return objs, page, unused_1, unused_2

    def test_fast_mode_decodes_only_atlas_pages(self):
        objs, page, unused_1, unused_2 = self._make_env()
        reads = []
        with _patch_seams(reads=reads):
            with tempfile.TemporaryDirectory() as tmp:
                unpacker._export_objects(FakeEnv(objs), tmp, lambda m: False, fast=True)
        self.assertEqual(["illust_x"], reads, "只该读 atlas 登记页，实际: %s" % reads)
        self.assertEqual(0, unused_1.image_read_count)
        self.assertEqual(0, unused_2.image_read_count)

    def test_full_mode_still_decodes_everything(self):
        """对照：非 fast（正式解包给 repacker 用）必须全部解 —— 装进游戏
        替换的可能就是那些「预览用不上」的贴图，少一张就少替换一张。"""
        objs, page, unused_1, unused_2 = self._make_env()
        with tempfile.TemporaryDirectory() as tmp:
            unpacker._export_objects(FakeEnv(objs), tmp, lambda m: False, fast=False)
        self.assertEqual(1, page.image_read_count)
        self.assertEqual(1, unused_1.image_read_count)
        self.assertEqual(1, unused_2.image_read_count)

    def test_fast_mode_without_atlas_decodes_all(self):
        """atlas 为空的包（纯立绘合集，没有登记表可对照）不能误杀全部贴图。"""
        page = FakeTexture("bg_event")
        icon = FakeTexture("icon_badge")
        reads = []
        env = FakeEnv([
            FakeObj("Texture2D", "bg_event", 1, page),
            FakeObj("Texture2D", "icon_badge", 2, icon),
        ])
        with _patch_seams(reads=reads):
            with tempfile.TemporaryDirectory() as tmp:
                unpacker._export_objects(env, tmp, lambda m: False, fast=True)
        self.assertEqual({"bg_event", "icon_badge"}, set(reads))


class ParallelDecodeTest(unittest.TestCase):
    """fast 模式贴图并行解码（方案 1）。"""

    def _two_page_env(self):
        atlas = FakeTexture("illust_x.atlas",
                            script=ATLAS_CONTENT + "illust_y.png\n")
        return FakeEnv([
            FakeObj("TextAsset", "illust_x.atlas", 1, atlas),
            FakeObj("Texture2D", "illust_x", 2, FakeTexture("illust_x")),
            FakeObj("Texture2D", "illust_y", 3, FakeTexture("illust_y")),
        ])

    def test_fast_mode_decodes_in_parallel(self):
        """多张登记页时，解码必须发生在工作线程上（而不是主线程串行）。"""
        decodes = []
        with _patch_seams(decodes=decodes):
            with tempfile.TemporaryDirectory() as tmp:
                unpacker._export_objects(self._two_page_env(), tmp, lambda m: False, fast=True)
        self.assertEqual(2, len(decodes))
        main_id = threading.main_thread().ident
        self.assertTrue(
            any(tid != main_id for tid, _ in decodes),
            "贴图解码应发生在工作线程，实际线程: %s" % [t for t, _ in decodes])

    def test_fast_mode_thread_count_bounded(self):
        """解码并发不超过 FAST_DECODE_WORKERS 个线程。"""
        threads_seen = set()
        lock = threading.Lock()

        def fake_read(tex):
            return b"raw"

        def fake_decode(tex, raw):
            with lock:
                threads_seen.add(threading.current_thread().ident)
            import time
            time.sleep(0.05)
            return FakeImage()

        # 6 页：足够排队
        atlas = FakeTexture("illust_x.atlas",
                            script=ATLAS_CONTENT + "".join(
                                "illust_p%d.png\n" % i for i in range(5)))
        objs = [FakeObj("TextAsset", "illust_x.atlas", 1, atlas)]
        for i in range(6):
            objs.append(FakeObj("Texture2D", "illust_%s" % ("x" if i == 0 else "p%d" % (i - 1)),
                                2 + i, FakeTexture("illust_%s" % ("x" if i == 0 else "p%d" % (i - 1)))))
        with mock.patch.multiple(unpacker, _read_texture_bytes=fake_read,
                                 _decode_texture=fake_decode):
            with tempfile.TemporaryDirectory() as tmp:
                unpacker._export_objects(FakeEnv(objs), tmp, lambda m: False, fast=True)
        self.assertLessEqual(len(threads_seen), unpacker.FAST_DECODE_WORKERS,
                             "工作线程数越界: %d > %d" % (len(threads_seen), unpacker.FAST_DECODE_WORKERS))

    def test_single_texture_stays_serial(self):
        """单张贴图没必要开池：直接主线程解（省去线程建立开销）。"""
        atlas = FakeTexture("illust_x.atlas", script=ATLAS_CONTENT)
        decodes = []
        env = FakeEnv([
            FakeObj("TextAsset", "illust_x.atlas", 1, atlas),
            FakeObj("Texture2D", "illust_x", 2, FakeTexture("illust_x")),
        ])
        with _patch_seams(decodes=decodes):
            with tempfile.TemporaryDirectory() as tmp:
                unpacker._export_objects(env, tmp, lambda m: False, fast=True)
        self.assertEqual(1, len(decodes))
        self.assertEqual(threading.main_thread().ident, decodes[0][0],
                         "单张贴图应在主线程解码")

    def test_one_failed_texture_does_not_kill_the_batch(self):
        """一张图解码失败只报 FAILED，其余照常导出（与老行为一致）。"""
        atlas = FakeTexture("illust_x.atlas",
                            script=ATLAS_CONTENT + "illust_bad.png\n")
        messages = []
        env = FakeEnv([
            FakeObj("TextAsset", "illust_x.atlas", 1, atlas),
            FakeObj("Texture2D", "illust_x", 2, FakeTexture("illust_x")),
            FakeObj("Texture2D", "illust_bad", 3, FakeTexture("illust_bad")),
        ])
        with _patch_seams(fail_names=("illust_bad",)):
            with tempfile.TemporaryDirectory() as tmp:
                unpacker._export_objects(
                    env, tmp, lambda m: (messages.append(m) or False), fast=True)
        self.assertTrue(any("FAILED" in m and "illust_bad" in m for m in messages),
                        "坏图必须报 FAILED，实际消息: %s" % messages)
        # 好图成功与否：无 FAILED 即成功
        self.assertFalse(any("illust_x" in m and "FAILED" in m for m in messages),
                         "坏图连累了好图: %s" % messages)

    def test_cancel_still_works(self):
        """取消回调返回 True 时必须收手（预解包的取消按钮依赖这个）——
        对象遍历阶段和贴图喂料阶段都要能停。"""
        atlas = FakeTexture("illust_x.atlas", script=ATLAS_CONTENT)
        page = FakeTexture("illust_x")
        env = FakeEnv([
            FakeObj("TextAsset", "illust_x.atlas", 1, atlas),
            FakeObj("Texture2D", "illust_x", 2, page),
        ])
        reads = []
        with _patch_seams(reads=reads):
            with tempfile.TemporaryDirectory() as tmp:
                with self.assertRaises(unpacker.UnpackCancelled):
                    unpacker._export_objects(env, tmp, lambda m: True, fast=True)
        self.assertEqual([], reads, "取消后不该再读贴图字节")


if __name__ == "__main__":
    unittest.main()
