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
-keep class com.bd2toolsbox.core.storage.** { *; }
