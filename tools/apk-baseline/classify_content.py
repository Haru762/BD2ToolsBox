#!/usr/bin/env python3
"""T03 read-only APK content classifier.

Recomputes, for one APK, the per-group entry counts and payload bytes defined by
`docs/refactor/evidence/baseline.json` -> `apkClassification.method`, then
verifies the result against quantities derived *independently* of the grouping
loop, and (with --expect) against the frozen baseline.

This is an independent implementation from dump_entries.py: it reads the ZIP
central directory through the Python standard-library `zipfile` module instead
of a hand-rolled central-directory parser, so agreement between the two is a
real cross-check rather than a shared code path.

Read-only: nothing is extracted to disk. Only the standard library is used.

Usage:
    python classify_content.py <apk> [--package-id ID] [--out OUT.json]
    python classify_content.py <apk> --expect baseline.json --expect-package historical-x86_64

What the checks actually prove (and what they do not)
----------------------------------------------------

A check that compares the group sums against totals accumulated in the *same*
loop proves nothing: both sides move together by construction, so it can never
fail. This script therefore only gates its exit code on quantities that come
from somewhere else:

  eocdEntryCount        the entry count declared in the End Of Central Directory
                        record, read from raw bytes, vs. len(zipfile.infolist())
  localRegionAccounted  the local-file-header region size derived from the
                        Signing Block / central-directory offset, vs. the sum
                        over entries of 30 + nameLen + localExtraLen + compressSize
                        read directly at each entry's local header
  groupEntrySum         the grouped entry count vs. that same EOCD-declared count
  regression            (only with --expect) every group's entries/compressed/raw
                        vs. the frozen values in baseline.json

The last one is the only check that can catch a **misclassification** (e.g. a
typo in a group predicate moves 20 MB into another group without changing any
total). The first two catch truncation/parse errors. A bare internal
consistency check cannot catch either, so it is reported but never gates.

Exit code: 0 only when every gating check passes.
"""
import argparse
import hashlib
import json
import os
import re
import struct
import sys
import zipfile

# Ordered group definitions: (label, predicate). First match wins.
DEX_RE = re.compile(r"classes\d*\.dex$")

EOCD_SIG = b"PK\x05\x06"
LOCAL_SIG = b"PK\x03\x04"
SIGNING_BLOCK_MAGIC = b"APK Sig Block 42"
EOCD_MIN_SIZE = 22


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


GROUP_LABELS = [label for label, _ in _predicates()]
FALLTHROUGH_LABEL = "Manifest/metadata/other"


def classify(name, groups=None):
    """Label for one entry name. Returns None when no predicate matches and the
    caller's predicate list has no catch-all -- never silently inventing a label."""
    for label, pred in (groups or _predicates()):
        if pred(name):
            return label
    return None


# --------------------------------------------------------------------------
# Independent structural quantities (raw bytes, not the grouping loop)
# --------------------------------------------------------------------------

def find_eocd(raw):
    """(offset, declared_entries, cd_offset, cd_size) from the EOCD record."""
    start = max(0, len(raw) - 65557)
    pos = raw.rfind(EOCD_SIG, start)
    if pos < 0 or pos + EOCD_MIN_SIZE > len(raw):
        raise ValueError("no End Of Central Directory record found")
    entries = struct.unpack_from("<H", raw, pos + 10)[0]
    cd_size = struct.unpack_from("<I", raw, pos + 12)[0]
    cd_offset = struct.unpack_from("<I", raw, pos + 16)[0]
    return pos, entries, cd_offset, cd_size


def signing_block_start(raw, cd_offset):
    """Offset where the APK Signing Block begins, or cd_offset when absent."""
    if cd_offset < 24:
        return cd_offset
    if raw[cd_offset - 16:cd_offset] != SIGNING_BLOCK_MAGIC:
        return cd_offset
    size = struct.unpack_from("<Q", raw, cd_offset - 24)[0]
    start = cd_offset - 8 - size
    if start < 0:
        raise ValueError("signing block size field points before the file start")
    return start


def local_region_accounted(raw, infos):
    """Bytes occupied by local headers + their extra fields + stored payloads.

    Read at each entry's local header rather than trusting the central
    directory, so it is an independent measurement of the same region.
    """
    total = 0
    for info in infos:
        off = info.header_offset
        if raw[off:off + 4] != LOCAL_SIG:
            raise ValueError("entry %r: no local file header at %d" % (info.filename, off))
        name_len = struct.unpack_from("<H", raw, off + 26)[0]
        extra_len = struct.unpack_from("<H", raw, off + 28)[0]
        total += 30 + name_len + extra_len + info.compress_size
    return total


# --------------------------------------------------------------------------
# Regression oracle
# --------------------------------------------------------------------------

def expected_groups_from_baseline(path, package_id):
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    packages = (data.get("apkClassification") or {}).get("packages")
    if not packages:
        raise ValueError("%s has no apkClassification.packages" % path)
    for pkg in packages:
        if pkg.get("packageId") == package_id:
            return pkg["groups"]
    known = ", ".join(repr(p.get("packageId")) for p in packages)
    raise ValueError("packageId %r not in %s (known: %s)" % (package_id, path, known))


def compare_to_expected(groups, expected):
    diffs = []
    for label in sorted(set(groups) | set(expected)):
        got = groups.get(label) or {}
        want = expected.get(label) or {}
        for field in ("entries", "compressed", "raw"):
            g = got.get(field)
            w = want.get(field)
            if g != w:
                diffs.append({"group": label, "field": field, "expected": w, "actual": g})
    return {"packageMatched": not diffs, "differences": diffs}


# --------------------------------------------------------------------------

def analyze(apk_path, package_id=None, expect_path=None, expect_package=None):
    size = os.path.getsize(apk_path)
    with open(apk_path, "rb") as f:
        raw = f.read()
    sha256 = hashlib.sha256(raw).hexdigest()

    groups = {}
    for label in GROUP_LABELS:
        groups[label] = {"entries": 0, "compressed": 0, "raw": 0, "entryNames": []}

    measured_entries = measured_compressed = measured_raw = 0
    directory_entries = 0
    unclassified = []
    with zipfile.ZipFile(apk_path) as zf:
        infos = zf.infolist()
        for info in infos:
            label = classify(info.filename)
            if label is None:
                # No catch-all matched: refuse to guess, it would hide a predicate gap.
                unclassified.append(info.filename)
                continue
            g = groups[label]
            g["entries"] += 1
            g["compressed"] += info.compress_size
            g["raw"] += info.file_size
            g["entryNames"].append(info.filename)
            measured_entries += 1
            measured_compressed += info.compress_size
            measured_raw += info.file_size
            if info.is_dir():
                directory_entries += 1

    sum_entries = sum(g["entries"] for g in groups.values())
    sum_compressed = sum(g["compressed"] for g in groups.values())
    sum_raw = sum(g["raw"] for g in groups.values())

    failures = []
    try:
        eocd_offset, declared_entries, cd_offset, cd_size = find_eocd(raw)
        blk_start = signing_block_start(raw, cd_offset)
        accounted = local_region_accounted(raw, infos)
    except Exception as exc:                                   # noqa: BLE001
        checks = {"structuralParse": {"ok": False, "error": str(exc)}}
        failures.append("structuralParse: %s" % exc)
    else:
        checks = {
            "eocdEntryCount": {
                "ok": declared_entries == len(infos),
                "eocdDeclared": declared_entries,
                "zipfileListed": len(infos),
                "note": "EOCD record read from raw bytes vs. the central directory listing",
            },
            "localRegionAccounted": {
                "ok": accounted == blk_start,
                "accountedBytes": accounted,
                "regionBytes": blk_start,
                "note": "sum of 30+nameLen+localExtraLen+compressSize at each local header "
                        "vs. the region before the Signing Block / central directory",
            },
            "groupEntrySum": {
                "ok": sum_entries == declared_entries and not unclassified,
                "groupSum": sum_entries,
                "eocdDeclared": declared_entries,
                "unclassified": unclassified,
            },
        }
        for name, check in checks.items():
            if not check["ok"]:
                failures.append("%s: %s" % (name, json.dumps(
                    {k: v for k, v in check.items() if k != "note"}, ensure_ascii=False)))

    # Reported for transparency only -- these two sides move together by
    # construction and therefore cannot fail. Never gate on them.
    internal_consistency = {
        "entries": sum_entries == measured_entries,
        "compressed": sum_compressed == measured_compressed,
        "raw": sum_raw == measured_raw,
    }

    regression = None
    if expect_path:
        pkg = expect_package or package_id
        if not pkg:
            raise ValueError("--expect requires --expect-package or --package-id")
        regression = compare_to_expected(groups, expected_groups_from_baseline(expect_path, pkg))
        if not regression["packageMatched"]:
            failures.append("regression: %d group field(s) differ from %s"
                            % (len(regression["differences"]), pkg))

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
            "directoryEntries": directory_entries,
        },
        "containerOverheadBytes": size - sum_compressed,
        "checks": checks,
        "internalConsistency": internal_consistency,
        "regression": regression,
        "failures": failures,
        "allMatch": not failures,
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
    print("container overhead bytes (file - compressed payload): %d" % r["containerOverheadBytes"])
    print()
    print("gating checks (independent quantities):")
    for name, check in r["checks"].items():
        detail = " ".join("%s=%s" % (k, v) for k, v in check.items()
                          if k not in ("ok", "note", "unclassified"))
        print("  %-22s %s   %s" % (name, "PASS" if check.get("ok") else "FAIL", detail))
    if r["regression"] is not None:
        print("  %-22s %s   %s"
              % ("regression", "PASS" if r["regression"]["packageMatched"] else "FAIL",
                 r["packageId"]))
        for d in r["regression"]["differences"]:
            print("      %s.%s expected=%s actual=%s"
                  % (d["group"], d["field"], d["expected"], d["actual"]))
    print("internal consistency (does not gate; tautological by construction):")
    ic = r["internalConsistency"]
    print("  entries=%s compressed=%s raw=%s"
          % (ic["entries"], ic["compressed"], ic["raw"]))
    print()
    if r["failures"]:
        print("FAILED CHECKS:")
        for f in r["failures"]:
            print("  %s" % f)
    else:
        print("ALL GATING CHECKS PASS")
    print("allMatch: %s" % r["allMatch"])


def main(argv=None):
    p = argparse.ArgumentParser(description="Read-only APK content classifier (T03).")
    p.add_argument("apk", help="path to the APK to classify")
    p.add_argument("--package-id", default=None,
                   help="label to record as packageId (default: APK basename)")
    p.add_argument("--out", default=None, help="optional path to write the JSON result")
    p.add_argument("--expect", default=None,
                   help="baseline.json to compare against (frozen group tables)")
    p.add_argument("--expect-package", default=None,
                   help="packageId inside --expect to compare with (default: --package-id)")
    args = p.parse_args(argv)

    result = analyze(args.apk, args.package_id, args.expect, args.expect_package)
    print_summary(result)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=1)
        print("\nwrote %s" % args.out)
    return 0 if result["allMatch"] else 1


if __name__ == "__main__":
    sys.exit(main())
