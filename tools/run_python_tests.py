#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""结构化 unittest runner（T04）。

为什么需要它
------------
`python -m unittest` 的退出码只看 failed/errored —— **skip 完全不算失败**。
T02 实测到过这个假绿色：worktree 里的 test_resolution_catalog_fallback.py 有 6 条
真实兼容性用例因为找不到仓库外的 catalog 快照而全部 skip，进程照样 `exit 0`，
报告却是 "OK (skipped=6)"。只看退出码就会把「一条真实断言都没跑」当成通过。

所以本 runner 的判定**不看退出码**，看逐用例结果 + 必需测试集合 + 夹具哈希：

  · 零用例、发现错误、失败、错误、必需用例被 skip、必需测试 ID 未被发现、
    夹具缺失 / 哈希不符 / 尚未就绪、报告写不出来 —— 一律非零，并给出具体 reason；
  · 输出 JSON 报告，逐条记录用例 ID 与结果，另有 discovered / executed / passed /
    failed / errors / skipped / notRun 计数、git 提交、源码与夹具哈希、退出原因；
  · 日志只打印用例 ID 与计数，**绝不**打印夹具正文或账号类内容。

用法
----
    python tools/run_python_tests.py --pattern test_scan_checkpoint.py --output <report.json>

`--start-dir` 与 `--output` 都必须落在实施根内。catalog 这类受限输入**只**从已批准的
本地配置（docs/refactor/evidence/environment.local.json 的 externalFixtures）或同名
环境变量读取，不拼仓库父目录，也不搜索文件系统。
"""

import argparse
import hashlib
import inspect
import io
import json
import os
import re
import subprocess
import sys
import time
import traceback
import unittest
import uuid
from fnmatch import fnmatch

REPORT_SCHEMA_VERSION = 1
TOOL_ID = "tools/run_python_tests.py"
DEFAULT_PATTERN = "test_*.py"

# 判定为「本项所必需」的夹具等级。optional 只登记不判定，不抵扣必需项。
GATING_REQUIREDNESS = ("g0-required", "phase-required")

# 视为「已就绪」的夹具状态。planned / missing / known-gap 都不是。
READY_STATUSES = ("available",)


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------

def sha256_file(path):
    h = hashlib.sha256()
    with io.open(path, "rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def _norm(path):
    return os.path.normcase(os.path.abspath(path))


def _is_within(path, root):
    p = _norm(path)
    r = _norm(root)
    return p == r or p.startswith(r + os.sep)


def _rel(root, path):
    try:
        return os.path.relpath(path, root).replace(os.sep, "/")
    except ValueError:
        return path


def _run_git(root, args):
    try:
        out = subprocess.check_output(
            ["git", "-c", "safe.directory=" + root, "-C", root] + args,
            stderr=subprocess.STDOUT)
        return out.decode("utf-8", "replace").strip()
    except Exception:
        return None


# ---------------------------------------------------------------------------
# 结果收集
# ---------------------------------------------------------------------------

class RecordingResult(unittest.TestResult):
    """记录逐用例结果。只归类不判定 —— 判定规则集中在 main() 里一条可审阅的清单。"""

    def __init__(self):
        unittest.TestResult.__init__(self)
        self.records = {}

    def _record(self, test, outcome, message=""):
        test_id = test.id()
        rec = self.records.get(test_id)
        if rec is None:
            rec = self.records[test_id] = {"id": test_id, "outcome": outcome, "messages": []}
        else:
            rec["outcome"] = outcome
        if message:
            rec["messages"].append(message)

    @staticmethod
    def _last_line(err):
        if not err:
            return ""
        try:
            for line in reversed(traceback.format_exception(*err)):
                line = line.strip()
                if line:
                    return line
        except Exception:
            pass
        return repr(err[1])

    def startTest(self, test):
        unittest.TestResult.startTest(self, test)
        self._record(test, "executed")

    def addSuccess(self, test):
        unittest.TestResult.addSuccess(self, test)
        self._record(test, "passed")

    def addFailure(self, test, err):
        unittest.TestResult.addFailure(self, test, err)
        self._record(test, "failed", self._last_line(err))

    def addError(self, test, err):
        unittest.TestResult.addError(self, test, err)
        self._record(test, "error", self._last_line(err))

    def addSkip(self, test, reason):
        unittest.TestResult.addSkip(self, test, reason)
        self._record(test, "skipped", reason)

    def addExpectedFailure(self, test, err):
        unittest.TestResult.addExpectedFailure(self, test, err)
        self._record(test, "expected-failure", self._last_line(err))

    def addUnexpectedSuccess(self, test):
        unittest.TestResult.addUnexpectedSuccess(self, test)
        self._record(test, "unexpected-success")

    def addSubTest(self, test, subtest, err):
        unittest.TestResult.addSubTest(self, test, subtest, err)
        if err is not None:
            outcome = "failed" if issubclass(err[0], AssertionError) else "error"
            self._record(test, outcome, self._last_line(err))


def flatten(suite, out):
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            flatten(item, out)
        else:
            out.append(item)
    return out


# ---------------------------------------------------------------------------
# 夹具选择与校验
# ---------------------------------------------------------------------------

def _used_by_python_files(used_by):
    files = []
    for entry in used_by or []:
        for token in re.findall(r"[A-Za-z0-9_./\\-]+\.py", entry):
            files.append(os.path.basename(token.replace("\\", "/")))
    return files


def select_fixtures(manifest, patterns):
    """挑出「被本次选择范围需要」的夹具。

    只按 manifest 的 usedBy 里出现的 .py 文件名与 pattern 匹配 —— 没被选中的范围
    不会因为无关输入缺失而失败（§6.3 明确要求）。
    """
    selected = []
    seen = set()
    candidates = list(manifest.get("fixtures") or []) + list(manifest.get("derivedTargets") or [])
    for fixture in candidates:
        fid = fixture.get("id")
        if not fid or fid in seen:
            continue
        for pyfile in _used_by_python_files(fixture.get("usedBy")):
            if any(fnmatch(pyfile, pat) for pat in patterns):
                selected.append((fid, fixture))
                seen.add(fid)
                break
    return selected


def resolve_fixture(fixture, root, local_env):
    """返回 (kind, path, expected_sha256)。kind ∈ {repo, external-config}。"""
    config_key = fixture.get("configurationKey")
    if config_key:
        parts = config_key.split(".")
        key = parts[1] if len(parts) > 1 else config_key
        node = ((local_env or {}).get("externalFixtures") or {}).get(key)
        if not node:
            return ("external-config", None, None)
        return ("external-config", node.get("path"), node.get("sha256"))

    rel = fixture.get("relativePath") or fixture.get("targetPath")
    if not rel:
        return ("repo", None, None)
    return ("repo", os.path.join(root, rel.replace("/", os.sep)), fixture.get("sha256"))


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def parse_args(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    parser = argparse.ArgumentParser(
        description="结构化 unittest runner：skip / 零用例 / 坏哈希 / 缺报告一律非零")
    parser.add_argument("--start-dir", default=os.path.join(root, "tests"),
                        help="unittest 发现起点，必须位于实施根内")
    parser.add_argument("--pattern", action="append", default=None,
                        help="测试文件名模式（可重复；默认 test_*.py）")
    parser.add_argument("--fixture-manifest",
                        default=os.path.join(root, "docs", "refactor", "fixture-manifest.json"))
    parser.add_argument("--environment",
                        default=os.path.join(root, "docs", "refactor", "evidence",
                                             "environment.local.json"))
    parser.add_argument("--output", required=True, help="结果 JSON 路径，必须位于实施根内")
    parser.add_argument("--required", action="append", default=None,
                        help="必需测试 ID（module.Class.test）。给出时未发现的 ID 即非零；"
                             "缺省时本次发现到的全部用例都算必需")
    parser.add_argument("--allow-skip", action="append", default=None,
                        help="允许 skip 的测试 ID（必须显式登记；缺省任何 skip 都算失败）")
    parser.add_argument("--root", default=root, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    args.root = os.path.abspath(args.root)
    if not args.pattern:
        args.pattern = [DEFAULT_PATTERN]
    return args


def main(argv):
    try:
        # 报告是 UTF-8；控制台也按 UTF-8 输出，免得中文 reason 在日志里变成乱码
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    args = parse_args(argv)
    root = args.root
    run_id = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8]

    reasons = []
    fixtures_report = []
    counts = dict(discovered=0, executed=0, passed=0, failed=0,
                  errors=0, skipped=0, notRun=0)
    tests_report = []
    source_hashes = {}

    start_dir = os.path.abspath(args.start_dir)
    output = os.path.abspath(args.output)

    if not _is_within(start_dir, root):
        reasons.append({"code": "start-dir-outside-root",
                        "detail": "start-dir 必须位于实施根内: %s" % start_dir})
    elif not os.path.isdir(start_dir):
        reasons.append({"code": "start-dir-missing", "detail": start_dir})

    if not _is_within(output, root):
        reasons.append({"code": "output-outside-root",
                        "detail": "output 必须位于实施根内: %s" % output})

    manifest = {}
    if not os.path.isfile(args.fixture_manifest):
        reasons.append({"code": "fixture-manifest-missing", "detail": args.fixture_manifest})
    else:
        try:
            with io.open(args.fixture_manifest, "r", encoding="utf-8") as f:
                manifest = json.load(f)
        except Exception as exc:
            reasons.append({"code": "fixture-manifest-invalid",
                            "detail": "%s: %s" % (args.fixture_manifest, exc)})

    local_env = {}
    if os.path.isfile(args.environment):
        try:
            with io.open(args.environment, "r", encoding="utf-8") as f:
                local_env = json.load(f)
        except Exception as exc:
            reasons.append({"code": "environment-invalid",
                            "detail": "%s: %s" % (args.environment, exc)})

    # ---- 夹具校验（先校验，免得把「输入不对」跑成「测试失败」） --------------
    required_fixtures = select_fixtures(manifest, args.pattern) if manifest else []
    for fid, fixture in required_fixtures:
        kind, path, expected_sha = resolve_fixture(fixture, root, local_env)
        entry = {"id": fid, "kind": kind, "path": path,
                 "expectedSha256": expected_sha, "actualSha256": None, "ok": False}
        requiredness = fixture.get("requiredness")
        status = fixture.get("status")
        if path is None:
            entry["reason"] = "not-configured"
            reasons.append({"code": "fixture-not-configured",
                            "detail": "%s: 本地配置缺少 %s" % (fid, fixture.get("configurationKey"))})
        elif not os.path.isfile(path):
            entry["reason"] = "missing"
            reasons.append({"code": "fixture-missing", "detail": "%s: %s" % (fid, path)})
        else:
            actual = sha256_file(path)
            entry["actualSha256"] = actual
            entry["bytes"] = os.path.getsize(path)
            manifest_sha = fixture.get("sha256")
            if expected_sha and actual != expected_sha:
                entry["reason"] = "hash-mismatch"
                reasons.append({"code": "fixture-hash-mismatch",
                                "detail": "%s: 本地配置期望 %s，实际 %s" % (fid, expected_sha, actual)})
            elif manifest_sha and actual != manifest_sha:
                entry["reason"] = "hash-mismatch"
                reasons.append({"code": "fixture-hash-mismatch",
                                "detail": "%s: manifest 期望 %s，实际 %s"
                                          % (fid, manifest_sha, actual)})
            elif requiredness in GATING_REQUIREDNESS and status not in READY_STATUSES:
                entry["reason"] = "not-ready"
                reasons.append({"code": "fixture-not-ready",
                                "detail": "%s: status=%s（必需夹具尚未就绪）" % (fid, status)})
            else:
                entry["ok"] = True
                entry["reason"] = "ok"
        # catalog 环境变量只从已批准配置注入；不覆盖调用方已显式给出的值
        if kind == "external-config" and path:
            os.environ.setdefault("BD2_TEST_CATALOG", path)
            if expected_sha:
                os.environ.setdefault("BD2_TEST_CATALOG_SHA256", expected_sha)
        fixtures_report.append(entry)

    # ---- 发现 ---------------------------------------------------------------
    # 夹具判定失败时**仍然继续发现并运行**：报告里要能看到真实发现到了多少用例，
    # 而不是被一个输入错误短路成「0 个用例」。两者都是失败原因，但性质不同。
    discovered_list = []
    if not any(r["code"].startswith(("start-dir", "output-outside")) for r in reasons):
        loader = unittest.TestLoader()
        try:
            for pattern in args.pattern:
                flatten(loader.discover(start_dir, pattern=pattern), discovered_list)
        except Exception as exc:
            reasons.append({"code": "discovery-error",
                            "detail": "%s: %s" % (type(exc).__name__, exc)})

    seen = set()
    unique = []
    for t in discovered_list:
        tid = t.id()
        if tid not in seen:
            seen.add(tid)
            unique.append(t)
    discovered_list = unique
    discovered_ids = [t.id() for t in discovered_list]
    counts["discovered"] = len(discovered_list)

    if not discovered_list:
        reasons.append({"code": "zero-tests",
                        "detail": "发现到 0 个用例（patterns=%s, start-dir=%s）"
                                  % (",".join(args.pattern), start_dir)})

    for test in discovered_list:
        try:
            src = inspect.getfile(test.__class__)
        except Exception:
            continue
        if src and os.path.isfile(src) and src not in source_hashes:
            source_hashes[_rel(root, src)] = sha256_file(src)

    required = list(args.required) if args.required else list(discovered_ids)
    allow_skip = set(args.allow_skip or [])
    for tid in required:
        if tid not in discovered_ids:
            reasons.append({"code": "unknown-required-test",
                            "detail": "必需测试未被发现: %s" % tid})

    # ---- 运行 ---------------------------------------------------------------
    result = RecordingResult()
    if discovered_list and not any(r["code"].startswith("output-outside") for r in reasons):
        try:
            unittest.TestSuite(discovered_list).run(result)
        except Exception as exc:
            reasons.append({"code": "run-error",
                            "detail": "%s: %s" % (type(exc).__name__, exc)})

    required_set = set(required)
    for test in discovered_list:
        tid = test.id()
        rec = result.records.get(tid)
        outcome = rec["outcome"] if rec else "notRun"
        messages = rec["messages"] if rec else []
        if outcome == "executed":
            outcome = "notRun"
        tests_report.append({"id": tid, "outcome": outcome, "source": "testcase",
                             "required": tid in required_set, "messages": messages})

    # setUpClass / setUpModule 失败时 unittest 报的是 _ErrorHolder，不在发现列表里。
    # 只统计 tests_report 会把这些**真实错误**漏掉，所以显式补进来。
    for holder, err_text in list(result.errors) + list(result.failures):
        hid = holder.id()
        if hid in discovered_ids:
            continue
        outcome = "error" if any(h is holder for h, _ in result.errors) else "failed"
        last = err_text.strip().splitlines()[-1] if err_text.strip() else ""
        tests_report.append({"id": hid, "outcome": outcome, "source": "fixture-setup",
                             "required": True, "messages": [last]})

    counts["executed"] = sum(1 for e in tests_report if e["outcome"] != "notRun")
    counts["notRun"] = counts["discovered"] - sum(
        1 for e in tests_report if e["source"] == "testcase" and e["outcome"] != "notRun")
    for entry in tests_report:
        outcome = entry["outcome"]
        if outcome == "passed":
            counts["passed"] += 1
        elif outcome == "failed":
            counts["failed"] += 1
        elif outcome == "error":
            counts["errors"] += 1
        elif outcome == "skipped":
            counts["skipped"] += 1

    # ---- 判定 ---------------------------------------------------------------
    if counts["failed"]:
        reasons.append({"code": "test-failed", "detail": "%d 个用例失败" % counts["failed"]})
    if counts["errors"]:
        reasons.append({"code": "test-error", "detail": "%d 个用例出错" % counts["errors"]})
    for entry in tests_report:
        if entry["outcome"] == "skipped" and entry["required"] and entry["id"] not in allow_skip:
            reasons.append({"code": "required-test-skipped",
                            "detail": "%s: %s" % (entry["id"], "; ".join(entry["messages"]))})
        elif entry["outcome"] == "unexpected-success":
            reasons.append({"code": "unexpected-success", "detail": entry["id"]})
        elif entry["outcome"] == "notRun" and entry["required"]:
            reasons.append({"code": "required-test-not-run", "detail": entry["id"]})

    exit_code = 0 if not reasons else 1

    # ---- 报告 ---------------------------------------------------------------
    report = {
        "schemaVersion": REPORT_SCHEMA_VERSION,
        "tool": TOOL_ID,
        "runId": run_id,
        "finishedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "python": {"executable": sys.executable,
                   "version": "%d.%d.%d" % sys.version_info[:3]},
        "git": {"head": _run_git(root, ["rev-parse", "HEAD"]),
                "branch": _run_git(root, ["branch", "--show-current"]),
                "dirty": bool(_run_git(root, ["status", "--porcelain"]))},
        "scope": {"root": root, "startDir": start_dir, "patterns": list(args.pattern)},
        "sourceHashes": source_hashes,
        "required": required,
        "allowSkip": sorted(allow_skip),
        "counts": counts,
        "tests": tests_report,
        "fixtures": fixtures_report,
        "reasons": reasons,
        "result": "PASS" if exit_code == 0 else "FAIL",
        "exitCode": exit_code,
    }

    try:
        parent = os.path.dirname(output)
        if parent and not os.path.isdir(parent):
            os.makedirs(parent)
        with io.open(output, "w", encoding="utf-8", newline="\n") as f:
            f.write(json.dumps(report, ensure_ascii=False, indent=2))
            f.write("\n")
    except Exception as exc:
        print("FAIL report-write-failed %s: %s" % (output, exc))
        return 1

    # 读回来核对：报告存在、可解析、计数齐全，才算「产生了报告」
    try:
        with io.open(output, "r", encoding="utf-8") as f:
            back = json.load(f)
        for key in ("counts", "tests", "reasons", "result", "exitCode"):
            if key not in back:
                raise ValueError("报告缺少键: %s" % key)
    except Exception as exc:
        print("FAIL report-unreadable %s: %s" % (output, exc))
        return 1

    report_path = _rel(root, output)
    report_sha = sha256_file(output)

    print("%s %s" % (report["result"], run_id))
    print("  scope     : %s  patterns=%s" % (_rel(root, start_dir), ",".join(args.pattern)))
    print("  counts    : discovered=%d executed=%d passed=%d failed=%d errors=%d skipped=%d notRun=%d"
          % (counts["discovered"], counts["executed"], counts["passed"], counts["failed"],
             counts["errors"], counts["skipped"], counts["notRun"]))
    print("  fixtures  : %s" % (", ".join("%s=%s" % (e["id"], e["reason"])
                                          for e in fixtures_report) or "(none required)"))
    print("  report    : %s  sha256=%s" % (report_path, report_sha))
    for reason in report["reasons"]:
        print("  reason    : %s — %s" % (reason["code"], reason["detail"]))
    return exit_code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
