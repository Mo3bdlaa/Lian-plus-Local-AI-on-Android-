package com.lian.plus

import com.lian.plus.core.device.CapabilityAnalyzer
import com.lian.plus.core.device.DeviceProfile
import com.lian.plus.FitLevelFixtures.profile
import com.lian.plus.core.device.ThermalLevel
import com.lian.plus.core.model.FitLevel
import com.lian.plus.core.model.ModelFitEvaluator
import com.lian.plus.core.model.ModelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GB = 1024L * 1024 * 1024

class ModelFitTest {

    private val flagship = CapabilityAnalyzer.analyze(profile(ramGb = 12.0, freeGb = 60.0))
    private val entry = CapabilityAnalyzer.analyze(profile(ramGb = 4.0, freeGb = 20.0))

    @Test
    fun `a small model fits a flagship`() {
        val fit = ModelFitEvaluator.evaluate(2 * GB, ModelKind.TEXT, flagship)
        assertEquals(FitLevel.FITS, fit.level)
        assertTrue("should estimate a speed", (fit.tokensPerSecond ?: 0.0) > 0)
    }

    @Test
    fun `an oversized model is flagged rather than hidden`() {
        val fit = ModelFitEvaluator.evaluate(40 * GB, ModelKind.TEXT, flagship)
        assertEquals(FitLevel.TOO_LARGE, fit.level)
        // Still downloadable: the user is told, not blocked by a missing entry.
        assertTrue(fit.isDownloadable)
        assertTrue(fit.detail.isNotBlank())
    }

    @Test
    fun `the band between comfortable and impossible reads as tight`() {
        // The band is defined by the two live readings, not by a fraction:
        // above the reserve-adjusted budget it still loads, but the weights
        // start paging rather than staying resident.
        val tight = (flagship.maxModelFileBytes + flagship.hardLimitModelFileBytes) / 2
        assertEquals(
            FitLevel.TIGHT,
            ModelFitEvaluator.evaluate(tight, ModelKind.TEXT, flagship).level,
        )
        assertTrue(flagship.hardLimitModelFileBytes > flagship.maxModelFileBytes)
    }

    @Test
    fun `no storage beats every other verdict`() {
        val cramped = CapabilityAnalyzer.analyze(profile(ramGb = 12.0, freeGb = 1.0))
        val fit = ModelFitEvaluator.evaluate(2 * GB, ModelKind.TEXT, cramped)
        assertEquals(FitLevel.NO_SPACE, fit.level)
        assertFalse(fit.isDownloadable)
    }

    @Test
    fun `image support follows free memory, not the size on the box`() {
        // A phone with almost nothing free cannot, whatever its total RAM.
        val starved = CapabilityAnalyzer.analyze(
            profile(ramGb = 12.0, freeGb = 60.0, availableRamGb = 0.4),
        )
        assertEquals(
            FitLevel.UNSUPPORTED,
            ModelFitEvaluator.evaluate(2 * GB, ModelKind.IMAGE, starved).level,
        )

        // And a modest phone with room genuinely can: the model the user was
        // running is 1.5 GB, not the 5 GB the old fixed rule assumed.
        val modest = CapabilityAnalyzer.analyze(
            profile(ramGb = 6.0, freeGb = 20.0, availableRamGb = 3.5),
        )
        val fit = ModelFitEvaluator.evaluate(1_500L * 1024 * 1024, ModelKind.IMAGE, modest)
        assertTrue("1.5 GB should be loadable with 3.5 GB free", fit.isDownloadable)
    }

    @Test
    fun `the same phone is judged differently when it is busy`() {
        val idle = CapabilityAnalyzer.analyze(
            profile(ramGb = 12.0, freeGb = 60.0, availableRamGb = 10.0),
        )
        val busy = CapabilityAnalyzer.analyze(
            profile(ramGb = 12.0, freeGb = 60.0, availableRamGb = 1.5),
        )
        // Total RAM is identical; only the free figure differs, and that is
        // what the budget is now built from.
        assertTrue(idle.maxModelFileBytes > busy.maxModelFileBytes)
    }

    @Test
    fun `image models need more headroom than text of the same size`() {
        val size = (flagship.maxModelFileBytes * 0.9).toLong()
        assertEquals(FitLevel.FITS, ModelFitEvaluator.evaluate(size, ModelKind.TEXT, flagship).level)
        // A diffusion run needs scratch space on top of the weights.
        assertTrue(
            ModelFitEvaluator.evaluate(size, ModelKind.IMAGE, flagship).level != FitLevel.FITS,
        )
    }

    @Test
    fun `embedding models always fit`() {
        val fit = ModelFitEvaluator.evaluate(40L * 1024 * 1024, ModelKind.EMBEDDING, entry)
        assertEquals(FitLevel.FITS, fit.level)
    }

    @Test
    fun `an unprofiled device does not block anything`() {
        val fit = ModelFitEvaluator.evaluate(5 * GB, ModelKind.TEXT, null)
        assertTrue(fit.isDownloadable)
    }
}

/** Builds device profiles for the tests without touching Android APIs. */
object FitLevelFixtures {
    fun profile(
        ramGb: Double,
        freeGb: Double,
        availableRamGb: Double = ramGb * 0.5,
    ) = DeviceProfile(
        manufacturer = "Test",
        model = "Device",
        socModel = "Test SoC",
        board = "board",
        androidRelease = "15",
        sdkInt = 35,
        supportedAbis = listOf("arm64-v8a"),
        is64Bit = true,
        totalRamBytes = (ramGb * GB).toLong(),
        availableRamBytes = (availableRamGb * GB).toLong(),
        lowMemoryThresholdBytes = 256L * 1024 * 1024,
        perAppHeapMb = 512,
        cpuCores = 8,
        coreMaxFreqKhz = listOf(2000000, 2000000, 3000000, 3000000),
        cpuFeatures = setOf("asimddp", "i8mm"),
        gpuRenderer = null,
        gpuVendor = null,
        freeStorageBytes = (freeGb * GB).toLong(),
        totalStorageBytes = (256 * GB),
        thermalStatus = ThermalLevel.NONE,
        isLowRamDevice = false,
    )
}
