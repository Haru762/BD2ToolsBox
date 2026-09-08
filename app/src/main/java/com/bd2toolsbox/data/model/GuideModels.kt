package com.bd2toolsbox.data.model

/**
 * 「攻略」与「兑换码」板块的数据模型：列表类数据来自 GameKee 棕色尘埃2分区，
 * 兑换动作走游戏官方接口（见 Bd2RedeemRepository）。
 *
 * 放在 data/model 下是有意的：release 构建开了 minify，而 proguard-rules.pro
 * 里已有的 `-keep class com.bd2toolsbox.data.model.**` 正好覆盖 Gson 反序列化
 * 需要保留字段名的类，不用再为攻略单独加规则。
 */

/**
 * 攻略分类（gamekee 叫 entry）。
 *
 * gamekee 的 wiki 本质是「一棵由页面组成的树」：[contentId] > 0 表示这个节点
 * 绑定了一篇文章（叶子页面，点开即读）；为 0 则是纯目录，展开后继续选。
 * 映射来自 `query-entry-list-from-cdn` 接口的全站 dict 表 —— 精简树
 * （/v1/wiki/entry）里没有这个字段，而文章 id 与条目 id 是两套编号，不能混用。
 */
data class GuideCategory(
    val id: Long,
    val name: String,
    val contentId: Long = 0,
    /** 条目图标（protocol-relative 地址，仓库层已补 https:），目录节点为 null。 */
    val icon: String? = null,
    val children: List<GuideCategory> = emptyList()
)

/** 文章列表里的一条。[thumb] 是 protocol-relative（`//cdn...`）缩略图地址，可能为空。 */
data class GuideArticle(
    val id: Long,
    val title: String,
    val summary: String,
    val thumb: String?,
    val updatedAt: Long,
    val comments: Int
)

/**
 * 一篇文章的完整内容。[html] 是已经渲染好的完整 HTML 文档（含样式），
 * 由仓库层把 gamekee 的两种正文格式（新版 Slate 块 JSON / 旧版 HTML）统一转出来。
 */
data class GuideDetail(
    val article: GuideArticle,
    val html: String,
    /** 正文生成时间（epoch 毫秒），给「缓存于 xx」的提示用。 */
    val renderedAt: Long
)

/**
 * 一条兑换码。[endAt] 为 0 表示官方未标注有效期（当作永久）；[expired] 由仓库层
 * 按 [endAt] 与当前时间算好，界面直接用。
 */
data class CdkItem(
    val code: String,
    val reward: String,
    val endAt: Long,
    val expired: Boolean
)

/** 文章评论区的一条留言。[replyTo] 非空表示这是对某人的回复（「回复 @xx」）。 */
data class GuideComment(
    val id: Long,
    val author: String,
    val avatar: String?,
    val content: String,
    val replyTo: String?,
    val createdAt: Long,
    val likes: Int
)

/** 官方兑换的单码结果：成功与否 + 直接给用户看的文案。 */
data class RedeemOutcome(
    val code: String,
    val success: Boolean,
    val message: String
)

/**
 * 本地兑换记录的一条。官方接口没有「查历史」的端点，成功记录由 App 自己记
 * （见 Bd2RedeemRepository）；只记成功——失败原因每次都可能不同，记了没意义。
 */
data class RedeemRecord(
    val code: String,
    val reward: String,
    /** epoch 秒。 */
    val at: Long
)
