[![GitHub release](https://img.shields.io/github/v/release/Haru762/BD2ToolsBox)](https://github.com/Haru762/BD2ToolsBox/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](./LICENSE)
[![Platform](https://img.shields.io/badge/Android-10%2B%20(API%2029%2B)-3DDC84)](https://github.com/Haru762/BD2ToolsBox/releases)

[简体中文](./README.md) | [English](./README.en.md)

# BD2 ToolsBox

**Manage, preview, and install your BrownDust 2 mods directly on your Android device — no PC required.**

---

## Preview

| Core Workflow & Navigation | Themes & Dark Mode |
| :---: | :---: |
| ![Interaction Preview](./docs/assets/preview-interaction.gif) | ![Themes Preview](./docs/assets/preview-themes.gif) |

---

## Key Features

*   **PC-Free Operation**: Unpacks, converts textures (ASTC compression & automatic texture atlas merging), and repacks mods entirely on your Android device.
*   **Browse by Character & Costume**: Automatically scans multiple mod folders (both directories and nested `.zip` archives), grouping mods by character with portraits, quick A–Z index jump, and search/status filters.
*   **Batch Processing & Foreground Execution**: Select and convert multiple mods across characters in parallel; conversion, installation, bundle indexing, and batch pre-unpacking run inside foreground services so backgrounding the app won't interrupt long tasks.
*   **Shizuku One-Tap Install & Crash-Safe Recovery**:
    *   Automatically moves repacked bundles into the game directory via Shizuku using same-directory staging + SHA-256 verification + atomic replacement (never leaving half-written `__data` files);
    *   Backs up verified pristine original bundles before installing so uninstalling restores them offline;
    *   Detects interrupted installs after a process kill and offers one-tap **"Reconcile & Repair Records"** without re-converting;
    *   Automatically places `.nomedia` in mod source folders to keep mod textures out of your system gallery.
*   **Spine Animation & Static Image Preview**: Preview Spine animations before installing (adapting to light/dark theme); supports **Batch Pre-unpack** for instant previews and static image viewing for illustration-only mods.
*   **GameKee Wiki & Guides**: Browse the BrownDust 2 GameKee wiki with category trees, search, in-app v2/v3 battle lineup rendering, comments with emoji stickers, offline article caching, and automatic light/dark reader styling.
*   **Multi-Account Coupon Redemption**: View active coupon codes and redeem them across multiple saved in-game nicknames in one tap, automatically skipping already-settled or expired codes with per-account status summaries.
*   **Customizable Appearance**: Light / Dark / Follow-System modes, 7 colour presets, Android 12+ Material You dynamic colour, and custom background wallpaper with adjustable scrim.
*   **Built-in Utilities**: Standalone bundle unpacker (exports to `Download/outputs/`) and one-tap diagnostic log export.

---

## Getting Started

### Requirements

*   **Android 10 (API 29) or higher**.
*   The latest official version of *BrownDust 2* installed.
*   [Shizuku](https://shizuku.rikka.app/) for bundle indexing and one-tap game directory installation (without Shizuku, you can still convert and preview mods).

### Installation

1.  Download the matching single-ABI `.apk` from [Releases](https://github.com/Haru762/BD2ToolsBox/releases) (**~21.7 MB**, reduced by 35%):
    *   **Phones / Tablets**: install the `arm64-v8a` APK;
    *   **PC Android Emulators (MuMu, LDPlayer, etc.)**: install the `x86_64` APK.
2.  Start Shizuku and grant permission to BD2 ToolsBox.
3.  Tap the top-left folder icon (**"Open / Add mod folder"**) and select the directory where you store your mods (multiple folders are supported).
4.  On first use or after a major game update, run **"Build File Index"** from the top-left menu (supports checkpoint resume) so story, dating, and illustration mods can all be resolved.

> 💡 **Tip**: Create a dedicated folder (e.g. `BD2_Mods`) in your phone's storage for all your mods. The app automatically adds `.nomedia` to prevent textures from cluttering your gallery.

---

## Mod Folder Structure (Important)

Each mod should be in its own folder or `.zip` archive, and filenames inside must **match** the game's original asset names. Subfolders are scanned recursively — selecting a root collection folder works out of the box.

> **⚠️ Warning:** All filenames must be **lowercase** (e.g. `char000104.png`, not `Char000104.png`), and texture filenames referenced inside `.atlas` must also be lowercase, or the game may crash.

**Example Folder Structure:**
```text
BD2_Mods/
├── Lathel_DarkKnight_IDLE/
│   ├── char000104.skel      (or .json for the skeleton)
│   ├── char000104.atlas     (the atlas mapping file)
│   └── char000104.png       (the texture image)
└── Another_Mod.zip          (zip archives are supported directly)
```

---

## Installing & Uninstalling Mods

1.  **Select**:
    *   In **By Character** view, tap a character to open their costume sheet and install a single mod or batch-install selected costumes;
    *   In **All** view, select multiple mods across characters for batch processing.
2.  **Convert & Auto-Install**: The app reuses clean local originals (or downloads pristine bases from the official CDN), automatically merges extra texture pages if needed, and repacks the bundle. When Shizuku is running, converted files are **automatically installed into the game directory**. **Restart the game** for changes to take effect.
3.  **Uninstall**: Uninstall individual mods from the character sheet / mod list, or use **"Settings → Uninstall all mods"** to restore all modified bundles to their official originals.

Destination path (for manual transfers): `/Android/data/com.neowizgames.game.browndust2/files/UnityCache/`

---

## FAQ

**Q: My mods aren't showing up or appear as "Unrecognized".**
1. Verify the folder added via "Add mod folder" and make sure each mod contains `.skel`/`.json` + `.atlas` + `.png` (or a converted `__data` file);
2. For story, dating, or newly released costumes, run **"Build File Index"** from the top-left menu and then tap **"Rescan"**.

**Q: The game crashes or graphics are broken after installing a mod.**
*   Extra texture pages are merged automatically during conversion;
*   Check that all filenames and `.atlas` texture references are **strictly lowercase**;
*   Ensure the mod is complete (`.png`, `.atlas`, and `.skel`/`.json`).

**Q: What if the app shows a recovery banner on startup?**
If the app was force-stopped by the system mid-installation, tap **"Reconcile & Repair Records"** on the recovery banner. It verifies the game files' SHA-256 hashes read-only and repairs the install ledger without re-converting.

---

## Development & Verification

### One command for the full local verification (no device needed)

```powershell
& (Join-Path $Root 'tools/verification.ps1') -ProjectRoot $Root -Mode LocalAll
```

Runs, in order: dependency resolution -> all JVM unit tests -> all Python tests ->
both ABI release builds -> static audits (layering, APK composition/ABI/native/resources/
DEX key names, native ABI). **Any failing step exits non-zero; skipped tests are not
"passed".** Device tests are **not** included: call `Test-Bd2Device` /
`Test-Bd2ReleaseSmoke` explicitly - nothing is ever installed onto a connected device
by default.

See [`docs/refactor/build-and-verify.md`](docs/refactor/build-and-verify.md) for the full
checklist, clean-build notes and reproducibility boundaries (the release APK is **not**
claimed to be byte-for-byte reproducible - it contains a signature block and ZIP timestamps).

### Toolchain

| Item | Value | Where |
|---|---|---|
| Kotlin / AGP / Gradle | 2.2.20 / 8.11.1 / 8.13 | `gradle/libs.versions.toml` |
| Chaquopy (Python runtime) | 16.1.0, target interpreter **3.8** | `app/build.gradle.kts` |
| compileSdk / minSdk / targetSdk | 36 / **29** / 34 | `gradle/libs.versions.toml` |
| NDK (native stripping) | 26.3.11579264 | `gradle/libs.versions.toml` |
| App release version | `versionName` / `versionCode` | **`gradle/version.properties`** (override with `APP_VERSION_NAME`) |
| Python dependency lock | 9 direct deps pinned with `==` | `app/requirements-android.txt` |

### Two separate ABI packages

| ABI | flavor | Artifact |
|---|---|---|
| arm64-v8a (phones) | `arm64` | `app/build/outputs/apk/arm64/release/app-arm64-release.apk` |
| x86_64 (emulators) | `x64` | `app/build/outputs/apk/x64/release/app-x64-release.apk` |

No universal APK any more: single-ABI flavors keep **both** `lib/` and Chaquopy's
`assets/IMY` limited to the matching architecture.

### Theme & wallpaper

Light / dark / follow-system, plus seven colour presets and Material You dynamic colour
(Android 12+). A custom wallpaper stores only the SAF URI (the image is never copied into
app storage) with an adjustable scrim. **The wallpaper is visible only in the background
layer; text-heavy areas (lists, toolbars, dialogs) always use an opaque surface.**

### Background limits & data recovery

*   Convert / install / scan / prepack all run on **foreground services** with progress in
    the notification. If the system refuses to start the foreground service, the operation
    **fails explicitly** instead of running on regardless.
*   After a process kill, the app only **classifies** the operation journal
    (interrupted / needs reconciliation / unknown / committed) and tells the user what to do
    next. It never replays install steps, never rewrites the ledger, never touches game files.
    Committed facts are never overwritten.
*   Game file commits are **same-directory staging + verification + atomic replace**, and are
    *not* a transaction: each file in a directory copy is committed independently and failures
    are reported per target.

Disk formats and migration rules: [`docs/refactor/data-contracts.md`](docs/refactor/data-contracts.md).

### Licenses

The in-app "Settings -> Open source licenses" screen is generated by AboutLibraries from a
build-time `res/raw/aboutlibraries.json` (SPDX full texts included, **readable offline**).
See [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) and
[`docs/refactor/license-inventory.md`](docs/refactor/license-inventory.md).

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
