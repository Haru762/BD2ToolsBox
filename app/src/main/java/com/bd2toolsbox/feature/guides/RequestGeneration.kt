package com.bd2toolsbox.feature.guides

/**
 * 请求代次控制（T30）。
 *
 * 每次「用户意图」变化就 [next] 自增；在途请求只记住发起时的 token，
 * 回来时用 [isCurrent] 判断自己是否仍对应当前意图。只有仍对应当前意图的结果才允许提交。
 *
 * 关闭文章 / 退出搜索也调用 [next]，于是「关闭后迟到结果又重开文章」不再可能。
 *
 * 线程安全：网络回调可能在不同线程回来，读写都在同一把锁内。
 */
class RequestGeneration {
    private var current = 0L

    /** 开始一次新意图，返回它的 token。 */
    @Synchronized
    fun next(): Long {
        current += 1
        return current
    }

    /** 当前意图的 token（用于「属于同一意图」的追加请求，如分页加载更多）。 */
    @Synchronized
    fun current(): Long = current

    /** 该 token 是否仍对应当前意图。 */
    @Synchronized
    fun isCurrent(token: Long): Boolean = token == current

    /** 作废所有在途 token（关闭/取消）。等价于 [next] 但语义更明确。 */
    @Synchronized
    fun invalidate() {
        current += 1
    }
}
