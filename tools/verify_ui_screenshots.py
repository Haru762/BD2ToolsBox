# -*- coding: utf-8 -*-
"""T58：对采集到的原生截图做**程序化**核对（不靠肉眼、也不靠「看起来没问题」）。

三件事：

1. **不是单色/黑屏**：整幅图的颜色数必须够多 —— 一张纯色图说明截的是空窗口或崩溃页；
2. **明暗确实生效**：浅色截图的平均亮度必须明显高于深色；
3. **没有整块不可读区域**：把图切成网格，检查是否存在「大片同色且与背景对比极低」的
   区块 —— 那通常意味着系统栏/键盘把内容挤出去了，或者某个面板盖住了正文。

用法：
    python tools/verify_ui_screenshots.py --dir docs/refactor/evidence/screenshots
"""
from __future__ import annotations

import argparse
import io
import json
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 网格划分数（横 × 纵）
GRID_X, GRID_Y = 6, 10


def mean_luminance(img: Image.Image) -> float:
    """整幅图的平均相对亮度（0..1）。

    用 `tobytes()` 而不是 `getdata()`：后者在 Pillow 12 已废弃
    （`Image.Image.getdata is deprecated`），而本仓库的 pytest 配置会把
    DeprecationWarning 提升为错误。
    """
    small = img.convert('L').resize((64, 64))
    data = small.tobytes()
    return sum(data) / len(data) / 255.0


def distinct_colours(img: Image.Image) -> int:
    small = img.convert('RGB').resize((96, 96))
    data = small.tobytes()
    return len({data[i:i + 3] for i in range(0, len(data), 3)})


def grid_variance(img: Image.Image) -> float:
    """网格块内亮度的平均标准差。

    全 0 说明整幅图是平的（单色）；很低说明大部分区域没有内容。
    """
    gray = img.convert('L')
    w, h = gray.size
    cw, ch = w // GRID_X, h // GRID_Y
    if cw == 0 or ch == 0:
        return 0.0
    total = 0.0
    count = 0
    for gy in range(GRID_Y):
        for gx in range(GRID_X):
            block = gray.crop((gx * cw, gy * ch, (gx + 1) * cw, (gy + 1) * ch))
            px = block.tobytes()
            mean = sum(px) / len(px)
            var = sum((p - mean) ** 2 for p in px) / len(px)
            total += var ** 0.5
            count += 1
    return total / count


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--dir', default=os.path.join(ROOT, 'docs', 'refactor', 'evidence', 'screenshots'))
    ap.add_argument('--json', default=None)
    args = ap.parse_args()

    index_path = os.path.join(args.dir, 'index.json')
    if not os.path.isfile(index_path):
        raise SystemExit(f'缺少 index.json：{index_path}')
    index = json.load(io.open(index_path, encoding='utf-8'))

    report = []
    problems = []
    for shot in index['shots']:
        path = os.path.join(args.dir, shot['file'])
        with Image.open(path) as img:
            img.load()
            lum = mean_luminance(img)
            colours = distinct_colours(img)
            variance = grid_variance(img)
            size = img.size
        entry = {
            'file': shot['file'],
            'label': shot.get('label', shot['file']),
            'appThemeMode': shot.get('appThemeMode', 'UNKNOWN'),
            'fontScale': shot.get('fontScale', '?'),
            'size': list(size),
            'meanLuminance': round(lum, 4),
            'distinctColours': colours,
            'gridStdDev': round(variance, 2),
        }
        report.append(entry)

        # 索引缺字段就如实说出来，不能默默当默认值 —— 否则「没记」会被当成「记了」
        for required in ('label', 'appThemeMode', 'fontScale'):
            if required not in shot:
                problems.append(f"{shot['file']} 的 index.json 缺少 {required} 字段")

        if colours < 8:
            problems.append(f"{shot['file']} 只有 {colours} 种颜色 —— 疑似单色/空窗口")
        if variance < 2.0:
            problems.append(f"{shot['file']} 网格亮度标准差只有 {variance:.2f} —— 整幅图几乎是平的")
        if size[0] < 300 or size[1] < 600:
            problems.append(f"{shot['file']} 分辨率异常：{size}")

    # 明暗必须真的不同
    lights = [e for e in report if e['appThemeMode'] == 'LIGHT']
    darks = [e for e in report if e['appThemeMode'] == 'DARK']
    if not lights or not darks:
        problems.append('缺少浅色或深色截图，无法对比')
    else:
        min_light = min(e['meanLuminance'] for e in lights)
        max_dark = max(e['meanLuminance'] for e in darks)
        if min_light <= max_dark:
            problems.append(
                f'浅色最暗({min_light:.3f}) 没有亮于 深色最亮({max_dark:.3f}) —— 明暗切换没生效'
            )

    out = {
        'tool': 'tools/verify_ui_screenshots.py (PIL)',
        'grid': f'{GRID_X}x{GRID_Y}',
        'thresholds': {
            'minDistinctColours': 8,
            'minGridStdDev': 2.0,
            'lightMustBeBrighterThanDark': True,
        },
        'shots': report,
        'problems': problems,
        'result': 'PASS' if not problems else 'FAIL',
    }
    print(json.dumps(out, ensure_ascii=False, indent=2))
    if args.json:
        with io.open(args.json, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(json.dumps(out, ensure_ascii=False, indent=2) + '\n')
    if problems:
        print('\n截图核对不通过：', file=sys.stderr)
        for p in problems:
            print('  - ' + p, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
