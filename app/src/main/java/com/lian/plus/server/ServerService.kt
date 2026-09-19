package com.lian.plus.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import com.lian.plus.MainActivity
import com.lian.plus.R
import com.lian.plus.core.LianRuntime
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Keeps the local API reachable while the app is in the background.
 *
 * A foreground service is required: without one, Android suspends the process
 * and every in-flight request stalls. The notification is also the honest way
 * to show that a server is listening.
 */
class ServerService : LifecycleService() {

    private var server: HttpServer? = null
    private lateinit var runtime: LianRuntime

    override fun onCreate() {
        super.onCreate()
        runtime = LianRuntime.get(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }

        val settings = runtime.currentSettings
        val routes = OpenAiRoutes(runtime)

        val http = HttpServer(
            port = settings.serverPort,
            bindToAllInterfaces = settings.serverLanExposed,
        ) { request -> routes.handle(request) }

        val started = http.start()
        started.fold(
            onSuccess = { port ->
                server = http
                startForeground(NOTIFICATION_ID, buildNotification(port, settings.serverLanExposed))
                Log.i(TAG, "API server up on port $port")
            },
            onFailure = {
                Log.e(TAG, "server failed to start", it)
                startForeground(NOTIFICATION_ID, buildErrorNotification(it.message))
                stopSelf()
            },
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun createChannel() {
        val manager = getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Local API server",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while the on-device API is listening"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(port: Int, lan: Boolean): Notification {
        val address = if (lan) "${localIpAddress() ?: "0.0.0.0"}:$port" else "127.0.0.1:$port"
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Serving on $address")
            .setContentText(
                if (lan) "Reachable from other devices on this network"
                else "Reachable from this phone only",
            )
            .setSmallIcon(R.drawable.ic_server)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildErrorNotification(message: String?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("The API server could not start")
            .setContentText(message ?: "port may already be in use")
            .setSmallIcon(R.drawable.ic_server)
            .build()

    companion object {
        private const val TAG = "LianServer"
        private const val CHANNEL_ID = "lian_server"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.lian.plus.STOP_SERVER"

        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ServerService::class.java).setAction(ACTION_STOP),
            )
        }

        /** The phone's address on the local network, for the LAN-exposed case. */
        fun localIpAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
                ?.hostAddress
        }.getOrNull()
    }
}
