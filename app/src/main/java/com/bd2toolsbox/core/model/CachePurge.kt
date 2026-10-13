package com.bd2toolsbox.core.model

/**
 * 缓存清理结果的**用户可见文案**（T29）。
 *
 * 与 [BackupPurge.resultText] 分开：那个说的是「份原版备份」，这里说的是
 * 「条目 + 占用」。两者共用一个 [PurgeOutcome]，但话不能混着说 ——
 * 「已清空 3 份原版备份」出现在预览缓存上说不过去。
 *
 * **核心约定**：任何一条文案都不许把「没删掉的空间」说成 0。
 * 失败时给出的字节数来自**重新测量**的 `remainingBytes`，不是从
 * 「应该删了多少」推出来的。
 */
object CachePurge {

    /**
     * @param what 这个缓存叫什么（「预览缓存」「渲染组件」），用于拼句子。
     */
    fun resultText(outcome: PurgeOutcome, humanBytes: String, what: String): String = when {
        outcome.attempted == 0 && outcome.remainingCount == 0 ->
            "没有需要清理的$what。"

        outcome.failed == 0 && outcome.remainingCount == 0 ->
            "已清理$what（${outcome.deleted} 项）。"

        // 全失败：**必须**把实际占用说出来，不能显示成 0
        outcome.deleted == 0 ->
            "${what}清理失败：${outcome.attempted} 项均未删除，仍占用 $humanBytes。" +
                "请检查存储权限后重试。"

        else ->
            "部分成功：已清理 ${outcome.deleted} 项，${outcome.failed} 项未删除，" +
                "仍剩 ${outcome.remainingCount} 项（$humanBytes）。"
    }

    /** 清理是否算「完成了」——界面据此决定要不要让用户点「知道了」。 */
    fun needsAttention(outcome: PurgeOutcome): Boolean =
        outcome.failed > 0 || outcome.remainingCount > 0
}
