// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (watchdog alarm that restarts a dead service within five minutes)

package com.pandiyarajk.sportshub.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pandiyarajk.sportshub.appContainer
import com.pandiyarajk.sportshub.data.AppSettings
import com.pandiyarajk.sportshub.data.alerts.parseFootballLive
import com.pandiyarajk.sportshub.data.cricket.CricketRepository
import com.pandiyarajk.sportshub.data.football.FootballRepository
import com.pandiyarajk.sportshub.data.cricket.MatchState
import com.pandiyarajk.sportshub.data.cricket.parseCricketHeader
import com.pandiyarajk.sportshub.data.get
import com.pandiyarajk.sportshub.data.parseMillis
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Decides when to follow. Android lets an app start a foreground service from the background
 * only in a few cases; an exact alarm firing is one of them. So: start now when a followed match
 * is live (from the app, which is in the foreground), else set an exact alarm for the next start.
 * A periodic worker re-runs this every 30 minutes as a safety net for delays and new fixtures.
 */
object AlertScheduler {
    private const val WORK_NAME = "live-alerts-check"
    private const val REQUEST_NOW = 10
    private const val REQUEST_NEXT = 11
    private const val REQUEST_WATCHDOG = 12

    /** How long a dead service can stay dead while a match is live. */
    const val WATCHDOG_MILLIS = 5 * 60_000L

    /** The same day board the live cards use, so alerts and cards never disagree on the date. */
    fun footballDayUrl(today: LocalDate = LocalDate.now(ZoneId.of("America/New_York"))): String =
        FootballRepository.dayUrl(today)

    /**
     * [startIfLive] is false when the service itself is ending: it only schedules the next alarm,
     * so two feeds briefly disagreeing about a match cannot restart the service in a loop.
     */
    suspend fun sync(context: Context, startIfLive: Boolean = true) {
        val container = context.appContainer
        val settings = container.settings.read()
        if (!settings.anyAlerts || !AlertNotifier.canPost(context)) {
            cancel(context)
            return
        }
        ensurePeriodicCheck(context)

        val now = System.currentTimeMillis()
        var liveNow = false
        val starts = mutableListOf<Long>()

        if (settings.anyCricket) {
            runCatchingNonCancel {
                parseCricketHeader(container.fetch(get(CricketRepository.HEADER_URL)))
                    .filter { followsCricket(settings, it.id, it.isIndia) }
                    .forEach { m ->
                        when (m.state) {
                            MatchState.LIVE -> liveNow = true
                            MatchState.UPCOMING -> parseMillis(m.startIso)?.let(starts::add)
                            MatchState.RESULT -> Unit
                        }
                    }
            }
        }
        if (settings.footballAlerts) {
            runCatchingNonCancel {
                parseFootballLive(container.fetch(get(footballDayUrl())))
                    .filter { followsClub(settings, it.home.teamId, it.away.teamId) && it.matchId !in settings.mutedMatches }
                    .forEach { m ->
                        when (m.state) {
                            "in" -> liveNow = true
                            "pre" -> m.startMillis?.let(starts::add)
                        }
                    }
            }
        }

        if (liveNow && startIfLive) startFollowing(context)
        val next = starts.filter { it > now }.minOrNull()
        if (next != null) setAlarm(context, next, REQUEST_NEXT) else cancelAlarm(context, REQUEST_NEXT)
    }

    /** India matches when India alerts are on, plus any match followed with its bell; never a muted one. */
    fun followsCricket(settings: AppSettings, matchId: String, isIndia: Boolean): Boolean =
        matchId !in settings.mutedMatches && ((settings.cricketAlerts && isIndia) || matchId in settings.followedMatches)

    fun followsClub(settings: AppSettings, home: String, away: String): Boolean =
        !settings.footballFavouriteOnly || settings.favouriteClub.isBlank() ||
            settings.favouriteClub == home || settings.favouriteClub == away

    /**
     * Start the service, or arrange for it to start. From the background a direct start throws
     * ForegroundServiceStartNotAllowedException; an exact alarm a few seconds out is allowed to
     * start it, and without exact alarms only a tap on a notification is.
     */
    fun startFollowing(context: Context) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, LiveAlertService::class.java))
            return
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException extends IllegalStateException.
        } catch (e: SecurityException) {
            // Same outcome on some OEM builds.
        }
        if (canExact(context)) {
            setAlarm(context, System.currentTimeMillis() + 3_000, REQUEST_NOW)
        } else {
            AlertNotifier.postTapToFollow(context)
        }
    }

    /**
     * Re-armed by the service on every turn while it follows a match. If the process dies (seen on
     * the phone: the service went at 19:36 with four matches live and nothing restarted it for 17
     * minutes), this exact alarm fires and starts it again within five minutes. While the service
     * is alive the alarm is pushed back each minute and never fires; if it fires anyway, the
     * running service ignores the duplicate start.
     */
    fun armWatchdog(context: Context) = setAlarm(context, System.currentTimeMillis() + WATCHDOG_MILLIS, REQUEST_WATCHDOG)

    fun disarmWatchdog(context: Context) = cancelAlarm(context, REQUEST_WATCHDOG)

    private fun canExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    private fun pending(context: Context, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode, Intent(context, AlertReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun setAlarm(context: Context, atMillis: Long, requestCode: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, requestCode)
        if (canExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            // Inexact: it fires late and cannot start the service itself; the receiver then asks
            // for a tap instead.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    private fun cancelAlarm(context: Context, requestCode: Int) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, requestCode))
    }

    private fun ensurePeriodicCheck(context: Context) {
        val request = PeriodicWorkRequestBuilder<AlertWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Alerts off: no alarms, no periodic check, no service. */
    fun cancel(context: Context) {
        cancelAlarm(context, REQUEST_NOW)
        cancelAlarm(context, REQUEST_NEXT)
        cancelAlarm(context, REQUEST_WATCHDOG)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        context.stopService(Intent(context, LiveAlertService::class.java))
    }

    private inline fun runCatchingNonCancel(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed check leaves things as they are; the next check or alarm tries again.
        }
    }
}
