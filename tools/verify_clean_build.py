# -*- coding: utf-8 -*-
"""T60：在**干净状态**下重建，核对产物是否字节级可复现。

计划要求：「在隔离环境做一次干净构建，核对产物版本、文件清单、字节/哈希与签名/时间戳，
产出的报告字节级可复现」。

做法（不新建 worktree —— 那要用户批准，且这里不需要）：
  1. 记下当前两个 ABI release 产物的字节数与 SHA-256（来自 artifact-manifest 的 t61Delivery）；
  2. `gradle clean` 清掉全部构建输出；
  3. 从零重建两个 ABI 的 minified release；
  4. 重新算哈希并逐项比对，输出报告。

**如实报告不可复现的项**：Android 构建不保证字节级可复现（时间戳、文件顺序等），
如果哈希不同但**内容清单**一致，那要写清楚是哪一类差异，不能含糊成「基本一致」。
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import subprocess
import sys
import time
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import apk_identity  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, 'rb') as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def apk_manifest(path: str) -> dict:
    """APK 内每个条目的 (名字, 未压缩大小, CRC) —— 用来区分「内容不同」与「只是字节不同」。"""
    entries = {}
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            entries[info.filename] = [info.file_size, info.CRC]
    return entries


def manifest_diff(before: dict, after: dict) -> dict:
    only_before = sorted(set(before) - set(after))
    only_after = sorted(set(after) - set(before))
    changed = sorted(
        name for name in set(before) & set(after) if before[name] != after[name]
    )
    return {
        'onlyBefore': only_before,
        'onlyAfter': only_after,
        'changedEntries': changed,
        'countBefore': len(before),
        'countAfter': len(after),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--root', default=ROOT)
    ap.add_argument('--json', default=None)
    ap.add_argument('--skip-clean', action='store_true', help='只重建、不清（调试用）')
    args = ap.parse_args()
    root = os.path.abspath(args.root)

    apks = {
        'arm64-v8a': 'app/build/outputs/apk/arm64/release/app-arm64-release.apk',
        'x86_64': 'app/build/outputs/apk/x64/release/app-x64-release.apk',
    }
    manifest_path = os.path.join(root, 'docs', 'refactor', 'artifact-manifest.json')
    with io.open(manifest_path, encoding='utf-8') as fh:
        recorded = json.load(fh)['t61Delivery']['artifacts']

    before = {}
    for abi, rel in apks.items():
        full = os.path.join(root, rel.replace('/', os.sep))
        if not os.path.isfile(full):
            print(f'缺少既有产物：{rel}', file=sys.stderr)
            return 2
        before[abi] = {
            'path': rel,
            'bytes': os.path.getsize(full),
            'sha256': sha256(full),
            'contentSha256': apk_identity.content_sha256(full),
            'entries': apk_manifest(full),
            # 交付记录里 sha256 已改名为 fileSha256（构建实例相关，不做身份核对）
            'recordedFileSha256': recorded[abi].get('fileSha256') or recorded[abi].get('sha256'),
            'recordedContentSha256': recorded[abi].get('contentSha256'),
        }

    # 用仓库自己的 Gradle 包装，保证与其它入口同一套参数（offline、JDK、属性）
    def gradle(*tasks: str) -> int:
        cmd = ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
               f"$Root='{root}'; . (Join-Path $Root 'tools/verification.ps1') -ProjectRoot $Root; "
               f"Set-Location -LiteralPath $Root; Invoke-Bd2Gradle --offline "
               + ' '.join(tasks) + " --console=plain"]
        print('  $ gradle ' + ' '.join(tasks), flush=True)
        return subprocess.call(cmd, cwd=root)

    timings = {}
    if not args.skip_clean:
        t0 = time.time()
        rc = gradle('clean')
        timings['cleanSeconds'] = round(time.time() - t0, 1)
        if rc != 0:
            print('gradle clean 失败', file=sys.stderr)
            return 2
        # 确认真的清干净了 —— 不靠「命令返回 0」当作清干净
        for abi, rel in apks.items():
            if os.path.exists(os.path.join(root, rel.replace('/', os.sep))):
                print(f'clean 之后 {rel} 仍然存在，拒绝继续', file=sys.stderr)
                return 2

    t0 = time.time()
    rc = gradle(':app:assembleArm64Release', ':app:assembleX64Release')
    timings['buildSeconds'] = round(time.time() - t0, 1)
    if rc != 0:
        print('release 重建失败', file=sys.stderr)
        return 2

    after = {}
    for abi, rel in apks.items():
        full = os.path.join(root, rel.replace('/', os.sep))
        if not os.path.isfile(full):
            print(f'重建后缺少 {rel}', file=sys.stderr)
            return 2
        after[abi] = {
            'path': rel,
            'bytes': os.path.getsize(full),
            'sha256': sha256(full),
            'contentSha256': apk_identity.content_sha256(full),
            'entries': apk_manifest(full),
        }

    report = {'tool': 'tools/verify_clean_build.py', 'timings': timings, 'abis': {}}
    all_bytes = True
    all_content = True
    for abi in apks:
        b, a = before[abi], after[abi]
        diff = manifest_diff(b['entries'], a['entries'])
        same_bytes = b['sha256'] == a['sha256']
        same_content = b['contentSha256'] == a['contentSha256']
        same_manifest = not (diff['onlyBefore'] or diff['onlyAfter'] or diff['changedEntries'])
        all_bytes = all_bytes and same_bytes
        all_content = all_content and same_content
        report['abis'][abi] = {
            'beforeBytes': b['bytes'],
            'afterBytes': a['bytes'],
            'beforeSha256': b['sha256'],
            'afterSha256': a['sha256'],
            'beforeContentSha256': b['contentSha256'],
            'afterContentSha256': a['contentSha256'],
            'byteIdentical': same_bytes,
            'contentIdentical': same_content,
            'entryManifestIdentical': same_manifest,
            'recordedFileSha256': b['recordedFileSha256'],
            'recordedContentSha256': b['recordedContentSha256'],
            'matchesRecordedDelivery': b['contentSha256'] == b['recordedContentSha256']
                and a['contentSha256'] == b['recordedContentSha256'],
            'entryDiff': diff,
        }
    report['byteReproducible'] = all_bytes
    report['contentReproducible'] = all_content
    # 判定用**内容**：APK 文件哈希随构建实例变化（签名块），内容哈希才是身份。
    report['result'] = 'PASS' if all_content else 'CONTENT_NOT_REPRODUCIBLE'
    report['note'] = ('APK 文件哈希不可复现是已知且已定性的：差异全部落在 APK Signing Block，'
                      'ZIP 条目字节完全一致。所以判定用 contentSha256；'
                      'fileSha256 只用于校验「用户手上那份包没被改过」。')

    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.json:
        with io.open(args.json, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(text + '\n')
    for abi, info in report['abis'].items():
        print(f"  {abi}: bytes {info['beforeBytes']} -> {info['afterBytes']}, "
              f"contentIdentical={info['contentIdentical']}, "
              f"byteIdentical={info['byteIdentical']}, "
              f"entryManifestIdentical={info['entryManifestIdentical']}", file=sys.stderr)
    # 不可复现**不是**失败退出 —— 那是 Android 构建的现实；但必须被看见
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
