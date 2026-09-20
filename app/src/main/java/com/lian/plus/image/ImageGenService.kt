package com.lian.plus.image

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lian.plus.R
import com.lian.plus.util.Notifications
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the diffusion engine in the `:imagegen` process.
 *
 * Why a separate process rather than a thread:
 *
 *  * A diffusion run allocates well over a gigabyte transiently. Native heap
 *    is rarely returned to the OS afterwards, so doing this in the main process
 *    would permanently inflate the app's footprint and make the LLM the next
 *    thing Android kills. Killing this process reclaims every byte.
 *  * `liblian_sd.so` statically links its own copy of ggml. Loading it in a
 *    process that has never loaded `liblian_llm.so` removes any possibility of
 *    the two copies interfering.
 *  * A native crash inside the image engine takes down only this process; the
 *    chat session survives.
 */
class ImageGenService : Service() {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "lian-sd") }
    private val cancelled = AtomicBoolean(false)

    @Volatile private var handle: Long = 0
    @Volatile private var loadedPath: String? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    /**
     * Holds the process in the foreground for the duration of a job.
     *
     * Without this the worker is an ordinary background process, and Android
     * reclaims those first: a diffusion run that takes a minute would be killed
     * partway through, which the client sees only as the model having
     * "unloaded itself". A foreground notification is the documented way to
     * tell the OS that this work is the user's current intent.
     */
    private fun enterForeground(text: String) {
        val notification: Notification =
            NotificationCompat.Builder(this, Notifications.CHANNEL_IMAGE)
                .setContentTitle("Lian+ image engine")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_image)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    Notifications.ID_IMAGE,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(Notifications.ID_IMAGE, notification)
            }
            foreground = true
        }.onFailure { Log.w(TAG, "could not enter the foreground: ${it.message}") }
    }

    private fun leaveForeground() {
        if (!foreground) return
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        foreground = false
    }

    @Volatile private var foreground = false

    private val binder = object : IImageGenService.Stub() {

        override fun isEngineAvailable(): Boolean = SdNative.isAvailable

        override fun engineInfo(): String =
            if (SdNative.isAvailable) SdNative.systemInfo() else "image engine not built"

        override fun loadModel(
            modelPath: String,
            vaePath: String,
            taesdPath: String,
            threads: Int,
            flashAttn: Boolean,
            convDirect: Boolean,
        ): Boolean {
            if (!SdNative.isAvailable) return false
            if (handle != 0L && loadedPath == modelPath) return true

            enterForeground("Loading the model…")
            return worker.submit<Boolean> {
                freeLocked()
                val h = runCatching {
                    SdNative.loadContext(
                        modelPath = modelPath,
                        vaePath = vaePath,
                        taesdPath = taesdPath,
                        clipLPath = "",
                        clipGPath = "",
                        t5Path = "",
                        diffusionPath = "",
                        nThreads = threads,
                        wtype = -1,
                        flashAttn = flashAttn,
                        convDirect = convDirect,
                        mmap = true,
                    )
                }.getOrElse {
                    Log.e(TAG, "load failed", it); 0L
                }
                handle = h
                loadedPath = if (h != 0L) modelPath else null
                if (h == 0L) leaveForeground() else enterForeground("Model loaded")
                h != 0L
            }.get()
        }

        override fun unload() {
            leaveForeground()
            worker.submit { freeLocked() }
        }

        override fun modelVersion(): String = worker.submit<String> {
            if (handle == 0L) "" else runCatching { SdNative.modelVersion(handle) }.getOrDefault("")
        }.get()

        override fun generate(
            prompt: String,
            negativePrompt: String,
            width: Int,
            height: Int,
            steps: Int,
            cfgScale: Float,
            seed: Long,
            sampler: Int,
            scheduler: Int,
            initImagePath: String,
            strength: Float,
            outputPath: String,
            callback: IImageGenCallback,
        ) {
            cancelled.set(false)
            enterForeground("Generating…")
            worker.execute {
                val started = System.currentTimeMillis()
                try {
                    if (handle == 0L) {
                        callback.onError("No image model is loaded."); return@execute
                    }

                    val init = if (initImagePath.isNotBlank()) readRgb(File(initImagePath)) else null

                    val dims = IntArray(3)
                    val pixels = SdNative.txt2img(
                        handle = handle,
                        prompt = prompt,
                        negativePrompt = negativePrompt,
                        width = width,
                        height = height,
                        steps = steps,
                        cfgScale = cfgScale,
                        seed = seed,
                        sampleMethod = sampler,
                        scheduler = scheduler,
                        clipSkip = -1,
                        initRgb = init?.first,
                        initWidth = init?.second ?: 0,
                        initHeight = init?.third ?: 0,
                        strength = strength,
                        progress = { step, total, secs ->
                            runCatching { callback.onStep(step, total, secs) }
                        },
                        dims = dims,
                    )

                    if (pixels == null) {
                        callback.onError(
                            if (cancelled.get()) "Cancelled." else "The engine returned no image.",
                        )
                        return@execute
                    }

                    val file = File(outputPath)
                    writePng(pixels, dims[0], dims[1], dims[2], file)
                    callback.onComplete(
                        file.absolutePath, dims[0], dims[1],
                        System.currentTimeMillis() - started,
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "generation failed", t)
                    runCatching { callback.onError(t.message ?: "generation failed") }
                } finally {
                    // Back to "model loaded", so the process stays resident but
                    // the notification stops claiming work is in progress.
                    if (handle != 0L) enterForeground("Model loaded") else leaveForeground()
                }
            }
        }

        override fun cancel() {
            cancelled.set(true)
            // Deliberately not on `worker`: that thread is busy generating, and
            // the native cancel flag is what frees it.
            if (handle != 0L) runCatching { SdNative.cancel(handle) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        leaveForeground()
        worker.submit { freeLocked() }
        worker.shutdown()
        super.onDestroy()
    }

    private fun freeLocked() {
        if (handle != 0L) {
            runCatching { SdNative.freeContext(handle) }
            handle = 0
            loadedPath = null
        }
    }

    /** Converts the engine's raw RGB/RGBA output into a PNG on disk. */
    private fun writePng(pixels: ByteArray, width: Int, height: Int, channels: Int, out: File) {
        val argb = IntArray(width * height)
        for (i in argb.indices) {
            val base = i * channels
            val r = pixels[base].toInt() and 0xFF
            val g = pixels[base + 1].toInt() and 0xFF
            val b = pixels[base + 2].toInt() and 0xFF
            val a = if (channels >= 4) pixels[base + 3].toInt() and 0xFF else 0xFF
            argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** Loads an image file as packed RGB for img2img. */
    private fun readRgb(file: File): Triple<ByteArray, Int, Int>? {
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        bmp.recycle()
        val out = ByteArray(w * h * 3)
        for (i in argb.indices) {
            val p = argb[i]
            out[i * 3] = ((p shr 16) and 0xFF).toByte()
            out[i * 3 + 1] = ((p shr 8) and 0xFF).toByte()
            out[i * 3 + 2] = (p and 0xFF).toByte()
        }
        return Triple(out, w, h)
    }

    private companion object {
        const val TAG = "LianImageSvc"
    }
}
