#!/usr/bin/env python3
"""T48 — APK 组成 / ABI / native 的**只读**审计。

用途：把 release APK 按 ZIP 的**实际压缩载荷**分类，给后续减包任务（T49–T54）
提供可重复的 A/B 基线口径。脚本**不执行**任何 APK 代码、**不落盘解包**任何条目。

口径（统一 MiB = 1024*1024）：
  · 每个类别的 `compressedBytes` 是 ZIP 里的 `compress_size` 之和（STORED 条目
    `compress_size == file_size`），这是它**在 APK 里真正占的字节**；
  · `uncompressedBytes` 只作参考；
  · `containerOverhead` = APK 文件大小 − 所有条目 `compress_size` 之和，
    即 local header / data descriptor / central directory / EOCD 的开销。
    分类字节 + 开销 == APK 文件大小（脚本自校验，不等就报错）。

分类：
  · `dex`              — `classes*.dex`
  · `androidResources` — `resources.arsc` / `res/**` / `AndroidManifest.xml`
  · `chaquopy`         — `assets/chaquopy/**`（含 `.imy` 归档）
  · `native`           — `lib/**/*.so`
  · `other`            — 其余

嵌套 IMY：对 `assets/chaquopy/**/*.imy` 在**内存里**当作 ZIP 再读一层，
列出内部 `.so` 的 ELF 架构、以及未知平台文件；不写任何临时文件。

只读 ELF 解析：读 `e_ident`（EI_CLASS / EI_DATA）与 `e_machine`，不 dlopen、不执行。

失败条件（退出码 1）：
  · APK 内有**重复条目名**（ZIP 语义下解压结果不确定）；
  · 条目名含危险路径（绝对路径 / `..` 段）——只记录，绝不落盘；
  · 某个 `.so` 的 ELF 头畸形或架构未知。

退出码：0 = 通过；1 = 有上述发现；2 = 用法 / IO 错误。
输出：`--output` 写 JSON（`sort_keys=True`，同文件重复运行输出一致）。
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import struct
import sys
import zipfile
from typing import List, Optional

MIB = 1024 * 1024

EM_NAMES = {
    3: "x86",
    40: "armeabi-v7a",
    62: "x86_64",
    183: "arm64-v8a",
}

CATEGORY_DEX = "dex"
CATEGORY_RES = "androidResources"
CATEGORY_CHAQUOPY = "chaquopy"
CATEGORY_NATIVE = "native"
CATEGORY_OTHER = "other"
CATEGORY_ORDER = (
    CATEGORY_DEX, CATEGORY_RES, CATEGORY_CHAQUOPY, CATEGORY_NATIVE, CATEGORY_OTHER,
)


class AuditError(Exception):
    """用法 / IO 层错误（退出码 2）。"""


def _is_dangerous(name: str) -> Optional[str]:
    """条目名是否含危险路径。返回原因，安全则返回 None。"""
    if not name:
        return "empty name"
    if name.startswith("/") or name.startswith("\\"):
        return "absolute path"
    if len(name) > 1 and name[1] == ":":
        return "drive-letter path"
    parts = name.replace("\\", "/").split("/")
    if ".." in parts:
        return "parent traversal"
    return None


def classify(name: str) -> str:
    """按 APK 内路径分类。"""
    if name.startswith("lib/") and name.endswith(".so"):
        return CATEGORY_NATIVE
    if name.startswith("assets/chaquopy/"):
        return CATEGORY_CHAQUOPY
    if name.startswith("classes") and name.endswith(".dex"):
        return CATEGORY_DEX
    if name == "resources.arsc" or name == "AndroidManifest.xml" or name.startswith("res/"):
        return CATEGORY_RES
    return CATEGORY_OTHER


def parse_elf_header(data: bytes) -> dict:
    """只读解析 ELF 头：class / endianness / machine。不执行、不加载。"""
    if len(data) < 20:
        return {"valid": False, "reason": "shorter than 20 bytes"}
    if data[0:4] != b"\x7fELF":
        return {"valid": False, "reason": "bad magic"}
    ei_class = data[4]
    ei_data = data[5]
    if ei_class not in (1, 2):
        return {"valid": False, "reason": "unknown EI_CLASS %d" % ei_class}
    if ei_data not in (1, 2):
        return {"valid": False, "reason": "unknown EI_DATA %d" % ei_data}
    endian = "<" if ei_data == 1 else ">"
    # e_machine 在 64 位头里也是紧随 e_ident(16) + e_type(2) 之后的 2 字节
    machine = struct.unpack_from(endian + "H", data, 18)[0]
    return {
        "valid": True,
        "elfClass": 64 if ei_class == 2 else 32,
        "endianness": "little" if ei_data == 1 else "big",
        "machine": machine,
        "machineName": EM_NAMES.get(machine),
    }


def _read_nested_imy(blob: bytes, label: str, findings: list) -> dict:
    """在内存里读一层嵌套 IMY（ZIP）。绝不落盘。"""
    result = {"entry": label, "entries": 0, "soFiles": [], "unknownPlatform": [],
              "dangerous": [], "malformed": None}
    try:
        with zipfile.ZipFile(io.BytesIO(blob)) as zf:
            names = zf.namelist()
            result["entries"] = len(names)
            if len(names) != len(set(names)):
                findings.append({"kind": "duplicate-nested-entry", "entry": label})
            for name in sorted(names):
                danger = _is_dangerous(name)
                if danger:
                    result["dangerous"].append({"name": name, "reason": danger})
                    continue
                if not name.endswith(".so"):
                    continue
                info = zf.getinfo(name)
                head = zf.read(name)[:64] if info.file_size >= 20 else zf.read(name)
                parsed = parse_elf_header(head)
                record = {"name": name, "bytes": info.file_size}
                if parsed.get("valid"):
                    record["abi"] = parsed.get("machineName")
                    record["elfClass"] = parsed.get("elfClass")
                    if parsed.get("machineName") is None:
                        result["unknownPlatform"].append(
                            {"name": name, "machine": parsed.get("machine")}
                        )
                else:
                    record["abi"] = None
                    record["reason"] = parsed.get("reason")
                    result["malformed"] = {"name": name, "reason": parsed.get("reason")}
                    findings.append(
                        {"kind": "malformed-elf", "entry": "%s!%s" % (label, name),
                         "reason": parsed.get("reason")}
                    )
                result["soFiles"].append(record)
    except zipfile.BadZipFile as exc:
        result["malformed"] = str(exc)
        findings.append({"kind": "malformed-nested-archive", "entry": label,
                         "reason": str(exc)})
    return result


def _abi_tokens(abi: str) -> List[str]:
    if abi == "arm64-v8a":
        return ["arm64-v8a", "arm64"]
    if abi == "x86_64":
        return ["x86_64"]
    return [abi]


ALL_ABI_TOKENS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")


def _token_matches(text: str, token: str) -> bool:
    """ABI token 必须作为**独立片段**出现。

    否则 `x86` 会在 `stdlib-x86_64.imy` 里被子串命中，把正确的包误判成混架构
    （与 `feature/update/ApkSelector.kt` 同一条边界规则）。
    """
    pattern = "(?<![a-z0-9_])" + re.escape(token) + "(?![a-z0-9_])"
    return re.search(pattern, text, re.IGNORECASE) is not None


def check_expected_abi(report: dict, expected: str) -> List[dict]:
    """T49：确认这个包**只**含 [expected] 架构的内容。

    只看 `lib/` 目录不够 —— Chaquopy 的 assets/IMY 里还藏着另一套原生库，
    文件名里也带 ABI 后缀。这里两处都查。
    """
    problems = []
    allowed = set(_abi_tokens(expected))
    foreign = [t for t in ALL_ABI_TOKENS if t not in allowed]

    for abi_dir in sorted(report.get("nativeByAbiDir", {})):
        if abi_dir not in allowed:
            problems.append({"kind": "foreign-lib-abi-dir", "abi": abi_dir})

    for problem in report.get("elfProblems", []):
        problems.append({"kind": "elf-problem", "name": problem.get("name"),
                         "reason": problem.get("reason")})

    for inner in report.get("nestedImy", []):
        entry = inner.get("entry", "")
        for token in foreign:
            if _token_matches(entry, token):
                problems.append({"kind": "foreign-imy-name", "entry": entry, "token": token})
        for so in inner.get("soFiles", []):
            if so.get("abi") is not None and so.get("abi") not in allowed:
                problems.append({"kind": "foreign-imy-native", "entry": entry,
                                 "name": so.get("name"), "abi": so.get("abi")})
        for unknown in inner.get("unknownPlatform", []):
            problems.append({"kind": "unknown-imy-native", "entry": entry,
                             "name": unknown.get("name")})
    return problems


def audit(apk_path: str) -> dict:
    if not os.path.isfile(apk_path):
        raise AuditError("APK not found: %s" % apk_path)

    file_size = os.path.getsize(apk_path)
    sha = hashlib.sha256()
    with open(apk_path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            sha.update(chunk)

    findings: list = []
    categories = {name: {"entries": 0, "compressedBytes": 0, "uncompressedBytes": 0}
                  for name in CATEGORY_ORDER}
    nested_imy: list = []
    dangerous: list = []
    native_by_abi: dict = {}
    elf_problems: list = []

    with zipfile.ZipFile(apk_path) as zf:
        infos = zf.infolist()
        names = [i.filename for i in infos]
        if len(names) != len(set(names)):
            seen = set()
            for n in names:
                if n in seen:
                    findings.append({"kind": "duplicate-entry", "entry": n})
                seen.add(n)

        for info in infos:
            name = info.filename
            danger = _is_dangerous(name)
            if danger:
                dangerous.append({"name": name, "reason": danger})
                findings.append({"kind": "dangerous-path", "entry": name, "reason": danger})
                # 危险条目仍然计入分类（它确实占体积），但不读取内容
                category = classify(name)
                categories[category]["entries"] += 1
                categories[category]["compressedBytes"] += info.compress_size
                categories[category]["uncompressedBytes"] += info.file_size
                continue

            category = classify(name)
            categories[category]["entries"] += 1
            categories[category]["compressedBytes"] += info.compress_size
            categories[category]["uncompressedBytes"] += info.file_size

            if category == CATEGORY_NATIVE:
                head = zf.read(name)[:64] if info.file_size >= 20 else zf.read(name)
                parsed = parse_elf_header(head)
                abi = name.split("/")[1] if name.count("/") >= 2 else "?"
                native_by_abi.setdefault(abi, {"files": 0, "bytes": 0})
                native_by_abi[abi]["files"] += 1
                native_by_abi[abi]["bytes"] += info.file_size
                if not parsed.get("valid"):
                    elf_problems.append({"name": name, "reason": parsed.get("reason")})
                    findings.append({"kind": "malformed-elf", "entry": name,
                                     "reason": parsed.get("reason")})
                elif parsed.get("machineName") is None:
                    elf_problems.append(
                        {"name": name, "reason": "unknown machine %s" % parsed.get("machine")}
                    )
                elif parsed.get("machineName") != abi:
                    elf_problems.append(
                        {"name": name,
                         "reason": "abi dir %s but ELF machine %s" % (abi, parsed.get("machineName"))}
                    )
                    findings.append({"kind": "abi-mismatch", "entry": name,
                                     "reason": "dir=%s elf=%s" % (abi, parsed.get("machineName"))})

            if category == CATEGORY_CHAQUOPY and name.endswith(".imy"):
                nested_imy.append(_read_nested_imy(zf.read(name), name, findings))

    accounted = sum(c["compressedBytes"] for c in categories.values())
    overhead = file_size - accounted

    for name in CATEGORY_ORDER:
        entry = categories[name]
        entry["mib"] = round(entry["compressedBytes"] / MIB, 4)
        entry["uncompressedMib"] = round(entry["uncompressedBytes"] / MIB, 4)

    return {
        "schemaVersion": 1,
        "tool": "tools/audit_apk_size.py",
        "apk": {
            "path": os.path.basename(apk_path),
            "bytes": file_size,
            "mib": round(file_size / MIB, 4),
            "sha256": sha.hexdigest(),
        },
        "categories": categories,
        "containerOverhead": {
            "bytes": overhead,
            "mib": round(overhead / MIB, 4),
        },
        "categoryBytesPlusOverhead": accounted + overhead,
        "nativeByAbiDir": native_by_abi,
        "nestedImy": nested_imy,
        "dangerousEntries": dangerous,
        "elfProblems": elf_problems,
        "findings": findings,
    }


def _check_invariants(report: dict) -> list:
    """自校验：分类字节 + 容器开销必须等于 APK 文件大小。"""
    problems = []
    total = report["categoryBytesPlusOverhead"]
    if total != report["apk"]["bytes"]:
        problems.append(
            "accounted %d != apk size %d" % (total, report["apk"]["bytes"])
        )
    return problems


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="只读审计 APK 组成 / ABI / native")
    parser.add_argument("--apk", required=True, help="release APK 路径")
    parser.add_argument("--output", default=None, help="JSON 输出路径（省略则打印）")
    parser.add_argument("--expected-abi", default=None,
                        help="断言该包只含此 ABI（arm64-v8a / x86_64）；不符则退出码 1")
    parser.add_argument("--quiet", action="store_true", help="不打印摘要")
    args = parser.parse_args(argv)

    try:
        report = audit(args.apk)
    except AuditError as exc:
        sys.stderr.write("ERROR: %s\n" % exc)
        return 2
    except (OSError, zipfile.BadZipFile) as exc:
        sys.stderr.write("ERROR: %s\n" % exc)
        return 2

    problems = _check_invariants(report)
    report["invariantProblems"] = problems

    if args.expected_abi:
        abi_problems = check_expected_abi(report, args.expected_abi)
        report["expectedAbi"] = args.expected_abi
        report["expectedAbiProblems"] = abi_problems
        problems = problems + abi_problems

    text = json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    if args.output:
        with open(args.output, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
    if not args.quiet:
        sys.stdout.write(text)

    if problems or report["findings"]:
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
