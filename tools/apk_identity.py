# -*- coding: utf-8 -*-
"""APK 的**内容身份**：与构建实例无关的那一部分。

## 为什么需要它

实测（见 `docs/refactor/evidence/T60.md`）：同一个 commit、连续两次 `clean` + 重建，
产出的 APK **文件 SHA-256 不同**，但 163 个 ZIP 条目的名字与**字节全部一致**。
差异全部落在 APK Signing Block（最后一条 ZIP 条目与中央目录之间那一段）。

所以：

  · **APK 文件哈希** —— 构建实例相关，不能用来判断「两次构建是否一致」；
  · **内容哈希**（本模块）—— 只覆盖 ZIP 条目的名字与解压后的字节，
    与压缩方式、时间戳、签名块无关，**可复现**，才是「这次交付的是什么代码」的身份。

两者都要记：文件哈希用于校验「用户拿到的那份包没被改过」，
内容哈希用于校验「这次重建出来的还是不是同一份内容」。

## 排序

按条目名排序后再算，避免 ZIP 写入顺序影响结果。条目名与内容之间插一个 `\\0`
作分隔，避免 `("ab","c")` 与 `("a","bc")` 撞哈希。
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import sys
import zipfile


def content_sha256(apk_path: str) -> str:
    """ZIP 条目（按名字排序）的名字 + 解压字节的 SHA-256。"""
    h = hashlib.sha256()
    with zipfile.ZipFile(apk_path) as z:
        for info in sorted(z.infolist(), key=lambda i: i.filename):
            h.update(info.filename.encode('utf-8'))
            h.update(b'\0')
            h.update(z.read(info.filename))
    return h.hexdigest()


def describe(apk_path: str) -> dict:
    with zipfile.ZipFile(apk_path) as z:
        names = [i.filename for i in z.infolist()]
    return {
        'path': apk_path.replace(os.sep, '/'),
        'bytes': os.path.getsize(apk_path),
        'fileSha256': _file_sha256(apk_path),
        'contentSha256': content_sha256(apk_path),
        'entryCount': len(names),
    }


def _file_sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, 'rb') as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def main(argv=None) -> int:
    import argparse
    ap = argparse.ArgumentParser(description='算 APK 的内容身份（与构建实例无关）')
    ap.add_argument('apk', nargs='+')
    ap.add_argument('--json', default=None)
    args = ap.parse_args(argv)
    out = [describe(p) for p in args.apk]
    text = json.dumps(out if len(out) > 1 else out[0], ensure_ascii=False, indent=2)
    sys.stdout.write(text + '\n')
    if args.json:
        with io.open(args.json, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(text + '\n')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
