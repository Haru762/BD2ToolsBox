"""从 BD2ModManager.exe 里抠出内嵌的完整角色表。

`assets/character_meta.json`（性别 / 联动 / 中文名 / 头像文件名）就是这么来的。
游戏出了新角色、需要更新那份数据时，重跑这个脚本 + slim_chars.py 即可。

    python tools/extract_chars.py [BD2ModManager.exe 的路径]

默认找 D:/BD2ModManager/BD2ModManager.exe。那是 bruhnn 做的 PC 端 mod 管理器
（Tauri / Rust），characters.json 以明文嵌在二进制里，紧跟在
"Seeding characters.json to appdata" 这句日志文案后面。

为什么不联网取：那个项目原先在 GitHub 上有 characters.csv，manifest_v2.json 里
还写着它的地址，但仓库已经改成 Vue 前端项目，那个路径现在是 404。
"""

import json
import os
import re
import sys

DEFAULT_EXE = r"D:/BD2ModManager/BD2ModManager.exe"

# 版本号会随上游更新变化，所以按模式找而不是写死 "0.0.24"
NEEDLE = re.compile(rb'\{\r?\n\s*"version":\s*"\d+\.\d+\.\d+",\r?\n\s*"characters":\s*\[')

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "chars_full.json")


def extract(data, start):
    """从 start 处做括号配平，跳过字符串内容，返回 JSON 的字节切片。"""
    depth = 0
    i = start
    in_str = False
    esc = False
    while i < len(data):
        c = data[i]
        if in_str:
            if esc:
                esc = False
            elif c == 0x5C:      # 反斜杠
                esc = True
            elif c == 0x22:      # 引号
                in_str = False
        else:
            if c == 0x22:
                in_str = True
            elif c == 0x7B:      # {
                depth += 1
            elif c == 0x7D:      # }
                depth -= 1
                if depth == 0:
                    return data[start:i + 1]
        i += 1
    return None


def main():
    exe = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_EXE
    if not os.path.exists(exe):
        print(f"找不到 {exe}", file=sys.stderr)
        print("用法: python tools/extract_chars.py [BD2ModManager.exe 的路径]", file=sys.stderr)
        return 1

    data = open(exe, "rb").read()
    m = NEEDLE.search(data)
    if not m:
        print("没找到内嵌角色表（上游可能换了打包方式）", file=sys.stderr)
        return 1
    at = m.start()
    print(f"起始偏移 {at}")

    raw = extract(data, at)
    if raw is None:
        print("括号没配平，可能被截断", file=sys.stderr)
        return 1
    print(f"长度 {len(raw)} 字节")

    obj = json.loads(raw.decode("utf-8"))
    chars = obj["characters"]
    print(f"version {obj['version']}，{len(chars)} 条")
    print("字段:", list(chars[0].keys()))

    genders = {}
    stats = {"collab": 0, "dating": 0, "img": 0, "cn": 0}
    names = set()
    for c in chars:
        genders[c.get("gender")] = genders.get(c.get("gender"), 0) + 1
        if c.get("is_collab"):
            stats["collab"] += 1
        if c.get("dating_id"):
            stats["dating"] += 1
        if c.get("character_image"):
            stats["img"] += 1
        if (c.get("character_name") or {}).get("cn"):
            stats["cn"] += 1
        if c.get("character"):
            names.add(c["character"])

    print(f"gender 分布: {genders}")
    print(f"联动 {stats['collab']} 条，有 dating_id {stats['dating']} 条，"
          f"有头像图 {stats['img']} 条，有中文名 {stats['cn']} 条")
    print(f"不同角色数: {len(names)}")

    with open(OUT, "wb") as f:
        f.write(raw)
    print(f"已写出 {OUT}")
    print("接着跑: python tools/slim_chars.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
