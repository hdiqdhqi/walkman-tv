package com.walkman.tv

import android.app.Application
import com.walkman.tv.di.AppContainer
import com.whl.quickjs.android.QuickJSLoader

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Load the QuickJS native lib once for the whole process (custom-source engine).
        QuickJSLoader.init()
        container = AppContainer(this)
        // ExoPlayer must be created on the main thread.
        container.initPlayback()
        container.bootstrap()
        // One foreground poller for the imported online playlists (启动检查 / 恢复前台 / 前台定时);
        // WorkManager remains the background 兜底. Registered here so it is per process, not per
        // Activity: a rotation or a page switch must never start a second loop.
        container.observeAppLifecycle(this)
    }

    companion object {
        lateinit var instance: App
            private set
        lateinit var container: AppContainer
            private set
    }
}
