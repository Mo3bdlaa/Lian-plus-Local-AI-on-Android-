package com.lian.plus.core.device

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import androidx.core.content.getSystemService
import java.io.File

/**
 * Reads what the device can actually do. Every source here is best-effort:
 * OEMs restrict `/sys` and `/proc` differently, so each probe degrades to a
 * sensible default rather than throwing.
 */
object DeviceProfiler {

    private const val TAG = "LianProfiler"

    fun profile(context: Context): DeviceProfile {
        val am = context.getSystemService<ActivityManager>()
        val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val freqs = readCoreMaxFreqs()
        val storageDir = context.filesDir
        val stat = runCatching { StatFs(storageDir.absolutePath) }.getOrNull()

        return DeviceProfile(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            socModel = socModel(),
            board = Build.BOARD,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(),

            totalRamBytes = memInfo.totalMem,
            availableRamBytes = memInfo.availMem,
            lowMemoryThresholdBytes = memInfo.threshold,
            perAppHeapMb = am?.largeMemoryClass ?: 256,

            cpuCores = Runtime.getRuntime().availableProcessors(),
            coreMaxFreqKhz = freqs,
            cpuFeatures = readCpuFeatures(),

            gpuRenderer = null,
            gpuVendor = null,

            freeStorageBytes = stat?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L,
            totalStorageBytes = stat?.let { it.blockCountLong * it.blockSizeLong } ?: 0L,

            thermalStatus = thermalLevel(context),
            isLowRamDevice = am?.isLowRamDevice ?: false,
        )
    }

    /**
     * Fills in [DeviceProfile.gpuRenderer]. Needs an EGL context, so it is kept
     * out of [profile] and called once, off the main thread, from the
     * capability screen.
     */
    fun withGpuInfo(profile: DeviceProfile): DeviceProfile {
        val (renderer, vendor) = queryGpu() ?: return profile
        return profile.copy(gpuRenderer = renderer, gpuVendor = vendor)
    }

    fun thermalLevel(context: Context): ThermalLevel {
        val pm = context.getSystemService<PowerManager>() ?: return ThermalLevel.UNKNOWN
        return when (runCatching { pm.currentThermalStatus }.getOrNull()) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalLevel.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.SHUTDOWN
            else -> ThermalLevel.UNKNOWN
        }
    }

    private fun socModel(): String = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                .filter { it.isNotBlank() && it != Build.UNKNOWN }
                .joinToString(" ")
                .ifBlank { Build.HARDWARE }
        else -> Build.HARDWARE
    }

    private fun readCoreMaxFreqs(): List<Int> {
        val out = mutableListOf<Int>()
        var cpu = 0
        while (true) {
            val f = File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
            if (!f.exists()) break
            out += runCatching { f.readText().trim().toInt() }.getOrDefault(0)
            cpu++
            if (cpu > 32) break
        }
        return if (out.any { it > 0 }) out else emptyList()
    }

    private fun readCpuFeatures(): Set<String> = runCatching {
        File("/proc/cpuinfo").readLines()
            .firstOrNull { it.startsWith("Features") }
            ?.substringAfter(':')
            ?.trim()
            ?.split(' ')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
    }.getOrElse { emptySet() }

    /**
     * Spins up a throwaway 1x1 pbuffer surface purely to read GL_RENDERER.
     * The GPU is not used for inference today (llama.cpp's Vulkan/OpenCL
     * backends are not enabled in this build) but the name is the clearest
     * signal of which SoC class the user is on.
     */
    private fun queryGpu(): Pair<String, String>? = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        try {
            val configAttrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfig = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, numConfig, 0) ||
                numConfig[0] == 0
            ) return null

            val ctx = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            val surface = EGL14.eglCreatePbufferSurface(
                display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            try {
                if (!EGL14.eglMakeCurrent(display, surface, surface, ctx)) return null
                val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "unknown"
                val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "unknown"
                renderer to vendor
            } finally {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, ctx)
            }
        } finally {
            EGL14.eglTerminate(display)
        }
    }.onFailure { Log.d(TAG, "GPU probe failed: ${it.message}") }.getOrNull()
}
