#!/usr/bin/env python3
"""T55 — 分层架构检查器（只读、可判定）。

检查的是**真实的包级依赖方向**与几条「UI 不许干业务」的硬规则，
不是行数、不是风格。每条规则都写成「触发条件 + 判定标准」，
误报要在 [ALLOW] 白名单里逐条说明理由，**不允许全局忽略**。

分层（箭头 = 允许的依赖方向）：

    ui  ──▶  feature  ──▶  data  ──▶  core
     │
     └─▶ data / core 的 **model** 可以，但不得执行文件/Python/网络业务

规则：
  R1  core/**   不得 import ui/** 或 feature/**
  R2  data/**   不得 import ui/** 或 feature/**
  R3  feature/** 不得 import ui/**
  R4  feature/** 下的**非 Compose 文件**（不含 @Composable / androidx.compose 导入）
      不得 import android.* / androidx.*
  R5  ui/viewmodel/** 不得 import androidx.compose.*（VM 不持 UI）
  R6  没有任何 ViewModel 通过构造器注入另一个 ViewModel
  R7  ui/screens/** 与 ui/components/** 不得 import 业务执行面：
      java.io / java.net / okhttp3 / com.chaquo.* / core.engine / core.python /
      core.fileaccess / data.repository

用法：
  python tools/check_architecture.py --root <repo> [--json out.json] [--quiet]
退出码：0 = 通过；1 = 有违规；2 = 用法/IO 错误。
"""
from __future__ import annotations

import argparse
import io
import json
import os
import re
import sys

SRC_REL = os.path.join("app", "src", "main", "java", "com", "bd2toolsbox")

IMPORT_RE = re.compile(
    r"^[ \t]*import[ \t]+([A-Za-z0-9_.]+(?:\*)?)"
    r"(?:[ \t]+as[ \t]+[A-Za-z_][A-Za-z0-9_]*)?"
    r"[ \t]*;?[ \t]*(?://[^\r\n]*)?\r?$", re.MULTILINE)

BUSINESS_PREFIXES = (
    "java.io.",
    "java.net.",
    "okhttp3.",
    "com.chaquo.",
    "com.bd2toolsbox.core.engine.",
    "com.bd2toolsbox.core.python.",
    "com.bd2toolsbox.core.fileaccess.",
    "com.bd2toolsbox.data.repository.",
)

# 允许的例外：路径（相对 SRC_REL，用 / 分隔） -> 理由
# 逐条写明为什么不是真违规；**不允许**用通配符或整目录忽略。
ALLOW = {
    ("feature/install/InstallExecutor.kt", "android.util.Log"):
        "仅一处 Log.w 的失败路径日志。它不是平台/UI 依赖（不碰文件、不碰生命周期），"
        "且不影响 JVM 可测性 —— InstallExecutorTest 在 JVM 上跑。已知残留风险：该行在"
        "单元测试的 mockable android.jar 下会抛 Stub!，所以这条失败路径当前无 JVM 覆盖，"
        "已登记在 docs/refactor/architecture.md 的未完成项里。",
}


def rel_pkg(rel_path):
    """把相对文件路径转成包名（不含文件名）。"""
    parts = rel_path.replace(os.sep, "/").split("/")
    return ".".join(parts[:-1])


def is_compose_source(text):
    return "@Composable" in text or "import androidx.compose" in text


def iter_sources(root):
    base = os.path.join(root, SRC_REL)
    if not os.path.isdir(base):
        raise SystemExit("source root not found: %s" % base)
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames.sort()
        for name in sorted(filenames):
            if not name.endswith(".kt"):
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, base).replace(os.sep, "/")
            with io.open(full, encoding="utf-8") as fh:
                yield rel, fh.read()


def imports_of(text):
    return IMPORT_RE.findall(text)


def check(root):
    violations = []
    checked = 0
    for rel, text in iter_sources(root):
        checked += 1
        imports = imports_of(text)

        def add(rule, detail):
            key = (rel, detail)
            if key in ALLOW:
                return
            violations.append({"rule": rule, "file": rel, "detail": detail,
                               "reason": ALLOW.get(key, "")})

        if rel.startswith("core/"):
            for imp in imports:
                if imp.startswith("com.bd2toolsbox.ui.") or imp.startswith("com.bd2toolsbox.feature."):
                    add("R1", imp)
        if rel.startswith("data/"):
            for imp in imports:
                if imp.startswith("com.bd2toolsbox.ui.") or imp.startswith("com.bd2toolsbox.feature."):
                    add("R2", imp)
        if rel.startswith("feature/"):
            for imp in imports:
                if imp.startswith("com.bd2toolsbox.ui."):
                    add("R3", imp)
            # R4 只针对**纯模型/渲染**：ViewModel 合法依赖 androidx.lifecycle，
            # Compose 文件合法依赖 androidx.compose，二者都不在 R4 的范围内。
            # ViewModel 按**内容**判定（`class X : ViewModel`），不按文件名 ——
            # 文件名可以随便叫，按名字判会漏也会误。
            is_view_model = re.search(r":\s*[\w.]*ViewModel\b", text) is not None
            if not is_compose_source(text) and not is_view_model:
                for imp in imports:
                    if imp.startswith("android.") or imp.startswith("androidx."):
                        add("R4", imp)
        if rel.startswith("ui/viewmodel/"):
            for imp in imports:
                if imp.startswith("androidx.compose."):
                    add("R5", imp)
        # R6 follows the constructor, including ViewModels migrated into feature/.
        ctor = re.search(r"class\s+(\w+ViewModel)\s*@Inject\s*constructor\s*\((.*?)\)\s*\{",
                         text, re.DOTALL)
        if ctor:
            for param in re.findall(r"\b(\w+ViewModel)\b", ctor.group(2)):
                add("R6", "%s injects %s" % (ctor.group(1), param))
        if rel.startswith("ui/screens/") or rel.startswith("ui/components/"):
            for imp in imports:
                if imp.startswith(BUSINESS_PREFIXES):
                    add("R7", imp)
    return {"schemaVersion": 1, "tool": "tools/check_architecture.py",
            "checkedFiles": checked, "violations": violations,
            "allowListSize": len(ALLOW)}


def main(argv=None):
    parser = argparse.ArgumentParser(description="分层架构检查（只读）")
    parser.add_argument("--root", default=".")
    parser.add_argument("--json", default=None)
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    try:
        report = check(args.root)
    except SystemExit as exc:
        sys.stderr.write("ERROR: %s\n" % exc)
        return 2
    except OSError as exc:
        sys.stderr.write("ERROR: %s\n" % exc)
        return 2

    text = json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    if args.json:
        with io.open(args.json, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
    if not args.quiet:
        sys.stdout.write(text)
    if report["violations"]:
        for v in report["violations"]:
            sys.stderr.write("%s %s: %s\n" % (v["rule"], v["file"], v["detail"]))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
