package com.bd2toolsbox.feature.update

import com.bd2toolsbox.data.repository.UpdateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T32：按 ABI 选包 —— 不误选错架构、不回退 universal、无匹配时明确失败。 */
class ApkSelectorTest {

    private fun asset(name: String) = UpdateRepository.ApkAsset(name, "https://x/$name", 100L)

    private val arm64 = asset("app-arm64-v8a-release.apk")
    private val x64 = asset("app-x86_64-release.apk")
    private val universal = asset("app-universal-release.apk")

    @Test fun arm64DevicePicksArm64Asset() {
        val picked = ApkSelector.select(listOf(arm64, x64), listOf("arm64-v8a"))
        assertEquals("app-arm64-v8a-release.apk", picked?.name)
    }

    @Test fun x86_64DevicePicksX64Asset() {
        val picked = ApkSelector.select(listOf(arm64, x64), listOf("x86_64"))
        assertEquals("app-x86_64-release.apk", picked?.name)
    }

    @Test fun x86_64DeviceNeverPicksArm64() {
        val picked = ApkSelector.select(listOf(arm64), listOf("x86_64"))
        assertNull("没有匹配资产必须明确失败", picked)
    }

    @Test fun noUniversalFallback() {
        // 只有 universal 时必须失败，不能偷偷用 universal。
        assertNull(ApkSelector.select(listOf(universal), listOf("arm64-v8a")))
        assertNull(ApkSelector.select(listOf(universal), listOf("x86_64")))
    }

    @Test fun unknownAbiFailsExplicitly() {
        assertNull(ApkSelector.select(listOf(arm64, x64, universal), listOf("mips", "riscv64")))
    }

    @Test fun x86TokenDoesNotMatchX86_64Asset() {
        // 旧实现用 name.contains(abi)，x86 会命中 x86_64 —— 这里必须不命中。
        assertNull(ApkSelector.select(listOf(x64), listOf("x86")))
    }

    @Test fun arm64TokenMatchesPlainArm64Asset() {
        val plainArm64 = asset("app-arm64-release.apk")
        assertEquals("app-arm64-release.apk", ApkSelector.select(listOf(plainArm64), listOf("arm64-v8a"))?.name)
    }

    @Test fun supportedAbiOrderIsRespected() {
        // 首选 ABI 有资产就用它，即使备选 ABI 的资产排得更前。
        val picked = ApkSelector.select(listOf(x64, arm64), listOf("arm64-v8a", "x86_64"))
        assertEquals("app-arm64-v8a-release.apk", picked?.name)
    }

    @Test fun fallsBackToSecondSupportedAbiWhenFirstHasNoAsset() {
        val picked = ApkSelector.select(listOf(x64), listOf("arm64-v8a", "x86_64"))
        assertEquals("app-x86_64-release.apk", picked?.name)
    }

    @Test fun emptyAssetsFailExplicitly() {
        assertNull(ApkSelector.select(emptyList(), listOf("arm64-v8a")))
    }

    @Test fun tokensForCoversKnownAbis() {
        assertEquals(listOf("arm64-v8a", "arm64"), ApkSelector.tokensFor("arm64-v8a"))
        assertEquals(listOf("x86_64"), ApkSelector.tokensFor("x86_64"))
        assertEquals(listOf("armeabi-v7a", "armeabi"), ApkSelector.tokensFor("armeabi-v7a"))
    }
}
