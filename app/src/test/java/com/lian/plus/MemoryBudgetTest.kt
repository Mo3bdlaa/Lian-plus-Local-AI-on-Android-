package com.lian.plus

import com.lian.plus.core.device.MemoryBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GB = 1024L * 1024 * 1024
private const val MB = 1024L * 1024

/**
 * The snapshot is what the device reports, so the tests drive it directly
 * rather than through Android's ActivityManager.
 */
private fun snapshot(
    totalGb: Double,
    availableGb: Double,
    thresholdMb: Long = 256,
    residentGb: Double = 0.0,
) = MemoryBudget.Snapshot(
    totalBytes = (totalGb * GB).toLong(),
    availableBytes = (availableGb * GB).toLong(),
    systemThresholdBytes = thresholdMb * MB,
    residentBytes = (residentGb * GB).toLong(),
    isLowMemory = false,
)

class MemoryBudgetTest {

    @Test
    fun `free memory drives the budget, not total memory`() {
        // Same phone, different moments. The old fixed-fraction rule gave the
        // same answer for both, which was the bug.
        val busy = snapshot(totalGb = 12.0, availableGb = 1.5)
        val idle = snapshot(totalGb = 12.0, availableGb = 10.0)
        assertTrue(idle.freeForNewModel > busy.freeForNewModel * 4)
    }

    @Test
    fun `a small phone with room can still load a small model`() {
        // 1 GB free and a 200 MB model: nothing about this should be refused.
        val snap = snapshot(totalGb = 3.0, availableGb = 1.2, thresholdMb = 128)
        assertTrue(200 * MB <= snap.freeForNewModel)
    }

    @Test
    fun `the system threshold is respected rather than a made-up margin`() {
        val lenient = snapshot(totalGb = 8.0, availableGb = 4.0, thresholdMb = 128)
        val strict = snapshot(totalGb = 8.0, availableGb = 4.0, thresholdMb = 1024)
        // A device that declares a higher low-memory line gets less budget.
        assertEquals(
            (1024 - 128) * MB,
            lenient.freeForNewModel - strict.freeForNewModel,
        )
    }

    @Test
    fun `eviction headroom counts what this app already holds`() {
        val snap = snapshot(totalGb = 12.0, availableGb = 2.0, residentGb = 4.0)
        assertTrue(snap.freeIfEvicted > snap.freeForNewModel)
        assertEquals(4 * GB, snap.freeIfEvicted - snap.freeForNewModel)
    }

    @Test
    fun `a model that only fits after eviction is reported as such`() {
        val snap = snapshot(totalGb = 12.0, availableGb = 1.0, residentGb = 5.0)
        // 4 GB will not fit in 1 GB free, but will once 5 GB is released.
        assertFalse(4 * GB <= snap.freeForNewModel)
        assertTrue(4 * GB <= snap.freeIfEvicted)
    }

    @Test
    fun `nothing fits when the device is under its own threshold`() {
        val snap = snapshot(totalGb = 4.0, availableGb = 0.15, thresholdMb = 256)
        assertEquals(0L, snap.freeForNewModel)
    }

    @Test
    fun `the summary states free and total, not a fraction`() {
        val snap = snapshot(totalGb = 12.0, availableGb = 6.0)
        assertTrue(snap.summary.contains("free of"))
    }
}
