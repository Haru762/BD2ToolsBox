# 游戏文件提交的权限回归（真机验收发现，2026-10-11）

- 发现场景：T56 前置的**真机 arm64 验收**（Xiaomi 2509FPN0BC / `f7f409ac`），
  跑既有的 `GameFileCommitTest` 时红了 2 条。
- 结论：**`e993800`（T08/T10）引入的真实回归**，不是设备差异、不是测试写法问题。
  T07 建立并当时跑绿的权限契约被静默破坏，之后没人再跑过这个用例类。
- 状态：**已修复**，两个 ABI（arm64 真机 + x86_64 MuMu）都回到 **13/13**。

## 1. 症状

在 arm64 真机上（x86_64 MuMu 上同样复现，**所以不是设备差异**）：

```
1) commitDoesNotDowngradeTargetPermissions
   java.lang.AssertionError: 提交后必须保持目标原有权限，不许被暂存件的默认权限降级
     expected:<[OWNER_READ, OWNER_WRITE, GROUP_READ, OTHERS_READ]>   # rw-r--r-- 0644
      but was:<[OWNER_WRITE, OWNER_READ]>                            # rw------- 0600
2) newTargetFallsBackToParentDirectoryPermissionsWhenDirectoryHasNoPlainFiles
   java.lang.AssertionError: 目录里没有可参考的文件时，新建目标的权限必须是「父目录 rw 位」
     （父目录 [OWNER_WRITE, OWNER_READ, OWNER_EXECUTE, GROUP_EXECUTE, GROUP_READ]
       ⇒ [OWNER_READ, OWNER_WRITE, GROUP_READ]）                     # 期望 0640
      but was:<[OWNER_WRITE, OWNER_READ, GROUP_READ, OTHERS_READ]>   # 实际 0644
```

`Tests run: 13, Failures: 2`。

## 2. 根因

`app/src/main/java/com/bd2toolsbox/service/ShizukuFileService.kt`：

```kotlin
override fun copyFile(sourcePath: String, destPath: String): Boolean = try {
    val dest = File(destPath)
    dest.parentFile?.mkdirs()
    copyStream(File(sourcePath), dest)
    makeWorldReadable(dest)          // ← e993800 加的这一行
    ...
```

```kotlin
/** shell/root 写入 app 私有目录后，把属主改回父目录的 uid，否则 app 读 EACCES。 */
private fun makeWorldReadable(dest: File) {
    try {
        val parent = dest.parentFile ?: return
        val st = android.system.Os.stat(parent.absolutePath)
        android.system.Os.chown(dest.absolutePath, st.st_uid, st.st_gid)
        android.system.Os.chmod(dest.absolutePath, 384)   // ← 硬编码 0600
    } catch (e: Exception) {
        Log.w(TAG, "chown/chmod dest failed: ${dest.absolutePath}", e)
        dest.setReadable(true, false)                     // ← 退回 0644
    }
}
```

而权限契约**本来已经实现好了**，在 `core/fileaccess/AtomicFileWriter.preservePermissions`：
已存在的目标 → 照搬它的 POSIX 权限；新建目标 → 先抄同目录兄弟文件、没兄弟才退回
父目录的 rw 位（屏蔽执行位）。`copyStream` 用的正是 `AtomicFileWriter`。

**所以这一行是在正确结果之后又把权限覆盖掉**：

| 设备 | `Os.chown` | `Os.chmod(0600)` | 结果 | 违反 |
|---|---|---|---|---|
| arm64 真机 | 成功 | 成功 | `0600` | 目标被降级（游戏进程读不到） |
| x86_64 MuMu | 成功 | 抛异常 → 走 catch | `0644`（`setReadable`） | 把原本 `0640` 的目标悄悄放宽 |

两个后果都违反契约，而且**同一份代码在两台设备上产出不同权限** —— 这比单纯的错误更难发现。

函数名 `makeWorldReadable` 与它实际做的 `chmod 0600` 也自相矛盾（名字说「让全世界可读」，
代码说「只有属主可读」）。

## 3. 引入时间（`git log -S` 实证）

```
$ git log --oneline -S "makeWorldReadable(dest)" -- app/src/main/java/com/bd2toolsbox/service/ShizukuFileService.kt
e993800 T08/T10: 记录 x86_64 授权验收，并补上恢复门禁
```

`e993800`（2026-09-22）的 diff 里就是那两处新增：`copyFile` 里的调用 + `makeWorldReadable` 本体。

- T07（`06f4384`）建好权限搬运 + `GameFileCommitTest`，当时 **13 条设备用例全绿**
  （`docs/refactor/evidence/T07.md` §5.3）。
- `e993800` 之后**再没有人跑过 `GameFileCommitTest`** —— 回归因此静默了 19 天，
  直到 2026-10-11 真机验收时才被跑出来。

> 这也说明「测试存在」不等于「防线存在」：这个用例类一直在仓库里，
> 但它没有进入任何常规验证入口（`verification.ps1` 的 `Test-Bd2Device` 是按类调的，
> 没有一份「哪些类必须跑」的清单）。已记入 T56 的待办。

## 4. 修法

**只保留 chown，去掉 chmod 与 `setReadable` 回退**：

```kotlin
private fun restoreParentOwner(dest: File) {
    try {
        val parent = dest.parentFile ?: return
        val st = android.system.Os.stat(parent.absolutePath)
        android.system.Os.chown(dest.absolutePath, st.st_uid, st.st_gid)
    } catch (e: Exception) {
        Log.w(TAG, "chown dest failed: ${dest.absolutePath}", e)
    }
}
```

理由：

- 「shell/root 写的文件 app 读不到」的真正成因是**属主**，chown 解决它；权限位不是。
- 权限位是 `AtomicFileWriter` 的契约（有 3 条设备用例 + 3 条 JVM 用例直接守着），
  这里再动一次只会与它打架。
- chown 失败时**保持可见的失败**（调用方读到 EACCES），不用「偷偷放宽权限」盖过去 ——
  后者正是把游戏文件 `__data` 悄悄改成 world-readable 的来源。
- 函数改名 `restoreParentOwner`，与它实际做的事一致。

## 5. 验证

| 项 | 结果 |
|---|---|
| arm64 真机 `GameFileCommitTest` | **13/13 PASS**（修复前 11/13） |
| x86_64 MuMu `GameFileCommitTest` | **13/13 PASS**（修复前 11/13） |
| 全量 JVM（`testArm64DebugUnitTest` + `testX64DebugUnitTest`） | **694/694 × 2**，failures=0 errors=0 skipped=0 |
| arm64 真机回归集（24 个目标，见 [device-arm64.md](device-arm64.md)） | **全部一次通过** |

## 6. 顺带修掉的**门禁缺口**（不是产品缺陷）

`InstallServiceLifecycleTest` / `ScanServiceTest` 的通知断言要求
`POST_NOTIFICATIONS` 已授权（它们的 `@Before` 会明确失败并给出
`adb shell pm grant com.bd2toolsbox android.permission.POST_NOTIFICATIONS` 的提示）。
而 `install -r` 每次都会把该权限打回未授权，`tools/verification.ps1` 的 `Test-Bd2Device`
**没有补这一步** —— 于是「刚装完就跑」必红、重跑一次（权限被上一轮用例打开）又绿，
表现为假抖动。

已在 `Test-Bd2Device` 的安装循环之后补上 `pm grant`（API 33 以下失败即忽略），
与它已有的字体/夜间模式保存-恢复是同一类「恢复用例声明的运行前提」。

## 7. 未覆盖

- 修复后**没有**再验证真实 Shizuku 路径（`copyFile` 跑在 shell/root uid 下）的
  可读性：T19 的真实 Shizuku 授权/断连本来就未验，本轮没有推进它。
  去掉 `setReadable` 回退后，若 chown 在真机 Shizuku 路径上失败，调用方会读到
  **可见的 EACCES**（而不是拿到一个被放宽权限的文件）—— 这是有意的取舍，但**未实测**。
- 没有补一条「提交后权限与目标原权限一致」的 JVM 级回归（权限语义依赖真实文件系统，
  只能留在设备用例里）。
- 没有建立「哪些设备用例类必须跑」的清单 —— 这正是本次回归能静默 19 天的原因，
  记入 T56。
