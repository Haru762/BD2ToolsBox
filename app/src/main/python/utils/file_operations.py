"""文件查找辅助。

mod 多半在 Windows 上打包，文件名大小写经常和游戏原始资源对不上，
查找一律按大小写不敏感处理。
"""
import os
from typing import Optional


def find_file_case_insensitive(folder: str, filename: str) -> Optional[str]:
    """在 folder 及其子目录里找 filename（忽略大小写），返回真实路径。

    遍历顺序与 os.walk 相同（当前目录的文件先于子目录内容），
    同名文件出现在多个子目录时命中哪一个，与老实现保持一致。
    """
    want = filename.lower()
    for root, _dirs, names in os.walk(folder):
        for name in names:
            if name.lower() == want:
                return os.path.join(root, name)
    return None
