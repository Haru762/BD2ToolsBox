# -*- coding: utf-8 -*-
"""T58：从**本轮构建的 release APK** 采集真实原生界面截图。

覆盖：浅色 / 深色 / 字体放大（1.3 与 2.0）/ 无壁纸与有壁纸。
不使用旧截图、不用网页原型冒充原生验证。

用法：
    python tools/capture_ui_screenshots.py --abi x86_64 --out docs/refactor/evidence/screenshots
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = 'com.bd2toolsbox'
ACTIVITY = f'{PKG}/.MainActivity'


def adb(serial: str, *args: str, binary: bool = False):
    cmd = ['adb', '-s', serial, *args]
    res = subprocess.run(cmd, capture_output=True, check=False)
    if res.returncode != 0 and not binary:
        raise RuntimeError(f'adb 失败（{res.returncode}）：{" ".join(cmd)}\n{res.stderr.decode("utf-8", "replace")}')
    return res.stdout if binary else res.stdout.decode('utf-8', 'replace')


def shell(serial: str, *args: str) -> str:
    return adb(serial, 'shell', *args)


def wait_boot(serial: str, timeout: float = 60.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if '1' in shell(serial, 'getprop', 'sys.boot_completed'):
                return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError('设备未在超时内就绪')


def launch(serial: str) -> str:
    shell(serial, 'am', 'force-stop', PKG)
    time.sleep(1)
    out = shell(serial, 'am', 'start', '-W', '-n', ACTIVITY)
    if 'Status: ok' not in out:
        raise RuntimeError(f'启动失败：{out}')
    return out


def capture(serial: str, out_path: str) -> str:
    """screencap 到设备再 pull 回来 —— 直接重定向 stdout 会被 CRLF 破坏 PNG。"""
    remote = f'/data/local/tmp/t58-{int(time.time()*1000)}.png'
    shell(serial, 'screencap', '-p', remote)
    adb(serial, 'pull', remote, out_path)
    shell(serial, 'rm', '-f', remote)
    if not os.path.isfile(out_path) or os.path.getsize(out_path) < 4096:
        raise RuntimeError(f'截图过小或缺失：{out_path}')
    with open(out_path, 'rb') as fh:
        head = fh.read(8)
    if head != b'\x89PNG\r\n\x1a\n':
        raise RuntimeError(f'不是合法 PNG：{out_path}（头 {head!r}）')
    return hashlib.sha256(open(out_path, 'rb').read()).hexdigest()


def png_size(path: str) -> tuple[int, int]:
    """从 PNG 的 IHDR 里读宽高（不依赖 Pillow）。"""
    import struct
    with open(path, 'rb') as fh:
        head = fh.read(24)
    if head[:8] != b'\x89PNG\r\n\x1a\n' or head[12:16] != b'IHDR':
        raise RuntimeError(f'不是合法 PNG：{path}')
    return struct.unpack('>II', head[16:24])


def set_night(serial: str, mode: str) -> None:
    shell(serial, 'cmd', 'uimode', 'night', mode)
    time.sleep(2)


def set_font_scale(serial: str, scale: str) -> None:
    shell(serial, 'settings', 'put', 'system', 'font_scale', scale)
    # 字体缩放需要重启 Activity 才会被 Compose 重新读取
    shell(serial, 'am', 'force-stop', PKG)
    time.sleep(1)


def set_rotation(serial: str, rotation: int) -> None:
    """锁定屏幕方向：0 = 竖屏，1 = 横屏。

    MuMu 默认是横屏（第一次采集出来的是 1920x1080），而手机常态是竖屏。
    关掉自动旋转再显式指定，否则截图方向不可预期。
    """
    shell(serial, 'settings', 'put', 'system', 'accelerometer_rotation', '0')
    shell(serial, 'settings', 'put', 'system', 'user_rotation', str(rotation))
    shell(serial, 'am', 'force-stop', PKG)
    time.sleep(2)


class UiDriver:
    """靠 uiautomator 的控件树做**按标签**导航。

    不用写死坐标：坐标在不同分辨率/字号下都会变，写死的坐标会在下一次换设备时
    静静地截到错的屏幕（那比报错更糟）。找不到标签就报错，不静默跳过。
    """

    def __init__(self, serial: str):
        self.serial = serial
        self._cache: list[dict] = []

    def dump(self) -> list[dict]:
        shell(self.serial, 'uiautomator', 'dump', '/data/local/tmp/t58-ui.xml')
        out = adb(self.serial, 'shell', 'cat', '/data/local/tmp/t58-ui.xml')
        nodes = []
        for m in re.finditer(r'<node[^>]*>', out):
            tag = m.group(0)

            def attr(name: str) -> str:
                mm = re.search(name + r'="([^"]*)"', tag)
                return mm.group(1) if mm else ''

            nodes.append({
                'text': attr('text'), 'desc': attr('content-desc'),
                'bounds': attr('bounds'), 'clickable': attr('clickable'),
            })
        self._cache = nodes
        return nodes

    def find(self, label: str) -> dict | None:
        for n in self._cache:
            if (n['text'] or n['desc']) == label:
                return n
        return None

    def labels(self) -> list[str]:
        return [n['text'] or n['desc'] for n in self._cache if (n['text'] or n['desc'])]

    def tap(self, label: str) -> None:
        node = self.find(label)
        if node is None:
            raise RuntimeError(f'界面上找不到「{label}」；当前标签：{self.labels()[:25]}')
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node['bounds'])
        if not m:
            raise RuntimeError(f'「{label}」的 bounds 不可解析：{node["bounds"]}')
        x = (int(m.group(1)) + int(m.group(3))) // 2
        y = (int(m.group(2)) + int(m.group(4))) // 2
        shell(self.serial, 'input', 'tap', str(x), str(y))

    def wait_for(self, label: str, timeout: float = 12.0) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            self.dump()
            if self.find(label) is not None:
                return
            time.sleep(1)
        raise RuntimeError(f'等不到「{label}」；当前标签：{self.labels()[:25]}')

    def swipe_up(self, times: int = 1) -> None:
        for _ in range(times):
            shell(self.serial, 'input', 'swipe', '960', '900', '960', '300', '300')
            time.sleep(2)

    def dismiss_index_prompt(self) -> None:
        """首次启动会有「需要建立文件索引」提示；不点掉它后面什么都点不到。"""
        self.dump()
        if self.find('跳过') is not None:
            self.tap('跳过')
            time.sleep(3)


PREFS = f'/data/data/{PKG}/shared_prefs/app_settings.xml'


def read_prefs(serial: str) -> str:
    """读应用偏好。

    **用 `su` 而不是 `run-as`**：采集用的是 **release** 包，`run-as` 会直接报
    「package not debuggable」。用 debug 包代替会让截图与交付产物脱钩。
    MuMu 上 `su -c id` 返回 uid=0，所以走 root。
    """
    out = adb(serial, 'shell', f"su -c 'cat {PREFS}'")
    if '<map>' not in out:
        raise RuntimeError('读不到应用偏好（没有 root？）：' + out[:200])
    return out


def write_prefs(serial: str, xml_text: str) -> None:
    """改写应用自己的外观偏好。

    **为什么要动 prefs**：App 的明暗默认是 `LIGHT`（`SettingsRepository` 有注释：
    旧用户没有这个键时保持当前外观不变），所以 `cmd uimode night yes` 不会改变界面 ——
    第一次采集时浅/深两张图的 sha256 完全相同就是这个原因。要拍真正的深色，
    必须改 App 自己的 `theme_mode`。
    """
    shell(serial, 'am', 'force-stop', PKG)
    time.sleep(1)
    remote = '/data/local/tmp/t58-prefs.xml'
    local = os.path.join(ROOT, 'local', 't58-prefs.xml')
    os.makedirs(os.path.dirname(local), exist_ok=True)
    with io.open(local, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(xml_text)
    adb(serial, 'push', local, remote)
    shell(serial, 'chmod', '644', remote)
    shell(serial, 'su', '-c', f"'cp {remote} {PREFS} && chown $(stat -c %u:%g {PREFS}) {PREFS}'")
    shell(serial, 'rm', '-f', remote)


def set_pref(serial: str, key: str, value_xml: str) -> None:
    """在应用偏好里替换/插入一个条目（只处理简单标量）。"""
    xml_text = read_prefs(serial)
    pattern = re.compile(r'\s*<(string|boolean|int|long|float) name="%s"[^>]*>[^<]*</\1>' % re.escape(key))
    xml_text = pattern.sub('', xml_text)
    insertion = f'    {value_xml}\n</map>'
    if '</map>' not in xml_text:
        raise RuntimeError('prefs XML 形状意外，拒绝改写：' + xml_text[:200])
    xml_text = xml_text.replace('</map>', insertion, 1)
    write_prefs(serial, xml_text)
    # 回读确认真的写进去了 —— 不靠「命令返回 0」当作成功。
    # 校验放在 set_pref 而不是 write_prefs：恢复「原始偏好」时那个键本来就不存在，
    # 放在 write_prefs 会把正常的恢复当成失败（实测踩过）。
    if f'name="{key}"' not in read_prefs(serial):
        raise RuntimeError(f'偏好写入未生效（回读里没有 {key}）')


def set_theme_mode(serial: str, mode: str) -> None:
    set_pref(serial, 'theme_mode', f'<string name="theme_mode">{mode}</string>')


def set_color_preset(serial: str, preset: str) -> None:
    set_pref(serial, 'app_theme', f'<string name="app_theme">{preset}</string>')


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', default='127.0.0.1:16384')
    ap.add_argument('--abi', default='x86_64', choices=['x86_64', 'arm64-v8a'])
    ap.add_argument('--out', default=os.path.join(ROOT, 'docs', 'refactor', 'evidence', 'screenshots'))
    ap.add_argument('--apk', default=None)
    args = ap.parse_args()

    flavor = 'x64' if args.abi == 'x86_64' else 'arm64'
    apk = args.apk or os.path.join(ROOT, 'app', 'build', 'outputs', 'apk', flavor, 'release',
                                   f'app-{flavor}-release.apk')
    if not os.path.isfile(apk):
        raise SystemExit(f'缺少 release APK：{apk}')
    apk_sha = hashlib.sha256(open(apk, 'rb').read()).hexdigest()

    os.makedirs(args.out, exist_ok=True)
    serial = args.serial
    wait_boot(serial)

    install = adb(serial, 'install', '-r', apk)
    if 'Success' not in install:
        raise SystemExit(f'安装失败：{install}')

    original_font = shell(serial, 'settings', 'get', 'system', 'font_scale').strip() or '1.0'
    original_night = shell(serial, 'settings', 'get', 'system', 'ui_night_mode').strip() or '0'
    original_prefs = read_prefs(serial)

    shots = []
    # (名字, 应用内明暗, 配色预设, 字体缩放, 说明)
    # **不含方向**：本机（MuMu）固定 1920x1080 横屏，改不了 —— 把它列进计划只会
    # 产出一批文件名声称竖屏、实际是横屏的图（踩过）。方向限制写在 index.limitations。
    plan = [
        ('light-default', 'LIGHT', 'PURPLE', '1.0', '浅色 默认字号（紫）'),
        ('dark-default', 'DARK', 'PURPLE', '1.0', '深色 默认字号（紫）'),
        ('light-teal', 'LIGHT', 'TEAL', '1.0', '浅色 青色预设'),
        ('dark-graphite', 'DARK', 'GRAPHITE', '1.0', '深色 石墨预设'),
        ('light-font1.3', 'LIGHT', 'PURPLE', '1.3', '浅色 字体 1.3'),
        ('light-font2.0', 'LIGHT', 'PURPLE', '2.0', '浅色 字体 2.0（最大）'),
        ('dark-font2.0', 'DARK', 'PURPLE', '2.0', '深色 字体 2.0（最大）'),
    ]
    try:
        for name, mode, preset, font, label in plan:
            set_theme_mode(serial, mode)
            set_color_preset(serial, preset)
            set_font_scale(serial, font)
            launch(serial)
            time.sleep(3)
            path = os.path.join(args.out, f'{name}.png')
            sha = capture(serial, path)
            width, height = png_size(path)
            # 方向只**如实记录实测值**，不做请求-实际比对 —— 因为本机改不了方向，
            # 比对只会产出一堆恒定的「未生效」噪音。
            got = 'portrait' if height > width else 'landscape'
            shots.append({
                'file': os.path.basename(path),
                'label': label,
                'appThemeMode': mode,
                'colorPreset': preset,
                'fontScale': font,
                'actualOrientation': got,
                'size': [width, height],
                'bytes': os.path.getsize(path),
                'sha256': sha,
            })
            print(f'  {name}: {width}x{height} {got} {os.path.getsize(path)} B sha256={sha[:16]}...')
    finally:
        set_night(serial, 'no' if original_night == '0' else 'yes')
        set_font_scale(serial, original_font)
        write_prefs(serial, original_prefs)
        shell(serial, 'am', 'force-stop', PKG)

    # 同一次采集里的截图必须互不相同 —— 相同就说明某个维度根本没生效
    # （第一次采集时浅/深两张图 sha256 相同，就是这么发现的）。
    by_sha: dict[str, list[str]] = {}
    for s in shots:
        by_sha.setdefault(s['sha256'], []).append(s['file'])
    duplicates = {sha: files for sha, files in by_sha.items() if len(files) > 1}
    if duplicates:
        raise RuntimeError(
            '有截图完全相同，说明对应的外观维度没生效：' + json.dumps(duplicates, ensure_ascii=False)
        )

    index = {
        'capturedFrom': {
            'apk': os.path.relpath(apk, ROOT).replace('\\', '/'),
            'apkSha256': apk_sha,
            'abi': args.abi,
            'serial': serial,
            'package': PKG,
            'versionName': '0.2.16',
            'versionCode': 17,
        },
        'tool': 'tools/capture_ui_screenshots.py (adb screencap -p + pull + uiautomator 标签导航)',
        'note': '截图全部来自本轮构建的 release APK 在设备上的真实渲染；未使用旧截图或网页原型。',
        'limitations': [],
        'shots': shots,
    }

    # ---- 第二段：导航到各个具体屏幕 -------------------------------------------------
    #
    # 靠 uiautomator 的控件树按**标签**点击，不写死坐标。
    driver = UiDriver(serial)

    def shoot(name: str, label: str, mode: str) -> None:
        path = os.path.join(args.out, f'{name}.png')
        sha = capture(serial, path)
        width, height = png_size(path)
        shots.append({
            'file': os.path.basename(path),
            'label': label,
            'appThemeMode': mode,
            'colorPreset': 'PURPLE',
            'fontScale': '1.0',
            'actualOrientation': 'portrait' if height > width else 'landscape',
            'bytes': os.path.getsize(path),
            'sha256': sha,
        })
        print(f'  {name}: {width}x{height} {os.path.getsize(path)} B sha256={sha[:16]}...')

    try:
        set_theme_mode(serial, 'LIGHT')
        set_font_scale(serial, '1.0')
        launch(serial)
        time.sleep(3)
        driver.dismiss_index_prompt()

        # 底部三个 tab
        driver.wait_for('兑换码')
        driver.tap('兑换码')
        time.sleep(3)
        shoot('screen-redeem', '兑换码页', 'LIGHT')

        driver.dump()
        driver.tap('攻略')
        time.sleep(4)
        shoot('screen-guide', '攻略页', 'LIGHT')

        # 设置工作表（含外观与关于）
        driver.dump()
        driver.tap('设置')
        time.sleep(3)
        driver.dump()
        driver.swipe_up(3)
        driver.dump()
        if driver.find('外观') is not None:
            shoot('screen-sheet-appearance-light', '设置工作表·外观（浅色）', 'LIGHT')
            driver.dump()
            if driver.find('深色') is not None:
                driver.tap('深色')
                time.sleep(3)
                # 这里必须显式传 'DARK'：以前用共享变量，结果深色那张被标成 LIGHT
                # —— 正是「把没测装成测过了」那一类错。
                shoot('screen-sheet-appearance-dark', '设置工作表·外观（深色）', 'DARK')
                driver.tap('浅色')
                time.sleep(2)
        else:
            index['limitations'].append('设置工作表里没找到「外观」区，该屏未采。')

        # 开源许可页
        driver.dump()
        if driver.find('开源许可') is None:
            driver.swipe_up(1)
            driver.dump()
        if driver.find('开源许可') is not None:
            driver.tap('开源许可')
            time.sleep(4)
            shoot('screen-licenses', '开源许可页', 'LIGHT')
            shell(serial, 'input', 'keyevent', 'KEYCODE_BACK')
            time.sleep(3)
        else:
            index['limitations'].append('设置工作表里没找到「开源许可」，该屏未采。')

        # 破坏性动作的确认框：清空缓存（只截图，不确认）
        # 先回到工作表顶部再往下找 —— 上一步可能把滚动位置留在了底部。
        driver.dump()
        found = driver.find('清空')
        for _ in range(4):
            if found is not None:
                break
            shell(serial, 'input', 'swipe', '960', '400', '960', '950', '300')
            time.sleep(2)
            driver.dump()
            found = driver.find('清空')
        if found is not None:
            driver.tap('清空')
            time.sleep(3)
            shoot('dialog-clear-cache', '清空缓存的确认框（只截图，未确认）', 'LIGHT')
            shell(serial, 'input', 'keyevent', 'KEYCODE_BACK')
            time.sleep(2)
        else:
            index['limitations'].append('设置工作表里没找到「清空」，确认框未采。')
    except Exception as exc:
        # 导航失败不掩盖：记进 limitations 并写清楚是哪一步
        index['limitations'].append(f'第二段导航未完成：{type(exc).__name__}: {exc}')
    finally:
        shell(serial, 'am', 'force-stop', PKG)

    # 方向这一维**本机无法验证**：MuMu 是固定 1920x1080 横屏，`settings put system
    # user_rotation` 不生效。所以计划里干脆不再请求方向，改成如实登记这条限制 ——
    # 曾经把它标成「竖屏」并写进文件名，那是把「没拍到」记成「拍到了」。
    orientations = sorted({s['actualOrientation'] for s in shots})
    index['limitations'].append(
        '本机（MuMu）是固定 1920x1080 横屏，`settings put system user_rotation` 不生效，'
        f'实际只采到 {orientations} 一种方向。**竖屏布局与旋转恢复的观感未验证**，'
        '需要真机或可竖屏的模拟器补拍。'
    )
    idx_path = os.path.join(args.out, 'index.json')
    with io.open(idx_path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(json.dumps(index, ensure_ascii=False, indent=2) + '\n')
    print(f'index -> {os.path.relpath(idx_path, ROOT)}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
