"""游戏 catalog（catalog_alpha.json）的二进制解析器。

catalog 是 Addressables 的序列化格式：四个 base64 字段（bucket / key /
extra / entry）各是一段小端二进制。entry 表的每条记录描述一个资源，
provider 为 AssetBundleProvider 的 entry 在 extra 段里挂着 bundle 信息
（名称 / 哈希 / 大小）；普通资源的 entry 顺着 bucket 依赖链能找到它所属
的 bundle。key 段是资源地址表（ASCII 字符串或内嵌 JSON）。

本模块导出三个层次：
  read_int32 / read_serialized_object —— 无状态的原语，local_bundle_indexer
      等模块也直接复用，签名与布局不能随便动
  parse_catalog_for_bundle_names —— file_id → {idle/cutscene: bundle 名}，
      character_scraper 拿它生成 characters.json
对象布局本身（类型枚举、字段顺序）是 Unity 序列化格式的事实，照抄；
解析循环与数据结构是本项目的实现。
"""
import base64
import json
import re
import struct

# Unity SerializationUtilities.ObjectType 的取值（线上格式事实）
ASCII_STRING = 0
JSON_OBJECT = 7

# AssetBundleProvider 在 m_ProviderIds 里的标识串
BUNDLE_PROVIDER = "UnityEngine.ResourceManagement.ResourceProviders.AssetBundleProvider"


def read_int32(data, offset):
    """小端 int32。catalog 的所有定长字段都是它。"""
    return struct.unpack_from('<i', data, offset)[0]


def read_serialized_object(data, offset):
    """按 ObjectType 解出一个对象：ASCII 字符串或内嵌 JSON（dict）。

    JsonObject 的布局是 [程序集名长度][程序集名][类型名长度][类型名]
    [json 长度][utf-16 json]。程序集/类型名用不上，跳过。
    解析失败返回 None（坏数据不该炸掉整个 catalog 解析）。
    """
    try:
        obj_type = data[offset]
        pos = offset + 1
        if obj_type == ASCII_STRING:
            length = read_int32(data, pos)
            return data[pos + 4: pos + 4 + length].decode('ascii')
        if obj_type == JSON_OBJECT:
            # [程序集名长度(1B)][程序集名][类型名长度(1B)][类型名][json长度(4B)][utf-16 json]
            pos += 1 + data[pos]             # 长度字节 + 程序集名
            pos += 1 + data[pos]             # 长度字节 + 类型名
            length = read_int32(data, pos)
            return json.loads(data[pos + 4: pos + 4 + length].decode('utf-16'))
        return None
    except Exception as ex:
        print(f"Exception during object parsing: {ex}")
        return None


class Catalog:
    """一份解开的 catalog。构造即完成四段二进制的解码。"""

    def __init__(self, content):
        self.entries = []        # [{provider, data_index, dependency, primary_key, internal_id}]
        self.bundles = {}        # entry 序号 → {bundle_name, bundle_hash, bundle_size, download_key}
        self.keys = []           # key 段对象表（bucket 头偏移 → 对象，按出现序）
        self._dependencies = []  # bucket 依赖表
        if not content:
            return

        provider_ids = content.get('m_ProviderIds', [])
        bundle_provider_index = provider_ids.index(BUNDLE_PROVIDER) if BUNDLE_PROVIDER in provider_ids else -1

        bucket = base64.b64decode(content['m_BucketDataString'])
        key = base64.b64decode(content['m_KeyDataString'])
        extra = base64.b64decode(content['m_ExtraDataString'])
        entry = base64.b64decode(content['m_EntryDataString'])
        internal_ids = content.get('m_InternalIds') or []

        # bucket 段：[桶数][每桶 (key 偏移, 依赖 entry 数, 依赖 entry 序号...)]
        num_buckets = read_int32(bucket, 0)
        self._dependencies = [None] * num_buckets
        key_offsets = []
        pos = 4
        for b in range(num_buckets):
            key_offsets.append(read_int32(bucket, pos)); pos += 4
            count = read_int32(bucket, pos); pos += 4
            self._dependencies[b] = [read_int32(bucket, pos + 4 * i) for i in range(count)]
            pos += 4 * count
        self.keys = [read_serialized_object(key, off) for off in key_offsets]

        # entry 段：[条目数][每条 24 字节：internal_id, provider, dependency_key,
        # dependency_hash, data_index, primary_key, resource_type]
        count = read_int32(entry, 0)
        pos = 4
        for i in range(count):
            internal_id, provider, dependency, _dhash, data_index, primary_key, _rtype = \
                struct.unpack_from('<7i', entry, pos)
            pos += 28
            self.entries.append({
                'provider': provider,
                'data_index': data_index,
                'dependency': dependency,
                'primary_key': primary_key,
                'internal_id': internal_id,
            })
            if provider == bundle_provider_index and data_index >= 0:
                info = read_serialized_object(extra, data_index)
                if isinstance(info, dict):
                    self.bundles[i] = {
                        'bundle_name': info.get('m_BundleName'),
                        'bundle_hash': info.get('m_Hash'),
                        'bundle_size': info.get('m_BundleSize'),
                        'download_key': str(self.keys[primary_key]) if primary_key < len(self.keys) else '',
                    }

    def primary_key_of(self, entry_index):
        entry = self.entries[entry_index]
        return self.keys[entry['primary_key']] if entry['primary_key'] < len(self.keys) else None

    def internal_id_of(self, entry_index):
        entry = self.entries[entry_index]
        idx = entry['internal_id']
        internal_ids = self._internal_ids
        return internal_ids[idx] if 0 <= idx < len(internal_ids) else None

    def bundle_of(self, entry_index):
        """entry 所属的 bundle：是 bundle entry 本身就直接命中，否则走依赖链。"""
        if entry_index in self.bundles:
            return self.bundles[entry_index]
        if not (0 <= entry_index < len(self.entries)):
            return None
        dep = self.entries[entry_index]['dependency']
        if not (0 <= dep < len(self._dependencies)):
            return None
        for dep_entry in self._dependencies[dep] or []:
            if dep_entry in self.bundles:
                return self.bundles[dep_entry]
        return None

    _internal_ids = ()
    _catalog_content = None


# ---- file_id 映射（character_scraper 用） --------------------------------

# 兼容别名：local_bundle_indexer 等旧调用方仍引旧名（P1-5 重写时一并收编）
read_int32_from_byte_array = read_int32
read_object_from_byte_array = read_serialized_object

# 资源地址里的角色/立绘/剧情包模式；() 里是 file_id 本体
_FILE_ID_IN_ADDRESS = re.compile(
    r'(cutscene_char\d{6}|char\d{6}|illust_dating\d+|illust_special\d+|illust_talk\d+|npc\d+|specialillust\w+|storypack\w+|\bRhythmHitAnim\b)',
    re.IGNORECASE)

# idle 槽除了 .skel.bytes 外，新目录结构里角色/ NPC / 约会立绘还会用这些
# prefab 形式登记；白名单收窄，免得把无关 prefab 也归进 idle
_IDLE_EXTRA_PREFAB = re.compile(
    r'(^|/)(illust_char\d{6}_\d+|illust_npc\d+_\d+|illust_dating\d+)\.prefab$',
    re.IGNORECASE)


def parse_catalog_for_bundle_names(catalog_content):
    """catalog → {file_id: {idle: bundle名, cutscene: bundle名}}。

    思路（源自 ReDustX）：扫全部 entry 的主键地址，抽出 file_id；
    cutscene 只认 .skel.bytes（保证拿到骨架的 bundle 哈希而不是图集的）；
    顺依赖链找到所属 bundle。
    """
    catalog = Catalog(catalog_content)
    if not catalog.entries:
        return {}

    asset_map = {}
    for i in range(len(catalog.entries)):
        asset_key = catalog.primary_key_of(i)
        if not isinstance(asset_key, str):
            continue

        match = _FILE_ID_IN_ADDRESS.search(asset_key)
        if not match:
            continue
        file_id = match.group(1).lower()

        # 从地址前缀定槽位：cutscene_ 前缀 → cutscene，其余 → idle
        if file_id.startswith('cutscene_'):
            slot, file_id = "cutscene", file_id[len('cutscene_'):]
        else:
            slot = "idle"

        ext = asset_key.lower()
        if slot == "cutscene" and not ext.endswith('.skel.bytes'):
            continue
        if slot == "idle" and not (
                ext.endswith('.skel.bytes') or _IDLE_EXTRA_PREFAB.search(ext)):
            continue

        bundle = catalog.bundle_of(i)
        bundle_name = bundle and bundle.get('bundle_name')
        if bundle_name:
            asset_map.setdefault(file_id, {})[slot] = bundle_name
    return asset_map
