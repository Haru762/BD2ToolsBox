# 重构以外的补充问题清单

- 基线：2026-09-14，HEAD b83c1c0，当前仍为 main；本轮只读检查生产代码。
- 范围：数据写入、ZIP 输入、原版可信度、异步请求、Shizuku/前台任务、扫描错误协议和测试有效性。不是完整安全审计。
- 证据级别：下面列出的代码路径与缺少的校验已核实；风险发生需要相应输入、故障或调度条件。本轮没有安装 APK、操作真实游戏目录、注入磁盘故障或进行 Android 实机复现，不能推断用户已经发生数据丢失。
- 高优先级建议先作为新重构分支的回归门槛；本文件不代表已获准修改代码、创建分支或发布安全补丁。

## R1（高）ZIP 解压可越出目标目录，旧 Android 支持范围缺少防护

**证据：** [MainViewModel.kt:1553–1571](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt#L1553>) 中，extractModInto 直接用 File(dest, entry.name)，随后 mkdirs/outputStream；过滤只排除空名和 .modfile，没有规范化路径后的包含关系检查。

**触发条件：** 一个正常可识别的 MOD ZIP 同时含 ../outside.txt 一类条目。扫描会保留 entry.name；resolver 使用 basename 做匹配，并允许未识别文件和已识别文件共存为 KNOWN，不能依赖资源识别拦截危险路径。参见 [ModRepository.kt:616–634](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/data/repository/ModRepository.kt#L616>)、[resolver.py:163–332](<D:/bd2 mod/bdroid_src/app/src/main/python/resolver.py#L163>) 及 [现有 unresolved 测试:173–181](<D:/bd2 mod/bdroid_src/tests/test_resolution_catalog_fallback.py#L173>)。

**影响：** 在未由系统拦截该 entry 的环境下，写入可逃离指定解压目录，触及应用权限范围内的其他文件；不是声称能任意写系统目录或取得 root。

**系统边界：** 当前 minSdk29/targetSdk34。Android 14 为 target34+ 的 ZipFile/ZipInputStream.getNextEntry 增加了对 .. 或绝对路径的校验，因此不能声称所有设备都可触发。项目仍支持 API29–33，需要应用自己的边界检查。[Android 官方说明](https://developer.android.com/about/versions/14/behavior-changes-14#zip-path-traversal)（本轮已读取）。

**改进方向：** 规范化每个输出路径，确认它严格位于目标根下再创建目录/写文件；拒绝越界条目，并给出导入错误。归档数量、解压大小上限可作为同一输入模块的后续加固，但本轮未验证 ZIP bomb 场景。

**待执行回归：** 在隔离测试目录中放置 ../outside.txt、嵌套回退及正常子目录条目；断言边界外文件从未创建/覆盖。覆盖 API29–33 和 API34+，不在真实游戏目录做试验。

## R2（高）游戏文件不是原子替换，失败可能留下截断文件

**证据：** [ShizukuFileService.kt:98–119](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/service/ShizukuFileService.kt#L98>) 的 copyStream 直接打开最终 dest.outputStream 并 copyTo。旧文件会先被截断，没有同目录临时文件、完成验证、原子替换或回滚。

**触发条件：** 目标打开后出现空间不足、读取/写入中断或服务进程退出。copyFile/copyDirectory 会返回失败，但最终目标可能已经被截断或部分覆盖；“报错了”不等于“原文件还在”。

**影响：** 游戏的 __data 可能只剩部分内容。前台服务降低被回收概率，但不能替代提交/恢复机制。

**相关缺口：** [MainViewModel.kt:1709–1712](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt#L1709>) 没有检查 saveBackup 返回值，就记录“已备份原版”；[1824–1829](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt#L1824>) 随后继续覆盖。备份开关开启不等于已确认备份成功。

**改进方向：** 定义单 bundle 的提交点，写目标同目录临时文件、校验后替换；明确定义部分成功与失败恢复。备份失败必须作为真实结果传播，由策略决定中止或让用户明确确认，而不是静默记作成功。

**待执行回归：** 用可控 FileAccess fake 在复制半途抛错，原目标保持原样；备份存储返回 false 时不能打印/展示成功备份；验证中断后的重试不误用半成品。多文件/多 bundle 的整批事务语义另行明确，不把单文件 rename 宣称为整批原子性。

## R3（高）安装账本写入不原子，而且失败被吞掉

**证据：** [InstalledModRepository.kt:77–94](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/data/repository/InstalledModRepository.kt#L77>) 直接 writeText 覆盖 installed_mods.json，失败只 printStackTrace，put/putAll 返回 Unit。[41–54](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/data/repository/InstalledModRepository.kt#L41>) 在读取/解析失败时返回 emptyMap。

**影响链：** 写入中断 → JSON 残缺 → 后续读取被当作空账本 → 下一次 put 可能用新条目覆盖掉其余历史记录。文件数据尚在，也会影响同 bundle 合并、安装状态与卸载判断。

**锁的边界：** 代码已有 @Synchronized，不能说“完全没加锁”；但它只保护同一实例，且不提供磁盘原子性。当前多个 ViewModel 可分别创建仓库，旧安装协程又刻意保留旧 ViewModel，实例间保护仍需统一所有权。

**改进方向：** 原子写入/恢复副本；区分不存在、有效空表、读取失败/损坏；保存结果可观察；不能在读失败后无条件按空表写回。Hilt 可以收口实例，不能自动解决这些存储协议问题。

**待执行回归：** 保存失败后旧账本保留；损坏文件不被默认为空表后立即覆盖；并发更新不丢条目；失败明确传给调用者。

## R4（高）“原版”判定把同尺寸误当作内容一致

**证据：** [MainViewModel.kt:3274–3287](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt#L3274>) 在 hash 目录名一致、__data 大小等于 catalog 大小时直接判 PRISTINE；文件大小等于备份大小也可判 PRISTINE。没有对当前文件内容重新验证。

目录名本身来自原来的缓存布局，MOD 产物会沿用它，不等于当前文件的校验结果。代码注释 [3208–3213](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt#L3208>) 也说明主要通过大小区分。

**触发条件：** 修改后的文件恰好与原版或备份等长，目录名不变。两个不同内容的同长度文件会进入相同分类分支。

**影响：** [tryUseLocalBundle:1643–1658](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt#L1643>) 会将该文件当原版复用；[backupPristineOriginal:1700–1712](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt#L1700>) 可能把它保存为原版备份。后续重打包可能叠加旧 MOD，或还原后仍有旧改动。

**改进方向：** 大小只能作为快速排除条件，不足以证明可信原版。至少在复用/备份前进行有可信基准的内容验证；无法确认就保留 UNKNOWN。先确认 catalog hash 的具体语义与 CDN/游戏文件格式差异，不直接假定它就是文件 MD5/SHA256。

**待执行回归：** 两个等长但内容不同的 bundle 不能都被授权作为可信基底；经本项目重编码的真实原版有单独、可靠的验证来源。

## R5（中）攻略旧请求可以覆盖新页面，返回后还可能重新打开正文

**证据：** [GuideViewModel.kt:215–237](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/GuideViewModel.kt#L215>) 每次搜索直接 launch 并写共享列表，没有请求身份或取消管理。[282–295](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/GuideViewModel.kt#L282>) 的正文请求同样直接回写 _detail。

[302–315](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/GuideViewModel.kt#L302>) 的返回/关闭只把状态设为 Closed，并不取消或使正在进行的请求失效；[GuideScreen.kt:284–289](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/screens/GuideScreen.kt#L284>) 又会为非 Closed 状态重新显示阅读器。

**触发条件与影响：**
- 搜索 A 慢、搜索 B 快：A 后到会盖掉 B，标题与列表可能不匹配。
- 搜索中切回分类：旧搜索结果仍可覆盖该分类列表。
- 正文加载中返回：旧请求完成写入 Ready，已关闭的阅读器会重新出现。

**改进方向：** 请求 Job + generation/token 或 flatMapLatest；只有当前请求可提交状态。退出/切换使请求失效，取消不能当普通业务错误；finally 的 loading 清理也要属于当前请求。

**待执行回归：** 用可延迟的假数据源控制完成顺序，断言最终列表属于最新查询，关闭正文后完成旧请求仍保持 Closed。本轮未在 Android UI 上复现。

## R6（中）目录读取错误被编码成“成功的空列表”

**证据：** [ShizukuFileService.kt:47–88](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/service/ShizukuFileService.kt#L47>) 在异常时返回 []，listFiles 返回 null 也会被安全调用静默跳过。[ShizukuManager.kt:203–228](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/service/ShizukuManager.kt#L203>) 将其当正常最后一页，最终返回空 Map，而不是失败。

**影响：** 上层虽然区分 local == null 和有效列表，但底层把错误折叠成了有效空列表；[MainViewModel.kt:3276–3278](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt#L3276>) 会把查不到的资源判为 ABSENT。无法访问、目录不存在和目录真正为空缺少可区分的错误协议。

**改进方向：** 明确成功数据、目录缺失、权限/IO 失败；用可判别结果或可传播异常表达。不能在已经失败的分页中继续提交一个看似完整的结果集。

**待执行回归：** 第一页/中途一页失败、listFiles 返回 null、真实空目录分别得到不同结果；错误不显示为“扫描成功但零资源”。

## R7（高，需故障时序验证）后台任务与 Shizuku 连接生命周期冲突

**证据：** [MainViewModel.kt:298](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt#L298>) 的 installScope 和 [4420–4436](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt#L4420>) 明确让工作跨越 ViewModel 销毁。但 [MainActivity.kt:953–954](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/MainActivity.kt#L953>) 在 isFinishing 时解绑，底层 [ShizukuManager.kt:164–171](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/service/ShizukuManager.kt#L164>) 使用 remove=true 的 unbindUserService。

**影响条件：** 如果正在运行的任务此刻仍使用该服务，前台任务可能存活而 Binder 服务被移除，尤其正在覆盖文件时会叠加 R2 的风险。后续某些调用可重绑，所以不能声称“每次退出都会失败”。

**改进方向：** 生命周期/连接租约由任务宿主控制；页面退出不等于最后一个后台使用者释放。对无任务时的正常清理继续保留，避免修复后变成永久常驻。

**待执行回归：** Binder 调用中退出页面、返回通知重开页面、任务完成后退出、纯配置变更分别验证；确认不提前关闭服务，也不留下无人持有的长期任务。

## R8（高，重构门槛）部分“集成测试”没有验证生产行为

**证据：** [PrepackCacheIntegrationTest.kt:40–58](<D:/bd2 mod/bdroid_src/app/src/test/java/com/bd2toolsbox/integration/PrepackCacheIntegrationTest.kt#L40>) 把 totalTargets 固定为1000、cacheCount 固定为180，然后断言 cacheRatio<0.3 和 cacheCount<800，没有调用缓存或预解包实现。其他部分用例也直接断言局部硬编码状态。

**影响：** 生产逻辑无论修好或变坏，这些断言都不能反映回归，甚至把“缓存不足”的示例当作期望成立的条件。不能用这类测试的绿色结果证明重构安全。

**改进方向：** 在真实模块接口上驱动生产代码，可用 fake 控制外部 IO；断言真正的产物、状态与行为，并确认引入对应缺陷时测试会变红。已有 Wiki renderer、快照/发现、缓存分槽、Python resolver/checkpoint 等测试不应被一概否定。

## 额外性能/正确性观察（不当作已测出的性能故障）

listBundleDirectoryPaged 每一页都会重新枚举完整目录再截取 offset/limit，并不是从稳定快照上分页。N 条记录、页长 P，会做约 ceil(N/P) 次全量目录枚举；目录在分页过程中变化且枚举顺序无保证时，还要验证重复/遗漏。见 [ShizukuFileService.kt:58–85](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/service/ShizukuFileService.kt#L58>)。本轮没有测量耗时，不能把它直接说成用户慢扫描的根因。

## 已排除的泛化结论

- UpdateRepository 已查询 DownloadManager.STATUS_SUCCESSFUL，因此不报告“收到广播就完全不看下载是否成功”。包/签名校验若要扩大审核，应另行检查系统安装器保障与实际需求。
- ZIP 风险明确区分 Android 14 的系统防护与仍受支持的旧平台。
- 安装账本有实例级 @Synchronized；问题是持久化失败语义、原子性和实例范围，不是完全没有同步。
- 新装入的转换产物已有完整 bundle 校验入口；这不能替代对当前游戏文件作为“可信原版”的验证，但也不能说所有外来产物都未经校验。

## 建议优先顺序

先给 R1–R4 与 R7 建立安全/故障回归，再处理 R5–R6 的异步与错误状态；R8 是整轮重构的基础门槛。框架迁移继续按计划推进，但不要认为引入 Hilt 或替换 JSON 库会自动修复上述行为。
