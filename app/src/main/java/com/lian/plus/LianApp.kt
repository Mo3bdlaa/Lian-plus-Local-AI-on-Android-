package com.lian.plus

import android.app.Application
import android.util.Log
import com.lian.plus.core.LianRuntime
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
            Log.i(TAG, "image worker process started")
            return
        }

        runtime = LianRuntime.get(this)
        runtime.scope.launch {
            runtime.settingsStore.settings.collectLatest { runtime.syncToolsWithSettings(it) }
        }
        runtime.scope.launch {
            runCatching { runtime.restoreSelection() }
                .onFailure { Log.w(TAG, "could not restore the previous model: ${it.message}") }
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
