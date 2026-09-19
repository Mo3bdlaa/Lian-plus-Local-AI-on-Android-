package com.lian.plus.core.device

/** Everything we could learn about the hardware, in raw units. */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val socModel: String,
    val board: String,
    val androidRelease: String,
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val is64Bit: Boolean,

    val totalRamBytes: Long,
    val availableRamBytes: Long,
    /** Below this the system starts killing background apps. */
    val lowMemoryThresholdBytes: Long,
    val perAppHeapMb: Int,

    val cpuCores: Int,
    /** Max clock per core in kHz, index = cpu id. Empty when unreadable. */
    val coreMaxFreqKhz: List<Int>,
    val cpuFeatures: Set<String>,

    val gpuRenderer: String?,
    val gpuVendor: String?,

    val freeStorageBytes: Long,
    val totalStorageBytes: Long,

    val thermalStatus: ThermalLevel,
    val isLowRamDevice: Boolean,
) {
    val hasDotProd: Boolean get() = "asimddp" in cpuFeatures
    val hasI8mm: Boolean get() = "i8mm" in cpuFeatures
    val hasFp16: Boolean get() = "asimdhp" in cpuFeatures || "fphp" in cpuFeatures
    val hasSve: Boolean get() = "sve" in cpuFeatures

    /** Cores clocked within 15% of the fastest — the ones worth giving work to. */
    val performanceCores: Int
        get() {
            if (coreMaxFreqKhz.isEmpty()) return (cpuCores / 2).coerceAtLeast(1)
            val top = coreMaxFreqKhz.max()
            return coreMaxFreqKhz.count { it >= top * 0.85 }.coerceAtLeast(1)
        }

    val totalRamGb: Double get() = totalRamBytes / 1_073_741_824.0
}

enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN, UNKNOWN;

    /** Inference should back off (fewer threads) at MODERATE and stop at SEVERE+. */
    val shouldThrottle: Boolean get() = this >= MODERATE
    val shouldStop: Boolean get() = this >= SEVERE && this != UNKNOWN
}
