#!/usr/bin/env python3
"""T03 read-only APK content classifier.

Recomputes, for one APK, the per-group entry counts and payload bytes defined by
`docs/refactor/evidence/baseline.json` -> `apkClassification.method`, then
self-checks that the group sums equal the measured container totals.

This is an independent implementation from dump_entries.py: it reads the ZIP
central directory through the Python standard-library `zipfile` module instead
of a hand-rolled central-directory parser, so agreement between the two is a
real cross-check rather than a shared code path.

Read-only: nothing is extracted to disk. Only the standard library is used.

Usage:
    python classify_content.py <apk> [--package-id ID] [--out OUT.json]

Output: human-readable summary on stdout; when --out is given, the same data as
JSON is also written to that path.

Groups (identical labels to baseline.json):

    DEX                       classes*.dex
    Android resources         resources.arsc + res/**
    Chaquopy                  assets/chaquopy/**
    native                    lib/**
    WebView assets            assets/spine-viewer/**
    Other assets              remaining assets/**
    Manifest/metadata/other   AndroidManifest.xml, META-INF/** and other metadata

Byte semantics: `compressed` is the central-directory compressed payload size
(what the container actually stores); `raw` is the uncompressed entry size.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import zipfile

# Ordered group definitions: (label, predicate). First match wins.
DEX_RE = re.compile(r"classes\d*\.dex$")


def _predicates():
    return [
        ("DEX", lambda n: bool(DEX_RE.fullmatch(n))),
        ("Android resources", lambda n: n == "resources.arsc" or n.startswith("res/")),
        ("Chaquopy", lambda n: n.startswith("assets/chaquopy/")),
        ("native", lambda n: n.startswith("lib/")),
        ("WebView assets", lambda n: n.startswith("assets/spine-viewer/")),
        ("Other assets", lambda n: n.startswith("assets/")),
        ("Manifest/metadata/other", lambda n: True),
    ]


def classify(name, groups=None):
    for label, pred in (groups or _predicates()):
        if pred(name):
            return label
    return "Manifest/metadata/other"


def analyze(apk_path, package_id=None):
    size = os.path.getsize(apk_path)
    with open(apk_path, "rb") as f:
        sha256 = hashlib.sha256(f.read()).hexdigest()

    groups = {}
    for label, _ in _predicates():
        groups[label] = {
            "entries": 0,
            "compressed": 0,
            "raw": 0,
            "entryNames": [],
        }

    measured_entries = 0
    measured_compressed = 0
    measured_raw = 0
    direct_entries = 0
    with zipfile.ZipFile(apk_path) as zf:
        for info in zf.infolist():
            label = classify(info.filename)
            g = groups[label]
            g["entries"] += 1
            g["compressed"] += info.compress_size
            g["raw"] += info.file_size
            g["entryNames"].append(info.filename)
            measured_entries += 1
            measured_compressed += info.compress_size
            measured_raw += info.file_size
            if info.is_dir():
                direct_entries += 1

    container_overhead = size - measured_compressed

    sum_entries = sum(g["entries"] for g in groups.values())
    sum_compressed = sum(g["compressed"] for g in groups.values())
    sum_raw = sum(g["raw"] for g in groups.values())

    self_check = {
        "entries": {
            "groupSum": sum_entries,
            "measured": measured_entries,
            "matches": sum_entries == measured_entries,
        },
        "compressed": {
            "groupSum": sum_compressed,
            "measured": measured_compressed,
            "matches": sum_compressed == measured_compressed,
        },
        "raw": {
            "groupSum": sum_raw,
            "measured": measured_raw,
            "matches": sum_raw == measured_raw,
        },
        "containerOverhead": {
            "definition": "file bytes minus sum of compressed payload bytes",
            "value": container_overhead,
            "fileBytes": size,
        },
        "allMatch": (sum_entries == measured_entries
                     and sum_compressed == measured_compressed
                     and sum_raw == measured_raw),
    }

    return {
        "apk": apk_path.replace("\\", "/"),
        "packageId": package_id or os.path.basename(apk_path),
        "fileBytes": size,
        "sha256": sha256,
        "groups": groups,
        "measured": {
            "entries": measured_entries,
            "compressed": measured_compressed,
            "raw": measured_raw,
            "directoryEntries": direct_entries,
        },
        "containerOverheadBytes": container_overhead,
        "selfCheck": self_check,
    }


def print_summary(r):
    print("=== %s ===" % r["apk"])
    print("packageId: %s" % r["packageId"])
    print("file bytes: %d" % r["fileBytes"])
    print("sha256: %s" % r["sha256"])
    print()
    print("%-26s %8s %14s %14s" % ("group", "entries", "compressed", "raw"))
    for label, g in r["groups"].items():
        print("%-26s %8d %14d %14d" % (label, g["entries"], g["compressed"], g["raw"]))
    m = r["measured"]
    print("%-26s %8d %14d %14d" % ("MEASURED TOTAL", m["entries"], m["compressed"], m["raw"]))
    print()
    sc = r["selfCheck"]
    print("self-check entries:    groupSum=%d measured=%d match=%s"
          % (sc["entries"]["groupSum"], sc["entries"]["measured"], sc["entries"]["matches"]))
    print("self-check compressed: groupSum=%d measured=%d match=%s"
          % (sc["compressed"]["groupSum"], sc["compressed"]["measured"], sc["compressed"]["matches"]))
    print("self-check raw:        groupSum=%d measured=%d match=%s"
          % (sc["raw"]["groupSum"], sc["raw"]["measured"], sc["raw"]["matches"]))
    print("container overhead bytes (file - compressed payload): %d" % r["containerOverheadBytes"])
    print("ALL GROUP SUMS MATCH MEASURED TOTALS: %s" % sc["allMatch"])


def main(argv=None):
    p = argparse.ArgumentParser(description="Read-only APK content classifier (T03).")
    p.add_argument("apk", help="path to the APK to classify")
    p.add_argument("--package-id", default=None,
                   help="label to record as packageId (default: APK basename)")
    p.add_argument("--out", default=None, help="optional path to write the JSON result")
    args = p.parse_args(argv)

    result = analyze(args.apk, args.package_id)
    print_summary(result)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=1)
        print("\nwrote %s" % args.out)
    return 0 if result["selfCheck"]["allMatch"] else 1


if __name__ == "__main__":
    sys.exit(main())
