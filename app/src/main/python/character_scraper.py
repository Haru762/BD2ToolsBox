"""从 browndust2modding.pages.dev 抓角色表，配合游戏 catalog 生成 characters.json。

characters.json 是 App 端「按角色浏览」的数据底座：每个 file_id 对应
idle / cutscene 两类 bundle。网页表格提供「角色名 / 服装」这些游戏外
信息，catalog 提供 file_id → bundle 的映射，两边按 file_id 拼起来。
catalog 的下载由上层（main_script）负责，这里只做抓取与拼装。
"""
import json
import os
import sys

import requests
from bs4 import BeautifulSoup

import catalog_parser

CHARACTER_TABLE_URL = "https://browndust2modding.pages.dev/characters"

# 表格里每行的两种形态：5 格完整行（首格角色名）、4 格续行（省略角色名，
# 沿用上一行）。file_id 一律转小写再当键用。
_FULL_ROW, CONTINUATION_ROW = 5, 4


def _scrape_metadata_map():
    """抓网页表格，返回 file_id → {character, costume}；失败返回 (None, 错误)。"""
    print("[Python] Fetching character list from website...")
    try:
        response = requests.get(CHARACTER_TABLE_URL, timeout=15)
        response.raise_for_status()
    except requests.exceptions.RequestException as e:
        print(f"[Python] Error: Failed to retrieve the webpage. {e}", file=sys.stderr)
        return None, f"Failed to retrieve webpage: {e}"

    print("[Python] Parsing HTML content...")
    soup = BeautifulSoup(response.text, 'html.parser')
    table = soup.find('tbody')
    if table is None:
        print("[Python] Error: Could not find the data table (tbody) in the HTML.", file=sys.stderr)
        return None, "Could not find data table in HTML"

    print("[Python] Scraping website for character metadata...")
    metadata = {}
    last_character = ""
    for row in table.find_all('tr'):
        cells = [c.get_text(strip=True) for c in row.find_all('td')]
        if len(cells) == _FULL_ROW:
            character, file_id, costume = cells[0], cells[1].lower(), cells[2]
            last_character = character
        elif len(cells) == CONTINUATION_ROW:
            character, file_id, costume = last_character, cells[0].lower(), cells[1]
        else:
            continue
        if file_id and character and costume:
            metadata[file_id] = {"character": character, "costume": costume}

    print(f"[Python] Found metadata for {len(metadata)} file_ids from the website.")

    # 表格页可能很大，解析完尽早还给系统
    del soup, table
    import gc
    gc.collect()
    return metadata, None


def scrape_and_save_from_catalog(output_dir, version, catalog_content):
    """用网页元数据 + 调用方提供的游戏 catalog 重建 characters.json。"""
    output_path = os.path.join(output_dir, "characters.json")

    if not version:
        return False, "Version not provided to scraper."
    if not catalog_content:
        return False, "Catalog content is missing."

    metadata, error = _scrape_metadata_map()
    if metadata is None:
        return False, error

    print("[Python] Parsing provided catalog to build asset map...")
    try:
        asset_map = catalog_parser.parse_catalog_for_bundle_names(catalog_content)
        if not asset_map:
            raise Exception("Failed to parse catalog or catalog is empty.")
    except Exception as e:
        print(f"[Python] Error processing game catalog: {e}", file=sys.stderr)
        return False, f"Error processing game catalog: {e}"

    print("[Python] Asset map built successfully.")
    print(f"[Python] Generating character list based on {len(asset_map)} file_ids from the game catalog...")

    characters = []
    for file_id, bundles in asset_map.items():
        meta = metadata.get(file_id) or {
            "character": "Unknown Character",
            "costume": f"Unknown ({file_id})",
        }
        for kind in ("idle", "cutscene"):
            hashed_name = bundles.get(kind)
            if hashed_name:
                characters.append({
                    "character": meta["character"],
                    "file_id": file_id,
                    "costume": meta["costume"],
                    "type": kind,
                    "hashed_name": hashed_name,
                })

    if not characters:
        print("[Python] Warning: No character data could be generated from the catalog.", file=sys.stderr)

    print(f"[Python] Saving {len(characters)} total entries to {output_path}...")
    try:
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump({"version": version, "characters": characters}, f,
                      indent=4, ensure_ascii=False)
    except IOError as e:
        print(f"[Python] Error: Failed to write to file {output_path}. {e}", file=sys.stderr)
        return False, f"Failed to write to file: {e}"

    print(f"[Python] Success! Data saved to {output_path}")
    return True, "Scraper completed successfully."
