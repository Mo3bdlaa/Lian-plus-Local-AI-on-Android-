package com.lian.plus.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

/**
 * The app's notification channels.
 *
 * Each long-running job gets its own, so a user who does not want download
 * progress in the shade can silence it without losing the one that tells them
 * the API server is listening.
 */
object Notifications {

    const val CHANNEL_SERVER = "lian_server"
    const val CHANNEL_DOWNLOAD = "lian_download"
    const val CHANNEL_IMAGE = "lian_image"

    const val ID_SERVER = 42
    const val ID_DOWNLOAD = 43
    const val ID_IMAGE = 44

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVER, "Local API server", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while the on-device API is listening"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOAD, "Model downloads", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress, pause and resume for model downloads"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_IMAGE, "Image generation", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps image generation alive while it runs"
                setShowBadge(false)
            },
        )
    }
}
