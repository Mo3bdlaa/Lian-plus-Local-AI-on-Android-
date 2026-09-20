package com.lian.plus

import com.lian.plus.FitLevelFixtures.profile
import com.lian.plus.core.device.BenchmarkResult
import com.lian.plus.core.device.CapabilityAnalyzer
import com.lian.plus.core.device.DeviceBenchmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GIB = 1024L * 1024 * 1024

class DeviceBenchmarkTest {

    private fun measured(
        cpu: Double = 20.0,
        gpu: Double = 0.0,
        bandwidth: Double = 18.0,
    ) = BenchmarkResult(
        cpuGflops = cpu,
        gpuGflops = gpu,
        memoryBandwidthGbs = bandwidth,
        storageReadMbs = 900.0,
        ranAtMillis = 1L,
        fingerprint = "test",
    )

    @Test
    fun `a result with nothing measured yields no estimate`() {
        assertNull(DeviceBenchmark.tokensPerSecond(BenchmarkResult(), 2 * GIB))
    }

    @Test
    fun `a measured bandwidth produces an estimate that scales with model size`() {
        val small = DeviceBenchmark.tokensPerSecond(measured(), 2 * GIB)
        val large = DeviceBenchmark.tokensPerSecond(measured(), 8 * GIB)
        assertNotNull(small)
        assertNotNull(large)
        // Four times the weights, roughly a quarter the tokens.
        assertTrue("$small should be well above $large", small!! > large!! * 3)
    }

    @Test
    fun `a GPU no faster than the CPU is not worth the memory`() {
        val level = measured(cpu = 20.0, gpu = 22.0)
        assertEquals(1.1, level.gpuSpeedup!!, 0.01)
        assertFalse(level.gpuWorthUsing)
    }

    @Test
    fun `a clearly faster GPU is worth using`() {
        assertTrue(measured(cpu = 20.0, gpu = 60.0).gpuWorthUsing)
    }

    @Test
    fun `a GPU that was never measured offers no speedup figure`() {
        val result = measured(gpu = 0.0)
        assertNull(result.gpuSpeedup)
        assertFalse(result.gpuWorthUsing)
    }

    @Test
    fun `the measurement overrides the static estimate`() {
        val device = profile(ramGb = 12.0, freeGb = 60.0)
        val guessed = CapabilityAnalyzer.estimateTokensPerSecond(device, 4 * GIB)

        // Same phone, but it actually measured half the bandwidth the CPU's
        // feature flags imply. The report should follow the measurement.
        val slow = CapabilityAnalyzer.estimateTokensPerSecond(
            device,
            4 * GIB,
            measured(bandwidth = 4.0),
        )
        assertTrue("$slow should be below the guess of $guessed", slow < guessed)

        val fast = CapabilityAnalyzer.estimateTokensPerSecond(
            device,
            4 * GIB,
            measured(bandwidth = 50.0),
        )
        assertTrue("$fast should be above the guess of $guessed", fast > guessed)
    }

    @Test
    fun `an empty result falls back to the static estimate rather than zero`() {
        val device = profile(ramGb = 8.0, freeGb = 40.0)
        val withEmpty = CapabilityAnalyzer.estimateTokensPerSecond(
            device,
            3 * GIB,
            BenchmarkResult(),
        )
        val withNothing = CapabilityAnalyzer.estimateTokensPerSecond(device, 3 * GIB)
        assertEquals(withNothing, withEmpty, 0.001)
        assertTrue(withEmpty > 0)
    }

    @Test
    fun `a crashed GPU probe is remembered as a distinct state`() {
        val crashed = BenchmarkResult(
            cpuGflops = 20.0,
            gpuCrashed = true,
            ranAtMillis = 1L,
        )
        assertFalse(crashed.gpuWorthUsing)
        assertNull(crashed.gpuSpeedup)
        assertTrue(crashed.hasRun)
    }
}
