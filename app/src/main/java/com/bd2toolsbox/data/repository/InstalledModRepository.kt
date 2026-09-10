package com.bd2toolsbox.data.repository

import android.content.Context
import com.bd2toolsbox.data.model.InstalledModRecord
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 「哪些 mod 当前装在游戏里」的账本。
 *
 * 刻意独立于 [ModRepository] 的 mod_cache.json：那份缓存一旦 bundle 索引更新就整体作废
 * （见 ModRepository.loadModCache），而装入记录必须在游戏更新后继续存活 —— 正是要靠它
 * 判断出「这个 mod 需要重新应用」。
 *
 * 记账只回答「装了哪些 mod、装在哪个 bundle 上」。至于那个 bundle 现在究竟是干净原版
 * 还是装着 mod，由干净检测（catalog 权威值 vs 游戏目录实际值）客观给出，不靠这里推断。
 *
 * **主键是 mod 的 uri，一个 mod 一条记录**（见 [InstalledModRecord] 的说明：一个 bundle
 * 常装着多个角色的 mod，按 familyKey 一包一条会导致卸载连带和状态误判；而 familyKey
 * 本身也不唯一）。
 */
class InstalledModRepository(private val context: Context) {

    companion object {
        private const val FILENAME = "installed_mods.json"
    }

    private val gson = Gson()

    private fun file(): File = File(context.filesDir, FILENAME)

    /**
     * 读改写全部在这把锁里。
     *
     * 账本是「读全表 → 改一条 → 写回全表」：并发的两次写入各自读了旧表、写回时后写的那份
     * 会把先写的覆盖掉（丢记录）。这些方法都标了 @Synchronized（同一把实例锁，重入安全，
     * put 里调 load 不会自锁），单次读改写见 [removeAndReturn]。
     */
    /** modUri -> 记录 */
    @Synchronized
    fun load(): Map<String, InstalledModRecord> {
        val f = file()
        if (!f.exists()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, InstalledModRecord>>() {}.type
            val raw = gson.fromJson<Map<String, InstalledModRecord>>(f.readText(), type) ?: emptyMap()
            // 旧格式（familyKey 主键 + modUris 列表）反序列化后 modUri 会是 null。
            // Gson 不管 Kotlin 的非空约束，所以只能在这里挡：漏过去会在读 modUri 时
            // 抛 NPE，且发生在离此很远的地方，极难定位。
            raw.filterValues { @Suppress("SENSELESS_COMPARISON") it != null && it.modUri != null }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }

    /**
     * 账本是否是需要丢弃的旧格式。
     *
     * 旧格式一条记录含多个 modUris 却只有一个 familyKey，无法还原出「哪个 uri 对应哪个
     * familyKey」，所以没法安全迁移。账本只是记账，清空不动游戏里的实际文件 ——
     * 重新装一次就能重建准确记录，比带着一份错账继续跑安全。
     */
    @Synchronized
    fun needsReset(): Boolean {
        val f = file()
        if (!f.exists()) return false
        return try {
            val text = f.readText()
            if (text.isBlank()) false else text.contains("\"modUris\"")
        } catch (e: Exception) {
            false
        }
    }

    private fun save(records: Map<String, InstalledModRecord>) {
        try {
            file().writeText(gson.toJson(records))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 记下一次成功装入。同一个 mod 再装即覆盖（更新画质、时间等）。 */
    @Synchronized
    fun put(record: InstalledModRecord) {
        save(load().toMutableMap().apply { put(record.modUri, record) })
    }

    @Synchronized
    fun putAll(records: List<InstalledModRecord>) {
        if (records.isEmpty()) return
        save(load().toMutableMap().apply { records.forEach { put(it.modUri, it) } })
    }

    /** 某个 bundle 上当前装着哪些 mod。装入时要把它们一起重新打包，否则会丢。 */
    @Synchronized
    fun listByTargetHash(targetHash: String): List<InstalledModRecord> =
        load().values.filter { it.snapshotTargetHash == targetHash }

    /** 移除一个 mod 的记录。调用方需自行判断该 bundle 是否还有剩余（见 [listByTargetHash]）。 */
    @Synchronized
    fun remove(modUri: String) {
        val current = load()
        if (!current.containsKey(modUri)) return
        save(current.toMutableMap().apply { remove(modUri) })
    }

    /**
     * 读出并删掉一条记录，返回被删掉的那条（本来就没有则 null）。
     *
     * 「删源文件」那条路要先拿到记录（删除失败要原样放回）再销账：分成 load() + remove()
     * 两次调用的话，并发的另一次写入会在两次之间插进来，把记录又写回去。这里一次读完写完。
     */
    @Synchronized
    fun removeAndReturn(modUri: String): InstalledModRecord? {
        val current = load()
        val record = current[modUri] ?: return null
        save(current.toMutableMap().apply { remove(modUri) })
        return record
    }

    /**
     * 按装入时的 bundle 名删记录。
     *
     * 整组还原走的是 targetHash（bundle 名）而不是单个 mod，所以需要反查一次。
     * 注意 targetHash 会随游戏更新变化，因此这里只用于「刚还原了这个 bundle」这种
     * 当下就能对上的场合，不作为长期标识。
     */
    @Synchronized
    fun removeByTargetHash(targetHash: String) {
        val current = load()
        val keys = current.filterValues { it.snapshotTargetHash == targetHash }.keys
        if (keys.isEmpty()) return
        save(current.toMutableMap().apply { keys.forEach { remove(it) } })
    }

    @Synchronized
    fun clear() {
        try {
            file().delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
