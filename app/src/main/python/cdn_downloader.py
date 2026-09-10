"""官方 CDN 的访问层：版本查询、catalog 下载、bundle 下载。

CDN 的事实布局（2026-09 实测）：
  https://cdn.bd2.pmang.cloud/ServerData/Android/{HD|SD}/{version}/catalog_alpha.json
  https://cdn.bd2.pmang.cloud/ServerData/Android/{HD|SD}/{version}/{bundle 文件名}

版本号来自 mt.bd2.pmang.cloud 的 MaintenanceInfo 接口（protobuf，见
maintenance_info_pb2），HD/SD 各有一个 bundle_version 字段。

CDN 上只有 HD 和 SD 两档 —— 界面上的 FHD 是个档位名，对应资源仍是 HD 那份
（实测 FHD/<任何版本号> 一律 NoSuchKey）。所有要拼 CDN 路径的地方必须先过
[normalize_quality]。早先只在 download_bundle 那条路上手写了归一化、取
catalog 那条路漏了，后果不是「下载失败」而是「干净检测表为空 → 一键卸载
认为所有资源都是原版」—— 归一化收在一个函数里就是为了不再漏第二次。
"""
import base64
import json
import os
import struct
import threading

import requests

import maintenance_info_pb2
from catalog_parser import Catalog, read_serialized_object, read_int32

MAINTENANCE_URL = "https://mt.bd2.pmang.cloud/MaintenanceInfo"
CDN_BASE = "https://cdn.bd2.pmang.cloud/ServerData/Android"

# 请求头仿游戏客户端的 UnityWebRequest（服务端不校验，但保持同款最稳）
_MAINTENANCE_HEADERS = {
    'accept': '*/*',
    'accept-encoding': 'gzip',
    'connection': 'close',
    'content-type': 'multipart/form-data',
    'host': 'mt.bd2.pmang.cloud',
    'user-agent': 'UnityPlayer/2022.3.22f1 (UnityWebRequest/1.0, libcurl/8.5.0-DEV)',
}

CDN_QUALITIES = ("HD", "SD")


def normalize_quality(quality):
    """把界面画质换成 CDN 上真实存在的那一档（FHD → HD）。"""
    return "HD" if quality == "FHD" else quality


def get_cdn_version(quality):
    """查当前 CDN 版本号。HD → bundle_version，SD → bundle_version_sd。

    设备上走 java.net（Chaquopy 环境里 requests 也能用，但 HttpURLConnection
    对 gzip/超时的行为与游戏客户端一致）；PC 上没 java，退回 requests。
    失败返回 None，由调用方决定怎么报。
    """
    quality = normalize_quality(quality)
    binary = _fetch_maintenance_binary()
    if binary is None:
        return None
    try:
        response = maintenance_info_pb2.MaintenanceInfoResponse()
        response.ParseFromString(binary)
        market = response.market_info
        return market.bundle_version if quality == 'HD' else market.bundle_version_sd
    except Exception as e:
        print(f"Failed to parse maintenance info: {e}")
        return None


def _fetch_maintenance_binary():
    """请求 MaintenanceInfo，返回 protobuf 字节；失败返回 None。"""
    try:
        try:
            return _maintenance_via_java()
        except ImportError:
            pass  # PC / 无 JVM 环境，走 requests
    except Exception as e:
        print(f"Native HttpURLConnection failed in get_cdn_version: {e}")
    try:
        response = requests.put(MAINTENANCE_URL, headers=_MAINTENANCE_HEADERS,
                                data='EAQ=', timeout=20)
        response.raise_for_status()
        return base64.b64decode(json.loads(response.text)['data'])
    except Exception as e:
        print(f"Failed to get CDN version: {e}")
        return None


def _maintenance_via_java():
    """设备路径：java.net.HttpURLConnection 手搓请求与读流。"""
    from java.net import URL
    from java.lang import String

    conn = URL(MAINTENANCE_URL).openConnection()
    conn.setRequestMethod("PUT")
    for k, v in _MAINTENANCE_HEADERS.items():
        conn.setRequestProperty(k, v)
    conn.setDoOutput(True)
    out = conn.getOutputStream()
    out.write(b"EAQ=")
    out.flush()
    out.close()

    if conn.getResponseCode() != 200:
        print(f"Failed to get CDN version. HTTP Code: {conn.getResponseCode()}")
        return None

    stream = conn.getInputStream()
    encoding = conn.getContentEncoding()
    if encoding and "gzip" in str(encoding).lower():
        from java.util.zip import GZIPInputStream
        stream = GZIPInputStream(stream)
    from java.io import BufferedReader, InputStreamReader
    reader = BufferedReader(InputStreamReader(stream))
    chunks = []
    while True:
        line = reader.readLine()
        if line is None:
            break
        chunks.append(str(line))
    reader.close()
    return base64.b64decode(json.loads("".join(chunks))['data'])


def catalog_file_name(quality, version):
    """catalog 磁盘缓存文件名：catalog_{画质}_{版本}.json。

    画质段不能省。CDN 的 URL 是按画质取的（{CDN_BASE}/{quality}/{version}/
    catalog_alpha.json），HD/SD 的版本号今天恰好不同，但一旦两档共用一个
    版本串，不带画质的文件名会让第二档直接命中第一档那份 —— 跨档产物的
    字节数/哈希全对不上，会被整片误判「待更新」并改名（静默错档）。
    与 catalog_indexer 的 {前缀}{画质}_{版本}.json 同一套命名。
    """
    return f"catalog_{quality}_{version}.json"


def is_legacy_catalog_name(name):
    """是否是 0.2.2 之前那种不带画质的老命名 catalog_{版本}.json。

    版本号是纯数字，所以 catalog_HD_2026….json 不会被误判成老命名。
    """
    if not name.startswith("catalog_") or not name.endswith(".json"):
        return False
    return name[len("catalog_"):-len(".json")].isdigit()


def catalog_cache_key(quality, version):
    """catalog 内存缓存的键：(画质, 版本)。

    与磁盘文件名同理，画质段不能省 —— 只按版本号存的话，两档撞到同一个
    版本串时第二次调用会直接拿到第一档的内容。调用方（main_script 的
    _prune_catalog_cache）必须用同一个键去裁剪。
    """
    return (normalize_quality(quality), version)


def download_catalog(output_dir, quality, version, cache, lock, progress_callback=None):
    """下载 catalog（带内存 + 磁盘两级缓存），返回 (content, error)。

    catalog 约 60MB，而内存缓存每换一个安装批次就会被清空（见
    download_bundle 的 cache_key 逻辑），所以同版本优先复用磁盘上那份。
    文件名按「画质 + version」命名，既不会读到旧版本，也不会跨档互相命中。
    """
    quality = normalize_quality(quality)
    cache_key = (quality, version)
    report = (lambda m: progress_callback(m)) if progress_callback else None

    if cache_key in cache:
        report and report(f"Catalog for version {version} found in memory cache.")
        return cache[cache_key], None

    with lock:
        # 双检：等锁期间别的线程可能已填充
        if cache_key in cache:
            report and report(f"Catalog for version {version} found in memory cache after lock.")
            return cache[cache_key], None

        filename = os.path.join(output_dir, catalog_file_name(quality, version))

        if os.path.exists(filename):
            try:
                with open(filename, 'rb') as f:
                    catalog_content = json.loads(f.read())
                cache[cache_key] = catalog_content
                report and report(f"Catalog for version {version} loaded from disk cache.")
                return catalog_content, None
            except Exception as e:
                report and report(f"Disk-cached catalog unusable ({e}), re-downloading.")

        # 清掉旧版本的物理文件 —— 但必须等新版本完整落盘后再删：
        # 「先删后下」在网络不稳（比如 CDN 连接时断时续）时会把唯一的
        # catalog 删掉，之后每次进入都要重下 60MB 且 bundle 预筛失效，
        # 表现为「列表不能持久化、每次进去都重新下载」。
        url = f"{CDN_BASE}/{quality}/{version}/catalog_alpha.json"
        report and report(f"Downloading new catalog from {url}...")
        try:
            response = requests.get(url, timeout=300)
            response.raise_for_status()
            # 先写临时文件，成功后再原子替换并清理旧版本
            part_path = filename + ".part"
            with open(part_path, 'wb') as f:
                f.write(response.content)
            catalog_content = json.loads(response.content)
            os.replace(part_path, filename)
            # 落盘后清场，两件事：
            #  - 同画质的旧版本（游戏更新换代换了版本号，旧那份没用了）
            #  - 不带画质前缀的老命名 catalog_{版本}.json（0.2.2 迁移）：
            #    内容属于哪一档已无从考证，宁可让它重下一次，也不能挂着
            #    错误画质的名字继续用
            # 别的画质一律不动 —— 画质自检会同时用 HD 与 SD 两档，互删会让
            # 另一档每次进入都重下 65MB。
            try:
                kept = sorted(
                    (f for f in os.listdir(output_dir)
                     if f.startswith(f"catalog_{quality}_") and f.endswith(".json")),
                    key=lambda f: os.path.getmtime(os.path.join(output_dir, f)),
                    reverse=True)
                for old in kept[1:]:
                    os.remove(os.path.join(output_dir, old))
                for name in os.listdir(output_dir):
                    if is_legacy_catalog_name(name):
                        os.remove(os.path.join(output_dir, name))
            except OSError as e:
                report and report(f"Error removing old catalog: {e}")
            cache[cache_key] = catalog_content
            report and report("Catalog downloaded and cached successfully.")
            return catalog_content, None
        except requests.exceptions.RequestException as e:
            return None, f"Failed to download catalog: {e}"
        except json.JSONDecodeError as e:
            # 下载成功但内容坏了：临时文件留着没用，清掉
            try:
                os.remove(part_path)
            except OSError:
                pass
            return None, f"Failed to parse downloaded catalog JSON: {e}"


# ---------------------------------------------------------------- bundle 下载

def _candidate_urls(base_url, download_name, bundle_name, bundle_hash):
    """一个 bundle 在 CDN 上可能的三种文件名形态，按命中率排序。

    常规是 catalog 里登记的 download_name；少数 bundle 的实际文件名带/不带
    哈希后缀与登记不一致，依次回退尝试。
    """
    urls = [f"{base_url}/{download_name}"]
    if bundle_hash:
        if bundle_hash not in download_name and download_name.endswith('.bundle'):
            stem = download_name[:-len('.bundle')]
            urls.append(f"{base_url}/{stem}_{bundle_hash}.bundle")
        stripped = download_name.replace(f"_{bundle_hash}", "")
        if stripped != download_name:
            urls.append(f"{base_url}/{stripped}")
    if bundle_name and bundle_name != download_name:
        urls.append(f"{base_url}/{bundle_name}")
        if bundle_hash and bundle_name.endswith('.bundle'):
            stem = bundle_name[:-len('.bundle')]
            urls.append(f"{base_url}/{stem}_{bundle_hash}.bundle")
    # 去重保序
    return list(dict.fromkeys(urls))


def _download_to(url, dest, bundle_size, progress_callback=None):
    """流式下载一个文件，进度回调按 KB 汇报。返回 (成功, 错误)。"""
    try:
        response = requests.get(url, stream=True, timeout=120)
        response.raise_for_status()
        done = 0
        with open(dest, 'wb') as f:
            for chunk in response.iter_content(chunk_size=65536):
                if chunk:
                    f.write(chunk)
                    done += len(chunk)
                    if progress_callback:
                        progress_callback(f"Downloading... {done / 1024:.2f} KB / {bundle_size / 1024:.2f} KB")
        return True, None
    except requests.exceptions.RequestException as e:
        return False, str(e)


def find_and_download_bundle(catalog_content, version, quality, hashed_name,
                             output_dir, progress_callback=None):
    """在 catalog 里找到目标 bundle 并下载。返回 (产物路径, 错误)。

    产物按 CDN 原样布局到 output_dir/<bundle_name>/<bundle_hash>/__data，
    与游戏目录结构一致，重打包直接以此为基底。
    """
    quality = normalize_quality(quality)
    report = (lambda m: progress_callback(m)) if progress_callback else None
    if not catalog_content:
        return None, "Catalog content is missing or empty."

    catalog = Catalog(catalog_content)
    target = None
    for info in catalog.bundles.values():
        if info.get('bundle_name') == hashed_name:
            target = info
            break
    if target is None:
        return None, f"Bundle with hash {hashed_name} not found in catalog."

    download_name = target.get('download_key') or ''
    if not download_name:
        return None, "Bundle has no download name in catalog."
    bundle_name = target['bundle_name']
    bundle_hash = target.get('bundle_hash')
    bundle_size = target.get('bundle_size') or 0

    dest = os.path.join(output_dir, bundle_name, bundle_hash, "__data")
    os.makedirs(os.path.dirname(dest), exist_ok=True)

    base_url = f"{CDN_BASE}/{quality}/{version}"
    urls = _candidate_urls(base_url, download_name, bundle_name, bundle_hash)

    last_error = None
    for i, url in enumerate(urls):
        if report:
            if i == 0:
                report(f"Found bundle. Downloading from {url}...")
            else:
                report(f"Retrying with alternative URL ({i + 1}/{len(urls)}): {url}...")
        ok, error = _download_to(url, dest, bundle_size, progress_callback)
        if ok:
            report and report("Download complete.")
            return dest, None
        last_error = error
        if report and i < len(urls) - 1:
            report(f"Download failed: {error}. Trying alternative...")

    return None, (f"Failed to download bundle after trying {len(urls)} URL(s). "
                  f"Last error: {last_error}")
