#!/usr/bin/env python3
"""arm64/x86_64 APK 的静态 ABI 审计（2026-09-26 起替代多数任务的 arm64 真机验收）。

只读，不运行任何 APK 代码。检查：
  1. APK 声明的 native ABI 与 lib/<abi>/ 目录，以及不该出现的 ABI 目录；
  2. 每个 lib/<abi>/*.so 的 ELF 头：ELFCLASS64、小端、e_machine 与 ABI 一致；
  3. 每个 .so 的 PT_LOAD 段对齐（p_align），报告是否满足 16 KB 页（>= 0x4000）；
  4. lib/ 下 .so 在 ZIP 中是否 STORED 且数据偏移页对齐（可直接 mmap，不需要解压）；
  5. 必需的 JNI/C 导出符号（例如 texture2ddecoder 的 20 个 decode_*），读自 .dynsym；
  6. DT_NEEDED 依赖都能在 APK 的同 ABI lib/ 或 Android 系统 NDK 公开库中找到；
  7. Chaquopy 资产：bootstrap-native/<abi>/、stdlib-<abi>.imy 与 requirements 的 ABI/common
     归档逐项读取并校验完整性，内部 .so 与 bootstrap-native/<abi>/*.so 必须也是对应架构。
     单 ABI flavor 可将依赖全部放入非空的 requirements-common.imy，ABI 归档只作空占位。

它不能证明运行时行为（JNI 注册、dlopen 链接期错误、指令集特性），只能拦住
「打错包 / 漏文件 / 错架构 / 缺符号 / 对齐不满足」这一类问题。

用法：
  python tools/audit_native_abi.py --apk <apk> --abi arm64-v8a [--output report.json]
退出码：0 = 所有必需检查通过（16 KB 对齐只报告不判失败，除非 --require-16k）；1 = 有失败；2 = 用法/IO 错误。
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import struct
import sys
import zipfile
import zlib

EM = {"arm64-v8a": 183, "x86_64": 62}          # EM_AARCH64, EM_X86_64
ALL_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

# Android NDK 公开的系统库（API 29+ 可链接），DT_NEEDED 指向它们时不要求随 APK 打包。
SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so",
    "libjnigraphics.so", "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so",
    "libvulkan.so", "libOpenSLES.so", "libOpenMAXAL.so", "libmediandk.so", "libcamera2ndk.so",
    "libneuralnetworks.so", "libnativewindow.so", "libsync.so", "libaaudio.so", "libamidi.so",
    "libbinder_ndk.so", "libstdc++.so", "libc++_shared.so",
}

# 按库名要求的导出符号。缺一个即失败。
REQUIRED_EXPORTS = {
    "libtexture2ddecoder.so": [
        "decode_atc_rgb4", "decode_atc_rgba8",
        "decode_etc1", "decode_etc2", "decode_etc2a1", "decode_etc2a8",
        "decode_eacr", "decode_eacr_signed", "decode_eacrg", "decode_eacrg_signed",
        "decode_bc1", "decode_bc3", "decode_bc4", "decode_bc5", "decode_bc6", "decode_bc7",
        "decode_astc", "decode_pvrtc", "crunch_unpack_level", "unity_crunch_unpack_level",
    ],
    "libchaquopy_java.so": ["JNI_OnLoad"],
}

# APK 必须带的 lib/<abi>/ 库（按 ABI 独立判定）。
REQUIRED_LIBS = [
    "libpython3.8.so", "libchaquopy_java.so", "libastcenc.so", "libtexture2ddecoder.so",
]

PT_LOAD, PT_DYNAMIC = 1, 2
DT_NEEDED, DT_STRTAB, DT_SONAME = 1, 5, 14
SHT_DYNSYM = 11


class ElfError(Exception):
    pass


def parse_elf(data: bytes) -> dict:
    if len(data) < 64:
        raise ElfError("truncated ELF header")
    if data[:4] != b"\x7fELF":
        raise ElfError("not an ELF file")
    ei_class, ei_data = data[4], data[5]
    if ei_class != 2:
        raise ElfError(f"ELFCLASS{32 if ei_class == 1 else ei_class} (expected ELFCLASS64)")
    if ei_data != 1:
        raise ElfError("big-endian ELF (expected little-endian)")
    (e_type, e_machine, _v, _entry, e_phoff, e_shoff, _flags, _ehsize,
     e_phentsize, e_phnum, e_shentsize, e_shnum, _shstrndx) = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)

    phdrs = []
    for i in range(e_phnum):
        p_type, _pflags, p_offset, p_vaddr, _paddr, p_filesz, _memsz, p_align = struct.unpack_from(
            "<IIQQQQQQ", data, e_phoff + i * e_phentsize)
        phdrs.append(dict(type=p_type, offset=p_offset, vaddr=p_vaddr, filesz=p_filesz, align=p_align))

    def vaddr_to_off(addr: int) -> int:
        for p in phdrs:
            if p["type"] == PT_LOAD and p["vaddr"] <= addr < p["vaddr"] + p["filesz"]:
                return addr - p["vaddr"] + p["offset"]
        raise ElfError(f"vaddr 0x{addr:x} not in any PT_LOAD")

    def cstr(off: int) -> str:
        end = data.index(b"\0", off)
        return data[off:end].decode("utf-8", "replace")

    needed, soname = [], None
    dyn = next((p for p in phdrs if p["type"] == PT_DYNAMIC), None)
    if dyn:
        entries, strtab = [], None
        for off in range(dyn["offset"], dyn["offset"] + dyn["filesz"], 16):
            tag, val = struct.unpack_from("<qQ", data, off)
            if tag == 0:
                break
            entries.append((tag, val))
            if tag == DT_STRTAB:
                strtab = vaddr_to_off(val)
        if strtab is not None:
            for tag, val in entries:
                if tag == DT_NEEDED:
                    needed.append(cstr(strtab + val))
                elif tag == DT_SONAME:
                    soname = cstr(strtab + val)

    exports = set()
    for i in range(e_shnum):
        (_name, sh_type, _f, _addr, sh_offset, sh_size, sh_link, _info, _align, sh_entsize) = struct.unpack_from(
            "<IIQQQQIIQQ", data, e_shoff + i * e_shentsize)
        if sh_type != SHT_DYNSYM or not sh_entsize:
            continue
        str_off = struct.unpack_from("<IIQQQQIIQQ", data, e_shoff + sh_link * e_shentsize)[4]
        for j in range(sh_size // sh_entsize):
            st_name, st_info, _other, st_shndx, _value, _size = struct.unpack_from(
                "<IBBHQQ", data, sh_offset + j * sh_entsize)
            bind = st_info >> 4
            if st_shndx != 0 and bind in (1, 2) and st_name:   # defined, GLOBAL/WEAK
                exports.add(cstr(str_off + st_name))

    loads = [p for p in phdrs if p["type"] == PT_LOAD]
    min_align = min((p["align"] for p in loads), default=0)
    return dict(type=e_type, machine=e_machine, needed=needed, soname=soname,
                exports=exports, load_count=len(loads), min_load_align=min_align)


def zip_data_offset(zf: zipfile.ZipFile, raw: bytes, info: zipfile.ZipInfo) -> int:
    h = info.header_offset
    name_len, extra_len = struct.unpack_from("<HH", raw, h + 26)
    return h + 30 + name_len + extra_len


def audit_python_archive(zf: zipfile.ZipFile, name: str, abi: str, failures: list,
                         *, allow_empty: bool = False) -> dict:
    """Read every entry (including CRC) and inspect native payloads without extracting them."""
    result = {"files": 0, "nativeFiles": 0}
    try:
        with zipfile.ZipFile(io.BytesIO(zf.read(name))) as archive:
            entries = [entry for entry in archive.infolist() if not entry.is_dir()]
            if not entries and not allow_empty:
                raise ValueError("empty Python archive")
            result["files"] = len(entries)
            for entry in entries:
                data = archive.read(entry)
                if entry.filename.endswith(".so"):
                    elf = parse_elf(data)
                    result["nativeFiles"] += 1
                    if elf["machine"] != EM[abi]:
                        failures.append(f"{name}!/{entry.filename}: e_machine={elf['machine']} expected {EM[abi]}")
    except (OSError, EOFError, RuntimeError, zipfile.BadZipFile, zlib.error,
            ElfError, ValueError, IndexError, struct.error) as exc:
        failures.append(f"{name}: invalid archive or native payload ({exc})")
    return result


def audit(apk_path: str, abi: str, require_16k: bool) -> dict:
    if abi not in EM:
        raise SystemExit(f"unsupported --abi {abi}")
    with open(apk_path, "rb") as fh:
        raw = fh.read()
    zf = zipfile.ZipFile(io.BytesIO(raw))
    names = zf.namelist()
    failures, warnings = [], []
    report = dict(apk=apk_path, apkSha256=hashlib.sha256(raw).hexdigest(), apkBytes=len(raw),
                  abi=abi, libs={}, chaquopy={}, failures=failures, warnings=warnings)

    present_abis = sorted({n.split("/")[1] for n in names if n.startswith("lib/") and n.count("/") >= 2})
    report["libAbis"] = present_abis
    if abi not in present_abis:
        failures.append(f"lib/{abi}/ missing")
    for other in present_abis:
        if other != abi:
            warnings.append(f"extra lib/{other}/ present (APK is not single-ABI)")

    libs = {n.split("/")[-1]: n for n in names if n.startswith(f"lib/{abi}/") and n.endswith(".so")}
    for req in REQUIRED_LIBS:
        if req not in libs:
            failures.append(f"required lib/{abi}/{req} missing")

    for lib, path in sorted(libs.items()):
        info = zf.getinfo(path)
        entry = dict(bytes=info.file_size, compress=("STORED" if info.compress_type == 0 else "DEFLATED"))
        try:
            elf = parse_elf(zf.read(path))
        except ElfError as e:
            failures.append(f"{path}: {e}")
            report["libs"][lib] = entry | dict(error=str(e))
            continue
        entry.update(machine=elf["machine"], soname=elf["soname"], needed=elf["needed"],
                     exportCount=len(elf["exports"]), minLoadAlign=elf["min_load_align"],
                     align16k=elf["min_load_align"] >= 0x4000)
        if elf["machine"] != EM[abi]:
            failures.append(f"{path}: e_machine={elf['machine']} expected {EM[abi]}")
        for need in elf["needed"]:
            if need not in libs and need not in SYSTEM_LIBS:
                failures.append(f"{path}: DT_NEEDED {need} not in APK lib/{abi}/ nor NDK system libs")
        missing = [s for s in REQUIRED_EXPORTS.get(lib, []) if s not in elf["exports"]]
        if missing:
            failures.append(f"{path}: missing exports {missing}")
        if lib in REQUIRED_EXPORTS:
            entry["requiredExportsPresent"] = len(REQUIRED_EXPORTS[lib]) - len(missing)
        if not entry["align16k"]:
            msg = f"{path}: PT_LOAD align 0x{elf['min_load_align']:x} < 16 KB"
            (failures if require_16k else warnings).append(msg)
        if info.compress_type == 0:
            off = zip_data_offset(zf, raw, info)
            entry["zipDataOffset"] = off
            entry["zipPageAligned4k"] = off % 0x1000 == 0
            entry["zipPageAligned16k"] = off % 0x4000 == 0
            if off % 0x1000:
                failures.append(f"{path}: STORED but ZIP data offset {off} not 4 KB aligned")
        report["libs"][lib] = entry

    cq = report["chaquopy"]
    requirements_abi = f"assets/chaquopy/requirements-{abi}.imy"
    for req in (f"assets/chaquopy/stdlib-{abi}.imy", requirements_abi,
                "assets/chaquopy/requirements-common.imy",
                f"assets/chaquopy/bootstrap-native/{abi}/zlib.cpython-38.so", "assets/chaquopy/bootstrap.imy",
                "assets/chaquopy/build.json"):
        cq[req] = req in names
        if req not in names:
            failures.append(f"{req} missing")
        elif req.endswith(".imy"):
            # Single-ABI flavors put even native dependencies in common. Only
            # the ABI requirements archive may be empty; common remains required
            # and nonempty, and its native payloads must pass the same checks.
            cq.setdefault("archives", {})[req] = audit_python_archive(
                zf, req, abi, failures, allow_empty=req == requirements_abi)
    boot = [n for n in names if n.startswith(f"assets/chaquopy/bootstrap-native/{abi}/") and n.endswith(".so")]
    bad = []
    for n in boot:
        try:
            if parse_elf(zf.read(n))["machine"] != EM[abi]:
                bad.append(n)
        except ElfError as e:
            bad.append(f"{n} ({e})")
    cq["bootstrapNativeCount"] = len(boot)
    if bad:
        failures.append(f"bootstrap-native wrong arch: {bad}")
    others = sorted({n.split("/")[3] for n in names if n.startswith("assets/chaquopy/bootstrap-native/")
                     and n.count("/") >= 4} - {abi})
    if others:
        warnings.append(f"Chaquopy bootstrap-native also contains {others} (known T49 topic)")

    report["result"] = "PASS" if not failures else "FAIL"
    return report


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--apk", required=True)
    ap.add_argument("--abi", required=True, choices=sorted(EM))
    ap.add_argument("--output")
    ap.add_argument("--require-16k", action="store_true", help="treat <16 KB PT_LOAD alignment as failure")
    a = ap.parse_args(argv)
    try:
        report = audit(a.apk, a.abi, a.require_16k)
    except (OSError, zipfile.BadZipFile) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    text = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=False)
    if a.output:
        with open(a.output, "w", encoding="utf-8", newline="\n") as f:
            f.write(text + "\n")
    print(f"{report['result']} abi={a.abi} libs={len(report['libs'])} "
          f"failures={len(report['failures'])} warnings={len(report['warnings'])}")
    for f in report["failures"]:
        print("  FAIL", f)
    for w in report["warnings"]:
        print("  WARN", w)
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
