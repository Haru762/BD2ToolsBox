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


def parse_elf_sections(data: bytes) -> dict:
    """只读解析 ELF 节表：列出可剥离的调试/符号节及其字节数。

    只读头部与节表；不执行、不加载、不写盘。用于 T51 的「剥离前后可剥离节减少」度量。
    """
    if len(data) < 0x40 or data[0:4] != b"\x7fELF":
        return {"valid": False, "reason": "bad magic"}
    is64 = data[4] == 2
    if not is64 and data[4] != 1:
        return {"valid": False, "reason": "unknown EI_CLASS"}
    if data[5] not in (1, 2):
        return {"valid": False, "reason": "unknown EI_DATA"}
    endian = "<" if data[5] == 1 else ">"
    try:
        if is64:
            e_shoff = struct.unpack_from(endian + "Q", data, 0x28)[0]
            e_shentsize, e_shnum, e_shstrndx = struct.unpack_from(endian + "HHH", data, 0x3A)
        else:
            e_shoff = struct.unpack_from(endian + "I", data, 0x20)[0]
            e_shentsize, e_shnum, e_shstrndx = struct.unpack_from(endian + "HHH", data, 0x2E)
    except struct.error:
        return {"valid": False, "reason": "truncated header"}
    if e_shoff == 0 or e_shnum == 0 or e_shstrndx >= e_shnum:
        return {"valid": True, "debugSections": {}, "debugBytes": 0,
                "sectionNames": [], "note": "no section header table"}

    def section(index):
        off = e_shoff + index * e_shentsize
        name_off = struct.unpack_from(endian + "I", data, off)[0]
        if is64:
            sh_offset = struct.unpack_from(endian + "Q", data, off + 0x18)[0]
            sh_size = struct.unpack_from(endian + "Q", data, off + 0x20)[0]
        else:
            sh_offset = struct.unpack_from(endian + "I", data, off + 0x10)[0]
            sh_size = struct.unpack_from(endian + "I", data, off + 0x14)[0]
        return name_off, sh_offset, sh_size

    _, strtab_off, _ = section(e_shstrndx)

    def name_at(offset):
        start = strtab_off + offset
        end = data.find(b"\x00", start)
        if end < 0:
            return ""
        return data[start:end].decode("utf-8", "replace")

    debug_sections = {}
    names = []
    for i in range(e_shnum):
        name_off, _, sh_size = section(i)
        name = name_at(name_off)
        names.append(name)
        if name.startswith(".debug") or name in (".symtab", ".strtab"):
            debug_sections[name] = sh_size
    return {
        "valid": True,
        "debugSections": debug_sections,
        "debugBytes": sum(debug_sections.values()),
        "sectionNames": sorted(names),
    }


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


def check_required_resource_names(apk_path: str, required: List[str]) -> List[dict]:
    """T53：确认被**按名字动态查表**的资源没有被资源收缩删掉。

    资源名在 `resources.arsc` 的字符串池里（即使文件路径被混淆，资源名也会保留 ——
    否则 `Resources.getIdentifier` 就无法工作），因此可以直接在 arsc 字节里找。
    只读，不解析 arsc 结构。
    """
    problems = []
    with zipfile.ZipFile(apk_path) as zf:
        if "resources.arsc" not in zf.namelist():
            return [{"kind": "missing-resources-arsc"}]
        blob = zf.read("resources.arsc")
    for name in required:
        leaf = name.split("/")[-1]
        if leaf.encode("utf-8") not in blob:
            problems.append({"kind": "missing-resource-name", "name": name})
    return problems


def compare_reports(before: dict, after: dict) -> dict:
    """T54：两份审计报告的逐项对照（同一口径可复算）。"""
    categories = {}
    for name in CATEGORY_ORDER:
        b = before["categories"].get(name, {})
        a = after["categories"].get(name, {})
        categories[name] = {
            "entriesBefore": b.get("entries"),
            "entriesAfter": a.get("entries"),
            "bytesBefore": b.get("compressedBytes"),
            "bytesAfter": a.get("compressedBytes"),
            "deltaBytes": (a.get("compressedBytes") or 0) - (b.get("compressedBytes") or 0),
        }
    return {
        "schemaVersion": 1,
        "tool": "tools/audit_apk_size.py --compare",
        "apk": {
            "bytesBefore": before["apk"]["bytes"],
            "bytesAfter": after["apk"]["bytes"],
            "mibBefore": before["apk"]["mib"],
            "mibAfter": after["apk"]["mib"],
            "deltaBytes": after["apk"]["bytes"] - before["apk"]["bytes"],
            "deltaMib": round((after["apk"]["bytes"] - before["apk"]["bytes"]) / MIB, 4),
            "deltaPercent": round(
                100.0 * (after["apk"]["bytes"] - before["apk"]["bytes"])
                / before["apk"]["bytes"], 2
            ),
            "sha256Before": before["apk"]["sha256"],
            "sha256After": after["apk"]["sha256"],
        },
        "categories": categories,
        "containerOverhead": {
            "bytesBefore": before["containerOverhead"]["bytes"],
            "bytesAfter": after["containerOverhead"]["bytes"],
        },
        "nativeDebugBytes": {
            "before": before.get("nativeDebugBytes"),
            "after": after.get("nativeDebugBytes"),
        },
        "abi": {
            "before": list(sorted(before.get("nativeByAbiDir", {}))),
            "after": list(sorted(after.get("nativeByAbiDir", {}))),
        },
        "findings": {"before": len(before.get("findings", [])),
                     "after": len(after.get("findings", []))},
    }


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
    native_detail: list = []
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
                blob = zf.read(name)
                parsed = parse_elf_header(blob[:64] if len(blob) >= 20 else blob)
                sections = parse_elf_sections(blob)
                abi = name.split("/")[1] if name.count("/") >= 2 else "?"
                native_by_abi.setdefault(abi, {"files": 0, "bytes": 0})
                native_by_abi[abi]["files"] += 1
                native_by_abi[abi]["bytes"] += info.file_size
                native_detail.append({
                    "name": name,
                    "bytes": info.file_size,
                    "compressedBytes": info.compress_size,
                    "elfClass": parsed.get("elfClass"),
                    "abi": parsed.get("machineName"),
                    "debugBytes": sections.get("debugBytes"),
                    "debugSections": sections.get("debugSections"),
                })
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
        "nativeLibraries": sorted(native_detail, key=lambda d: d["name"]),
        "nativeDebugBytes": sum(d["debugBytes"] or 0 for d in native_detail),
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
    parser.add_argument("--apk", default=None, help="release APK 路径")
    parser.add_argument("--compare", nargs=2, default=None, metavar=("BEFORE", "AFTER"),
                        help="对照两份已有的审计 JSON，不再读 APK")
    parser.add_argument("--output", default=None, help="JSON 输出路径（省略则打印）")
    parser.add_argument("--expected-abi", default=None,
                        help="断言该包只含此 ABI（arm64-v8a / x86_64）；不符则退出码 1")
    parser.add_argument("--require-resource-name", action="append", default=[],
                        metavar="NAME",
                        help="断言 resources.arsc 里仍有该资源名（可重复，如 raw/aboutlibraries）")
    parser.add_argument("--quiet", action="store_true", help="不打印摘要")
    args = parser.parse_args(argv)

    if args.compare:
        try:
            with open(args.compare[0], encoding="utf-8") as fh:
                before = json.load(fh)
            with open(args.compare[1], encoding="utf-8") as fh:
                after = json.load(fh)
        except (OSError, ValueError) as exc:
            sys.stderr.write("ERROR: %s\n" % exc)
            return 2
        report = compare_reports(before, after)
        text = json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
        if args.output:
            with open(args.output, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(text)
        if not args.quiet:
            sys.stdout.write(text)
        return 0

    if not args.apk:
        sys.stderr.write("ERROR: --apk is required (or use --compare BEFORE AFTER)\n")
        return 2

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

    if args.require_resource_name:
        res_problems = check_required_resource_names(args.apk, args.require_resource_name)
        report["requiredResourceNames"] = sorted(set(args.require_resource_name))
        report["requiredResourceProblems"] = res_problems
        problems = problems + res_problems

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
