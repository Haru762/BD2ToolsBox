# Chaquopy: a minimal ProGuard configuration.
# See https://chaquo.com/chaquopy/doc/current/android.html#proguard.

-keep class com.chaquo.python.** { *; }
-keep class com.chaquo.python.runtime.** { *; }

# Standalone release-probe reflects this production entry through the target app classloader.
# Preserve only the facade class name and its public static Unit/void entry, not the package.
-keep class com.bd2toolsbox.core.python.PythonRuntime {
    public static void ensureStarted(android.content.Context);
}

# Shizuku
-keep class com.bd2toolsbox.service.ShizukuFileService { *; }

# Gson: preserve generic signatures for TypeToken
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Keep data model classes used with Gson serialization
-keep class com.bd2toolsbox.data.model.** { *; }

# 装入账本与操作日志（T08）：DTO 的字段名就是磁盘格式的一部分。
# 与上面 data.model 那条同一个理由 —— Gson 按字段名读写，混淆后字段名一变，
# 老账本/老日志就读不回来（静默变成「没有记录」）。这里刻意用包级规则而不是逐个类列举：
# 该包将来新增的持久化 DTO 自动被覆盖，不必记得回来加一行。
#
# !! 这条规则**只覆盖 core.storage 包**（T09 二轮整改更正）。
# 首版 T09 曾在此声称「包级规则自动覆盖新增 DTO」，于是把一个持久化 DTO
# （BundleBackupRepository$BackupMeta）放在了 **data.repository** 包里，以为它被保护 ——
# 实际没有：R8 把它的字段名改成了 a/b/c，Gson 写出的 sidecar 与读侧的字面量键对不上，
# release 包里整条可信链静默失效（debug 下完全看不出来）。
# 教训不是「再加一条包级规则」，而是**持久化格式不许依赖反射字段名**。
# T09 的 sidecar 因此改成两侧都用显式 JSON 键（见 BundleBackupRepository 的
# KEY_SCHEMA_VERSION 那组常量），与 R8 怎么改名无关 —— 不需要在这里为它加任何规则。
#
# 注：这条包级规则**确实覆盖嵌套类**（`…ModSnapshotStore$SnapshotV1` 也吃到）。
# T10 实测确认了这一点（见下面那段），因此 T10 为快照 DTO 另加的显式规则是**纵深**，
# 而不是「不写就会坏」的那类 —— 理由写在下面。
-keep class com.bd2toolsbox.core.storage.** { *; }

# 冷启动快照（T10）：DTO 的字段名同样是磁盘格式的一部分。
#
# ## 实测：没有 keep 规则时，R8 会做与 0.2.16 完全相同的事
#
# 本工作区 `./gradlew :app:minifyReleaseWithR8` 现场复现（T10.md §6.2）：
# 把 core.storage 的全部 keep 规则去掉后，release mapping.txt 里是
#     com.bd2toolsbox.core.storage.ModSnapshotStore$SnapshotV1 -> n1.t:
#         int version -> a
#         long savedAt -> b
#         java.util.List mods -> c
#         java.util.List sourceDirs -> d
# —— 与 0.2.16 historical 包里 MainViewModel$ModListSnapshot 的 a/b/c 是**同一个现象**。
# 磁盘键名一旦漂成这样，Gson 按新名字读写，老快照静默读不回来。
#
# ## 下面两条规则是**纵深**，不是「今天不写就会坏」
#
# 实测：上面那条包级规则已覆盖嵌套类，所以下面两条去掉之后字段名照样是规范名
# （`getVersion`/`getSavedAt`/`getMods`/`getSourceDirs` 未被改名）。
# 留着的理由是**磁盘格式的守卫不该只依赖一条包级规则**：T13/T14 会调 R8 配置，
# 而「整包放开」正是最容易被换掉/收窄的一条；届时这两条显式规则替持久化格式兜底，
# 不随包级规则的存废而变。这是 T09 二轮那条教训的另一面 ——
# 「持久化格式不许依赖会被改的东西」：今天是反射字段名，明天是包级 keep。
#
# 为什么不 `-keep class …ModSnapshotStore { *; }` 整类放开：那会把 Store 的实现方法也钉死，
# 白占体积，且掩盖了「到底哪几个东西是磁盘契约」这个事实。
#
# ## 关于 version 字段（0.2.16 就是被 R8 折叠掉的那个）
#
# 0.2.16 的 ModListSnapshot 里 version **整个字段被消掉**（mapping 里无任何条目，
# 但构造器签名仍是 (int,long,List,List)）：R8 能证明它恒等于 SNAPSHOT_VERSION、
# 只被用来和同一个常量比较，于是把比较折成 false、分支变死、字段被删 ——
# 生产包里根本没有版本闸。T10 的 SnapshotV1.version 由 **Gson 反射写入**（值来自磁盘文本），
# R8 无法证明它是常量，所以「常量传播」这一半天然不成立；剩下唯一能毁掉它的手段是
# 改名或删字段，由下面这一条挡住。
#
# **不依赖 `@SerializedName`**：DTO 的注解值与字段名故意写成同一个字面量，所以即使
# keepattributes 被去掉、Gson 退回按字段名读写，磁盘格式也不变（见 SnapshotEntry 的类注释）。
# 这条 -keepattributes 是纵深，不是唯一防线（`Signature` 那条已经在上头，这里补注解属性）。
-keepclassmembers class com.bd2toolsbox.core.storage.ModSnapshotStore$SnapshotV1 { <fields>; }
-keepclassmembers class com.bd2toolsbox.core.storage.ModSnapshotStore$LegacyObfuscatedSnapshot { <fields>; }
-keepattributes RuntimeVisibleAnnotations
