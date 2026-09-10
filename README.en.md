[![GitHub release](https://img.shields.io/github/v/release/Haru762/BD2ToolsBox)](https://github.com/Haru762/BD2ToolsBox/releases/latest)

[简体中文](./README.md) | [English](./README.en.md)

# BD2 ToolsBox

**Manage and install your BrownDust 2 mods directly on your phone. No PC required.**

---

## Key Features

*   **PC-Free Operation**: Unpacks, converts textures (ASTC), and repacks mods entirely on your Android device.
*   **Browse by Character**: Automatically scans your mod folder and groups mods by character, preventing conflicts.
*   **Batch Processing**: Select and install mods from different groups in a single operation.
*   **Shizuku Integration**: Move repacked files into the game directory with a single tap.
*   **Live Animation Preview**: Long-press any mod to preview its Spine animation before installing.
*   **Guide Library**: Guides from the GameKee BrownDust 2 wiki — category tree, search, comments; articles are cached locally for offline reading.
*   **Coupon Codes**: Code list plus one-tap redemption through the official channel — enter your in-game nickname once and rewards go straight to your in-game mailbox. No login, no launching the game.
*   **Built-in Utilities**: Merge texture atlases and unpack game bundles.

---

## Getting Started

### Requirements

*   Android 8 or higher.
*   The latest official version of BrownDust 2 installed.
*   [Shizuku](https://shizuku.rikka.app/) for bundle scanning and one-tap file transfer. Without it you can convert mods but not install them.

### Installation

1.  Download the latest `.apk` from the [Releases page](https://github.com/Haru762/BD2ToolsBox/releases). On emulators, force the x86_64 ABI or the app will crash under ARM translation:
    emulators install `BD2ToolsBox-<version>-android-x86_64.apk` (phones use arm64-v8a; the universal APK still needs `--abi x86_64` on emulators)
2.  Start Shizuku and follow the onboarding to grant access.
3.  Tap **"Add mod folder"** and pick the directory where you keep your mods.

> Tip: create a dedicated folder (e.g. `.BD2_Mods`) in your phone's internal storage and keep all mods there.

---

## Mod Folder Structure (Important)

Each mod needs its own folder, and filenames inside must **exactly match** the game's original asset names. Zip archives are recognized at any depth, and subfolders are scanned automatically — selecting a collection's root folder works too.

> **⚠️ Warning:** All filenames must be **lowercase** (e.g. `char000104.png`, not `Char000104.png`). The image filename referenced inside the `.atlas` file must also be lowercase. Otherwise, the game might **crash**.

**Example Folder Structure:**
```
.BD2_Mods/
├── Lathel_DarkKnight_IDLE/
│   ├── char000104.skel      (or .json for the skeleton)
│   ├── char000104.atlas     (the atlas mapping file)
│   └── char000104.png       (the texture image)
└── Another_Mod/
    └── ... (other mod files)
```

---

## Installing Mods

1.  **Select**: check the mods you want to install.
2.  **Convert**: tap **Start** — the app downloads the necessary original game files and repacks your selection, with live progress in a dialog.
3.  **Install**: tap **"Install to game"** (Shizuku must be running) — files are moved into the game directory. **Restart the game** for changes to take effect.
4.  **Uninstall**: "Uninstall all mods" or the per-mod Uninstall button restores the originals.

Destination path (for manual transfers): `/Android/data/com.neowizgames.game.browndust2/files/UnityCache/`

---

## Other Tools

### Spine Animation Preview
Not sure what a mod looks like in action? **Long-press** it in the list to open a live preview.

### Spine Atlas Merger (Troubleshooting Tool)
If a mod displays incorrectly after installing: select **only that mod**, tap Merge. The originals are backed up into a `.old` subfolder; re-install the merged mod.

### Standalone Bundle Unpacker
To extract original game files: deselect all mods, tap the unpack icon, and pick the `__data` file. Output goes to `Download/outputs`.

---

## FAQ

**Q: My mods aren't showing up.**
Double-check the folder selected via "Add mod folder"; make sure your mods follow the structure above and their filenames match the game's original assets.

**Q: The game crashes after installing a mod.**
Usually one of these: texture count mismatch with the original (the app usually fixes this automatically), or filenames that aren't fully lowercase (see the warning above). Try the Atlas Merger; if it still fails, the mod itself is likely corrupted or incompatible.

**Q: Installation failed.**
Common causes: poor network (original files failed to download), incorrect mod filenames, or a repacking error. Check the message in the dialog.

**Q: Graphics are broken after installing.**
The mod is likely incomplete or incompatible with the Android version. A complete character mod requires three files: `.png`, `.atlas`, and either `.skel` or `.json`.

---

## Credits

This project stands on the shoulders of:

*   [browndust2-repacker-android](https://codeberg.org/kxdekxde/browndust2-repacker-android) (GPL-3.0) — the core repacking and texture compression techniques.
*   [ReDustX](https://github.com/Jelosus2/ReDustX) (MIT) — `.json`-to-`.skel` conversion logic and CDN download methods.
*   [UnityPy](https://github.com/K0lb3/UnityPy) (MIT) — reading and modifying Unity game assets.
*   [astc-encoder](https://github.com/ARM-software/astc-encoder) (Apache-2.0) — the official ASTC texture encoder.
*   [BD2ModManager](https://github.com/bruhnn/BD2ModManager) (GPL-3.0) — source of the character metadata (Chinese names / gender / avatars).
*   Data sources: [browndust2modding.pages.dev](https://browndust2modding.pages.dev/characters) (character table), [Brown-Dust-2-Asset](https://github.com/myssal/Brown-Dust-2-Asset) (avatars and art).

Where each component lives in the tree is listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

---

## License

This project is licensed under the **GNU General Public License v3** — see [`LICENSE`](LICENSE).

GPLv3 is a deliberate choice: the character metadata is extracted from a GPL-3.0 project (see Credits), and we want to keep the same openness obligations as the community tooling ecosystem. One thing to note (see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)):

-   `pixi-spine` is **not bundled** in the APK. It uses Esoteric Software's proprietary SPINE-LICENSE, which is incompatible with the GPL, so the app downloads it from npm on first use of the animation preview instead.

*Brown Dust 2* and all of its game assets are the property of Neowiz / Round8 Studio. This tool only modifies local game caches; it redistributes no game assets.
