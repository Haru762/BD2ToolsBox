package com.bd2toolsbox.core.storage

import android.content.SharedPreferences

/**
 * 设置项的底层存储抽象（T27）。
 *
 * 抽这一层只为让 [SettingsRepository] 能在 JVM 单测里用内存实现验证迁移与回退；
 * 生产实现仍是 `SharedPreferences`（不引入 DataStore）。
 */
interface SettingsStore {
    /** 当前全部键值（原样，不做类型假设）。 */
    fun all(): Map<String, Any?>

    /**
     * 同步写入；值为 `null` 表示删除该键。
     *
     * 返回 `false` 表示**落盘失败**（可观察），调用方据此提示用户，而不是静默丢弃。
     */
    fun put(updates: Map<String, Any?>): Boolean
}

/** `SharedPreferences` 实现：键值与旧版本完全一致，迁移不丢配置。 */
class SharedPreferencesSettingsStore(private val prefs: SharedPreferences) : SettingsStore {

    override fun all(): Map<String, Any?> = prefs.all

    override fun put(updates: Map<String, Any?>): Boolean {
        val editor = prefs.edit()
        for ((key, value) in updates) {
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                else -> editor.putString(key, value.toString())
            }
        }
        // commit() 而不是 apply()：写入失败必须可观察（T27 验收）。
        return editor.commit()
    }
}
