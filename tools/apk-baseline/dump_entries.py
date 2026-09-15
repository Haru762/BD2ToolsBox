#!/usr/bin/env python3
"""T03 read-only APK entry-level extractor.

Parses the ZIP central directory + local file headers + APK Signing Block of an
APK without extracting anything to disk. Outputs JSON.

Usage: python dump_entries.py <apk> <out.json>
"""
import json
import struct
import sys
import zlib
import hashlib

EOCD_SIG = b"PK\x05\x06"
EOCD64_LOCATOR_SIG = b"PK\x06\x07"
EOCD64_SIG = b"PK\x06\x06"
CD_SIG = b"PK\x01\x02"
LFH_SIG = b"PK\x03\x04"
APK_SIG_MAGIC = b"APK Sig Block 42"


def find_eocd(data):
    # EOCD is at most 64KiB + 22 bytes from EOF (comment field)
    n = len(data)
    start = max(0, n - 65557)
    idx = data.rfind(EOCD_SIG, start)
    if idx < 0:
        raise SystemExit("EOCD not found")
    return idx


def parse_eocd(data, off):
    (sig, disk, cd_disk, cd_entries_disk, cd_entries,
     cd_size, cd_off, comment_len) = struct.unpack_from("<IHHHHIIH", data, off)
    return {
        "eocd_offset": off,
        "disk": disk,
        "cd_disk": cd_disk,
        "cd_entries_disk": cd_entries_disk,
        "cd_entries": cd_entries,
        "cd_size": cd_size,
        "cd_offset": cd_off,
        "comment_len": comment_len,
        "comment": data[off + 22: off + 22 + comment_len].decode("latin-1"),
        # where the container content (entries) ends
        "content_end": off,
    }


def parse_zip64(data, eocd):
    loc = data.rfind(EOCD64_LOCATOR_SIG, 0, eocd["eocd_offset"])
    out = {}
    if loc >= 0:
        (_, disk, z64_off, total_disks) = struct.unpack_from("<IIQI", data, loc)
        out["locator_offset"] = loc
        out["zip64_eocd_offset"] = z64_off
        if data[z64_off:z64_off + 4] == EOCD64_SIG:
            (_, size, vermade, verneed, disk, cd_disk, ent_disk, ent_total,
             cd_size, cd_off) = struct.unpack_from("<IQHHIIQQQQ", data, z64_off)
            out.update({
                "zip64_cd_entries": ent_total,
                "zip64_cd_size": cd_size,
                "zip64_cd_offset": cd_off,
            })
    return out


def parse_extra(extra):
    """Return list of (header_id, size, hex payload)."""
    out = []
    i = 0
    while i + 4 <= len(extra):
        hid, sz = struct.unpack_from("<HH", extra, i)
        payload = extra[i + 4:i + 4 + sz]
        out.append({"id": hid, "size": sz, "hex": payload.hex()})
        i += 4 + sz
    return out


def parse_signing_block(data, cd_offset):
    """APK Signing Block sits immediately before the central directory."""
    if cd_offset < 32:
        return None
    # The block ends right before CD; last 24 bytes are size(8)+magic(16)
    magic_off = cd_offset - 16
    if data[magic_off:magic_off + 16] != APK_SIG_MAGIC:
        return {"present": False}
    block_size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
    block_start = cd_offset - (block_size + 8)
    if block_start < 0 or struct.unpack_from("<Q", data, block_start)[0] != block_size:
        # size field mismatch; still report what we have
        pass
    pairs = []
    p = block_start + 8
    end = cd_offset - 24
    while p + 12 <= end:
        pair_len = struct.unpack_from("<Q", data, p)[0]
        if pair_len < 4 or p + 8 + pair_len > end:
            pairs.append({"error": "bad pair length", "at": p, "len": pair_len})
            break
        id_size = struct.unpack_from("<I", data, p + 8)[0]
        if 4 + id_size > pair_len:
            pairs.append({"error": "bad id size", "at": p, "id_size": id_size})
            break
        pair_id = data[p + 12:p + 12 + id_size]
        value = data[p + 12 + id_size:p + 8 + pair_len]
        pairs.append({
            "id_u32": struct.unpack_from("<I", pair_id[:4].ljust(4, b"\0"))[0],
            "id_ascii": pair_id.decode("latin-1", "replace"),
            "value_len": len(value),
            "value_sha256": hashlib.sha256(value).hexdigest(),
            "value_head_hex": value[:32].hex(),
        })
        p += 8 + pair_len
    return {
        "present": True,
        "magic_at": magic_off,
        "block_size_field": block_size,
        "block_start": block_start,
        "block_end": cd_offset,
        "pairs": pairs,
        "block_sha256": hashlib.sha256(data[block_start:cd_offset]).hexdigest(),
    }


def main():
    apk_path, out_path = sys.argv[1], sys.argv[2]
    with open(apk_path, "rb") as f:
        data = f.read()
    total = len(data)

    eocd = parse_eocd(data, find_eocd(data))
    z64 = parse_zip64(data, eocd)
    cd_off = z64.get("zip64_cd_offset", eocd["cd_offset"])
    cd_size = z64.get("zip64_cd_size", eocd["cd_size"])
    n_entries = z64.get("zip64_cd_entries", eocd["cd_entries"])

    entries = []
    p = cd_off
    for _ in range(n_entries):
        if data[p:p + 4] != CD_SIG:
            raise SystemExit("bad CD signature at %d" % p)
        (sig, vermade, verneed, flags, method, mtime, mdate, crc, csize, usize,
         nlen, elen, clen, disk_start, iattr, eattr, lho) = struct.unpack_from(
            "<IHHHHHHIIIHHHHHII", data, p)
        name = data[p + 46:p + 46 + nlen]
        extra = data[p + 46 + nlen:p + 46 + nlen + elen]
        comment = data[p + 46 + nlen + elen:p + 46 + nlen + elen + clen]
        entries.append({
            "name": name.decode("utf-8", "replace"),
            "crcc": crc,
            "compressed_size": csize,
            "uncompressed_size": usize,
            "method": method,
            "flags": flags,
            "mod_time": mtime,
            "mod_date": mdate,
            "version_made_by": vermade,
            "version_needed": verneed,
            "external_attr": eattr,
            "internal_attr": iattr,
            "local_header_offset": lho,
            "cd_extra_hex": extra.hex(),
            "cd_extra_fields": parse_extra(extra),
            "cd_comment_len": clen,
            "cd_entry_offset": p,
        })
        p += 46 + nlen + elen + clen

    cd_end = p

    # local headers
    for e in entries:
        lho = e["local_header_offset"]
        (lsig, lver, lflags, lmethod, ltime, ldate, lcrc, lcsize, lusize,
         lnlen, lelen) = struct.unpack_from("<IHHHHHIIIHH", data, lho)
        lname = data[lho + 30:lho + 30 + lnlen]
        lextra = data[lho + 30 + lnlen:lho + 30 + lnlen + lelen]
        e["local"] = {
            "sig_ok": lsig == 0x04034B50,
            "name": lname.decode("utf-8", "replace"),
            "crc_field": lcrc,
            "compressed_size_field": lcsize,
            "uncompressed_size_field": lusize,
            "method": lmethod,
            "flags": lflags,
            "mod_time": ltime,
            "mod_date": ldate,
            "extra_hex": lextra.hex(),
            "extra_fields": parse_extra(lextra),
            "extra_len": lelen,
            "data_offset": lho + 30 + lnlen + lelen,
        }

    # map [data_offset, data_offset+csize) -> entry for overlap/gap analysis
    spans = sorted(((e["local"]["data_offset"],
                     e["local"]["data_offset"] + e["compressed_size"], e["name"])
                    for e in entries), key=lambda x: x[0])
    gaps = []
    prev_end = 0
    prev_name = "<file start>"
    for s, t, nm in spans:
        if s > prev_end:
            gaps.append({"at": prev_end, "len": s - prev_end, "after": prev_name, "before": nm})
        elif s < prev_end:
            gaps.append({"overlap": True, "at": s, "after": prev_name, "before": nm})
        if t > prev_end:
            prev_end, prev_name = t, nm

    sign_block = parse_signing_block(data, cd_off)

    result = {
        "path": apk_path.replace("\\", "/"),
        "bytes": total,
        "sha256": hashlib.sha256(data).hexdigest(),
        "eocd": eocd,
        "zip64": z64,
        "central_directory": {"offset": cd_off, "size": cd_size,
                              "end": cd_end, "declared_entries": n_entries},
        "entries": entries,
        "entry_count": len(entries),
        "overhead": {
            # bytes not covered by any entry payload: local headers + extras + CD + signing block + EOCD
            "local_headers_total": sum(30 + len(e["local"]["extra_hex"]) // 2
                                       + len(e["name"].encode()) for e in entries),
            "cd_total": cd_end - cd_off,
            "eocd_total": total - eocd["eocd_offset"],
            "signing_block_total": (cd_off - sign_block["block_start"]) if sign_block and sign_block.get("present") else 0,
            "gap_bytes": sum(g["len"] for g in gaps if "len" in g and not g.get("overlap")),
        },
        "gaps": gaps,
        "signing_block": sign_block,
        "first_content_offset": min(s for s, _, _ in spans) if spans else 0,
        "content_end_from_headers": max(t for _, t, _ in spans) if spans else 0,
        "compressed_total": sum(e["compressed_size"] for e in entries),
        "uncompressed_total": sum(e["uncompressed_size"] for e in entries),
        "stored_entries": sum(1 for e in entries if e["method"] == 0),
        "deflated_entries": sum(1 for e in entries if e["method"] == 8),
    }
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    print(json.dumps({
        "path": result["path"], "bytes": total, "entries": len(entries),
        "sha256": result["sha256"],
        "compressed_total": result["compressed_total"],
        "cd": [cd_off, cd_size], "eocd_off": eocd["eocd_offset"],
        "signing_block": (sign_block or {}).get("present"),
    }, indent=1))


if __name__ == "__main__":
    main()
