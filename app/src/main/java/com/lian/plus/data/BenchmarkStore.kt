package com.lian.plus.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lian.plus.core.device.BenchmarkResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.benchmarkDataStore by preferencesDataStore(name = "lian_benchmark")

/**
 * Stores what the device measured, and the one bit that can only be observed
 * by its absence.
 *
 * A GPU driver that faults inside a compute dispatch kills the process; no
 * exception reaches Kotlin and nothing is written afterwards. So the probe
 * arms a flag before it runs and clears it after. A flag still set at startup
 * means the last attempt did not come back, and the GPU is not offered again
 * until the user asks for it explicitly.
 */
class BenchmarkStore(private val context: Context) {

    val result: Flow<BenchmarkResult> = context.benchmarkDataStore.data.map { p ->
        BenchmarkResult(
            cpuGflops = p[CPU_GFLOPS] ?: 0.0,
            gpuGflops = p[GPU_GFLOPS] ?: 0.0,
            memoryBandwidthGbs = p[BANDWIDTH] ?: 0.0,
            storageReadMbs = p[STORAGE] ?: 0.0,
            gpuRejected = p[GPU_REJECTED] ?: false,
            gpuCrashed = p[GPU_CRASHED] ?: false,
            ranAtMillis = p[RAN_AT] ?: 0L,
            fingerprint = p[FINGERPRINT] ?: "",
        )
    }

    suspend fun save(result: BenchmarkResult) {
        context.benchmarkDataStore.edit { p ->
            p[CPU_GFLOPS] = result.cpuGflops
            p[GPU_GFLOPS] = result.gpuGflops
            p[BANDWIDTH] = result.memoryBandwidthGbs
            p[STORAGE] = result.storageReadMbs
            p[GPU_REJECTED] = result.gpuRejected
            p[GPU_CRASHED] = result.gpuCrashed
            p[RAN_AT] = result.ranAtMillis
            p[FINGERPRINT] = result.fingerprint
        }
    }

    /** True when a GPU probe was armed and never disarmed - it took the process down. */
    suspend fun gpuProbeLeftArmed(): Boolean {
        var armed = false
        context.benchmarkDataStore.edit { p ->
            armed = p[GPU_PROBE_ARMED] ?: false
            if (armed) {
                p[GPU_PROBE_ARMED] = false
                p[GPU_CRASHED] = true
            }
        }
        return armed
    }

    /**
     * Arms or disarms the crash marker. Suspends until the write reaches disk,
     * which is the whole point: a marker still in memory when the driver
     * faults tells us nothing.
     */
    suspend fun setGpuProbeArmed(armed: Boolean) {
        context.benchmarkDataStore.edit { it[GPU_PROBE_ARMED] = armed }
    }

    /** Clears the crash verdict so the GPU can be tried again on request. */
    suspend fun clearGpuCrash() {
        context.benchmarkDataStore.edit {
            it[GPU_CRASHED] = false
            it[GPU_PROBE_ARMED] = false
        }
    }

    private companion object {
        val CPU_GFLOPS = doublePreferencesKey("cpu_gflops")
        val GPU_GFLOPS = doublePreferencesKey("gpu_gflops")
        val BANDWIDTH = doublePreferencesKey("memory_bandwidth_gbs")
        val STORAGE = doublePreferencesKey("storage_read_mbs")
        val GPU_REJECTED = booleanPreferencesKey("gpu_rejected")
        val GPU_CRASHED = booleanPreferencesKey("gpu_crashed")
        val GPU_PROBE_ARMED = booleanPreferencesKey("gpu_probe_armed")
        val RAN_AT = longPreferencesKey("ran_at")
        val FINGERPRINT = stringPreferencesKey("fingerprint")
    }
}
