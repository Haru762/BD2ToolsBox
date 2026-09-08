package com.bd2toolsbox.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

class SafManager {

    companion object {
        /**
         * 目录选择器。用于选择**存放 mod 的源目录**。
         *
         * 这里刻意不设置 INITIAL_URI：早前的版本把初始位置指向游戏的
         * UnityCache/Shared（即写入的目标目录），导致每次选 mod 文件夹都要
         * 先从游戏目录导航出来。交给系统决定初始位置（通常是上次访问的位置）
         * 更贴近用户实际存放 mod 的地方。
         */
        fun createAccessIntent(): Intent {
            return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        }
    }

    class PickDirectoryWithSpecialAccess : ActivityResultContract<Unit, Uri?>() {
        override fun createIntent(context: Context, input: Unit): Intent {
            return createAccessIntent()
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
            if (resultCode != Activity.RESULT_OK) {
                return null
            }
            // The contract's only job is to return the URI.
            // The caller is responsible for persisting the permission.
            return intent?.data
        }
    }
}
