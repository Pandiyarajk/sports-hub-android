// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pandiyarajk.sportshub.MainActivity
import com.pandiyarajk.sportshub.R
import com.pandiyarajk.sportshub.data.alerts.Alert
import com.pandiyarajk.sportshub.data.alerts.AlertSport

/**
 * Notification channels and posting. Three channels so the user can tune them separately in
 * system settings: the quiet "following" line the service must show, routine match updates
 * (every over, kick-off, half-time) and big moments (wickets, goals, results), which alert.
 */
object AlertNotifier {
    const val CHANNEL_FOLLOWING = "following"
    const val CHANNEL_UPDATES = "match_updates"
    const val CHANNEL_BIG = "big_moments"

    const val SERVICE_NOTIFICATION_ID = 1
    private const val PROMPT_NOTIFICATION_ID = 2

    const val EXTRA_SPORT = "open.sport"
    const val EXTRA_MATCH = "open.match"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_FOLLOWING, "Following live matches", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while Sports Hub is following a live match." },
                NotificationChannel(CHANNEL_UPDATES, "Match updates", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "End of each over, kick-off, half-time, new innings." },
                NotificationChannel(CHANNEL_BIG, "Wickets, goals and results", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Wickets, goals, results and full-time." },
            ),
        )
    }

    /** Covers POST_NOTIFICATIONS on Android 13+ and a user who switched the app's notifications off. */
    fun canPost(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openApp(context: Context, sport: AlertSport?, matchId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (sport != null) intent.putExtra(EXTRA_SPORT, sport.name)
        if (matchId != null) intent.putExtra(EXTRA_MATCH, matchId)
        return PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun followingNotification(context: Context, text: String): Notification {
        ensureChannels(context)
        val stop = PendingIntent.getService(
            context, 0,
            Intent(context, LiveAlertService::class.java).setAction(LiveAlertService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_FOLLOWING)
            .setSmallIcon(R.drawable.ic_stat_live)
            .setContentTitle("Following live")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context, null, null, 0))
            .addAction(0, "Stop until next match", stop)
            .build()
    }

    /** One notification per match, replaced by each later alert for it. */
    fun post(context: Context, alert: Alert) {
        if (!canPost(context)) return
        ensureChannels(context)
        val id = notificationId(alert.sport, alert.matchId)
        val n = NotificationCompat.Builder(context, if (alert.kind.important) CHANNEL_BIG else CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_live)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(if (alert.kind.important) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, alert.sport, alert.matchId, id))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // Permission withdrawn between the check and the post: nothing to show, nothing to crash.
        }
    }

    /**
     * When Android will not let the app start following from the background (no exact alarm
     * access), a tap is the one thing that may start it, so ask for the tap.
     */
    fun postTapToFollow(context: Context) {
        if (!canPost(context)) return
        ensureChannels(context)
        val start = PendingIntent.getForegroundService(
            context, 1, Intent(context, LiveAlertService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_live)
            .setContentTitle("A followed match is live")
            .setContentText("Tap to follow it here.")
            .setAutoCancel(true)
            .setContentIntent(start)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(PROMPT_NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            // As above.
        }
    }

    fun notificationId(sport: AlertSport, matchId: String): Int =
        // Kept clear of the two fixed ids above.
        ("${sport.name}:$matchId".hashCode() and 0x3FFFFFFF) + 16
}
