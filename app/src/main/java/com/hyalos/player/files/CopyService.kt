package com.hyalos.player.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hyalos.player.HyalosApp
import com.hyalos.player.R
import com.hyalos.player.ui.common.sizeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Holds the process up while a copy runs, and gives the progress bar somewhere
 * to live.
 *
 * It does no copying. The work belongs to [CopyCoordinator], which is
 * process-wide; this is the part Android requires to be a service — a
 * notification the user can see, and a reason for the system not to reclaim the
 * process in the middle of moving a film.
 *
 * Started and stopped by the coordinator rather than by any screen: a screen can
 * come and go while this is running, and if it owned the service the copy would
 * end with it.
 */
class CopyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // From the notification, so the app may not be in the foreground at
            // all — which is exactly when a cancel is most wanted.
            coordinator.cancel()
            return START_NOT_STICKY
        }

        // Posted before anything can go wrong: a foreground service has five
        // seconds to show a notification or be killed, and "be killed" here
        // means the copy it was protecting dies with it.
        startForeground(NOTIFICATION_ID, notification(coordinator.progress.value))

        if (watching == null) {
            watching = scope.launch {
                coordinator.progress.collectLatest { progress ->
                    if (progress == null) {
                        // The copy finished or was cancelled. Stopping takes the
                        // notification with it, which is the whole of the
                        // cleanup — nothing else here holds state.
                        stopSelf()
                    } else {
                        notificationManager().notify(NOTIFICATION_ID, notification(progress))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private val coordinator: CopyCoordinator
        get() = (application as HyalosApp).container.copy

    private fun notificationManager() =
        ContextCompat.getSystemService(this, NotificationManager::class.java)!!

    /**
     * The progress bar, and the one thing worth offering while it fills.
     *
     * Indeterminate until the total is known: the copy spends its first moments
     * counting what it is about to move, and a bar at zero would look like a
     * stall rather than a preparation.
     */
    private fun notification(progress: CopyProgress?): Notification {
        ensureChannel()
        val fraction = progress?.fraction
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_storage)
            .setContentTitle(progress?.label ?: getString(R.string.copy_running))
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .addAction(
                0,
                getString(R.string.copy_cancel),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, CopyService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        progress?.let {
            if (it.total > 0uL) {
                builder.setContentText(
                    getString(R.string.copy_progress, sizeText(this, it.copied.toLong()), sizeText(this, it.total.toLong())),
                )
            }
        }
        return builder.build()
    }

    private fun ensureChannel() {
        val manager = notificationManager()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.copy_channel),
                // Low: this is a progress bar, not news. It should sit quietly
                // and not make a sound for every file of a folder.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "copy"
        private const val NOTIFICATION_ID = 1
        const val ACTION_CANCEL = "com.hyalos.player.action.CANCEL_COPY"

        fun begin(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CopyService::class.java))
        }

        fun end(context: Context) {
            context.stopService(Intent(context, CopyService::class.java))
        }
    }
}
