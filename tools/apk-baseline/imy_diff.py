#!/usr/bin/env python3
"""T03 read-only nested-archive comparison of assets/chaquopy/app.imy.

Compares the Chaquopy archive that is embedded inside two APKs:

  * entry-name sets (what exists in one side only)
  * per-entry CRC-32 and sizes
  * whether entries whose CRC differs are nevertheless byte-identical after
    CRLF -> LF normalisation

The last point is what separates "the build environment re-wrote line endings"
from "the content actually changed".

Read-only: the APK and the nested archive are read into memory only; nothing is
extracted to disk. Only the standard library is used.

Usage:
    python imy_diff.py <apk_a> <apk_b> [--inner PATH] [--label-a A] [--label-b B]
                       [--out OUT.json]
"""
import argparse
import hashlib
import io
import json
import sys
import zipfile

DEFAULT_INNER = "assets/chaquopy/app.imy"


def read_inner(apk_path, inner_path):
    with zipfile.ZipFile(apk_path) as zf:
        try:
            info = zf.getinfo(inner_path)
        except KeyError:
            raise SystemExit("%s: no entry %r" % (apk_path, inner_path))
        data = zf.read(inner_path)
    return {
        "apk": apk_path.replace("\\", "/"),
        "innerPath": inner_path,
        "entryInApk": {
            "compressSize": info.compress_size,
            "fileSize": info.file_size,
            "crc": info.CRC,
            "method": info.compress_type,
        },
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "data": data,
    }


def inner_entries(data):
    out = {}
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        for info in zf.infolist():
            out[info.filename] = {
                "crc": info.CRC,
                "compressSize": info.compress_size,
                "fileSize": info.file_size,
                "method": info.compress_type,
                "isDir": info.is_dir(),
            }
    return out


def inner_read(data, name):
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        return zf.read(name)


def normalize_crlf(blob):
    """Normalise CRLF line endings to LF; leave everything else untouched."""
    return blob.replace(b"\r\n", b"\n")


def compare(apk_a, apk_b, inner_path=DEFAULT_INNER, label_a="A", label_b="B"):
    a = read_inner(apk_a, inner_path)
    b = read_inner(apk_b, inner_path)
    ea = inner_entries(a["data"])
    eb = inner_entries(b["data"])

    only_a = sorted(set(ea) - set(eb))
    only_b = sorted(set(eb) - set(ea))
    common = sorted(set(ea) & set(eb))

    crc_diff = [n for n in common if ea[n]["crc"] != eb[n]["crc"]]
    crc_same = [n for n in common if ea[n]["crc"] == eb[n]["crc"]]

    size_diff = []
    for n in common:
        if (ea[n]["fileSize"], ea[n]["compressSize"]) != (eb[n]["fileSize"], eb[n]["compressSize"]):
            size_diff.append({
                "name": n,
                "a": {"raw": ea[n]["fileSize"], "compressed": ea[n]["compressSize"]},
                "b": {"raw": eb[n]["fileSize"], "compressed": eb[n]["compressSize"]},
            })

    normalized_identical = []
    true_content_diff = []
    for n in crc_diff:
        ra = inner_read(a["data"], n)
        rb = inner_read(b["data"], n)
        if normalize_crlf(ra) == normalize_crlf(rb):
            normalized_identical.append(n)
        else:
            true_content_diff.append(n)

    return {
        "innerPath": inner_path,
        "a": {k: v for k, v in a.items() if k != "data"},
        "b": {k: v for k, v in b.items() if k != "data"},
        "entryCounts": {"a": len(ea), "b": len(eb), "common": len(common)},
        "onlyIn": {label_a: only_a, label_b: only_b},
        "onlyInCounts": {label_a: len(only_a), label_b: len(only_b)},
        "onlyInAllDirectories": all(ea[n]["isDir"] for n in only_a) and
                                all(eb[n]["isDir"] for n in only_b),
        "crcDifferingCount": len(crc_diff),
        "crcDiffering": crc_diff,
        "crcSameCount": len(crc_same),
        "sizeDiffering": size_diff,
        "crcDifferingNormalizedIdenticalCount": len(normalized_identical),
        "crcDifferingNormalizedIdentical": normalized_identical,
        "trueContentDifferenceCount": len(true_content_diff),
        "trueContentDifferences": true_content_diff,
        "allCrcDifferencesAreLineEndingOnly": len(crc_diff) == len(normalized_identical),
    }


def print_summary(r, label_a, label_b):
    print("=== %s (inner: %s) ===" % ("app.imy comparison", r["innerPath"]))
    print("%-8s apk=%s" % (label_a, r["a"]["apk"]))
    print("         bytes=%d sha256=%s (apk entry compressed=%d raw=%d crc=0x%08X)"
          % (r["a"]["bytes"], r["a"]["sha256"], r["a"]["entryInApk"]["compressSize"],
             r["a"]["entryInApk"]["fileSize"], r["a"]["entryInApk"]["crc"]))
    print("%-8s apk=%s" % (label_b, r["b"]["apk"]))
    print("         bytes=%d sha256=%s (apk entry compressed=%d raw=%d crc=0x%08X)"
          % (r["b"]["bytes"], r["b"]["sha256"], r["b"]["entryInApk"]["compressSize"],
             r["b"]["entryInApk"]["fileSize"], r["b"]["entryInApk"]["crc"]))
    print()
    ec = r["entryCounts"]
    print("inner entries: %s=%d  %s=%d  common=%d" % (label_a, ec["a"], label_b, ec["b"], ec["common"]))
    for lbl in (label_a, label_b):
        names = r["onlyIn"][lbl]
        print("only in %s: %d%s" % (lbl, len(names),
                                    " (all directory entries)" if r["onlyInAllDirectories"] else ""))
        for n in names:
            print("   %s" % n)
    print()
    print("common entries with differing CRC-32: %d" % r["crcDifferingCount"])
    print("  ... of which byte-identical after CRLF->LF normalisation: %d"
          % r["crcDifferingNormalizedIdenticalCount"])
    print("  ... of which truly different content: %d" % r["trueContentDifferenceCount"])
    for n in r["trueContentDifferences"]:
        print("   TRUE-CONTENT-DIFF %s" % n)
    print("common entries with differing sizes: %d" % len(r["sizeDiffering"]))
    for d in r["sizeDiffering"]:
        print("   SIZE-DIFF %s  raw %d -> %d  compressed %d -> %d" % (
            d["name"], d["a"]["raw"], d["b"]["raw"],
            d["a"]["compressed"], d["b"]["compressed"]))
    print()
    print("ALL CRC DIFFERENCES ARE LINE-ENDING ONLY: %s"
          % r["allCrcDifferencesAreLineEndingOnly"])


def main(argv=None):
    p = argparse.ArgumentParser(
        description="Read-only comparison of the nested assets/chaquopy/app.imy of two APKs (T03).")
    p.add_argument("apk_a", help="first APK (labelled --label-a)")
    p.add_argument("apk_b", help="second APK (labelled --label-b)")
    p.add_argument("--inner", default=DEFAULT_INNER, help="nested archive path (default: %(default)s)")
    p.add_argument("--label-a", default="A", help="label for the first APK")
    p.add_argument("--label-b", default="B", help="label for the second APK")
    p.add_argument("--out", default=None, help="optional path to write the JSON result")
    args = p.parse_args(argv)

    result = compare(args.apk_a, args.apk_b, args.inner, args.label_a, args.label_b)
    print_summary(result, args.label_a, args.label_b)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=1)
        print("\nwrote %s" % args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
