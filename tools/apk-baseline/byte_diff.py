#!/usr/bin/env python3
"""T03: byte-level diff of two files, cross-referenced against APK entry spans.

Read-only. Usage: byte_diff.py <a.apk> <b.apk> <a_entries.json> <b_entries.json> <out.json>
"""
import json
import sys
import hashlib


def spans(entries_json, size):
    """Return list of (start, end, name) for entry payload regions."""
    spans = [(e["local"]["data_offset"],
              e["local"]["data_offset"] + e["compressed_size"], e["name"], e["crcc"])
             for e in entries_json["entries"]]
    spans.sort()
    return spans


def label_for(spans, off):
    for s, t, n, crc in spans:
        if s <= off < t:
            return n
    return None


def main():
    pa, pb, ja, jb, out = sys.argv[1:6]
    A = open(pa, "rb").read()
    B = open(pb, "rb").read()
    ea = json.load(open(ja, encoding="utf-8"))
    eb = json.load(open(jb, encoding="utf-8"))

    assert len(A) == len(B), "sizes differ; only equal-length comparison implemented"
    sa = spans(ea, len(A))
    sb = spans(eb, len(B))
    crc_by_name_a = {e["name"]: e["crcc"] for e in ea["entries"]}
    crc_by_name_b = {e["name"]: e["crcc"] for e in eb["entries"]}

    ranges = []
    cur = None
    for i in range(len(A)):
        if A[i] != B[i]:
            if cur is None:
                cur = [i, i + 1]
            else:
                cur[1] = i + 1
        else:
            if cur is not None:
                ranges.append(tuple(cur))
                cur = None
    if cur is not None:
        ranges.append(tuple(cur))

    total_diff = sum(t - s for s, t in ranges)
    print("file size: %d ; differing byte positions: %d ; differing runs: %d" % (len(A), total_diff, len(ranges)))

    # region classification
    def region(off):
        if off < ea["signing_block"]["block_start"] if ea["signing_block"].get("present") else len(A):
            return "entry-or-container"
        return "?"

    details = []
    for s, t in ranges:
        n_a = label_for(sa, s)
        n_b = label_for(sb, s)
        details.append({
            "start": s, "end": t, "len": t - s,
            "in_entry_A": n_a, "in_entry_B": n_b,
            "same_entry": n_a == n_b,
            "crc_equal": (crc_by_name_a.get(n_a) == crc_by_name_b.get(n_b)) if n_a and n_b else None,
        })

    # aggregate by entry name
    agg = {}
    for d in details:
        k = d["in_entry_A"] or "(container/header/signature)"
        v = agg.setdefault(k, {"runs": 0, "bytes": 0, "crc_equal": d["crc_equal"]})
        v["runs"] += 1
        v["bytes"] += d["len"]
    print("\n=== differing bytes grouped by APK region (historical entry name) ===")
    for k, v in sorted(agg.items(), key=lambda x: -x[1]["bytes"]):
        print("  %-60s runs=%-5d bytes=%-9d crc_equal=%s" % (k, v["runs"], v["bytes"], v["crc_equal"]))

    # regions outside entry payloads
    sig_start = ea["signing_block"]["block_start"] if ea["signing_block"].get("present") else None
    cd_start = ea["central_directory"]["offset"]
    cd_end = ea["central_directory"]["end"]
    zones = {
        "entry payloads": sum(v["bytes"] for k, v in agg.items() if k != "(container/header/signature)"),
        "local headers+extra (unattributed)": 0,
        "APK Signing Block": 0,
        "Central Directory": 0,
        "EOCD": 0,
    }
    for d in details:
        s = d["start"]
        if sig_start is not None and sig_start <= s < cd_start:
            zones["APK Signing Block"] += d["len"]
        elif cd_start <= s < cd_end:
            zones["Central Directory"] += d["len"]
        elif s >= cd_end:
            zones["EOCD"] += d["len"]
        elif d["in_entry_A"] is None:
            zones["local headers+extra (unattributed)"] += d["len"]
    print("\n=== differing bytes by APK structure zone ===")
    for k, v in zones.items():
        print("  %-40s %d" % (k, v))

    # verify: for entries whose CRC matches, are the compressed payload bytes identical?
    same_crc_identical = 0
    same_crc_diff = 0
    for name, crc in crc_by_name_a.items():
        if crc_by_name_b.get(name) != crc:
            continue
        ea_e = next(e for e in ea["entries"] if e["name"] == name)
        eb_e = next(e for e in eb["entries"] if e["name"] == name)
        ra = A[ea_e["local"]["data_offset"]:ea_e["local"]["data_offset"] + ea_e["compressed_size"]]
        rb = B[eb_e["local"]["data_offset"]:eb_e["local"]["data_offset"] + eb_e["compressed_size"]]
        if ra == rb:
            same_crc_identical += 1
        else:
            same_crc_diff += 1
            if same_crc_diff <= 5:
                print("  payload differs despite equal CRC:", name)
    print("\nentries with equal CRC32+equal compressed size: payload bytes identical in %d cases, differ in %d"
          % (same_crc_identical, same_crc_diff))

    # same test for the 3 changed entries
    print("\n=== changed entries: payload hashes ===")
    for name in ["META-INF/version-control-info.textproto", "assets/chaquopy/app.imy", "assets/chaquopy/build.json"]:
        ea_e = next(e for e in ea["entries"] if e["name"] == name)
        eb_e = next(e for e in eb["entries"] if e["name"] == name)
        ra = A[ea_e["local"]["data_offset"]:ea_e["local"]["data_offset"] + ea_e["compressed_size"]]
        rb = B[eb_e["local"]["data_offset"]:eb_e["local"]["data_offset"] + eb_e["compressed_size"]]
        print("  %s\n    A crc=%d csize=%d usize=%d sha256=%s\n    B crc=%d csize=%d usize=%d sha256=%s" % (
            name, ea_e["crcc"], ea_e["compressed_size"], ea_e["uncompressed_size"], hashlib.sha256(ra).hexdigest(),
            eb_e["crcc"], eb_e["compressed_size"], eb_e["uncompressed_size"], hashlib.sha256(rb).hexdigest()))

    json.dump({"ranges": details, "zones": zones, "by_region": agg},
              open(out, "w", encoding="utf-8"), indent=1)
    print("\nwrote", out)


if __name__ == "__main__":
    main()
