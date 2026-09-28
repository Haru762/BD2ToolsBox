package com.bd2toolsbox

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt 的应用入口（T16）。
 *
 * 刻意只做一件事：让 Hilt 生成 `SingletonComponent` 与各入口的对象图。**不在这里**
 * 抢先启动 Python、扫描游戏文件或做任何阻塞首屏的初始化 —— 这些仍按实际使用懒加载：
 *   · Python 由 [com.bd2toolsbox.core.python.PythonRuntime.ensureStarted] 在真正调用时启动；
 *   · 分类树 / mod 列表 / 缓存用量由各 ViewModel 在界面出现时拉取。
 *
 * 生产形态用真实 `@HiltAndroidApp` Application（不是 `HiltTestApplication`），
 * 与 T13 兼容探针一致。跨进程的 [com.bd2toolsbox.service.ShizukuFileService] 是
 * 外部 shell 进程里的 Binder Stub，**不**接 Hilt、也不依赖本进程的对象图。
 */
@HiltAndroidApp
class Bd2Application : Application()
