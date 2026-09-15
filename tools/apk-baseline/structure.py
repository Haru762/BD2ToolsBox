#!/usr/bin/env python3
"""T03: APK structural comparison: EOCD, central directory, APK Signing Block,
and per-entry payload identity. Uses dumps from dump_entries.py. Read-only.

Usage: structure.py <a.apk> <b.apk> <a.json> <b.json>
"""
import hashlib
import json
import struct
import sys

APK_SIG_MAGIC = b"APK Sig Block 42"

ID_NAMES = {
    0x7109871A: "v2 signature scheme block",
    0xF05368C0: "v3 signature scheme block",
    0x42726577: "verity padding block ('Brew')",
    0x504B4453: "dependency block ('PKDS')",
    0x6DFF8006: "source stamp (v1.x)",
    0x2B09189E: "v4.1 signing block?",
}


def read_pair(seq, off):
    """Read one length-prefixed (uint32 len, bytes) element."""
    n = struct.unpack_from("<I", seq, off)[0]
    return seq[off + 4:off + 4 + n], off + 4 + n


def parse_lenpref(buf, off=0):
    n = struct.unpack_from("<I", buf, off)[0]
    return buf[off + 4:off + 4 + n], off + 4 + n


def parse_v2_signer(value):
    """Parse the v2 signer sequence inside an ID-value pair value."""
    out = {}
    signers = []
    off = 0
    count = struct.unpack_from("<I", value, 0)[0]
    off = 4
    for _ in range(count):
        signer, off = parse_lenpref(value, off)
        sd, o = parse_lenpref(signer, 0)
        digests = []
        dcount = struct.unpack_from("<I", sd, 0)[0]
        oo = 4
        for _ in range(dcount):
            algo = struct.unpack_from("<I", sd, oo)[0]
            dg, oo = parse_lenpref(sd, oo + 4)
            digests.append({"algo": algo, "len": len(dg), "sha256": hashlib.sha256(dg).hexdigest()})
        certs = []
        cc, oo = parse_lenpref(sd, oo)
        oo2 = 0
        for _ in range(struct.unpack_from("<I", cc, 0)[0]):
            c, oo2 = parse_lenpref(cc, oo2 + 4)
            certs.append({"len": len(c), "sha256": hashlib.sha256(c).hexdigest()})
        # additional attributes
        attrs, oo = parse_lenpref(sd, oo)
        signers.append({
            "digests": digests,
            "certificates": certs,
            "additional_attributes_len": len(attrs),
            "signed_data_sha256": hashlib.sha256(sd).hexdigest(),
        })
        # signatures
        sigseq, o2 = parse_lenpref(signer, o)
        sigs = []
        so = 4
        for _ in range(struct.unpack_from("<I", sigseq, 0)[0]):
            algo = struct.unpack_from("<I", sigseq, so)[0]
            sg, so = parse_lenpref(sigseq, so + 4)
            sigs.append({"algo": algo, "len": len(sg), "sha256": hashlib.sha256(sg).hexdigest()})
        signers[-1]["signatures"] = sigs
        pk, o2 = parse_lenpref(signer, o2)
        signers[-1]["public_key_len"] = len(pk)
        signers[-1]["public_key_sha256"] = hashlib.sha256(pk).hexdigest()
    out["signers"] = signers
    return out


def parse_signing_block(data, cd_offset):
    magic_off = cd_offset - 16
    if data[magic_off:magic_off + 16] != APK_SIG_MAGIC:
        return {"present": False}
    block_size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
    block_start = cd_offset - (block_size + 8)
    pairs = []
    p = block_start + 8
    end = cd_offset - 24
    while p + 12 <= end:
        plen = struct.unpack_from("<Q", data, p)[0]
        pid = struct.unpack_from("<I", data, p + 8)[0]
        value = data[p + 12:p + 8 + plen]
        entry = {
            "id_u32": pid,
            "id_hex": "0x%08X" % pid,
            "id_name": ID_NAMES.get(pid, "unknown"),
            "value_len": len(value),
            "value_sha256": hashlib.sha256(value).hexdigest(),
        }
        if pid in (0x7109871A, 0xF05368C0):
            try:
                entry["parsed"] = parse_v2_signer(value)
            except Exception as e:  # noqa: BLE001
                entry["parse_error"] = repr(e)
        pairs.append(entry)
        p += 8 + plen
    return {
        "present": True,
        "block_start": block_start,
        "block_end": cd_offset,
        "block_total_bytes": cd_offset - block_start,
        "block_size_field": block_size,
        "block_sha256": hashlib.sha256(data[block_start:cd_offset]).hexdigest(),
        "gap_magic_and_size": cd_offset - 24 - (p),
        "pairs": pairs,
    }


def main():
    pa, pb, ja, jb = sys.argv[1:5]
    A = open(pa, "rb").read()
    B = open(pb, "rb").read()
    ea = json.load(open(ja, encoding="utf-8"))
    eb = json.load(open(jb, encoding="utf-8"))

    print("=== file sizes ===\n  A %d\n  B %d\n  delta %+d" % (len(A), len(B), len(B) - len(A)))

    # --- EOCD ---
    sa, sb = ea["eocd"]["eocd_offset"], eb["eocd"]["eocd_offset"]
    eocd_a, eocd_b = A[sa:], B[sb:]
    print("\n=== EOCD (offset %d / %d, %d bytes) bytes identical: %s sha256 %s / %s" % (
        sa, sb, len(eocd_a), eocd_a == eocd_b,
        hashlib.sha256(eocd_a).hexdigest()[:16], hashlib.sha256(eocd_b).hexdigest()[:16]))

    # --- Central directory ---
    ca, cb = ea["central_directory"], eb["central_directory"]
    cda = A[ca["offset"]:ca["end"]]
    cdb = B[cb["offset"]:cb["end"]]
    print("\n=== Central Directory ===\n  offset %d/%d size %d/%d identical: %s" % (
        ca["offset"], cb["offset"], len(cda), len(cdb), cda == cdb))
    runs = []
    cur = None
    for i in range(min(len(cda), len(cdb))):
        if cda[i] != cdb[i]:
            cur = [i, i + 1] if cur is None else [cur[0], i + 1]
        elif cur is not None:
            runs.append(tuple(cur))
            cur = None
    if cur:
        runs.append(tuple(cur))
    print("  differing runs: %d, total bytes %d" % (len(runs), sum(t - s for s, t in runs)))
    # map to entries
    for s, t in runs:
        # find entry whose CD record contains this offset
        hit = [e["name"] for e in ea["entries"] if e["cd_entry_offset"] <= ca["offset"] + s < e["cd_entry_offset"] + 46 + len(e["name"].encode()) + len(e["cd_extra_hex"]) // 2]
        field = []
        for e in ea["entries"]:
            base = e["cd_entry_offset"] - ca["offset"]
            if base <= s:
                # 16 bytes reserved, ... local header offset at CD+42
                if s < base + 46:
                    field.append("header-field@%d" % s)
            if base + 42 <= s < base + 46:
                field.append("local_header_offset(%s)" % e["name"])
        print("   run [%d,%d) len %d  entry=%r  fields=%r" % (s, t, t - s, hit, field))

    # --- Signing block ---
    sb_a = parse_signing_block(A, ca["offset"])
    sb_b = parse_signing_block(B, cb["offset"])
    print("\n=== APK Signing Block ===")
    for tag, s in (("A", sb_a), ("B", sb_b)):
        print("  %s: start=%d end=%d total=%d size_field=%d sha256=%s pairs=%d" % (
            tag, s["block_start"], s["block_end"], s["block_total_bytes"],
            s["block_size_field"], s["block_sha256"][:16], len(s["pairs"])))
        for pr in s["pairs"]:
            print("     %s %-32s value_len=%-10d sha256=%s" % (pr["id_hex"], pr["id_name"], pr["value_len"], pr["value_sha256"][:16]))

    print("\n  --- pair-by-pair comparison ---")
    ids_a = [p["id_u32"] for p in sb_a.get("pairs", [])]
    ids_b = [p["id_u32"] for p in sb_b.get("pairs", [])]
    print("  id sequences equal: %s" % (ids_a == ids_b))
    for pra, prb in zip(sb_a.get("pairs", []), sb_b.get("pairs", [])):
        same = pra["value_sha256"] == prb["value_sha256"]
        print("   %s %-32s same=%s len %d/%d" % (pra["id_hex"], pra["id_name"], same, pra["value_len"], prb["value_len"]))
        if pra.get("parsed") and prb.get("parsed"):
            for i, (sa_, sb_) in enumerate(zip(pra["parsed"]["signers"], prb["parsed"]["signers"])):
                print("      signer[%d]: signed_data same=%s" % (
                    i, sa_["signed_data_sha256"] == sb_["signed_data_sha256"]))
                for da, db in zip(sa_["digests"], sb_["digests"]):
                    print("        digest algo=0x%08X len=%d/%d same=%s" % (
                        da["algo"], da["len"], db["len"], da["sha256"] == db["sha256"]))
                for cda_, cdb_ in zip(sa_["certificates"], sb_["certificates"]):
                    print("        cert len=%d/%d same=%s sha256=%s" % (
                        cda_["len"], cdb_["len"], cda_["sha256"] == cdb_["sha256"], cda_["sha256"][:16]))
                for sga, sgb in zip(sa_["signatures"], sb_["signatures"]):
                    print("        signature algo=0x%08X len=%d/%d same=%s" % (
                        sga["algo"], sga["len"], sgb["len"], sga["sha256"] == sgb["sha256"]))
                print("        public_key same=%s len=%d/%d" % (
                    sa_["public_key_sha256"] == sb_["public_key_sha256"], sa_["public_key_len"], sb_["public_key_len"]))

    # --- structural accounting ---
    print("\n=== structural accounting (bytes) ===")
    for tag, e, sb_ in (("A", ea, sb_a), ("B", eb, sb_b)):
        lh = sum(30 + len(x["name"].encode()) + len(x["local"]["extra_hex"]) // 2 for x in e["entries"])
        payload = e["compressed_total"]
        cd = e["central_directory"]["size"]
        eocd = e["bytes"] - e["eocd"]["eocd_offset"]
        sig = sb_["block_total_bytes"]
        total = lh + payload + sig + cd + eocd
        print("  %s: local-headers=%d payload=%d signing=%d cd=%d eocd=%d  SUM=%d  FILE=%d  match=%s" % (
            tag, lh, payload, sig, cd, eocd, total, e["bytes"], total == e["bytes"]))

    # --- per-entry payload identity ---
    print("\n=== per-entry payload byte comparison (matched by name) ===")
    ea_by = {e["name"]: e for e in ea["entries"]}
    eb_by = {e["name"]: e for e in eb["entries"]}
    ident = 0
    diff = []
    for n in sorted(ea_by):
        x, y = ea_by[n], eb_by[n]
        ra = A[x["local"]["data_offset"]:x["local"]["data_offset"] + x["compressed_size"]]
        rb = B[y["local"]["data_offset"]:y["local"]["data_offset"] + y["compressed_size"]]
        if ra == rb:
            ident += 1
        else:
            diff.append((n, x, y, ra, rb))
    print("  identical payloads: %d / %d" % (ident, len(ea_by)))
    print("  differing payloads: %d" % len(diff))
    for n, x, y, ra, rb in diff:
        print("   %s\n     A csize=%d usize=%d crc=%d method=%d sha256=%s\n     B csize=%d usize=%d crc=%d method=%d sha256=%s" % (
            n, x["compressed_size"], x["uncompressed_size"], x["crcc"], x["method"], hashlib.sha256(ra).hexdigest(),
            y["compressed_size"], y["uncompressed_size"], y["crcc"], y["method"], hashlib.sha256(rb).hexdigest()))


if __name__ == "__main__":
    main()
