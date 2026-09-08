"""Spine .atlas 图集索引的页块解析。

.atlas 的结构：每页（一张 png）一个小节，节首是页名（以 .png 结尾的行），
随后是 size:/filter:/format: 等页级属性行，其余行是精灵条目（名字 +
bounds/xy 等属性）。repacker 合并图集时要的是每页三样东西：精灵行原文、
filter 行（缺省补 Linear）、页大小行，这里原样交出去不做语义解释。
"""
from typing import Dict, Optional, Tuple

# 页里没有 filter 行时补的缺省值（注意保留行首空格，repacker 原样回写）
DEFAULT_FILTER_LINE = ' filter: Linear, Linear'


def parse_atlas_file(atlas_path: str) -> Tuple[Optional[Dict], Optional[str]]:
    """把 .atlas 解析成 {页名: {'sprites': str, 'filter_line': str, size_line?}}。

    成功返回 (dict, None)；读不到文件返回 (None, 错误信息)。
    没有任何 .png 页头的文件也按「首行当页名」出一个小节，与逐块切分的
    老实现行为一致（空文件则返回空 dict）。
    """
    try:
        with open(atlas_path, 'r', encoding='utf-8') as f:
            text = f.read()
    except FileNotFoundError:
        return None, f"Atlas file not found at {atlas_path}"
    except Exception as e:
        return None, f"Error reading atlas file: {e}"

    pages: Dict[str, dict] = {}
    name = None      # 当前页名
    size_line = None
    filter_line = None
    sprite_lines = []

    def flush():
        nonlocal name, size_line, filter_line, sprite_lines
        if name is None:
            return
        # 页尾的空行去掉（下一页头之前的分隔空行不算精灵内容）
        while sprite_lines and sprite_lines[-1] == '':
            sprite_lines.pop()
        page = {
            'sprites': '\n'.join(sprite_lines),
            'filter_line': filter_line if filter_line is not None else DEFAULT_FILTER_LINE,
        }
        if size_line is not None:
            page['size_line'] = size_line
        pages[name] = page

    for line in text.strip().split('\n'):
        if line.endswith('.png'):
            flush()
            name, size_line, filter_line, sprite_lines = line.strip(), None, None, []
        elif name is None:
            # 页头之前的内容：把首个非空行当页名开一个隐式小节（空文件则一节都没有）
            if line.strip():
                name, size_line, filter_line, sprite_lines = line.strip(), None, None, []
        else:
            stripped = line.strip()
            if stripped.startswith('size:'):
                size_line = line
            elif stripped.startswith('filter:'):
                filter_line = line
            else:
                sprite_lines.append(line)
    flush()

    return pages, None
