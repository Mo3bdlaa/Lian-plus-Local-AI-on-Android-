package com.lian.plus

import android.app.Application
import android.util.Log
import com.lian.plus.core.LianRuntime
import com.lian.plus.util.Notifications
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LianApp : Application() {

    lateinit var runtime: LianRuntime
        private set

    override fun onCreate() {
        super.onCreate()

        // The image worker runs in :imagegen and must not build a second
        // runtime - it has no use for the database, engines or HTTP server.
        if (isImageGenProcess()) {
            // The worker still needs its channel: startForeground in that
            // process will fail without one.
            Notifications.ensureChannels(this)
            Log.i(TAG, "image worker process started")
            return
        }

        Notifications.ensureChannels(this)

        runtime = LianRuntime.get(this)
        runtime.scope.launch {
            runtime.settingsStore.settings.collectLatest { runtime.syncToolsWithSettings(it) }
        }
        // Deliberately not loading a model here. Which engine is needed
        // depends on what the user does first, and paying several seconds and
        // a few gigabytes for a guess is worse than loading on intent.
        runtime.scope.launch {
            runCatching { runtime.modelStore.sync() }
                .onFailure { Log.w(TAG, "model sync failed: ${it.message}") }
        }
    }

    private fun isImageGenProcess(): Boolean {
        val name = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getProcessName()
        } else {
            runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000') }.getOrNull()
        }
        return name?.endsWith(":imagegen") == true
    }

    private companion object {
        const val TAG = "LianApp"
    }
}
