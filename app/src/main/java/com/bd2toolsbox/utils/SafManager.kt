package com.bd2toolsbox.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/** mod 源目录的 SAF 选择入口。 */
class SafManager {

    companion object {
        /**
         * 打开目录选择器。刻意不设 INITIAL_URI：早期版本把它指向游戏
         * UnityCache/Shared（写入目标），选 mod 文件夹每次都要先导航出来；
         * 交给系统记上次的位置更贴近用户放 mod 的地方。
         */
        fun createAccessIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    }

    /** ActivityResult 契约：拉起目录选择器，返回选中的树 URI。
     *  权限持久化由调用方负责（takePersistableUriPermission）。 */
    class PickDirectoryWithSpecialAccess : ActivityResultContract<Unit, Uri?>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            SafManager.createAccessIntent()

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
    }
}
