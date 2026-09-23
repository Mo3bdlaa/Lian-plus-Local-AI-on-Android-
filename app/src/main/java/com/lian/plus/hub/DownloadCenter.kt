package com.lian.plus.hub

import com.lian.plus.core.model.ModelKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What a download is doing right now. */
enum class DownloadStatus { QUEUED, RUNNING, PAUSED, FAILED, DONE }

data class DownloadJob(
    val id: String,
    val url: String,
    val fileName: String,
    val repoId: String?,
    /** The file's path inside the repository, folders included. */
    val repoPath: String? = null,
    val kind: ModelKind,
    val targetPath: String,
    val totalBytes: Long,
    val doneBytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val message: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val percent: Int get() = (fraction * 100).toInt()

    val secondsRemaining: Long?
        get() = if (bytesPerSecond > 0 && totalBytes > doneBytes) {
            (totalBytes - doneBytes) / bytesPerSecond
        } else null

    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING
}

/**
 * Process-wide download state.
 *
 * Downloads used to live in the Models screen's ViewModel, which meant leaving
 * the screen cancelled a multi-gigabyte transfer. They belong to the app, not
 * to a screen, so the queue lives here and [DownloadService] does the work;
 * any screen can observe without owning the lifetime.
 */
object DownloadCenter {

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    val active: DownloadJob? get() = _jobs.value.firstOrNull { it.isActive }

    fun enqueue(job: DownloadJob) {
        _jobs.update { current ->
            // Re-queuing the same file resumes it rather than adding a duplicate.
            val existing = current.firstOrNull { it.id == job.id }
            if (existing != null) {
                current.map {
                    if (it.id == job.id) it.copy(status = DownloadStatus.QUEUED, message = null)
                    else it
                }
            } else {
                current + job
            }
        }
    }

    fun update(id: String, transform: (DownloadJob) -> DownloadJob) {
        _jobs.update { current -> current.map { if (it.id == id) transform(it) else it } }
    }

    fun remove(id: String) {
        _jobs.update { current -> current.filterNot { it.id == id } }
    }

    fun byId(id: String): DownloadJob? = _jobs.value.firstOrNull { it.id == id }

    /** Drops finished entries so the list does not grow without bound. */
    fun clearFinished() {
        _jobs.update { current -> current.filter { it.status != DownloadStatus.DONE } }
    }

    /** Stable id for a file, so pause/resume and de-duplication line up. */
    fun idFor(repoId: String?, fileName: String): String =
        "${repoId.orEmpty()}/$fileName".replace('/', '_')
}
