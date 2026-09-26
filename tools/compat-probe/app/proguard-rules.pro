# 探针只保留测试需要反射访问的最小入口；序列化与 Hilt 依赖各自自带的 consumer rules。
-keep class com.bd2toolsbox.compat.ProbeChecks { public *; }
