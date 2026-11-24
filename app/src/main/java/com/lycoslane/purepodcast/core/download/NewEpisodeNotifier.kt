package com.lycoslane.purepodcast.core.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lycoslane.purepodcast.MainActivity
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.PodcastEntity

object NewEpisodeNotifier {

    private const val CHANNEL_ID = "purepodcast_new_episodes"
    private const val NOTIFICATION_ID = 6001
    private const val MAX_LINES = 5

    fun notify(
        context: Context,
        downloaded: List<Pair<PodcastEntity, EpisodeEntity>>,
        available: List<Pair<PodcastEntity, EpisodeEntity>>
    ) {
        if (downloaded.isEmpty() && available.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_new_episodes_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        val title = when {
            downloaded.isNotEmpty() && available.isNotEmpty() -> {
                context.getString(R.string.notif_new_episodes_mixed_title, downloaded.size, available.size)
            }
            downloaded.size == 1 -> context.getString(R.string.notif_new_episode_downloaded_one)
            downloaded.isNotEmpty() -> context.getString(R.string.notif_new_episodes_downloaded_many, downloaded.size)
            available.size == 1 -> context.getString(R.string.notif_new_episode_available_one)
            else -> context.getString(R.string.notif_new_episodes_available_many, available.size)
        }

        val combined = downloaded + available
        val firstLine = "${combined[0].first.title}: ${combined[0].second.title}"
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        combined.take(MAX_LINES).forEach { (pod, ep) ->
            style.addLine("${pod.title}: ${ep.title}")
        }
        if (combined.size > MAX_LINES) {
            style.setSummaryText(context.getString(R.string.notif_new_episodes_more, combined.size - MAX_LINES))
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val smallIcon = if (downloaded.isNotEmpty() && available.isEmpty()) {
            android.R.drawable.stat_sys_download_done
        } else {
            R.drawable.ic_notif_podcast
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(firstLine)
            .setStyle(style)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}
