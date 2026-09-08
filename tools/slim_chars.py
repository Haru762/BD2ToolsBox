"""把 extract_chars.py 抠出的角色表精简成随 APK 打包的那份。

    python tools/slim_chars.py

原始 257 KB 里有一多半用不到：jp/kr/tw 译名、skill_preview_image、release_date 等。
只留界面真正会用的字段 —— 解析更快，APK 也不用白背 200 KB（52 KB vs 258 KB）。

输出 app/src/main/assets/character_meta.json，由 CharacterMetaRepository 读取。
"""

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "chars_full.json")
DST = os.path.join(HERE, "..", "app", "src", "main", "assets", "character_meta.json")


def main():
    if not os.path.exists(SRC):
        print(f"找不到 {SRC}，先跑 python tools/extract_chars.py", file=sys.stderr)
        return 1

    obj = json.load(open(SRC, encoding="utf-8"))
    out = []
    for c in obj["characters"]:
        assets = c.get("assets") or {}
        out.append({
            "cid": c.get("costume_id") or c.get("id") or "",
            "char": c.get("character") or "",
            "charCn": (c.get("character_name") or {}).get("cn") or "",
            "costume": c.get("costume") or "",
            "costumeCn": (c.get("costume_name") or {}).get("cn") or "",
            "gender": c.get("gender") or "",
            "collab": bool(c.get("is_collab")),
            "dating": c.get("dating_id") or "",
            # head   = 方形头像图标（约 10 KB），角色列表里用
            # illust = 立绘缩略图（约 70 KB），角色卡片每行用
            "head": assets.get("head_image") or "",
            "illust": assets.get("character_image") or c.get("character_image") or "",
            "element": c.get("element") or "",
            "grade": c.get("grade") or 0,
            "skin": c.get("skin_type") or "",
        })

    payload = {
        "dataVersion": obj.get("version", ""),
        "source": "BD2ModManager (bruhnn) 内嵌角色表",
        "count": len(out),
        "costumes": out,
    }
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    os.makedirs(os.path.dirname(DST), exist_ok=True)
    with open(DST, "w", encoding="utf-8") as f:
        f.write(raw)

    print(f"{len(out)} 条 -> {os.path.normpath(DST)}")
    print(f"体积 {len(raw.encode('utf-8'))} 字节（原 {os.path.getsize(SRC)}）")
    chars = {c["char"] for c in out if c["char"]}
    print(f"角色 {len(chars)} 个，"
          f"男 {sum(1 for c in out if c['gender'] == 'male')} 条，"
          f"女 {sum(1 for c in out if c['gender'] == 'female')} 条，"
          f"联动 {sum(1 for c in out if c['collab'])} 条，"
          f"心契 {sum(1 for c in out if c['dating'])} 条")
    print(f"有头像图 {sum(1 for c in out if c['head'])} 条，"
          f"有立绘图 {sum(1 for c in out if c['illust'])} 条")
    return 0


if __name__ == "__main__":
    sys.exit(main())
