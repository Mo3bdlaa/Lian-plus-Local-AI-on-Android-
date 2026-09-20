package com.lian.plus.core.device

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class DeviceTier(val label: String) {
    UNSUPPORTED("Not supported"),
    MINIMAL("Minimal"),
    ENTRY("Entry"),
    CAPABLE("Capable"),
    HIGH_END("High end"),
    FLAGSHIP("Flagship"),
}

enum class CheckStatus { PASS, WARN, FAIL }

data class CapabilityCheck(
    val title: String,
    val detail: String,
    val status: CheckStatus,
)

/**
 * The verdict the app shows before letting anyone download several gigabytes of
 * weights: what this phone can run, how big, and with how much context.
 */
data class CapabilityReport(
    val profile: DeviceProfile,
    val tier: DeviceTier,
    /** The hardware is capable, whatever the APK happens to ship. */
    val hardwareSupportsLlm: Boolean,
    /** Capable *and* the engine is present in this build. */
    val canRunLlm: Boolean,
    val canRunImageGen: Boolean,
    /** Largest model file we are willing to recommend, in bytes. */
    val maxModelFileBytes: Long,
    /** Largest model file that will still load, but with no headroom to spare. */
    val hardLimitModelFileBytes: Long,
    val recommendedContext: Int,
    val maxContext: Int,
    val recommendedThreads: Int,
    val recommendedImageSize: Int,
    val checks: List<CapabilityCheck>,
    val summary: String,
) {
    val blockers: List<CapabilityCheck> get() = checks.filter { it.status == CheckStatus.FAIL }
}

object CapabilityAnalyzer {

    private const val GB = 1_073_741_824.0

    /**
     * Weights are mmap'd, so they live in the page cache rather than the app's
     * heap — but the kernel will happily evict them under pressure, and every
     * eviction turns the next token into a disk read. Budgeting ~45% of total
     * RAM for weights keeps the working set resident while leaving Android and
     * the foreground app alive.
     */
    private const val WEIGHT_BUDGET_FRACTION = 0.45

    /** Absolute ceiling before the OOM killer becomes a certainty. */
    private const val HARD_BUDGET_FRACTION = 0.62

    fun analyze(profile: DeviceProfile): CapabilityReport {
        val checks = mutableListOf<CapabilityCheck>()
        val ramGb = profile.totalRamBytes / GB

        // ---- architecture -------------------------------------------------
        val arm64 = profile.supportedAbis.any { it == "arm64-v8a" }
        checks += CapabilityCheck(
            title = "CPU architecture",
            detail = if (arm64) {
                "arm64-v8a" + buildString {
                    val ext = buildList {
                        if (profile.hasDotProd) add("dotprod")
                        if (profile.hasI8mm) add("i8mm")
                        if (profile.hasFp16) add("fp16")
                        if (profile.hasSve) add("sve")
                    }
                    if (ext.isNotEmpty()) append(" · ").append(ext.joinToString(", "))
                }
            } else {
                "This build ships arm64-v8a only; device reports " +
                    profile.supportedAbis.joinToString()
            },
            status = if (arm64) CheckStatus.PASS else CheckStatus.FAIL,
        )

        // ---- memory --------------------------------------------------------
        val ramStatus = when {
            ramGb < 2.5 -> CheckStatus.FAIL
            ramGb < 6 -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        checks += CapabilityCheck(
            title = "Memory",
            detail = "%.1f GB total · %.1f GB free right now".format(
                ramGb, profile.availableRamBytes / GB,
            ),
            status = ramStatus,
        )

        // ---- cores ---------------------------------------------------------
        checks += CapabilityCheck(
            title = "CPU cores",
            detail = "${profile.cpuCores} cores · ${profile.performanceCores} performance cores" +
                (profile.coreMaxFreqKhz.maxOrNull()
                    ?.let { " · up to %.2f GHz".format(it / 1_000_000.0) } ?: ""),
            status = if (profile.performanceCores >= 2) CheckStatus.PASS else CheckStatus.WARN,
        )

        // ---- storage -------------------------------------------------------
        val freeGb = profile.freeStorageBytes / GB
        checks += CapabilityCheck(
            title = "Free storage",
            detail = "%.1f GB available".format(freeGb),
            status = when {
                freeGb < 1.5 -> CheckStatus.FAIL
                freeGb < 6 -> CheckStatus.WARN
                else -> CheckStatus.PASS
            },
        )

        // ---- accelerators --------------------------------------------------
        checks += CapabilityCheck(
            title = "Matrix extensions",
            detail = when {
                profile.hasI8mm && profile.hasDotProd ->
                    "i8mm + dotprod available — quantised matmuls run on the fast path"
                profile.hasDotProd -> "dotprod available; no i8mm, expect ~20-30% slower prefill"
                else -> "Neither dotprod nor i8mm detected — generation will be noticeably slower"
            },
            status = when {
                profile.hasI8mm -> CheckStatus.PASS
                profile.hasDotProd -> CheckStatus.WARN
                else -> CheckStatus.WARN
            },
        )

        if (profile.gpuRenderer != null) {
            checks += CapabilityCheck(
                title = "GPU",
                detail = "${profile.gpuRenderer} — inference runs on CPU in this build",
                status = CheckStatus.PASS,
            )
        }

        // ---- thermal -------------------------------------------------------
        if (profile.thermalStatus.shouldThrottle) {
            checks += CapabilityCheck(
                title = "Thermal state",
                detail = "Device is at ${profile.thermalStatus.name.lowercase()} — " +
                    "speeds will be reduced until it cools down",
                status = if (profile.thermalStatus.shouldStop) CheckStatus.FAIL else CheckStatus.WARN,
            )
        }

        if (!com.lian.plus.llm.LlamaNative.isAvailable) {
            checks += CapabilityCheck(
                title = "Text engine",
                detail = "Native library missing — this APK was built without the engines",
                status = CheckStatus.FAIL,
            )
        }

        // ---- budgets -------------------------------------------------------
        val weightBudget = (profile.totalRamBytes * WEIGHT_BUDGET_FRACTION).toLong()
        val hardBudget = (profile.totalRamBytes * HARD_BUDGET_FRACTION).toLong()
        // Never recommend something that will not fit on disk either.
        val storageCap = max(0L, profile.freeStorageBytes - 800L * 1024 * 1024)

        val tier = when {
            !arm64 -> DeviceTier.UNSUPPORTED
            ramGb < 2.5 -> DeviceTier.UNSUPPORTED
            ramGb < 4 -> DeviceTier.MINIMAL
            ramGb < 6 -> DeviceTier.ENTRY
            ramGb < 8 -> DeviceTier.CAPABLE
            ramGb < 12 -> DeviceTier.HIGH_END
            else -> DeviceTier.FLAGSHIP
        }

        val recommendedContext = when (tier) {
            DeviceTier.UNSUPPORTED, DeviceTier.MINIMAL -> 2048
            DeviceTier.ENTRY -> 4096
            DeviceTier.CAPABLE -> 8192
            DeviceTier.HIGH_END -> 8192
            DeviceTier.FLAGSHIP -> 16384
        }
        val maxContext = when (tier) {
            DeviceTier.UNSUPPORTED, DeviceTier.MINIMAL -> 4096
            DeviceTier.ENTRY -> 8192
            DeviceTier.CAPABLE -> 16384
            DeviceTier.HIGH_END -> 32768
            DeviceTier.FLAGSHIP -> 65536
        }

        // Leave one performance core for the UI thread and the HTTP server.
        val threads = max(2, min(profile.performanceCores, profile.cpuCores - 1))

        val canRunImage = arm64 && ramGb >= 5.5 && freeGb >= 3
        val imageSize = when {
            ramGb >= 11 -> 1024
            ramGb >= 7.5 -> 768
            else -> 512
        }

        val summary = buildSummary(tier, ramGb, min(weightBudget, storageCap), canRunImage)

        return CapabilityReport(
            profile = profile,
            tier = tier,
            hardwareSupportsLlm = arm64 && ramGb >= 2.5,
            canRunLlm = arm64 && ramGb >= 2.5 && com.lian.plus.llm.LlamaNative.isAvailable,
            canRunImageGen = canRunImage,
            maxModelFileBytes = min(weightBudget, storageCap),
            hardLimitModelFileBytes = min(hardBudget, storageCap),
            recommendedContext = recommendedContext,
            maxContext = maxContext,
            recommendedThreads = threads,
            recommendedImageSize = imageSize,
            checks = checks,
            summary = summary,
        )
    }

    private fun buildSummary(
        tier: DeviceTier,
        ramGb: Double,
        budget: Long,
        canRunImage: Boolean,
    ): String {
        if (tier == DeviceTier.UNSUPPORTED) {
            return "This device cannot run local models: it needs a 64-bit Arm CPU " +
                "and at least 3 GB of RAM."
        }
        val budgetGb = budget / GB
        val sizeHint = when (tier) {
            DeviceTier.MINIMAL -> "1B-2B models at Q4"
            DeviceTier.ENTRY -> "up to 3B-4B models at Q4"
            DeviceTier.CAPABLE -> "up to 8B models at Q4"
            DeviceTier.HIGH_END -> "8B models at Q4/Q5 comfortably"
            DeviceTier.FLAGSHIP -> "up to 14B models at Q4, or 8B at Q5/Q6"
            DeviceTier.UNSUPPORTED -> ""
        }
        return buildString {
            append("%.0f GB of RAM puts this device in the ".format(ramGb))
            append(tier.label.lowercase())
            append(" tier. It can run ")
            append(sizeHint)
            append(" — roughly %.1f GB of weights. ".format(budgetGb))
            append(
                if (canRunImage) "Image generation is supported."
                else "Image generation needs at least 6 GB of RAM and is disabled."
            )
        }
    }

    /**
     * Estimated bytes the KV cache will occupy. Used to warn before a context
     * size is chosen that will not fit alongside the weights.
     */
    fun kvCacheBytes(
        nLayers: Int,
        nEmbdKv: Int,
        contextSize: Int,
        bytesPerElement: Int = 2,
    ): Long = 2L * nLayers * nEmbdKv * contextSize * bytesPerElement

    /** Rough tokens/second estimate, for setting expectations before a download. */
    fun estimateTokensPerSecond(profile: DeviceProfile, modelFileBytes: Long): Double {
        if (modelFileBytes <= 0) return 0.0
        // Generation is memory-bandwidth bound: one full pass over the weights
        // per token. These bandwidth figures are deliberately conservative.
        val bandwidthGbPerSec = when {
            profile.hasI8mm && profile.performanceCores >= 4 -> 22.0
            profile.hasI8mm -> 16.0
            profile.hasDotProd -> 11.0
            else -> 6.0
        }
        val modelGb = modelFileBytes / GB
        val raw = bandwidthGbPerSec / modelGb
        // Sustained throughput on a phone lands well under the peak.
        return (raw * 0.55 * 10).roundToInt() / 10.0
    }
}
