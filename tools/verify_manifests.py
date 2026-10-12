#!/usr/bin/env python3
"""T60 — 核对 fixture / artifact 清单里登记的哈希与磁盘上的实际文件是否一致。

只读。**不删除、不移动、不重建任何东西** —— T60 明确要求「先核对本地历史/重建/
优化前/最终产物与 fixture 清单，操作后再次核对原始基线哈希，不能删掉唯一基线或探针源码」。

清单里的条目分两类：
  · `gitTracked=true` 的（探针源码等）：路径相对仓库根，直接核；
  · 本地保全产物（`/local/` 已忽略）：路径相对仓库根，**缺失即报 UNVERIFIED**
    （不是失败 —— 换一台机器本来就该缺；但必须说出来，不能假装核过了）。

用法：
  python tools/verify_manifests.py --root <repo> [--json out.json] [--quiet]
退出码：0 = 所有**存在**的条目哈希都对上；1 = 有哈希不符；2 = 用法/IO 错误。
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import sys
from typing import Dict, List, Optional

MANIFESTS = (
    'docs/refactor/fixture-manifest.json',
    'docs/refactor/artifact-manifest.json',
)


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, 'rb') as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def iter_entries(node, trail, local_dir=None):
    """深度遍历清单，收集所有带 sha256 的条目，并带上最近的 localDir。"""
    if isinstance(node, dict):
        here = node.get('localDir') if isinstance(node.get('localDir'), str) else local_dir
        if 'sha256' in node and isinstance(node.get('sha256'), str):
            yield trail, node, here
        for key, value in node.items():
            yield from iter_entries(value, trail + [str(key)], here)
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from iter_entries(value, trail + [str(index)], local_dir)


def entry_path(entry: dict) -> Optional[str]:
    """条目里的路径字段。

    **顺序有讲究**：`t48Freeze.toolRun[]` 同时有 `apk` 与 `output`，而那里的 `sha256`
    指的是**输出报告**（`output`）的哈希，不是被审计的 APK —— 先取 `apk` 会拿 APK
    的字节去比报告哈希，报出一个假的「哈希不符」。所以 `output` 排在 `apk` 前面。
    """
    for key in ('relative', 'path', 'localPath', 'output', 'apk', 'file'):
        value = entry.get(key)
        if isinstance(value, str) and value and not value.startswith('/'):
            return value
    return None


def resolve(root: str, entry: dict, local_dir: Optional[str]) -> Optional[str]:
    candidate = entry_path(entry)
    if candidate is None:
        return None
    # 清单里的 `file` 常常只是文件名，真实位置在所属类目的 `localDir` 下。
    if '/' not in candidate and local_dir:
        candidate = local_dir.rstrip('/') + '/' + candidate
    return os.path.join(root, candidate.replace('/', os.sep))


def verify(root: str) -> Dict:
    checked: List[Dict] = []
    missing: List[Dict] = []
    mismatched: List[Dict] = []
    for manifest in MANIFESTS:
        full = os.path.join(root, manifest.replace('/', os.sep))
        if not os.path.isfile(full):
            missing.append({'manifest': manifest, 'reason': 'manifest file missing'})
            continue
        with io.open(full, encoding='utf-8') as fh:
            data = json.load(fh)
        for trail, entry, local_dir in iter_entries(data, [manifest]):
            path = resolve(root, entry, local_dir)
            if path is None:
                continue
            record = {
                'manifest': manifest,
                'trail': '/'.join(trail),
                'path': os.path.relpath(path, root).replace(os.sep, '/'),
                'expected': entry['sha256'],
                'bytes': entry.get('bytes'),
            }
            if not os.path.isfile(path):
                record['reason'] = 'not present locally'
                missing.append(record)
                continue
            actual = sha256(path)
            record['actual'] = actual
            size = os.path.getsize(path)
            if record['bytes'] is not None and int(record['bytes']) != size:
                record['reason'] = 'byte size mismatch (expected %s, actual %s)' % (
                    record['bytes'], size)
                mismatched.append(record)
                continue
            if actual != entry['sha256']:
                record['reason'] = 'sha256 mismatch'
                mismatched.append(record)
                continue
            checked.append(record)
    return {
        'schemaVersion': 1,
        'tool': 'tools/verify_manifests.py',
        'checked': checked,
        'mismatched': mismatched,
        'missing': missing,
        'summary': {
            'checked': len(checked),
            'mismatched': len(mismatched),
            'missing': len(missing),
        },
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description='核对 fixture/artifact 清单哈希（只读）')
    parser.add_argument('--root', default='.')
    parser.add_argument('--json', default=None)
    parser.add_argument('--quiet', action='store_true')
    args = parser.parse_args(argv)

    try:
        report = verify(os.path.abspath(args.root))
    except OSError as exc:
        sys.stderr.write('ERROR: %s\n' % exc)
        return 2

    text = json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False) + '\n'
    if args.json:
        with io.open(args.json, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(text)
    if not args.quiet:
        sys.stdout.write(text)
    summary = report['summary']
    sys.stderr.write(
        'manifests: checked=%d mismatched=%d missing=%d\n'
        % (summary['checked'], summary['mismatched'], summary['missing'])
    )
    for record in report['mismatched']:
        sys.stderr.write('  MISMATCH %s: %s\n' % (record['path'], record['reason']))
    return 1 if report['mismatched'] else 0


if __name__ == '__main__':
    sys.exit(main())
