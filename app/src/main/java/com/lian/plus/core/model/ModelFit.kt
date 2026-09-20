package com.lian.plus.core.model

import com.lian.plus.core.device.BenchmarkResult
import com.lian.plus.core.device.CapabilityAnalyzer
import com.lian.plus.core.device.CapabilityReport

/** How well a model suits this device. */
enum class FitLevel {
    /** Comfortable headroom. */
    FITS,

    /** Will load, but with little room for a long context or other apps. */
    TIGHT,

    /** Past what the device can hold; loading it will likely be killed. */
    TOO_LARGE,

    /** Not enough free storage to even download it. */
    NO_SPACE,

    /** Nothing on this device can run it. */
    UNSUPPORTED,
}

data class ModelFit(
    val level: FitLevel,
    val headline: String,
    val detail: String,
    /** Rough generation speed for a text model, or null when not applicable. */
    val tokensPerSecond: Double? = null,
) {
    val isDownloadable: Boolean get() = level != FitLevel.UNSUPPORTED && level != FitLevel.NO_SPACE
}

/**
 * Judges a model against the device rather than hiding it.
 *
 * The earlier design filtered anything that would not fit out of the list
 * entirely, which is worse than useless: it looks like the catalogue is tiny,
 * and it gives no way to see *why* something is missing or how far off it is.
 * Everything is listed; this says what will happen if you tap it.
 */
object ModelFitEvaluator {

    fun evaluate(
        sizeBytes: Long,
        kind: ModelKind,
        report: CapabilityReport?,
        measured: BenchmarkResult? = null,
    ): ModelFit {
        if (report == null) {
            return ModelFit(
                FitLevel.FITS,
                "Unknown",
                "The device has not been profiled yet.",
            )
        }

        val free = report.profile.freeStorageBytes
        // Leave room for the download's own temporary file plus breathing space.
        if (sizeBytes > free - 400L * 1024 * 1024) {
            return ModelFit(
                FitLevel.NO_SPACE,
                "Not enough storage",
                "Needs ${formatBytes(sizeBytes)}, but only ${formatBytes(free)} is free.",
            )
        }

        return when (kind) {
            ModelKind.EMBEDDING -> ModelFit(
                FitLevel.FITS,
                "Fits",
                "Embedding models are small enough for any supported device.",
            )

            ModelKind.IMAGE, ModelKind.IMAGE_COMPONENT -> evaluateImage(sizeBytes, report)

            ModelKind.TEXT -> evaluateText(sizeBytes, report, measured)
        }
    }

    private fun evaluateText(
        sizeBytes: Long,
        report: CapabilityReport,
        measured: BenchmarkResult?,
    ): ModelFit {
        // Deliberately the hardware question, not canRunLlm: whether this build
        // shipped the engine is a property of the APK, and answering "not
        // supported" for it would blame the phone for the wrong thing. The
        // missing engine is reported on its own on the Device screen.
        if (!report.hardwareSupportsLlm) {
            return ModelFit(
                FitLevel.UNSUPPORTED,
                "Not supported",
                report.blockers.firstOrNull()?.detail
                    ?: "This device cannot run local text models.",
            )
        }
        val tps = CapabilityAnalyzer.estimateTokensPerSecond(report.profile, sizeBytes, measured)
        return when {
            sizeBytes <= report.maxModelFileBytes -> ModelFit(
                FitLevel.FITS,
                "Fits",
                "About %.1f tokens/s expected.".format(tps),
                tps,
            )

            sizeBytes <= report.hardLimitModelFileBytes -> ModelFit(
                FitLevel.TIGHT,
                "Tight",
                "Will load, but leaves little room for a long context or other " +
                    "apps. About %.1f tokens/s.".format(tps),
                tps,
            )

            else -> ModelFit(
                FitLevel.TOO_LARGE,
                "Too large",
                "Needs ${formatBytes(sizeBytes)} of weights resident; this device can " +
                    "hold about ${formatBytes(report.hardLimitModelFileBytes)}.",
                tps,
            )
        }
    }

    private fun evaluateImage(sizeBytes: Long, report: CapabilityReport): ModelFit {
        if (!report.canRunImageGen) {
            return ModelFit(
                FitLevel.UNSUPPORTED,
                "Not enough free memory",
                "A diffusion run needs the checkpoint resident plus roughly as " +
                    "much again in scratch. This device has " +
                    "${formatBytes(report.profile.availableRamBytes)} free right " +
                    "now — closing a few apps may be enough.",
            )
        }
        // A diffusion run needs roughly the weights again in scratch space for
        // the UNet and VAE, so the usable ceiling is lower than for text.
        val ceiling = (report.maxModelFileBytes * 0.75).toLong()
        return when {
            sizeBytes <= ceiling -> ModelFit(
                FitLevel.FITS,
                "Fits",
                "Up to ${report.recommendedImageSize}px output.",
            )

            sizeBytes <= report.hardLimitModelFileBytes * 0.75 -> ModelFit(
                FitLevel.TIGHT,
                "Tight",
                "Should run at 512px, but larger sizes may be killed for memory.",
            )

            else -> ModelFit(
                FitLevel.TOO_LARGE,
                "Too large",
                "A diffusion run needs roughly twice the checkpoint size in memory.",
            )
        }
    }
}
