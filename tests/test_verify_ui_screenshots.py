# -*- coding: utf-8 -*-
"""T58：截图核对器自身的回归测试。

核对器坏了会**静默地**说「PASS」—— 那比没有核对器更糟。这里断言的是判定逻辑本身：
单色图、黑屏、明暗颠倒都必须被抓出来。

写成 `unittest.TestCase` 而不是 pytest 风格函数：本仓库的 Python 门禁
（`tools/run_python_tests.py` + `Test-Bd2Python`）是 **unittest runner**，
pytest 风格的函数它**一条都收不到** —— 那样的测试等于没进门禁（实测踩过：
`LocalAll` 逐个文件调用时 `discovered=0`）。
"""
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _load():
    path = os.path.join(ROOT, 'tools', 'verify_ui_screenshots.py')
    spec = importlib.util.spec_from_file_location('verify_ui_screenshots', path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _write_shot(path, kind):
    """造一张**像真实界面**的图。

    只画几个点的图在缩放到 96x96 后只剩两三种颜色，会被核对器当成单色图拒掉 ——
    那是夹具不真实，不是核对器有错。这里画背景 + 顶栏 + 多行文字条 + 按钮。
    """
    if kind == 'flat':
        Image.new('RGB', (1080, 1920), (128, 128, 128)).save(path)
        return
    if kind == 'light':
        bg, fg, bar, accent = (245, 245, 250), (30, 30, 35), (225, 222, 235), (102, 80, 164)
    elif kind == 'dark':
        bg, fg, bar, accent = (18, 18, 22), (232, 230, 235), (32, 31, 38), (208, 188, 255)
    else:
        raise ValueError(kind)

    img = Image.new('RGB', (1080, 1920), bg)
    px = img.load()
    for y in range(0, 200):
        for x in range(0, 1080):
            px[x, y] = bar
    y = 260
    for row in range(26):
        width = 300 + ((row * 137) % 640)
        for yy in range(y, y + 14):
            for xx in range(60, 60 + width):
                px[xx, yy] = fg
        y += 62
    for yy in range(1700, 1800):
        for xx in range(60, 460):
            px[xx, yy] = accent
    img.save(path)


def _make_dir(shots):
    tmp = tempfile.mkdtemp(prefix='t58-shots-')
    entries = []
    for name, kind, mode in shots:
        _write_shot(os.path.join(tmp, name), kind)
        entries.append({
            'file': name, 'label': name, 'appThemeMode': mode,
            'colorPreset': 'PURPLE', 'fontScale': '1.0',
        })
    with io.open(os.path.join(tmp, 'index.json'), 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(json.dumps({'shots': entries}, ensure_ascii=False))
    return tmp


def _run(directory):
    # 子进程的 stdout 在 Windows 上默认走 locale 编码（cp936），中文 JSON 会变成 GBK 字节，
    # 父进程按 UTF-8 解就会 JSONDecodeError。显式指定子进程用 UTF-8。
    env = dict(os.environ, PYTHONIOENCODING='utf-8')
    return subprocess.run(
        [sys.executable, os.path.join(ROOT, 'tools', 'verify_ui_screenshots.py'),
         '--dir', directory],
        capture_output=True, check=False, env=env,
    )


def _report(res):
    return json.loads(res.stdout.decode('utf-8'))


class VerifyUiScreenshotsTest(unittest.TestCase):

    def test_light_and_dark_pass(self):
        d = _make_dir([('a.png', 'light', 'LIGHT'), ('b.png', 'dark', 'DARK')])
        res = _run(d)
        self.assertEqual(res.returncode, 0, res.stderr.decode('utf-8', 'replace'))
        report = _report(res)
        self.assertEqual(report['result'], 'PASS')
        self.assertEqual(report['problems'], [])

    def test_flat_single_colour_image_is_rejected(self):
        d = _make_dir([('a.png', 'light', 'LIGHT'), ('b.png', 'flat', 'DARK')])
        res = _run(d)
        self.assertEqual(res.returncode, 1)
        problems = _report(res)['problems']
        self.assertTrue(any('平的' in p or '单色' in p for p in problems), problems)

    def test_inverted_light_dark_is_rejected(self):
        """浅色比深色还暗 —— 说明明暗切换根本没生效，必须报错。"""
        d = _make_dir([('a.png', 'dark', 'LIGHT'), ('b.png', 'light', 'DARK')])
        res = _run(d)
        self.assertEqual(res.returncode, 1)
        problems = _report(res)['problems']
        self.assertTrue(any('明暗切换没生效' in p for p in problems), problems)

    def test_missing_dark_shot_is_rejected(self):
        d = _make_dir([('a.png', 'light', 'LIGHT'), ('b.png', 'light', 'LIGHT')])
        res = _run(d)
        self.assertEqual(res.returncode, 1)
        problems = _report(res)['problems']
        self.assertTrue(any('缺少浅色或深色' in p for p in problems), problems)

    def test_tiny_resolution_is_rejected(self):
        tmp = tempfile.mkdtemp(prefix='t58-shots-')
        Image.new('RGB', (100, 100), (250, 250, 250)).save(os.path.join(tmp, 'a.png'))
        Image.new('RGB', (100, 100), (10, 10, 10)).save(os.path.join(tmp, 'b.png'))
        with io.open(os.path.join(tmp, 'index.json'), 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(json.dumps({'shots': [
                {'file': 'a.png', 'label': 'a', 'appThemeMode': 'LIGHT', 'fontScale': '1.0'},
                {'file': 'b.png', 'label': 'b', 'appThemeMode': 'DARK', 'fontScale': '1.0'},
            ]}, ensure_ascii=False))
        res = _run(tmp)
        self.assertEqual(res.returncode, 1)
        problems = _report(res)['problems']
        self.assertTrue(any('分辨率异常' in p for p in problems), problems)

    def test_missing_index_is_rejected(self):
        tmp = tempfile.mkdtemp(prefix='t58-shots-')
        res = _run(tmp)
        self.assertNotEqual(res.returncode, 0)
        self.assertIn(b'index.json', res.stderr)

    def test_missing_metadata_field_is_reported(self):
        """索引缺字段必须被记成问题，不能默默用默认值掩盖。"""
        tmp = tempfile.mkdtemp(prefix='t58-shots-')
        _write_shot(os.path.join(tmp, 'a.png'), 'light')
        _write_shot(os.path.join(tmp, 'b.png'), 'dark')
        with io.open(os.path.join(tmp, 'index.json'), 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(json.dumps({'shots': [
                {'file': 'a.png', 'appThemeMode': 'LIGHT'},
                {'file': 'b.png', 'appThemeMode': 'DARK'},
            ]}, ensure_ascii=False))
        res = _run(tmp)
        self.assertEqual(res.returncode, 1)
        problems = _report(res)['problems']
        self.assertTrue(any('缺少' in p and '字段' in p for p in problems), problems)


if __name__ == '__main__':
    unittest.main()
