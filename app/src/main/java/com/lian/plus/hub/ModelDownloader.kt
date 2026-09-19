package com.lian.plus.hub

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

sealed interface DownloadEvent {
    data class Progress(
        val bytesDone: Long,
        val bytesTotal: Long,
        val bytesPerSecond: Long,
    ) : DownloadEvent {
        val fraction: Float
            get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f

        val secondsRemaining: Long?
            get() = if (bytesPerSecond > 0 && bytesTotal > bytesDone) {
                (bytesTotal - bytesDone) / bytesPerSecond
            } else null
    }

    data class Done(val file: File) : DownloadEvent
    data class Failed(val message: String, val retryable: Boolean) : DownloadEvent
}

/**
 * Resumable downloader for weight files.
 *
 * Downloads land in `<name>.part` and are renamed only once the full
 * Content-Length has arrived, so an interrupted transfer can never be mistaken
 * for a usable model. Resuming uses a Range request; if the server answers 200
 * instead of 206 the partial file is discarded and the transfer restarts.
 */
class ModelDownloader(
    private val client: OkHttpClient = HuggingFaceApi.defaultClient(),
    private val tokenProvider: () -> String? = { null },
) {
    fun download(url: String, target: File): Flow<DownloadEvent> = callbackFlow {
        val part = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()

        var call: okhttp3.Call? = null
        try {
            val existing = if (part.exists()) part.length() else 0L

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", HuggingFaceApi.USER_AGENT)
                .apply {
                    if (url.startsWith(HuggingFaceApi.BASE)) {
                        tokenProvider()?.let { header("Authorization", "Bearer $it") }
                    }
                    if (existing > 0) header("Range", "bytes=$existing-")
                }
                .build()

            call = client.newCall(request)
            val response = call.execute()

            response.use { resp ->
                if (!resp.isSuccessful) {
                    val retryable = resp.code in 500..599 || resp.code == 429
                    trySend(DownloadEvent.Failed("HTTP ${resp.code}", retryable))
                    close(); return@use
                }

                val resumed = resp.code == 206
                if (existing > 0 && !resumed) {
                    // Server ignored the range — start over rather than
                    // appending to a file whose prefix we cannot trust.
                    Log.w(TAG, "range ignored for $url, restarting")
                    part.delete()
                }

                val startAt = if (resumed) existing else 0L
                val body = resp.body ?: run {
                    trySend(DownloadEvent.Failed("empty response body", true)); close(); return@use
                }
                val total = body.contentLength().let { if (it > 0) it + startAt else -1L }

                RandomAccessFile(part, "rw").use { out ->
                    out.seek(startAt)
                    val buffer = ByteArray(1 shl 16)
                    var done = startAt
                    var lastEmit = System.nanoTime()
                    var lastBytes = done

                    body.byteStream().use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n

                            val now = System.nanoTime()
                            val elapsedNs = now - lastEmit
                            if (elapsedNs > 250_000_000L) {
                                val rate = ((done - lastBytes) * 1_000_000_000L) / elapsedNs
                                trySend(DownloadEvent.Progress(done, total, rate))
                                lastEmit = now
                                lastBytes = done
                            }
                        }
                    }

                    if (total > 0 && done != total) {
                        trySend(
                            DownloadEvent.Failed(
                                "transfer ended early ($done of $total bytes)", true,
                            ),
                        )
                        close(); return@use
                    }
                    trySend(DownloadEvent.Progress(done, if (total > 0) total else done, 0))
                }

                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    trySend(DownloadEvent.Failed("could not move the file into place", false))
                    close(); return@use
                }
                trySend(DownloadEvent.Done(target))
            }
        } catch (e: CancellationException) {
            // Leave the .part file behind; the next attempt resumes from it.
            throw e
        } catch (e: IOException) {
            trySend(DownloadEvent.Failed(e.message ?: "network error", true))
        } catch (e: Exception) {
            trySend(DownloadEvent.Failed(e.message ?: "unexpected error", false))
        } finally {
            close()
        }

        awaitClose { call?.cancel() }
    }.flowOn(Dispatchers.IO)

    /** Bytes already fetched for [target], so the UI can show a resume point. */
    fun partialBytes(target: File): Long =
        File(target.parentFile, target.name + ".part").let { if (it.exists()) it.length() else 0L }

    /** Throws away an interrupted transfer. */
    fun discardPartial(target: File) {
        File(target.parentFile, target.name + ".part").delete()
    }

    private companion object {
        const val TAG = "LianDownload"
    }
}
