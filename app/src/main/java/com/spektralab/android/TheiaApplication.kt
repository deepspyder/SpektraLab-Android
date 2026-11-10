package com.spektralab.android

import android.app.Application

/**
 * 应用入口。目前只做占位——后续在这里持有全局单例（引擎客户端、磁盘缓存、
 * 色彩管理等）以及应用级初始化。
 */
class TheiaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
