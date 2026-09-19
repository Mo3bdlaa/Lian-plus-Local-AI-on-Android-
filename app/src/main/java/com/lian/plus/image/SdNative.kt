package com.lian.plus.image

import android.util.Log

/**
 * Thin binding to `liblian_sd.so`.
 *
 * Loaded **only** in the `:imagegen` process. See [ImageGenService] for why.
 */
object SdNative {

    val isAvailable: Boolean = runCatching {
        System.loadLibrary("lian_sd")
        // A stub build loads fine but has no entry points, so probe one.
        physicalCores()
        true
    }.getOrElse { t ->
        Log.w("LianSd", "image engine unavailable: ${t.message}")
        false
    }

    fun interface StepCallback {
        fun onStep(step: Int, totalSteps: Int, secondsForStep: Float)
    }

    external fun systemInfo(): String
    external fun physicalCores(): Int

    external fun loadContext(
        modelPath: String,
        vaePath: String,
        taesdPath: String,
        clipLPath: String,
        clipGPath: String,
        t5Path: String,
        diffusionPath: String,
        nThreads: Int,
        /** sd_type_t, or -1 to keep the file's own quantisation. */
        wtype: Int,
        flashAttn: Boolean,
        convDirect: Boolean,
        mmap: Boolean,
    ): Long

    external fun freeContext(handle: Long)
    external fun modelVersion(handle: Long): String

    /** Safe to call from another thread during [txt2img]. */
    external fun cancel(handle: Long)

    /**
     * Runs a diffusion job. [dims] must be an `IntArray(3)`; on success it
     * receives `[width, height, channels]` and the return value holds the raw
     * pixels. Returns null if generation failed or was cancelled.
     */
    external fun txt2img(
        handle: Long,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfgScale: Float,
        seed: Long,
        sampleMethod: Int,
        scheduler: Int,
        clipSkip: Int,
        initRgb: ByteArray?,
        initWidth: Int,
        initHeight: Int,
        strength: Float,
        progress: StepCallback?,
        dims: IntArray,
    ): ByteArray?
}
