# -*- coding: utf-8 -*-
"""T06 — Python 侧归档/导出路径越界封堵：`unpacker._unique_export_path` 的真实行为。

计划原文把 Python 半边设想成「新增 `safe_archive.py` + 修改 `resolver.py`」，
但实测（见 docs/refactor/evidence/T06.md §2）：
  · `app/src/main/python/` 下除 `vendor/UnityPy/` 外**零个** `zipfile`；
  · `resolver.py` 全文不写任何文件；
  · `ModdingService.kt` 里没有任何「解压归档」的入口。
所以「Python 归档解压入口」**不存在**，`safe_archive.py` 会是一个没人调用的死模块。
本文件保留计划规定的文件名，但**被测对象是真实的写盘点**：
`unpacker._unique_export_path` / `_safe_export_name` / `_require_within`。

测试约定（沿用 tests/test_resolution_catalog_fallback.py）：
  · 只驱动真实实现，不 mock 被测逻辑；
  · 每个用例在系统临时目录里自建输入，**不使用任何夹具**，因此不往
    docs/refactor/fixture-manifest.json 注册（自建名字的用例没有外部输入）；
  · Python 3.8 兼容（无 walrus / 无 dict 并集运算符 / 无 f-string '='）。
    本文件另外**不**使用 `Path.is_relative_to`（3.9+）—— 生产代码用的
    `os.path.commonpath`（3.5+）在测试里同样可用。

关于「是不是已证实的越界」，两件事分开说（见 T06.md §3 的改动前行为表）：
  · `'../../evil'` 在**任何**平台上都逃不出去 —— 改动前 `replace('/', '_')` 已经把它
    变成 `.._.._evil`；本文件对它的断言是**回归**（改完不许变坏），不是这次修好的东西。
  · `'..\\..\\evil'` 与 `'C:\\evil'` 只在 Windows 语义下逃逸。生产是 Android(POSIX)，
    所以这次加固的性质是**纵深防御**，不是修一个已证实的生产漏洞。
"""
import os
import shutil
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.dirname(_HERE)
_PY_DIR = os.path.join(_REPO, "app", "src", "main", "python")
if _PY_DIR not in sys.path:
    sys.path.insert(0, _PY_DIR)

import unpacker  # noqa: E402


def _is_inside(root, candidate):
    """candidate 是否严格落在 root 之内（按 realpath，与生产实现同一套语义）。"""
    real_root = os.path.realpath(root)
    real = os.path.realpath(candidate)
    try:
        common = os.path.commonpath([real_root, real])
    except ValueError:
        return False
    return common == real_root and real != real_root


class SafeExportNameTest(unittest.TestCase):
    """`_safe_export_name`：把外部派生的资产名规范化成不含分隔符的文件名片段。"""

    def test_forward_slash_becomes_underscore(self):
        # 改动前就有这条语义，属于回归：不许变。
        self.assertEqual('.._.._evil', unpacker._safe_export_name('../../evil'))
        self.assertEqual('_etc_passwd', unpacker._safe_export_name('/etc/passwd'))

    def test_backslash_becomes_underscore(self):
        # **这是本次真正改掉的行为**：改动前 '\\' 原样保留，在 Windows 语义下
        # os.path.join 会把它当分隔符，`..\\..\\evil` 于是逃出 output_dir。
        self.assertEqual('.._.._evil', unpacker._safe_export_name('..\\..\\evil'))
        self.assertEqual('a_b', unpacker._safe_export_name('a\\b'))

    def test_nested_chinese_name_becomes_flat_but_keeps_characters(self):
        self.assertEqual('角色_皮肤_data', unpacker._safe_export_name('角色/皮肤/data'))
        self.assertEqual('角色_皮肤_data', unpacker._safe_export_name('角色\\皮肤\\data'))

    def test_drive_prefix_is_neutralised(self):
        # 改动前 Windows 上 os.path.join(out, 'C:\\evil') 返回 'C:\\evil'，把 out 丢了。
        self.assertNotIn(':', unpacker._safe_export_name('C:\\evil'))
        self.assertNotIn(':', unpacker._safe_export_name('C:/evil'))
        self.assertNotIn(':', unpacker._safe_export_name('C:evil'))

    def test_nul_is_replaced(self):
        # NUL 在**任何**平台上都会让 open() 抛 ValueError: embedded null character。
        self.assertEqual('a_b', unpacker._safe_export_name('a\x00b'))
        self.assertEqual('_', unpacker._safe_export_name('\x00'))

    def test_normal_names_are_untouched(self):
        for name in ['atlas', 'char_004091', 'page [dup]', '#prefab_1',
                     'SPINE_PAGE', 'spine_page', 'data.txt.old', '..x', 'x..y']:
            self.assertEqual(name, unpacker._safe_export_name(name))

    def test_empty_and_none_fall_back_to_unnamed(self):
        self.assertEqual('unnamed', unpacker._safe_export_name(''))
        self.assertEqual('unnamed', unpacker._safe_export_name(None))

    def test_result_never_contains_a_path_separator(self):
        # 对着一整张恶意名单做「输出面」的断言，而不是逐个字符串比对。
        nasty = [
            '../../evil', '..\\..\\evil', 'nested/../../evil', '/etc/passwd',
            'C:\\evil', 'C:/evil', 'C:evil', '\\\\server\\share\\evil',
            '..', 'a/../../b', '角色/皮肤/data', 'a\x00b',
        ]
        for base in nasty:
            out = unpacker._safe_export_name(base)
            self.assertNotIn('/', out, base)
            self.assertNotIn('\\', out, base)
            self.assertNotIn('\x00', out, base)
            self.assertNotEqual('', out, base)


class UniqueExportPathTest(unittest.TestCase):
    """真实写盘点：`_unique_export_path` 组装出来的路径。**

    每条越界用例都断言两件事：(1) 结果严格落在 output_dir 内；(2) 真的照这个路径写盘、
    也不会在 output_dir 之外留下任何东西。只断言 (1) 会漏掉「路径没越界但写盘时越界」
    这一类（例如 NUL 的 ValueError），所以两条都留。
    """

    def setUp(self):
        self.top = tempfile.mkdtemp(prefix='t06py_')
        self.out = os.path.join(self.top, 'output')
        os.makedirs(self.out)
        self.before = sorted(os.listdir(self.top))

    def tearDown(self):
        shutil.rmtree(self.top, ignore_errors=True)

    def _dest_and_write(self, base_name, extension='.png', path_id=7):
        dest = unpacker._unique_export_path(self.out, base_name, extension, path_id)
        with open(dest, 'wb') as fh:
            fh.write(b'x')
        return dest

    def _assert_no_new_entry_beside_output(self):
        self.assertEqual(
            self.before, sorted(os.listdir(self.top)),
            '输出目录之外不得多出任何东西',
        )

    # ---------------- 越界形态 ----------------

    def test_posix_traversal_stays_inside(self):
        # 回归项：改动前这条就已经安全（'/' 早被换掉），改完必须仍然安全。
        dest = self._dest_and_write('../../evil')
        self.assertTrue(_is_inside(self.out, dest), dest)
        self.assertTrue(os.path.exists(dest))
        self._assert_no_new_entry_beside_output()

    def test_nested_traversal_stays_inside(self):
        dest = self._dest_and_write('nested/../../evil')
        self.assertTrue(_is_inside(self.out, dest), dest)
        self._assert_no_new_entry_beside_output()

    def test_backslash_traversal_stays_inside(self):
        # **本次改掉的行为**：改动前在 Windows 语义下这条会在 top/ 下落 evil.png。
        dest = self._dest_and_write('..\\..\\evil')
        self.assertTrue(_is_inside(self.out, dest), dest)
        self.assertFalse(os.path.exists(os.path.join(self.top, 'evil.png')))
        self._assert_no_new_entry_beside_output()

    def test_absolute_and_drive_paths_stay_inside(self):
        for base in ['/etc/passwd', 'C:\\evil', 'C:/evil', '\\\\server\\share\\evil']:
            dest = self._dest_and_write(base)
            self.assertTrue(_is_inside(self.out, dest), base)
            self.assertTrue(os.path.dirname(os.path.realpath(dest)) == os.path.realpath(self.out),
                            base)
        self._assert_no_new_entry_beside_output()

    def test_bare_dotdot_stays_inside(self):
        for extension in ('.png', ''):
            dest = self._dest_and_write('..', extension)
            self.assertTrue(_is_inside(self.out, dest), repr(extension))
        self._assert_no_new_entry_beside_output()

    # ---------------- 正常语义 ----------------

    def test_nested_chinese_name_is_flattened_but_found(self):
        dest = self._dest_and_write('角色/皮肤/data')
        self.assertEqual('角色_皮肤_data.png', os.path.basename(dest))
        self.assertEqual(os.path.realpath(self.out), os.path.dirname(os.path.realpath(dest)))

    def test_duplicate_name_gets_the_path_id_suffix(self):
        # 既有去重语义：第二次导出同名资产追加 ' #<path_id>'，不能因为加固而丢掉。
        first = self._dest_and_write('atlas')
        second = unpacker._unique_export_path(self.out, 'atlas', '.png', 4242)
        with open(second, 'wb') as fh:
            fh.write(b'y')
        self.assertEqual('atlas.png', os.path.basename(first))
        self.assertEqual('atlas #4242.png', os.path.basename(second))
        self.assertNotEqual(first, second)

    def test_empty_extension_is_kept_as_is(self):
        # MonoBehaviour/TextAsset 走的是 extension='' 这条路（unpacker.py:314）。
        dest = self._dest_and_write('char_004091.skel', '')
        self.assertEqual('char_004091.skel', os.path.basename(dest))

    def test_spine_page_case_is_preserved(self):
        # 页名大小写敏感（Android 区分大小写，差一个字母 Spine 就找不到页文件）。
        dest = unpacker._unique_export_path(self.out, 'SPINE_Page', '.png', 1)
        self.assertEqual('SPINE_Page.png', os.path.basename(dest))

    def test_no_exported_path_ever_escapes_output_dir(self):
        """端到端：把整张恶意名单跑一遍，输出目录之外一个东西都不许多。"""
        nasty = [
            '../../evil', '..\\..\\evil', 'nested/../../evil', '/etc/passwd',
            'C:\\evil', 'C:/evil', '\\\\server\\share\\evil', '..',
            'a/../../b', '..\\..\\..\\..\\deep\\evil', '角色\\皮肤\\data',
        ]
        for index, base in enumerate(nasty):
            dest = unpacker._unique_export_path(self.out, base, '.png', index)
            self.assertTrue(_is_inside(self.out, dest), base)
            with open(dest, 'wb') as fh:
                fh.write(b'x')
        self._assert_no_new_entry_beside_output()


class RequireWithinTest(unittest.TestCase):
    """纵深防御闸门 `_require_within` 本身。

    诚实登记它今天的位置：`_safe_export_name` 已经把 `base_name` 里的分隔符消干净，
    所以**单靠 base_name 到不了这个分支**。它守的是同一次拼接里另两条未经规范化的
    输入（`output_dir`、`extension`/`path_id`），以及将来新加的调用点。
    因此这里的用例是直接驱动这个真实函数，而不是假装 `_unique_export_path` 会走到它。
    """

    def setUp(self):
        self.top = tempfile.mkdtemp(prefix='t06py_')
        self.out = os.path.join(self.top, 'output')
        os.makedirs(self.out)
        self.evil = os.path.join(self.top, 'output-evil')
        os.makedirs(self.evil)

    def tearDown(self):
        shutil.rmtree(self.top, ignore_errors=True)

    def test_accepts_a_path_inside(self):
        dest = os.path.join(self.out, 'a.png')
        self.assertEqual(dest, unpacker._require_within(self.out, dest))
        nested = os.path.join(self.out, 'sub', 'deep', 'b.png')
        self.assertEqual(nested, unpacker._require_within(self.out, nested))

    def test_rejects_parent_traversal(self):
        with self.assertRaises(ValueError):
            unpacker._require_within(self.out, os.path.join(self.out, '..', 'evil.png'))

    def test_rejects_absolute_path_outside(self):
        outside = os.path.join(self.top, 'evil.png')
        with self.assertRaises(ValueError):
            unpacker._require_within(self.out, outside)

    def test_rejects_the_directory_itself(self):
        with self.assertRaises(ValueError):
            unpacker._require_within(self.out, self.out)

    def test_same_prefix_sibling_is_not_accepted(self):
        # 实测：'out-evil/x'.startswith('out') 是 True —— 纯字符串前缀判定会在这里漏。
        # Path.commonpath 不会。
        sibling = os.path.join(self.evil, 'x.png')
        self.assertTrue(sibling.startswith(self.out))   # 前提：确实是前缀关系
        with self.assertRaises(ValueError):
            unpacker._require_within(self.out, sibling)


class NoDeadSafeArchiveModuleTest(unittest.TestCase):
    """把「计划设想的 Python 归档入口不存在」这件事变成一条可执行的记录。"""

    def test_planned_safe_archive_module_does_not_exist(self):
        # T06.md §2 的结论：plan 要求新增的 safe_archive.py 没有生产调用点，
        # 故本轮**不**创建（裁决 prep-notes §6.2）。这里断言它是真的不存在，
        # 免得以后有人读到文件头这句注释还以为模块被漏建了。
        self.assertFalse(os.path.exists(os.path.join(_PY_DIR, 'safe_archive.py')))
        self.assertFalse(hasattr(unpacker, 'extract_zip'))

    def test_the_hardened_function_is_on_the_real_write_path(self):
        # 加固对象必须是**真实写盘路径**，不是一个只为测试存在的函数。
        # 判据：unpacker.py 自己就调用它三次（TextAsset/MonoBehaviour 一处、
        # 贴图串行一处、贴图线程池一处），而且它被 unpack_bundle 这条生产链路
        # （ModdingService.kt → unpack_bundle）间接用到。
        with open(os.path.join(_PY_DIR, 'unpacker.py'), encoding='utf-8') as fh:
            source = fh.read()
        self.assertEqual(4, source.count('_unique_export_path('))  # 1 处 def + 3 处调用
        self.assertIn('def unpack_bundle(', source)


if __name__ == '__main__':
    unittest.main()
