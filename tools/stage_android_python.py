#!/usr/bin/env python3
"""T50 — 把 Android 侧 Python 源集暂存到构建目录，并按清单剔除已确认无关的路径。

为什么需要这一步：`app/src/main/python` 里有 vendored 的 UnityPy，它随包带着
**桌面平台**的 FMOD 原生库（macOS dylib / Windows dll / 桌面 Linux glibc so），
共 18 个文件、约 10.5 MB 未压缩。这些在 Android/Bionic 上既不会被加载、
也没有调用路径（`pyfmodex` 根本不在 pip 清单里）。直接删掉 vendored 原文件会
破坏「第三方来源原样保留」的约束，所以在**构建暂存**阶段剔除：

    app/src/main/python  --(本脚本)-->  app/build/staged-python/main  --(Chaquopy)--> app.imy

性质：
  · **只读源**：源树一个字节都不改；
  · **确定性**：目标目录先清空；拷贝后把所有文件的 mtime 归一（避免时间戳进入
    归档造成不可复现差异）；输出 JSON 里不出现绝对路径；
  · **清单防腐烂**：清单里每条路径必须在源树里真实命中，否则直接失败退出
    （避免「排除条目悄悄失效、东西又打回包里」）；
  · **不做按扩展名/整目录的粗暴删除**：只删清单点名的路径。

用法：
  python tools/stage_android_python.py --source app/src/main/python \\
      --dest app/build/staged-python/main --config config/android-python-excludes.json \\
      [--manifest app/build/staged-python/manifest.json] [--quiet]

退出码：0 = 成功；1 = 清单条目未命中 / 目标在源树内等逻辑错误；2 = 用法 / IO 错误。
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import shutil
import sys
import time

# 固定 mtime：1980-01-01 00:00:00 UTC（ZIP 纪元下限附近，避免时区/夏令时边界问题）。
FIXED_MTIME = 315532800

DEFAULT_SOURCE = os.path.join("app", "src", "main", "python")
DEFAULT_DEST = os.path.join("app", "build", "staged-python", "main")
DEFAULT_CONFIG = os.path.join("config", "android-python-excludes.json")


class StageError(Exception):
    """用法 / IO 层错误（退出码 2）。"""


class StageLogicError(Exception):
    """清单未命中之类的逻辑错误（退出码 1）。"""


def load_config(path):
    if not os.path.isfile(path):
        raise StageError("exclude config not found: %s" % path)
    with io.open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    entries = data.get("excludes")
    if not isinstance(entries, list):
        raise StageError("exclude config has no 'excludes' list: %s" % path)
    paths = []
    for entry in entries:
        rel = (entry or {}).get("path")
        if not rel or not isinstance(rel, str):
            raise StageError("exclude entry without a 'path': %r" % (entry,))
        rel = rel.replace("\\", "/").strip("/")
        if not rel or rel.startswith(".."):
            raise StageError("unsafe exclude path: %r" % (rel,))
        paths.append(rel)
    if len(paths) != len(set(paths)):
        raise StageError("duplicate exclude paths in %s" % path)
    return sorted(set(paths))


def _normalise(rel):
    return rel.replace("\\", "/").strip("/")


def _is_excluded(rel, excludes):
    for excluded in excludes:
        if rel == excluded or rel.startswith(excluded + "/"):
            return excluded
    return None


def plan(source_root, excludes):
    """返回 (included, excluded_hits)。excluded_hits: 清单条目 -> 命中的文件数。"""
    if not os.path.isdir(source_root):
        raise StageError("source dir not found: %s" % source_root)
    included = []
    hits = {entry: 0 for entry in excludes}
    for dirpath, dirnames, filenames in os.walk(source_root):
        dirnames.sort()
        rel_dir = _normalise(os.path.relpath(dirpath, source_root))
        if rel_dir == ".":
            rel_dir = ""
        for name in sorted(filenames):
            rel = _normalise(os.path.join(rel_dir, name))
            matched = _is_excluded(rel, excludes)
            if matched is not None:
                hits[matched] += 1
            else:
                included.append(rel)
    return included, hits


def stage(source_root, dest_root, excludes):
    if os.path.abspath(dest_root) == os.path.abspath(source_root):
        raise StageLogicError("dest must not be the source dir")
    if os.path.abspath(source_root).startswith(os.path.abspath(dest_root) + os.sep):
        raise StageLogicError("source must not live inside dest")
    if os.path.abspath(dest_root).startswith(os.path.abspath(source_root) + os.sep):
        raise StageLogicError("dest must not live inside source")

    included, hits = plan(source_root, excludes)
    missing = sorted(entry for entry, count in hits.items() if count == 0)
    if missing:
        raise StageLogicError(
            "exclude entries matched nothing in the source tree: %s" % ", ".join(missing)
        )

    if os.path.isdir(dest_root):
        shutil.rmtree(dest_root)
    os.makedirs(dest_root)

    records = []
    for rel in sorted(included):
        src = os.path.join(source_root, rel.replace("/", os.sep))
        dst = os.path.join(dest_root, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(src, dst)
        # 归一 mtime：归档里不能带构建机时间戳
        os.utime(dst, (FIXED_MTIME, FIXED_MTIME))
        with open(dst, "rb") as fh:
            digest = hashlib.sha256(fh.read()).hexdigest()
        records.append({"path": rel, "bytes": os.path.getsize(dst), "sha256": digest})

    return {
        "schemaVersion": 1,
        "tool": "tools/stage_android_python.py",
        "sourceDir": os.path.basename(source_root.rstrip(os.sep)) or "python",
        "includedCount": len(records),
        "includedBytes": sum(r["bytes"] for r in records),
        "excludedPaths": sorted(excludes),
        "excludedFileCount": sum(hits.values()),
        "included": records,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description="按清单暂存 Android Python 源集")
    parser.add_argument("--source", default=DEFAULT_SOURCE)
    parser.add_argument("--dest", default=DEFAULT_DEST)
    parser.add_argument("--config", default=DEFAULT_CONFIG)
    parser.add_argument("--manifest", default=None)
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    try:
        excludes = load_config(args.config)
        report = stage(args.source, args.dest, excludes)
    except StageLogicError as exc:
        sys.stderr.write("ERROR: %s\n" % exc)
        return 1
    except StageError as exc:
        sys.stderr.write("ERROR: %s\n" % exc)
        return 2
    except OSError as exc:
        sys.stderr.write("ERROR: %s\n" % exc)
        return 2

    if args.manifest:
        parent = os.path.dirname(os.path.abspath(args.manifest))
        if parent:
            os.makedirs(parent, exist_ok=True)
        with io.open(args.manifest, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False) + "\n")

    if not args.quiet:
        sys.stdout.write(
            "staged %d files (%d bytes), excluded %d files under %d paths\n"
            % (report["includedCount"], report["includedBytes"],
               report["excludedFileCount"], len(report["excludedPaths"]))
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
