package com.lian.plus.core.device

import android.util.Log
import com.lian.plus.core.model.formatBytes
import com.lian.plus.llm.LlamaNative

/** A compute device ggml can actually use, as reported by the engine. */
data class ComputeDevice(
    val name: String,
    val description: String,
    val type: Type,
    val freeBytes: Long,
    val totalBytes: Long,
) {
    enum class Type { CPU, GPU, IGPU, ACCEL }

    val isGpu: Boolean get() = type == Type.GPU || type == Type.IGPU

    val label: String
        get() = description.ifBlank { name } +
            if (totalBytes > 0) " · ${formatBytes(totalBytes)}" else ""
}

/**
 * What this phone can actually compute on.
 *
 * Compiling the Vulkan backend in proves nothing about the device: plenty of
 * Android drivers advertise Vulkan and then refuse to enumerate a compute
 * device, or enumerate one that fails on the first dispatch. The only honest
 * answer comes from asking the engine at runtime, which is what this does —
 * once, on a background thread, with the result cached.
 */
object ComputeDevices {

    private const val TAG = "LianDevices"

    @Volatile
    private var cached: List<ComputeDevice>? = null

    /** True when the shipped engine has the Vulkan backend compiled in. */
    val vulkanCompiledIn: Boolean
        get() = LlamaNative.isAvailable && runCatching { LlamaNative.hasVulkanSupport() }
            .getOrDefault(false)

    fun devices(): List<ComputeDevice> = cached ?: probe().also { cached = it }

    fun gpu(): ComputeDevice? = devices().firstOrNull { it.isGpu }

    /** The reason there is no GPU to offer, or null when there is one. */
    fun gpuUnavailableReason(): String? = when {
        !LlamaNative.isAvailable -> "The engine is not present in this build."
        !vulkanCompiledIn -> "This build was compiled without the Vulkan backend."
        gpu() == null ->
            "The GPU driver did not offer a compute device. This is common on " +
                "Android: Vulkan is present for graphics but not usable for the " +
                "kind of work a model needs."
        else -> null
    }

    fun refresh() {
        cached = null
    }

    private fun probe(): List<ComputeDevice> {
        if (!LlamaNative.isAvailable) return emptyList()
        return runCatching {
            LlamaNative.backendDevices()
                .lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split('|')
                    if (parts.size < 5) return@mapNotNull null
                    ComputeDevice(
                        name = parts[0],
                        description = parts[1],
                        type = when (parts[2]) {
                            "gpu" -> ComputeDevice.Type.GPU
                            "igpu" -> ComputeDevice.Type.IGPU
                            "accel" -> ComputeDevice.Type.ACCEL
                            else -> ComputeDevice.Type.CPU
                        },
                        freeBytes = parts[3].toLongOrNull() ?: 0,
                        totalBytes = parts[4].toLongOrNull() ?: 0,
                    )
                }
                .toList()
        }.onFailure { Log.w(TAG, "device probe failed: ${it.message}") }
            .getOrDefault(emptyList())
    }
}
