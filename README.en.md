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
