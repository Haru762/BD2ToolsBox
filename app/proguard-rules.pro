# Chaquopy: a minimal ProGuard configuration.
# See https://chaquo.com/chaquopy/doc/current/android.html#proguard.

-keep class com.chaquo.python.** { *; }
-keep class com.chaquo.python.runtime.** { *; }

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
-keep class com.bd2toolsbox.core.storage.** { *; }
