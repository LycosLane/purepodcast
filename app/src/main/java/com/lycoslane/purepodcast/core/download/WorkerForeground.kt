package com.lycoslane.purepodcast.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

/** Builds [ForegroundInfo] for long-running data-sync workers so they exceed the 10 minute limit. */
internal object WorkerForeground {

    fun info(
        context: Context,
        notificationId: Int,
        channelId: String,
        channelName: String,
        title: String,
        text: String,
        progressMax: Int = 0,
        progressCurrent: Int = 0
    ): ForegroundInfo {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.createNotificationChannel(
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        val indeterminate = progressMax <= 0
        val notification: Notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setProgress(progressMax, progressCurrent, indeterminate)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}
