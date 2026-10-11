# =============================================================================
# R8 规则（T52 逐条复核）
#
# 原则：**每条自定义 keep 都要能说出「谁在动态调用它」以及「哪个用例覆盖它」**。
# 不用「保留所有业务类」、不用「忽略所有警告」、不用 -dontoptimize。
#
# T35 起序列化全部走 kotlinx.serialization：磁盘/网络键名来自 @SerialName 的**字符串
# 常量**，由编译器生成的 `$$serializer` 直接引用，**不依赖反射字段名**，所以
# 「按包 keep 住所有 DTO」这条老防线已经没有必要（它的理由在 Gson 时代才成立）。
# 原先的 `-keep class com.bd2toolsbox.data.model.** { *; }` 与
# `-keep class com.bd2toolsbox.core.storage.** { *; }` 已按实测证据删除
# （证据与前后对照见 docs/refactor/evidence/r8-rules.md）。
# =============================================================================

# --- 1. Chaquopy 的 Python 桥接 ---------------------------------------------
# 动态调用者：Python 运行时按类名/方法名反射调用 Kotlin 侧桥接类
# （com.chaquo.python.PyObject / PyApplication 等）以及 Cython 生成的代码。
# 覆盖：T18 NativeEngineSmokeTest、T50 AndroidPythonStagingTest。
# **这里不重复声明**：Chaquopy 16.1.0 自己把
# `-keep class com.chaquo.python.** { * ; }` 等规则接入 R8
# （见 mapping/<variant>Release/configuration.txt 的 section 头
# “The proguard configuration file for the following section is
#  …\app\build\python\proguard-rules.pro”），以及 chaquopy_java AAR 的 consumer rules。
# T49 已核对：删掉本文件里重复的两行后，configuration.txt 里该规则仍在。

# --- 2. release-probe 反射调用的生产入口 -------------------------------------
# 动态调用者：release-probe/src/main/java/com/bd2toolsbox/integration/ 下的探针
# 通过 `targetContext.classLoader.loadClass(...)` + `getMethod(...)` 调用
# PythonRuntime.ensureStarted —— 这是跨包反射，R8 看不到。
# 覆盖：Test-Bd2ReleaseSmoke -Case 'baseline-startup'。
# 只保留门面类名与这一个 public static 入口，**不**保留整个包。
-keep class com.bd2toolsbox.core.python.PythonRuntime {
    public static void ensureStarted(android.content.Context);
}

# --- 3. Shizuku 用户服务 -----------------------------------------------------
# 动态调用者：Shizuku 按 manifest 声明的组件名绑定该 Service，再通过 AIDL
# （IShizukuFileService，AGP 的 AIDL 规则负责保名）跨进程调用其成员；
# 服务类自身还会被 Android 框架按名字实例化。
# 覆盖：T19（ShizukuFileService 契约）。**注意**：T19 的「真实 Shizuku 授权/断连」
# 至今未在真机上验证过，因此这条规则按「未知依赖宁可记录而非猜着删」保留原样，
# 不因为「看起来只用了几个方法」而收窄。
-keep class com.bd2toolsbox.service.ShizukuFileService { *; }

# --- 4. release-probe 反射的快照契约面 -----------------------------------------
# 动态调用者：release-probe/src/main/java/com/bd2toolsbox/integration/
# ReleaseSnapshotProbeTest —— 跨包（targetContext.classLoader）按**名字**取：
#   Class.forName("…ModSnapshotStore")、getConstructor(File)、getMethod(load/capture/commit)、
#   Class.forName("…PendingWrite")、以及返回的 sealed 子类与条目上的 Kotlin getter。
# 覆盖：Test-Bd2ReleaseSmoke -Case 'snapshot-compatibility'。
#
# 这条**不是给生产用的**：生产代码直接静态引用这些类，R8 本来就能看到。
# 但探针是本仓的验证工具，它一旦 ClassNotFoundException，minified release 的快照契约
# 就没人能验了（T52 实测：删掉原先的包级 keep 后正是这个错）。
# 因此只钉住**探针真的用到的那几个名字与成员**，不恢复整包 keep。
-keep class com.bd2toolsbox.core.storage.ModSnapshotStore {
    public <init>(java.io.File);
    public *** load(java.util.List);
    public *** capture(java.util.List, java.util.List);
    public *** commit(com.bd2toolsbox.core.storage.PendingWrite, java.util.List);
}
-keep class com.bd2toolsbox.core.storage.PendingWrite { *; }
-keep class com.bd2toolsbox.core.storage.SnapshotLoad { *; }
-keep class com.bd2toolsbox.core.storage.SnapshotLoad$* { *; }
-keep class com.bd2toolsbox.core.storage.SnapshotCommit { *; }
-keep class com.bd2toolsbox.core.storage.SnapshotCommit$* { *; }
-keep class com.bd2toolsbox.core.storage.SnapshotEntry { *; }

# --- 5. 持久化格式的守卫 -------------------------------------------------------
# T35 之后磁盘键名是 @SerialName 常量，字段名被 R8 改掉也不影响读写；
# 但**快照这一处有硬闸**：Test-Bd2ReleaseSmoke -Case 'snapshot-compatibility'
# 直接读 mapping.txt 断言 “SnapshotV1 未被改名、version/savedAt/mods/sourceDirs
# 四个字段未被改名”（T10 建立的闸）。所以这里的类名是**载重**的，不是纵深：
# 用 `-keep`（连类名一起钉）而不是 `-keepclassmembers`。
# LegacyObfuscatedSnapshot 没有被任何闸检查类名，只需钉字段。
# 覆盖：JsonMigrationTest / ModSnapshotStoreTest（JVM）与
# release-probe 的 ReleaseSnapshotProbeTest（minified release，跨进程）。
-keep class com.bd2toolsbox.core.storage.ModSnapshotStore$SnapshotV1 { <fields>; }
-keepclassmembers class com.bd2toolsbox.core.storage.ModSnapshotStore$LegacyObfuscatedSnapshot { <fields>; }
