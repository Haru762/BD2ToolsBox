[![GitHub release](https://img.shields.io/github/v/release/Haru762/BD2ToolsBox)](https://github.com/Haru762/BD2ToolsBox/releases/latest)

[简体中文](./README.md) | [English](./README.en.md) | [繁體中文](./README.zh-TW.md)

# BD2 ToolsBox

**在手機上直接管理和裝入 Brown Dust 2 的 mod，不需要電腦。**

---

## 功能

*   **全程手機操作**：解包、紋理轉換（ASTC）、重打包全部在安卓裝置上完成。
*   **按角色瀏覽**：自動掃描 mod 資料夾，按遊戲角色分組，防止衝突。
*   **批次處理**：一次勾選多個不同分組的 mod，一起轉換裝入。
*   **Shizuku 整合**：轉換完一鍵移入遊戲目錄。
*   **動畫預覽**：長按列表裡的 mod，直接播放它的 Spine 動畫。
*   **攻略板塊**：GameKee 棕塵2分區攻略——分類樹、搜尋、留言區，正文本地快取，可離線重讀。
*   **兌換碼板塊**：兌換碼列表 + 官方通道一鍵兌換，填一次遊戲內暱稱，獎勵由官方直發遊戲內信箱，不用登入、不用進遊戲。
*   **內建工具**：圖集合併、遊戲 bundle 解包。

---

## 上手

### 需要

*   Android 8 及以上。
*   已安裝官方最新版 Brown Dust 2。
*   [Shizuku](https://shizuku.rikka.app/)——掃描與移入遊戲目錄都靠它。沒有 Shizuku 能轉換，但裝不進遊戲。

### 安裝

1.  從 [Releases](https://github.com/Haru762/BD2ToolsBox/releases) 下載最新 APK 安裝。模擬器上務必強制 x86_64，否則會走 ARM 轉譯閃退：
    `adb install --abi x86_64 BD2_ToolsBox_debug.apk`
2.  啟動 Shizuku，按首次引導完成授權。
3.  點「**新增 mod 資料夾**」，選到你放 mod 的目錄。

> 建議在手機儲存空間裡建一個專用目錄（如 `.BD2_Mods`），把所有 mod 集中放在裡面。

---

## mod 的目錄結構（重要）

每個 mod 一個資料夾，裡面的檔名必須與遊戲原始資源**完全一致**。zip 包也認，任意層級都會被找出來；子資料夾會自動往下找，選合集根目錄也可以。

> **⚠️ 注意：所有檔名必須全小寫**（`char000104.png` 而不是 `Char000104.png`），且 `.atlas` 裡引用的圖片檔名也得是小寫，否則遊戲可能當機。

**目錄結構範例：**
```
.BD2_Mods/
├── Lathel_DarkKnight_IDLE/
│   ├── char000104.skel      （骨架，或 .json）
│   ├── char000104.atlas     （圖集映射）
│   └── char000104.png       （貼圖）
└── Another_Mod/
    └── ...
```

---

## 裝 mod

1.  **勾選**：在列表裡勾上要裝的 mod。
2.  **轉換**：點「**開始**」——app 會自動下載所需的原版檔案並重打包，彈窗顯示即時進度。
3.  **裝入**：轉換完點「**一鍵裝入遊戲**」（需 Shizuku 執行中）——檔案移入遊戲目錄，**重新啟動遊戲後生效**。
4.  **解除安裝**：「一鍵解除全部 mod」或單個 mod 的「解除安裝」，還原原版。

手動搬運時的目標路徑：`/Android/data/com.neowizgames.game.browndust2/files/UnityCache/`

---

## 其他工具

### 動畫預覽
不確定 mod 裝上什麼效果？**長按**列表裡的 mod 即可開啟預覽播放。

### 圖集合併（自救工具）
裝入後貼圖錯亂時：只勾選出問題的那個 mod，點合併。原始檔會備份進 `.old` 子資料夾，重新裝入即可。

### bundle 解包
想提取原版遊戲檔案：不勾任何 mod，點解包圖示，選要解的 `__data` 檔案，產物儲存在 `Download/outputs`。

---

## 常見問題

**Q：列表裡看不到我的 mod。**
檢查「新增 mod 資料夾」選的目錄對不對；mod 要按上面的結構放；檔名要和遊戲原始資源一致。

**Q：裝完 mod 遊戲當機。**
多半是這兩種：貼圖數量和原版對不上（app 一般會自動修）；或檔名沒全小寫（見上文警告）。先試試「圖集合併」工具；還不行就是 mod 本身損壞或不相容。

**Q：裝入失敗。**
常見原因：網路不好（原版檔案沒下載下來）、mod 檔名不對、轉換出錯。看彈窗裡的錯誤訊息定位。

**Q：裝完後遊戲裡貼圖錯亂。**
mod 不完整或不相容安卓版。完整的角色 mod 需要三個檔案：`.png`、`.atlas`、`.skel`（或 `.json`）。

---

## 致謝

本專案站在這些專案之上：

*   [Ark-Repoleved/BDroid_X](https://github.com/Ark-Repoleved/BDroid_X)——本專案直接改造自它（其授權狀態見下文）。
*   [browndust2-repacker-android](https://codeberg.org/kxdekxde/browndust2-repacker-android)（GPL-3.0）——重打包與紋理壓縮核心。
*   [ReDustX](https://github.com/Jelosus2/ReDustX)（MIT）——json 轉 skel 邏輯與 CDN 下載方法。
*   [UnityPy](https://github.com/K0lb3/UnityPy)（MIT）——Unity 資源讀寫。
*   [astc-encoder](https://github.com/ARM-software/astc-encoder)（Apache-2.0）——官方 ASTC 紋理編碼器。
*   [BD2ModManager](https://github.com/bruhnn/BD2ModManager)（GPL-3.0）——角色詮釋資料（中文名 / 性別 / 頭像）來源。
*   資料來源：[browndust2modding.pages.dev](https://browndust2modding.pages.dev/characters)（角色表）、[Brown-Dust-2-Asset](https://github.com/myssal/Brown-Dust-2-Asset)（頭像與立繪）。

各元件在程式碼裡的位置與完整清單見 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

---

## 授權條款

本專案按 **GNU General Public License v3** 分發，全文見 [`LICENSE`](LICENSE)。

GPLv3 不是自由選擇：轉換核心（`python/repacker/repacker.py`）源自 GPL-3.0 專案，授權隨之傳染。開源前有兩件事需要說清楚（詳見 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)）：

-   本專案從 [Ark-Repoleved/BDroid_X](https://github.com/Ark-Repoleved/BDroid_X) 改造而來，該倉庫目前**沒有 LICENSE 檔案**，其原創部分的再分發授權尚未明確。來源已標注，並已向上游提 issue 請其表態；若你是上游作者對此有異議，請開 issue，我們會立即配合處理。
-   `pixi-spine` 使用與 GPL 不相容的 SPINE-LICENSE，**不隨 APK 分發**——首次使用動畫預覽時由 app 從 npm CDN 自行下載。

*Brown Dust 2* 及其全部遊戲素材版權屬 Neowiz / Round8 Studio 所有。本工具只修改本地遊戲快取，不分發任何遊戲素材。
