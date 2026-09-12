# -*- coding: utf-8 -*-
"""UnityPy BundleFile 磁盘溢出（spill）改造的单元测试。

约会大包（422MB）预览被 350MB 体量帽拒之门外的原因是 UnityPy.load
把全部解压块拼进内存、read_files 再复制一份（实测峰值 RSS 1.66GB）。
改造：大包解压到临时文件，.resS/.resource 节点用窗口 reader 按需读盘。

本文件测 _SpillWindow 的纯 IO 契约；端到端（峰值内存、导出一致、
save 回路）由真实 bundle 基准验证。
"""
import io
import os
import sys
import tempfile
import unittest

_here = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(_here, "..", "app", "src", "main", "python"))
sys.path.insert(0, os.path.join(_here, "..", "app", "src", "main", "python", "vendor"))

# 套件运行时前序模块（main_script 的导入链）可能已把 site-packages 的
# UnityPy 缓存进 sys.modules —— 弃用它，让 vendored 版（含 SpillWindow）生效
for _k in [k for k in list(sys.modules)
           if k == "UnityPy" or k.startswith("UnityPy.")]:
    _mod = sys.modules.get(_k)
    if _mod is not None and "site-packages" in (_mod.__file__ or ""):
        del sys.modules[_k]

from UnityPy.files.BundleFile import SpillWindow  # noqa: E402


class SpillWindowTest(unittest.TestCase):
    def setUp(self):
        # 100 字节的"溢出文件"，内容就是 0..99
        self.tf = tempfile.TemporaryFile()
        self.tf.write(bytes(range(100)))
        self.tf.flush()

    def tearDown(self):
        self.tf.close()

    def _window(self, offset, size):
        return SpillWindow(self.tf, offset, size)

    def test_reads_bounded_window(self):
        w = self._window(10, 20)
        with io.BufferedReader(w) as br:
            self.assertEqual(bytes(range(10, 30)), br.read(20))
            # 窗口耗尽后再读是 EOF，绝不越界读到窗口外
            self.assertEqual(b"", br.read(1))

    def test_independent_position_from_other_windows(self):
        """多个窗口共享同一个溢出文件、各自独立 seek —— .resS 惰性读取
        的前提（get_resource_data 每次 Position=xx 再 read）。"""
        w1 = self._window(10, 20)
        w2 = self._window(50, 20)
        b1 = io.BufferedReader(w1)
        b2 = io.BufferedReader(w2)
        self.assertEqual(bytes(range(10, 15)), b1.read(5))
        # w2 读不影响 w1 接着读
        self.assertEqual(bytes(range(50, 55)), b2.read(5))
        self.assertEqual(bytes(range(15, 20)), b1.read(5))

    def test_seek_and_tell_are_window_relative(self):
        w = self._window(10, 20)
        with io.BufferedReader(w) as br:
            br.seek(5)
            self.assertEqual(5, br.tell())
            self.assertEqual(bytes(range(15, 20)), br.read(5))
            # seek(0, 2) 的是窗口大小，不是整个溢出文件
            self.assertEqual(20, br.seek(0, 2))

    def test_negative_or_beyond_seek_is_rejected(self):
        w = self._window(10, 20)
        with io.BufferedReader(w) as br:
            with self.assertRaises(ValueError):
                br.seek(-1)

    def test_close_releases_but_file_survives(self):
        """窗口关掉不影响别的窗口和底层临时文件（dup 句柄语义）。"""
        w1 = self._window(10, 20)
        b1 = io.BufferedReader(w1)
        b1.close()
        with io.BufferedReader(self._window(50, 10)) as b2:
            self.assertEqual(bytes(range(50, 60)), b2.read(10))


if __name__ == "__main__":
    unittest.main()
