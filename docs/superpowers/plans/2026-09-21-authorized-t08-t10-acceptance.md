# T08/T10 授权设备验收与提交计划

> 按 subagent-driven-development / executing-plans 执行；本计划细化主清单的剩余验收，不新增产品功能。

**授权：** 用户选择 1、2、4：允许本次白名单设备真实游戏目录测试、完整业务列表冷启动验收，以及最终 Git 提交；arm64 设备测试明确延后。无 push/发布授权。

**目标：** 验证替换后进程死亡仍能根据目标内容补账且不重复安装；验证实际 minified UI 的扫描保存、进程重启、列表恢复；有证据后提交本轮重构改动。

**安全边界：** 仅 MuMu x86_64 / API32，Android ID `f78bb6042e832709`。游戏必须停止。选择一个小型真实 bundle，主机保存原字节、SHA、权限及应用账本/日志/配置/快照。重新编码同一 bundle 得到不同字节但相同资源内容，不启动游戏读取测试产物。测试后恢复该文件和测试相关应用状态，核对字节/权限。保留所有失败证据；任何不确定结果停止后续写入。不给子代理设备或提交权限。

## 任务 1：测试前准备与 move 门禁检查

- [x] 核对工作树、进程、设备与当前代码，记录已保全文件清单。
- [x] 检查生产 `moveFilesToGame` 所有 journal 返回值。现有代码忽略 beginSubmit/markStaged/markTargetReplacing，失败分支 finishSubmit(null) 可能清掉未知替换窗口；先用 JVM 失败用例固定门禁和 journal 保留，再最小修复。
- [x] 抽取 `MoveInstallSubmitter`，由现有 Repository 决定租约、阶段及原子记账，MainViewModel 只传 move/commit 回调并显示真实结果。不修改 unrelated UI、转换算法或业务字段。
- [x] 编译及定向测试；独立审查差异。核心断言：门禁失败时 `moveCalls == 0`；move 返回 false/异常时 journal 仍 Pending；记账失败不能清 journal；完整成功才允许 finishSubmit 关闭。2026-09-22 补了 `markTargetReplacing` 失败用例，`MoveInstallSubmitterTest` 12/0/0/0。

## 任务 2：T08 真实目标的受控崩溃与恢复

- [x] 新增 `app/src/androidTest/java/com/bd2toolsbox/service/RealGameRecoveryDeviceTest.kt`。默认拒绝执行，要求显式 runId、allowGameWrite、固定 Android ID 和主机准备的配置/摘要。
- [x] 转换提交使用 `ConvertedInstallSubmitter`、move 提交使用 `MoveInstallSubmitter`，真实替换使用 Shizuku；测试内容仅覆盖选定 bundle/hash/__data。
- [x] 在 `commitLedger` 回调开头写入阶段 marker，然后等待主机杀死进程，绝不返回伪成功。主机先断言 journal 含 TARGET_REPLACED 且 ledger 未变化，再 force-stop 并证明原 PID 消失。
- [x] 用实际 MainActivity 冷启动证明待核对提示出现；点击“核对并修复记录”驱动生产恢复路径。断言目标 SHA 未变化、账本新增事实与冻结记录完全相等、无关记录不变、journal 已关闭。
- [x] 再次冷启动/核对，检查账本无重复和游戏目标 inode/mtime/SHA 未再变化。保存截图/XML/各阶段文件。
- [x] 转换与 move 模式分别验证。x86_64 上 converted `200114` 与 move `200548` 为 PASS，更早失败保留；最终都恢复了原目标。arm64 未跑。

## 任务 3：T10 实际业务列表保存与冷启动

- [x] 使用新建测试源目录和 Android 系统目录选择器取得真实持久 URI 授权，不改已有素材。
- [x] 通过真实生产扫描生成至少两个可识别列表项并保存 canonical 快照；断言 version/savedAt/mods/sourceDirs、条目数及字段，账本不变。
- [x] 停止目标进程，证明新 PID，使用 am start 冷启动真实 minified MainActivity，不从 instrumentation 启动 Compose。
- [x] 在不允许重新扫描伪造恢复结果的条件下验证完整预期列表（不是任意一个名称或 chrome），记录快照来源、UI 截图/XML、进程与原文件指纹。x86_64 两条夹具通过，并有无快照对照。
- [x] 恢复测试前配置/快照及仅本次新增的授权，保留测试源/产物到可追溯隔离位置，不批量删除设备数据。

## 任务 4：验证、记录、提交

- [x] 2026-09-22 全量 JVM **416/0/0/0**，宿主 `unittest discover -s tests` **122** 项通过、6 项因缺少真实 catalog 快照跳过。x86_64 设备证据沿用已通过的授权验收；本次没有重编 Release，因为生产源码时间早于那次 APK。arm64 未跑。
- [x] 独立复审 T08/T10 新测试与生产改动。arm64 仍记录 deferred，不用 x86_64 代替。
- [x] 修正 native patch 在 core.autocrlf 下的哈希/换行契约，核对可提交输入。
- [x] 更新任务证据、授权范围和未完成条件；不清除历史失败、不用宿主验证冒充设备验证。T12 仍是 IN_PROGRESS。
- [x] 仅显式列举相关源码/测试/文档/native 输入 staging，排除 local、APK、mapping、原始数据、临时 patch.py、秘密。查看 staged diff 后提交。不得 push/发布。
