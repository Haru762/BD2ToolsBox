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