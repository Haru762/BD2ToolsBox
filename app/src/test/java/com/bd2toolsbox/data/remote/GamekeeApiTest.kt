package com.bd2toolsbox.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T30：HTTP 层的**可判定部分**（JVM）。
 *
 * 真实请求需要网络与服务器，这里只钉住那条唯一的分界线：拿到的是数据还是 CDN 的
 * 验证页。它在拆分前藏在 `httpGet`/`httpPostJson` 的 IO 里，没法单独验证；
 * 拆出后是一条纯函数，可以直接断言。
 */
class GamekeeApiTest {

    @Test
    fun objectAndArrayBodiesAreAccepted() {
        assertTrue(GamekeeApi.looksLikeJson("""{"code":0}"""))
        assertTrue(GamekeeApi.looksLikeJson("""[1,2,3]"""))
        // 前导空白（含换行）不影响判断
        assertTrue(GamekeeApi.looksLikeJson("\n\t  {\"a\":1}"))
    }

    @Test
    fun htmlInterstitialIsRejected() {
        // CDN 偶尔吐验证页；按约定「content 必以 { 或 [ 开头」判真假
        assertFalse(GamekeeApi.looksLikeJson("<html><body>checking…</body></html>"))
        assertFalse(GamekeeApi.looksLikeJson(""))
        assertFalse(GamekeeApi.looksLikeJson("null"))
        assertFalse(GamekeeApi.looksLikeJson("\"just a string\""))
    }

    @Test
    fun fixedIdentityMatchesTheBrownDust2Section() {
        // 这两个值进请求头与 CDN 路径；改了会直接 404 / 拿到别的游戏的数据
        assertEquals("50118", GamekeeApi.GAME_ID)
        assertEquals("zsca2", GamekeeApi.GAME_ALIAS)
        assertEquals("https://www.gamekee.com", GamekeeApi.BASE_URL)
        assertEquals(
            "https://www.gamekee.com/wiki2.0/pro/50118/content",
            GamekeeApi.CDN_CONTENT
        )
    }
}
