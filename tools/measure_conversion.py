# -*- coding: utf-8 -*-
"""T59：转换路径里**不需要原生库**的那些阶段的耗时（多采样 + 中位数）。

ASTC 编解码要 `libastcenc`（只有真机/模拟器上有），所以那一段不在本脚本里 ——
见 docs/refactor/evidence/performance.md 的「未覆盖」一节。

测的是两个真实热点：
  · `_build_file_index`：一次 os.walk 建索引（取代反复 os.walk）；
  · `json_to_skel`：Spine 的 json -> skel 二进制转换。

用法：
    python tools/measure_conversion.py [--repeat 3] [--scale 1.0] [--json out.json]
"""
from __future__ import annotations

import argparse
import io
import json
import os
import shutil
import statistics
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'app', 'src', 'main', 'python'))

from repacker import json_to_skel as j2s  # noqa: E402
from repacker import repacker  # noqa: E402


def build_tree(root: str, bundles: int, files_per_bundle: int) -> int:
    """造一棵有代表性的 bundle 树，返回总文件数。"""
    total = 0
    for b in range(bundles):
        for d in ('', 'atlas', 'spine'):
            path = os.path.join(root, f'bundle{b:03d}', d)
            os.makedirs(path, exist_ok=True)
            for f in range(files_per_bundle):
                with open(os.path.join(path, f'asset_{f:03d}.dat'), 'wb') as fh:
                    fh.write(b'\x00' * 64)
                total += 1
        # 每个 bundle 一份 skel/json 对，模拟真实结构
        for ext in ('skel', 'json'):
            with open(os.path.join(root, f'bundle{b:03d}', f'spine_{b}.{ext}'), 'wb') as fh:
                fh.write(b'{}')
            total += 1
        # 干扰项：备份目录与标记文件必须被跳过
        os.makedirs(os.path.join(root, f'bundle{b:03d}', '.old'), exist_ok=True)
        with open(os.path.join(root, f'bundle{b:03d}', '.old', 'stale.dat'), 'wb') as fh:
            fh.write(b'\x00' * 64)
        with open(os.path.join(root, f'bundle{b:03d}', '.nomedia'), 'wb') as fh:
            fh.write(b'')
    return total


def make_skel_json(path: str, bones: int, slots: int, animations: int) -> None:
    """造一份规模可调的 Spine 3.8 json。"""
    doc = {
        'skeleton': {'spine': '4.1.00', 'hash': 'perf', 'width': 0, 'height': 0},
        'bones': [
            {'name': f'b{i}', 'parent': (f'b{i - 1}' if i else None),
             'length': 10.0, 'x': 1.0, 'y': 2.0, 'rotation': 0.5}
            for i in range(bones)
        ],
        'slots': [{'name': f's{i}', 'bone': f'b{i % bones}', 'attachment': 'a'} for i in range(slots)],
        'skins': [{'name': 'default', 'attachments': {
            f's{i}': {'a': {'x': 0, 'y': 0, 'width': 8, 'height': 8}} for i in range(slots)
        }}],
        'animations': {
            f'anim{i}': {
                'bones': {f'b{j}': {'rotate': [
                    {'time': t * 0.1, 'angle': float(t)} for t in range(10)
                ]} for j in range(bones)},
                'slots': {f's{j}': {'attachment': [
                    {'time': t * 0.2, 'name': 'a'} for t in range(5)
                ]} for j in range(slots)},
            } for i in range(animations)
        },
    }
    with io.open(path, 'w', encoding='utf-8') as fh:
        json.dump(doc, fh)


def timeit(fn, repeat: int):
    samples = []
    for _ in range(repeat):
        t0 = time.perf_counter()
        fn()
        samples.append((time.perf_counter() - t0) * 1000.0)
    return samples


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--repeat', type=int, default=3)
    ap.add_argument('--bundles', type=int, default=120)
    ap.add_argument('--files-per-bundle', type=int, default=25)
    ap.add_argument('--bones', type=int, default=60)
    ap.add_argument('--slots', type=int, default=80)
    ap.add_argument('--animations', type=int, default=6)
    ap.add_argument('--json', default=None, help='把原始结果写成 JSON')
    args = ap.parse_args()

    work = tempfile.mkdtemp(prefix='t59-conv-')
    # skel 源放在**树外**：放进树里会多出一个 skel/json 词干，
    # 把「索引到的词干数 == bundle 数」这条断言弄成假的失败（实测踩过）。
    staging = tempfile.mkdtemp(prefix='t59-skel-')
    try:
        total_files = build_tree(work, args.bundles, args.files_per_bundle)
        skel_src = os.path.join(staging, 'big.json')
        make_skel_json(skel_src, args.bones, args.slots, args.animations)

        index_samples = timeit(lambda: repacker._build_file_index(work), args.repeat)
        # 结果正确性顺带断言：干扰项必须被跳过，skel/json 对必须都在
        index = repacker._build_file_index(work)
        assert len(index['skel_json']) == args.bundles, len(index['skel_json'])
        assert not any('.old' in p for p in index['all_files']), '备份目录没被跳过'
        assert not any(p.endswith('.nomedia') for p in index['all_files']), '标记文件没被跳过'

        skel_samples = timeit(
            lambda: j2s.json_to_skel(skel_src, os.path.join(staging, 'out.skel')), args.repeat
        )
        out_skel = os.path.join(staging, 'out.skel')
        assert os.path.getsize(out_skel) > 0, '转换产物为空'

        report = {
            'input': {
                'bundles': args.bundles,
                'filesPerBundle': args.files_per_bundle,
                'totalFiles': total_files,
                'skelJson': {'bones': args.bones, 'slots': args.slots, 'animations': args.animations},
            },
            'repeat': args.repeat,
            'buildFileIndexMs': index_samples,
            'buildFileIndexMedianMs': round(statistics.median(index_samples), 2),
            'jsonToSkelMs': skel_samples,
            'jsonToSkelMedianMs': round(statistics.median(skel_samples), 2),
            'outputSkelBytes': os.path.getsize(out_skel),
        }
        print(json.dumps(report, ensure_ascii=False, indent=2))
        if args.json:
            with io.open(args.json, 'w', encoding='utf-8', newline='\n') as fh:
                fh.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)
        shutil.rmtree(staging, ignore_errors=True)


if __name__ == '__main__':
    raise SystemExit(main())
