package com.lian.plus.core.device

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService
import com.lian.plus.core.model.formatBytes

/**
 * What is loadable right now, measured rather than assumed.
 *
 * The earlier version budgeted a fixed 45% of *total* RAM for weights. That is
 * wrong in both directions: on a phone with 10 GB genuinely free it refused
 * models that would have run comfortably, and on a phone with 3 GB free out of
 * 8 it happily recommended something that could not fit. Total RAM says what
 * the phone was sold with; it says nothing about what is available while the
 * user has forty tabs open.
 *
 * So nothing here is a fraction of anything. The numbers come from the system,
 * at the moment the question is asked:
 *
 *  * `availMem` — what Android says can be handed out now.
 *  * `threshold` — the level at which Android starts killing background
 *    processes. It is device-specific and the OS publishes it, which makes it
 *    the right safety margin to respect instead of one invented here.
 *  * whatever this app already has resident, since freeing it is an option.
 */
class MemoryBudget(private val context: Context) {

    data class Snapshot(
        val totalBytes: Long,
        val availableBytes: Long,
        /** The OS's own low-memory line; going under it invites the killer. */
        val systemThresholdBytes: Long,
        /** Bytes this app currently holds in loaded models. */
        val residentBytes: Long,
        val isLowMemory: Boolean,
    ) {
        /**
         * What a new model can take without pushing the system under its own
         * threshold, keeping a little room for the runtime that drives it.
         */
        val freeForNewModel: Long
            get() = (availableBytes - systemThresholdBytes - RUNTIME_RESERVE)
                .coerceAtLeast(0)

        /**
         * The same, if everything this app holds were released first — the
         * ceiling for "unload the other model and load this one".
         */
        val freeIfEvicted: Long
            get() = (availableBytes + residentBytes - systemThresholdBytes - RUNTIME_RESERVE)
                .coerceAtLeast(0)

        val summary: String
            get() = "${formatBytes(availableBytes)} free of ${formatBytes(totalBytes)}" +
                if (residentBytes > 0) " · ${formatBytes(residentBytes)} in loaded models" else ""
    }

    /** Reads the current state. Cheap enough to call before every decision. */
    fun snapshot(residentBytes: Long = 0): Snapshot {
        val am = context.getSystemService<ActivityManager>()
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        return Snapshot(
            totalBytes = info.totalMem,
            availableBytes = info.availMem,
            systemThresholdBytes = info.threshold,
            residentBytes = residentBytes,
            isLowMemory = info.lowMemory,
        )
    }

    /**
     * Whether [sizeBytes] of weights will fit, and what it would cost.
     *
     * Weights are mmap'd, so they land in the page cache rather than the app's
     * own heap: exceeding the budget degrades into paging from storage rather
     * than an immediate kill. That is why [Verdict.TIGHT] exists as a distinct
     * answer — it is slow, not fatal.
     */
    fun evaluate(sizeBytes: Long, residentBytes: Long = 0): Fit {
        val snap = snapshot(residentBytes)
        val free = snap.freeForNewModel
        val afterEviction = snap.freeIfEvicted

        // A little slack so a model that exactly equals the free figure is not
        // called comfortable.
        val comfortable = (free * 0.85).toLong()

        return when {
            sizeBytes <= comfortable -> Fit(Verdict.FITS, snap, needsEviction = false)
            sizeBytes <= free -> Fit(Verdict.TIGHT, snap, needsEviction = false)
            residentBytes > 0 && sizeBytes <= (afterEviction * 0.85).toLong() ->
                Fit(Verdict.FITS_AFTER_EVICTION, snap, needsEviction = true)
            residentBytes > 0 && sizeBytes <= afterEviction ->
                Fit(Verdict.TIGHT_AFTER_EVICTION, snap, needsEviction = true)
            else -> Fit(Verdict.TOO_LARGE, snap, needsEviction = false)
        }
    }

    enum class Verdict {
        FITS,
        TIGHT,
        /** Would fit once the currently loaded model is released. */
        FITS_AFTER_EVICTION,
        TIGHT_AFTER_EVICTION,
        TOO_LARGE,
    }

    data class Fit(
        val verdict: Verdict,
        val snapshot: Snapshot,
        val needsEviction: Boolean,
    ) {
        val canLoad: Boolean get() = verdict != Verdict.TOO_LARGE
        val isComfortable: Boolean
            get() = verdict == Verdict.FITS || verdict == Verdict.FITS_AFTER_EVICTION
    }

    companion object {
        /**
         * Room for the KV cache, compute buffers, the UI and the JVM itself —
         * the parts that are not the weights. Deliberately modest: the point
         * is to leave the system its own threshold intact, not to impose a
         * second, invented ceiling on top of it.
         */
        private const val RUNTIME_RESERVE = 384L * 1024 * 1024
    }
}
