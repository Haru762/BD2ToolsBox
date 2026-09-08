"""catalog → bundle 元数据表，带磁盘缓存。

bundle meta 用于「干净检测」：游戏目录里 Shared/<name>/<hash>/__data 的
目录名与字节数若同时等于 catalog 登记的内容哈希与原版大小，就说明这个
bundle 没被 mod 改过，可直接拿来当重打包基底（省一次 CDN 下载）；
大小不符说明里面装着 mod。

刻意不做完整 asset 索引：只遍历 entry 表读 bundle 信息，不解码 26MB 的
key 数组，实测 0.14 秒（完整索引要 12.4 秒且已无调用方）。
"""
import json
from pathlib import Path

from catalog_parser import Catalog, read_int32

# bundle_meta 缓存的结构版本号；布局变了就 bump，老缓存自动作废重建
BUNDLE_META_SCHEMA_VERSION = 1


def build_bundle_meta(catalog_content):
    """catalog → {bundle 名: [原版字节数, 内容哈希]}。"""
    meta = {}
    catalog = Catalog(catalog_content)
    for info in catalog.bundles.values():
        name = info.get('bundle_name')
        size = info.get('bundle_size')
        bhash = info.get('bundle_hash')
        if name and size is not None and bhash:
            meta[name] = [size, bhash]
    return meta


def load_or_build_bundle_meta(output_dir, version, catalog_content):
    """带磁盘缓存的 [build_bundle_meta]。缓存约 150KB，远快于重解析。"""
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)

    cache_path = output_path.joinpath(f"bundle_meta_{version}.json")
    if cache_path.exists():
        try:
            cached = json.loads(cache_path.read_text(encoding='utf-8'))
            if cached.get('schemaVersion') == BUNDLE_META_SCHEMA_VERSION:
                return cached.get('bundleMeta') or {}
        except Exception:
            pass
        try:
            cache_path.unlink()
        except Exception:
            pass

    # 版本变了就清掉旧缓存，避免拿上个版本的原版大小判断当前 bundle
    for stale in output_path.glob("bundle_meta_*.json"):
        if stale != cache_path:
            try:
                stale.unlink()
            except Exception:
                pass

    meta = build_bundle_meta(catalog_content)
    try:
        with open(cache_path, 'w', encoding='utf-8') as f:
            json.dump({'schemaVersion': BUNDLE_META_SCHEMA_VERSION, 'bundleMeta': meta},
                      f, ensure_ascii=False, separators=(',', ':'))
    except Exception:
        pass
    return meta


# 保留导出：少数调用方（旧代码 / 测试）直接引 read_int32 做二进制探测
__all__ = ["build_bundle_meta", "load_or_build_bundle_meta", "read_int32",
           "BUNDLE_META_SCHEMA_VERSION"]
