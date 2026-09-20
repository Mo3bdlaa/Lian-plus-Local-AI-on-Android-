package com.lian.plus.core.device

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.system.measureNanoTime

/**
 * What the device measured, rather than what its spec sheet implies.
 *
 * A phone's SoC name, its Arm feature flags and its GPU renderer string are
 * all we can read for free, and none of them predicts throughput within a
 * factor of two: the same silicon differs by that much between a cool phone
 * and a warm one, between ROMs, and between a driver that will run compute
 * shaders and one that merely claims to. So the app spends a couple of seconds
 * once, in the background, finding out.
 *
 * Every field is nullable-by-absence: a measurement that could not be taken is
 * 0.0, and callers fall back to the static estimate rather than to a guess
 * dressed up as a measurement.
 */
data class BenchmarkResult(
    /** Matmul throughput on the CPU backend, GFLOP/s. */
    val cpuGflops: Double = 0.0,
    /** Matmul throughput on the best GPU device, GFLOP/s. 0 when unusable. */
    val gpuGflops: Double = 0.0,
    /** Sustained large-block read bandwidth, GB/s. */
    val memoryBandwidthGbs: Double = 0.0,
    /** Cold-ish sequential read from app storage, MB/s. */
    val storageReadMbs: Double = 0.0,
    /** True when the GPU probe was attempted and the device refused it. */
    val gpuRejected: Boolean = false,
    /** True when a previous GPU probe took the process down with it. */
    val gpuCrashed: Boolean = false,
    val ranAtMillis: Long = 0L,
    /** Identifies the hardware and build this was measured on. */
    val fingerprint: String = "",
) {
    val hasRun: Boolean get() = ranAtMillis > 0L

    /** How much faster the GPU is at dense matmul. Null when there is no figure. */
    val gpuSpeedup: Double?
        get() = if (gpuGflops > 0 && cpuGflops > 0) gpuGflops / cpuGflops else null

    /**
     * Whether offloading to the GPU is worth offering as the default.
     *
     * A GPU that is merely level with the CPU is not worth the memory it
     * would take from the image model, and on a shared memory bus a small
     * margin disappears under thermal load.
     */
    val gpuWorthUsing: Boolean get() = (gpuSpeedup ?: 0.0) >= 1.5

    companion object {
        /** Changing this invalidates stored results from older app versions. */
        const val SCHEMA = 2
    }
}

/**
 * Runs the measurements. Silent by design - it is started once, in the
 * background, and the user is never asked to wait for it or told it happened.
 * Its only visible effect is that the numbers elsewhere in the app get better.
 */
object DeviceBenchmark {

    private const val TAG = "LianBench"

    /** Square matmul dimension. 512 is ~268 MFLOP a pass: long enough to
     *  measure, short enough that a slow device is not stuck on one. */
    private const val MATMUL_DIM = 512

    /** Per-device time budget. Two of these plus the I/O probes stay under 2s. */
    private const val BUDGET_MS = 300

    /**
     * The read buffer has to be larger than the last-level cache or it
     * measures the cache rather than the bus - 32 MB clears any phone's. On a
     * device with little free memory it is scaled down instead of skipped: a
     * slightly optimistic figure beats none, and the probe must not be the
     * thing that pushes a struggling phone over.
     */
    private const val BANDWIDTH_BUFFER_BYTES = 32 * 1024 * 1024
    private const val MIN_BANDWIDTH_BUFFER_BYTES = 4 * 1024 * 1024

    private const val STORAGE_PROBE_BYTES = 16 * 1024 * 1024

    /** Identifies the hardware and the build; a change means re-measure. */
    fun fingerprintOf(context: Context): String {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "?"
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        return "${BenchmarkResult.SCHEMA}/${Build.DEVICE}/$soc/${Build.DISPLAY}/$version"
    }

    /**
     * Measures everything.
     *
     * [gpuProbeGuard] is invoked immediately before the GPU is touched and is
     * expected to persist a marker durably: a driver that faults during a
     * compute dispatch takes the process with it, and there is no way to catch
     * that from Kotlin. Finding the marker still set on the next launch is the
     * only evidence such a crash leaves behind, and it is enough to stop the
     * app from trying the same thing again.
     */
    suspend fun run(
        context: Context,
        threads: Int,
        gpuProbeGuard: suspend (armed: Boolean) -> Unit = {},
        previouslyCrashed: Boolean = false,
    ): BenchmarkResult = withContext(Dispatchers.Default) {
        val devices = ComputeDevices.devices()

        val cpuIndex = devices.indexOfFirst { it.type == ComputeDevice.Type.CPU }
        val gpuIndex = devices.indexOfFirst { it.isGpu }

        val cpu = if (cpuIndex >= 0) matmul(cpuIndex, threads) else 0.0

        var gpu = 0.0
        var rejected = false
        if (gpuIndex >= 0 && !previouslyCrashed) {
            gpuProbeGuard(true)
            gpu = matmul(gpuIndex, threads)
            gpuProbeGuard(false)
            rejected = gpu <= 0.0
        }

        val bufferBytes = MemoryBudget(context).snapshot().availableBytes
            .let { (it / 64).coerceIn(
                MIN_BANDWIDTH_BUFFER_BYTES.toLong(),
                BANDWIDTH_BUFFER_BYTES.toLong(),
            ) }
            .toInt()

        BenchmarkResult(
            cpuGflops = cpu,
            gpuGflops = gpu.coerceAtLeast(0.0),
            memoryBandwidthGbs = memoryBandwidth(bufferBytes),
            storageReadMbs = storageRead(context),
            gpuRejected = rejected,
            gpuCrashed = previouslyCrashed,
            ranAtMillis = System.currentTimeMillis(),
            fingerprint = fingerprintOf(context),
        )
    }

    private fun matmul(deviceIndex: Int, threads: Int): Double {
        if (!com.lian.plus.llm.LlamaNative.isAvailable) return 0.0
        return runCatching {
            com.lian.plus.llm.LlamaNative
                .benchmarkMatmul(deviceIndex, MATMUL_DIM, threads, BUDGET_MS)
        }.onFailure { Log.w(TAG, "matmul probe on device $deviceIndex failed: ${it.message}") }
            .getOrDefault(-1.0)
            .let { if (it.isFinite() && it > 0) it else 0.0 }
    }

    /**
     * Sequential read bandwidth over a buffer far larger than any phone's last
     * level cache, which is what token generation is actually limited by: one
     * full pass over the weights per token, from main memory.
     *
     * A direct ByteBuffer keeps the JVM's own copying out of the measurement.
     */
    private fun memoryBandwidth(bufferBytes: Int): Double = runCatching {
        val buffer = ByteBuffer
            .allocateDirect(bufferBytes)
            .order(ByteOrder.nativeOrder())
        val longs = buffer.asLongBuffer()
        val words = longs.capacity()

        // Touch every page first so the measurement is of reads, not faults.
        for (i in 0 until words) longs.put(i, i.toLong())

        var best = 0.0
        repeat(3) {
            var sink = 0L
            val nanos = measureNanoTime {
                var i = 0
                while (i < words) {
                    sink += longs.get(i)
                    i += 8 // one 64-byte cache line per step
                }
            }
            // Each step pulls a full line, so the traffic is the whole buffer.
            val gbs = bufferBytes / (nanos / 1e9) / 1e9
            if (sink != Long.MIN_VALUE && gbs > best) best = gbs
        }
        best
    }.onFailure { Log.w(TAG, "bandwidth probe failed: ${it.message}") }.getOrDefault(0.0)

    /**
     * How fast model weights come off storage. This is what decides whether a
     * model that does not fit in memory is merely slow or unusable, since
     * mmap'd weights evicted under pressure are re-read from here.
     */
    private suspend fun storageRead(context: Context): Double =
        withContext(Dispatchers.IO) {
            val probe = File(context.cacheDir, "lian-storage-probe.bin")
            runCatching {
                val block = ByteArray(1 shl 20) { (it and 0xFF).toByte() }
                probe.outputStream().use { out ->
                    repeat(STORAGE_PROBE_BYTES / block.size) { out.write(block) }
                    out.flush()
                }

                val sink = ByteArray(block.size)
                var read = 0L
                val nanos = measureNanoTime {
                    probe.inputStream().use { input ->
                        while (true) {
                            val n = input.read(sink)
                            if (n <= 0) break
                            read += n
                        }
                    }
                }
                if (nanos <= 0 || read <= 0) 0.0 else read / (nanos / 1e9) / (1 shl 20)
            }.onFailure { Log.w(TAG, "storage probe failed: ${it.message}") }
                .also { probe.delete() }
                .getOrDefault(0.0)
        }

    /**
     * Tokens/second for a model of [modelFileBytes], from measured bandwidth.
     *
     * Generation reads every weight once per token, so throughput is bandwidth
     * divided by model size. The 0.7 accounts for what a real forward pass
     * does beyond the streaming read - attention over the KV cache, sampling,
     * and the part of the bus the rest of the phone is using.
     */
    fun tokensPerSecond(result: BenchmarkResult, modelFileBytes: Long): Double? {
        if (!result.hasRun || result.memoryBandwidthGbs <= 0 || modelFileBytes <= 0) return null
        val modelGb = modelFileBytes / 1_073_741_824.0
        val raw = result.memoryBandwidthGbs * 0.7 / modelGb
        return (raw * 10).roundToInt() / 10.0
    }
}
