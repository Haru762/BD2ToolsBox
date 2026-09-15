#!/usr/bin/env python3
"""T03: compare two APK entry dumps produced by dump_entries.py (read-only)."""
import json
import sys

FIELDS = ["crcc", "compressed_size", "uncompressed_size", "method", "flags",
          "mod_time", "mod_date", "version_made_by", "version_needed",
          "external_attr", "internal_attr", "local_header_offset",
          "cd_extra_hex", "cd_entry_offset"]


def load(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def main():
    a, b = load(sys.argv[1]), load(sys.argv[2])
    label_a = sys.argv[3] if len(sys.argv) > 3 else "A"
    label_b = sys.argv[4] if len(sys.argv) > 4 else "B"
    ea = {e["name"]: e for e in a["entries"]}
    eb = {e["name"]: e for e in b["entries"]}

    only_a = sorted(set(ea) - set(eb))
    only_b = sorted(set(eb) - set(ea))
    common = sorted(set(ea) & set(eb))

    print("=== %s (%d entries) vs %s (%d entries) ===" % (label_a, a["entry_count"], label_b, b["entry_count"]))
    print("bytes: %d vs %d  (delta %+d)" % (a["bytes"], b["bytes"], b["bytes"] - a["bytes"]))
    print("compressed_total: %d vs %d (delta %+d)" % (a["compressed_total"], b["compressed_total"], b["compressed_total"] - a["compressed_total"]))
    print("uncompressed_total: %d vs %d (delta %+d)" % (a["uncompressed_total"], b["uncompressed_total"], b["uncompressed_total"] - a["uncompressed_total"]))
    print("entry name sets identical: %s" % (only_a == [] and only_b == []))
    if only_a:
        print("ONLY IN %s: %r" % (label_a, only_a))
    if only_b:
        print("ONLY IN %s: %r" % (label_b, only_b))

    diffs = []
    for n in common:
        x, y = ea[n], eb[n]
        d = {k: (x[k], y[k]) for k in FIELDS if x[k] != y[k]}
        lx, ly = x["local"], y["local"]
        for k in ["crc_field", "compressed_size_field", "uncompressed_size_field",
                  "method", "flags", "mod_time", "mod_date", "extra_hex", "data_offset"]:
            if lx[k] != ly[k]:
                d["local." + k] = (lx[k], ly[k])
        if d:
            diffs.append((n, d))

    print("\nentries differing in metadata or offsets: %d / %d" % (len(diffs), len(common)))

    # classify which fields differ, and their frequency
    freq = {}
    for n, d in diffs:
        for k in d:
            freq[k] = freq.get(k, 0) + 1
    print("field difference frequency:", json.dumps(freq, indent=1))

    # content identity: CRC + uncompressed size + method => payload identity (barring CRC collision)
    content_diffs = [(n, d) for n, d in diffs if "crcc" in d or "uncompressed_size" in d or "method" in d]
    print("\nentries with different content (crc/usize/method): %d" % len(content_diffs))
    for n, d in content_diffs[:50]:
        print("  CONTENT", n, json.dumps(d, default=str))

    # compressed-size-only differences (same content, different deflate output)
    csize_diffs = [(n, d) for n, d in diffs if "compressed_size" in d and not ("crcc" in d or "uncompressed_size" in d or "method" in d)]
    print("\nentries with same content but different compressed_size: %d, sum delta %+d" % (
        len(csize_diffs), sum(d["compressed_size"][1] - d["compressed_size"][0] for _, d in csize_diffs)))
    for n, d in csize_diffs[:60]:
        print("   %-70s %10d -> %10d  (%+d)" % (n, d["compressed_size"][0], d["compressed_size"][1],
                                                d["compressed_size"][1] - d["compressed_size"][0]))

    # report CD extra field differences
    cd_extra = [(n, d) for n, d in diffs if "cd_extra_hex" in d]
    print("\nentries with different central-directory extra field: %d" % len(cd_extra))
    for n, d in cd_extra[:20]:
        print("   %s\n      A=%s\n      B=%s" % (n, d["cd_extra_hex"][0] or "(empty)", d["cd_extra_hex"][1] or "(empty)"))

    lh_extra = [(n, d) for n, d in diffs if "local.extra_hex" in d]
    print("\nentries with different local-header extra field: %d" % len(lh_extra))
    for n, d in lh_extra[:20]:
        print("   %s\n      A=%s\n      B=%s" % (n, d["local.extra_hex"][0] or "(empty)", d["local.extra_hex"][1] or "(empty)"))

    # local header offsets: report total relative shift
    off = [(n, d) for n, d in diffs if "local_header_offset" in d]
    print("\nentries whose local_header_offset changed: %d" % len(off))
    if off:
        shifts = sorted(set(d["local_header_offset"][1] - d["local_header_offset"][0] for _, d in off))
        print("   distinct shifts: %r" % shifts[:20])

    # container comparison
    print("\n=== container ===")
    for k in ["compressed_total", "uncompressed_total", "stored_entries", "deflated_entries",
              "first_content_offset", "content_end_from_headers"]:
        print("  %-26s %14s %14s  delta %+d" % (k, a[k], b[k], b[k] - a[k]))
    print("  eocd:", a["eocd"]["eocd_offset"], b["eocd"]["eocd_offset"])
    print("  cd:  ", a["central_directory"], b["central_directory"])
    print("  overhead A:", a["overhead"])
    print("  overhead B:", b["overhead"])
    print("\n  signing block A:", json.dumps(a["signing_block"], indent=1))
    print("  signing block B:", json.dumps(b["signing_block"], indent=1))
    print("\n  gaps A (first 10):", a["gaps"][:10])
    print("  gaps B (first 10):", b["gaps"][:10])
    print("  gaps A count %d total %d | gaps B count %d total %d" % (
        len(a["gaps"]), sum(g.get("len", 0) for g in a["gaps"]),
        len(b["gaps"]), sum(g.get("len", 0) for g in b["gaps"])))


if __name__ == "__main__":
    main()
