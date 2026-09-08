"""图集合并自救工具。

装进游戏后贴图错乱（多半是 mod 的贴图页数和原版对不上）时用的修复流程：
把 mod 目录里的贴图两两横向拼成一张，同时重写 .atlas——第二张图的精灵
bounds 整体右移。原文件全部搬进 .old 子目录备份，可随时还原。

atlas 的解析复用 utils.atlas_operations（与 repacker 共用同一个解析器，
不再各养一份）。
"""
import glob
import os
import re
import shutil

from PIL import Image

from utils.atlas_operations import parse_atlas_file

# 返回给上层的结论字符串（界面直接展示，保持原文）
NO_ATLAS = "SKIPPING: No .atlas file found."
NO_OPERATIONS = "SKIPPING: No image operations to perform."
ATLAS_UNPARSABLE = "FAILED: Could not parse atlas file."


def _sorted_page_images(mod_dir, prefix, report):
    """列出这组贴图的所有 png，按「主图在前、_N 按编号」排序。

    排不进任何一类的（前缀碰巧相同的杂图）沉底，保持 glob 原序。
    """
    names = [os.path.basename(p) for p in glob.glob(os.path.join(mod_dir, f'{prefix}*.png'))]

    def order(name):
        if name == f"{prefix}.png":
            return 1
        m = re.search(r'_(\d+)\.png$', name)
        return int(m.group(1)) if m else float('inf')

    names.sort(key=order)
    report(f"  Found and sorted {len(names)} image files: {names}")
    return names


def _plan(pages, prefix):
    """排好序的贴图两两配对，产出 [(源文件名列表, 输出文件名), ...]。

    第 1 组输出 <prefix>.png，第 2 组 <prefix>_2.png，依此类推；
    凑不齐对的那组只含一张，走复制而不是拼接。
    """
    plan = []
    i = 0
    group = 1
    while i < len(pages):
        output = f"{prefix}.png" if group == 1 else f"{prefix}_{group}.png"
        if i + 1 < len(pages):
            plan.append(([pages[i], pages[i + 1]], output))
            i += 2
        else:
            plan.append(([pages[i]], output))
            i += 1
        group += 1
    return plan


def _shift_bounds(sprites, offset):
    """精灵行的 bounds 整体右移 offset 像素；不是 bounds 或解析不了的行原样保留。"""
    moved = []
    for line in sprites.split('\n'):
        s = line.strip()
        if s.startswith('bounds:'):
            try:
                x, y, w, h = [int(c.strip()) for c in s.split(':', 1)[1].split(',')]
                moved.append(f" bounds: {x + offset},{y},{w},{h}")
                continue
            except (ValueError, IndexError):
                pass
        moved.append(line)
    return '\n'.join(moved)


def run(mod_dir_path, progress_callback=print):
    """就地处理一个 mod 目录，返回给用户看的结论字符串。"""
    report = progress_callback if progress_callback else print
    report(f"\n--- Processing Mod Directory: {mod_dir_path} ---")

    atlas_candidates = glob.glob(os.path.join(mod_dir_path, '*.atlas'))
    if not atlas_candidates:
        return NO_ATLAS
    atlas_path = atlas_candidates[0]
    prefix = os.path.splitext(os.path.basename(atlas_path))[0]

    pages = _sorted_page_images(mod_dir_path, prefix, report)
    plan = _plan(pages, prefix)
    if not plan:
        return NO_OPERATIONS

    atlas_db, err = parse_atlas_file(atlas_path)
    if not atlas_db:
        report(f"  FATAL: {err or 'empty atlas'}")
        return ATLAS_UNPARSABLE

    # 原件（atlas + 全部贴图）搬进 .old 备份
    old_dir = os.path.join(mod_dir_path, ".old")
    os.makedirs(old_dir, exist_ok=True)
    shutil.move(atlas_path, os.path.join(old_dir, os.path.basename(atlas_path)))
    for name in pages:
        shutil.move(os.path.join(mod_dir_path, name), os.path.join(old_dir, name))
    report(f"  Created backup directory: {old_dir}")
    report(f"  Moved original atlas and {len(pages)} PNG files to .old directory.")
    report("  Generated the following operations:")
    for sources, output in plan:
        report(f"    - {'merge' if len(sources) == 2 else 'copy'}: {sources} -> {output}")

    blocks = []
    for sources, output in plan:
        out_path = os.path.join(mod_dir_path, output)
        if len(sources) == 1:
            # 单张：原样复制；atlas 小节照搬（页大小沿用原值）
            shutil.copy(os.path.join(old_dir, sources[0]), out_path)
            page = atlas_db.get(sources[0])
            if page:
                blocks.append('\n'.join(
                    [output, page['size_line'], page['filter_line'], page['sprites']]))
            continue

        first, second = sources
        img1 = Image.open(os.path.join(old_dir, first))
        img2 = Image.open(os.path.join(old_dir, second))
        w1, h1 = img1.size
        w2, h2 = img2.size
        merged = Image.new('RGBA', (w1 + w2, max(h1, h2)))
        merged.paste(img1, (0, 0))
        merged.paste(img2, (w1, 0))
        merged.save(out_path)

        p1, p2 = atlas_db.get(first), atlas_db.get(second)
        if not p1 or not p2:
            continue    # atlas 里没有对应小节：图照拼，小节跳过
        blocks.append('\n'.join([
            output,
            f" size: {w1 + w2},{max(h1, h2)}",
            p1['filter_line'],
            p1['sprites'],
            _shift_bounds(p2['sprites'], w1),
        ]))

    final_atlas = os.path.join(mod_dir_path, f"{prefix}.atlas")
    report(f"  Writing final atlas file to: {final_atlas}")
    with open(final_atlas, 'w') as f:
        f.write('\n\n'.join(blocks))

    return f"Successfully merged files in {mod_dir_path}"
