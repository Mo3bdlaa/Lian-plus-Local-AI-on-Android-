package com.lian.plus.hub

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.lian.plus.MainActivity
import com.lian.plus.R
import com.lian.plus.core.LianRuntime
import com.lian.plus.core.model.ModelKind
import com.lian.plus.core.model.formatBytes
import com.lian.plus.util.Notifications
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs model downloads in the foreground, with a progress notification.
 *
 * A foreground service is not decoration here. A multi-gigabyte transfer over
 * mobile data takes long enough that the user will switch away, and Android
 * suspends background processes — which is why the previous ViewModel-scoped
 * version died the moment the Models screen left composition.
 */
class DownloadService : LifecycleService() {

    private var currentJob: Job? = null
    private var currentId: String? = null
    private lateinit var runtime: LianRuntime

    override fun onCreate() {
        super.onCreate()
        runtime = LianRuntime.get(this)
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_PAUSE -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: currentId
                pause(id)
                return START_STICKY
            }

            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: currentId
                cancel(id)
                return START_NOT_STICKY
            }
        }

        val id = intent?.getStringExtra(EXTRA_ID)
        if (id != null) startJob(id) else startNextQueued()
        return START_STICKY
    }

    private fun startNextQueued() {
        val next = DownloadCenter.jobs.value.firstOrNull { it.status == DownloadStatus.QUEUED }
        if (next != null) {
            startJob(next.id)
            return
        }

        // Nothing queued. A paused job still needs its notification, because
        // that notification carries the only Resume button the user has - so
        // the service stays in the foreground until the queue is genuinely
        // empty of anything resumable.
        val resumable = DownloadCenter.jobs.value.firstOrNull {
            it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED
        }
        if (resumable != null) {
            startForeground(Notifications.ID_DOWNLOAD, buildNotification(resumable))
            return
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startJob(id: String) {
        if (currentId == id && currentJob?.isActive == true) return

        val job = DownloadCenter.byId(id) ?: run { startNextQueued(); return }
        currentJob?.cancel()
        currentId = id

        startForeground(Notifications.ID_DOWNLOAD, buildNotification(job))

        currentJob = lifecycleScope.launch {
            DownloadCenter.update(id) { it.copy(status = DownloadStatus.RUNNING, message = null) }
            val target = File(job.targetPath)

            runtime.downloader.download(job.url, target).collect { event ->
                when (event) {
                    is DownloadEvent.Progress -> {
                        DownloadCenter.update(id) {
                            it.copy(
                                doneBytes = event.bytesDone,
                                totalBytes = if (event.bytesTotal > 0) event.bytesTotal else it.totalBytes,
                                bytesPerSecond = event.bytesPerSecond,
                                status = DownloadStatus.RUNNING,
                            )
                        }
                        DownloadCenter.byId(id)?.let { notify(buildNotification(it)) }
                    }

                    is DownloadEvent.Done -> {
                        // A shard on its own is not a model: hold registration
                        // until the whole set has landed, then register through
                        // the first shard, which is how llama.cpp opens a split.
                        val first = firstShardOf(event.file)
                        if (runtime.modelStore.splitSetComplete(first)) {
                            runCatching {
                                runtime.modelStore.register(
                                    first,
                                    job.kind,
                                    job.repoId,
                                    relativeName = job.repoPath,
                                )
                            }.onFailure { Log.w(TAG, "could not register ${first.name}", it) }
                        }

                        DownloadCenter.update(id) {
                            it.copy(status = DownloadStatus.DONE, doneBytes = it.totalBytes)
                        }
                        notifyFinished(job.fileName)
                    }

                    is DownloadEvent.Failed -> {
                        DownloadCenter.update(id) {
                            it.copy(
                                status = DownloadStatus.FAILED,
                                message = event.message,
                                bytesPerSecond = 0,
                            )
                        }
                        DownloadCenter.byId(id)?.let { notify(buildNotification(it)) }
                    }
                }
            }
            currentId = null
            startNextQueued()
        }
    }

    /** Maps any shard to shard one of its set, or returns the file unchanged. */
    private fun firstShardOf(file: File): File {
        val base = com.lian.plus.core.model.GgufRoleDetector.splitBaseName(file.name)
            ?: return file
        val total = com.lian.plus.core.model.GgufRoleDetector.shardTotal(file.name)
            ?: return file
        val ext = com.lian.plus.core.model.GgufRoleDetector.shardExtension(file.name) ?: "gguf"
        return File(file.parentFile, "%s-%05d-of-%05d.%s".format(base, 1, total, ext))
    }

    /** Stops the transfer but keeps the partial file, so resuming is cheap. */
    private fun pause(id: String?) {
        if (id == null) return
        if (currentId == id) {
            currentJob?.cancel()
            currentJob = null
            currentId = null
        }
        DownloadCenter.update(id) { it.copy(status = DownloadStatus.PAUSED, bytesPerSecond = 0) }
        DownloadCenter.byId(id)?.let { notify(buildNotification(it)) }
        startNextQueued()
    }

    private fun cancel(id: String?) {
        if (id == null) return
        if (currentId == id) {
            currentJob?.cancel()
            currentJob = null
            currentId = null
        }
        DownloadCenter.byId(id)?.let { runtime.downloader.discardPartial(File(it.targetPath)) }
        DownloadCenter.remove(id)
        startNextQueued()
    }

    private fun notify(notification: Notification) {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(Notifications.ID_DOWNLOAD, notification)
        }
    }

    private fun notifyFinished(fileName: String) {
        val done = NotificationCompat.Builder(this, Notifications.CHANNEL_DOWNLOAD)
            .setContentTitle("Download complete")
            .setContentText(fileName)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(Notifications.ID_DOWNLOAD + 100, done)
        }
    }

    private fun buildNotification(job: DownloadJob): Notification {
        val paused = job.status != DownloadStatus.RUNNING
        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_DOWNLOAD)
            .setContentTitle(job.fileName)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(!paused)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSubText(
                buildString {
                    append(formatBytes(job.doneBytes))
                    append(" / ")
                    append(formatBytes(job.totalBytes))
                    if (!paused && job.bytesPerSecond > 0) {
                        append(" · ").append(formatBytes(job.bytesPerSecond)).append("/s")
                    }
                },
            )
            .setContentText(
                when {
                    job.status == DownloadStatus.FAILED ->
                        job.message?.let { "Failed: $it" } ?: "Failed"
                    job.status == DownloadStatus.PAUSED -> "Paused at ${job.percent}%"
                    job.secondsRemaining != null ->
                        "${job.percent}% · ${remaining(job.secondsRemaining!!)} left"
                    else -> "${job.percent}%"
                },
            )
            .setProgress(100, job.percent, job.totalBytes <= 0)

        when (job.status) {
            DownloadStatus.PAUSED, DownloadStatus.FAILED ->
                builder.addAction(0, "Resume", action(ACTION_START, job.id))
            else ->
                builder.addAction(0, "Pause", action(ACTION_PAUSE, job.id))
        }
        builder.addAction(0, "Cancel", action(ACTION_CANCEL, job.id))
        return builder.build()
    }

    private fun remaining(seconds: Long): String = when {
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        seconds >= 60 -> "${seconds / 60}m"
        else -> "${seconds}s"
    }

    private fun action(actionName: String, id: String): PendingIntent = PendingIntent.getService(
        this,
        (actionName + id).hashCode(),
        Intent(this, DownloadService::class.java).setAction(actionName).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "LianDownloadSvc"
        const val ACTION_START = "com.lian.plus.DOWNLOAD_START"
        const val ACTION_PAUSE = "com.lian.plus.DOWNLOAD_PAUSE"
        const val ACTION_CANCEL = "com.lian.plus.DOWNLOAD_CANCEL"
        const val EXTRA_ID = "download_id"

        /**
         * Queues every file of [asset].
         *
         * A split model is several files, and the previous version queued only
         * the first — which downloaded happily and then failed to load, because
         * the other eight shards were never fetched.
         */
        fun enqueue(context: Context, asset: HfAsset, kind: ModelKind, targetDir: File) {
            asset.files.forEach { file ->
                DownloadCenter.enqueue(
                    DownloadJob(
                        id = DownloadCenter.idFor(file.repoId, file.fileName),
                        url = file.downloadUrl,
                        fileName = file.fileName,
                        repoId = file.repoId,
                        repoPath = file.path,
                        kind = kind,
                        targetPath = File(targetDir, localNameFor(file.path)).absolutePath,
                        totalBytes = file.sizeBytes,
                    ),
                )
            }
            asset.files.firstOrNull()?.let {
                start(context, DownloadCenter.idFor(it.repoId, it.fileName))
            }
        }

        /**
         * The on-disk name for a repository path.
         *
         * Everything lands in one flat directory, and a pipeline repository
         * holds `vae/model.safetensors` beside `text_encoders/model.safetensors`
         * often enough that keeping only the last segment would have one
         * overwrite the other. Files at the repository root are unaffected.
         */
        fun localNameFor(repoPath: String): String = repoPath.replace('/', '_')

        fun start(context: Context, id: String) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ID, id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pause(context: Context, id: String) {
            context.startService(
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_PAUSE).putExtra(EXTRA_ID, id),
            )
        }

        fun cancel(context: Context, id: String) {
            context.startService(
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id),
            )
        }
    }
}
