// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (a failed poll is not "nothing live"; guarded loop; watchdog re-armed each turn)

package com.pandiyarajk.sportshub.alerts

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.pandiyarajk.sportshub.appContainer
import com.pandiyarajk.sportshub.data.AppSettings
import com.pandiyarajk.sportshub.data.alerts.AlertEngine
import com.pandiyarajk.sportshub.data.alerts.CricketLive
import com.pandiyarajk.sportshub.data.alerts.FootballLive
import com.pandiyarajk.sportshub.data.alerts.parseCricketLive
import com.pandiyarajk.sportshub.data.alerts.parseFootballLive
import com.pandiyarajk.sportshub.data.cricket.CricketRepository
import com.pandiyarajk.sportshub.data.cricket.MatchState
import com.pandiyarajk.sportshub.data.cricket.parseCricketHeader
import com.pandiyarajk.sportshub.data.get
import com.pandiyarajk.sportshub.data.parseMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Follows live matches while one the user cares about is in play, then stops itself.
 *
 * Every minute: India cricket through the small per-league scoreboard, EPL through the day's
 * scoreboard. Each snapshot is compared with the last one by [AlertEngine], and only changes
 * become notifications. The all-matches cricket feed (80 KB) is read every five minutes, only to
 * learn which India matches exist and in which league.
 */
class LiveAlertService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val cricketSeen = mutableMapOf<String, CricketLive>()
    private val footballSeen = mutableMapOf<String, FootballLive>()

    /** India match id to its league id, from the all-matches feed. */
    private var indiaTargets: Map<String, String> = emptyMap()
    private var indiaStarts: List<Long> = emptyList()
    private var indiaCheckedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // First, before anything that can wait: a service started with startForegroundService
        // that does not promote itself within seconds is killed with an exception.
        promote("Checking live matches")
        if (intent?.action == ACTION_STOP) {
            // Remember what was live, so the 30-minute check does not start following it again.
            val live = cricketSeen.values.filter { it.state == MatchState.LIVE }.map { it.matchId } +
                footballSeen.values.filter { it.state == "in" }.map { it.matchId }
            appContainer.scope.launch { appContainer.settings.mute(live) }
            loop?.cancel()
            finish(rescheduleOnly = true)
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        // Not sticky: a restart after the process dies is the alarm's and the worker's job,
        // and a sticky restart from the background is not allowed to promote itself.
        return START_NOT_STICKY
    }

    private fun promote(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this, AlertNotifier.SERVICE_NOTIFICATION_ID, AlertNotifier.followingNotification(this, text), type,
        )
    }

    private suspend fun run() {
        val container = appContainer
        var failedTurns = 0
        while (scope.isActive) {
            // Renewed each turn with a timeout, so a crash between turns cannot hold the CPU awake.
            // Without it the CPU sleeps with the screen off and a one-minute poll stretches to many.
            holdWake(WAKE_MILLIS)
            // Pushed back every turn; fires only if this process dies (see AlertScheduler.armWatchdog).
            AlertScheduler.armWatchdog(this)
            val settings = container.settings.read()
            if (!settings.anyAlerts || !AlertNotifier.canPost(this)) break

            val now = System.currentTimeMillis()
            val following = mutableListOf<String>()
            // true: something live or about to start. false: polled fine, nothing to follow.
            // null: the poll failed, which says nothing about whether a match is live.
            val results = try {
                buildList<Boolean?> {
                    if (settings.cricketAlerts) add(pollCricket(settings, now, following))
                    if (settings.footballAlerts) add(pollFootball(settings, now, following))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A bug in one turn must not end the service (an uncaught exception here would
                // take the whole process down); count it as a failed turn.
                listOf(null)
            }

            val live = results.any { it == true }
            val failed = results.any { it == null }
            when {
                live -> failedTurns = 0
                // Measured on the phone: the network dropped, both polls failed in one turn, the
                // service read that as "nothing live" and stopped with four matches in play.
                failed -> if (++failedTurns > MAX_FAILED_TURNS) break
                else -> break
            }

            updateFollowing(
                when {
                    failed && !live -> "Connection lost, retrying"
                    following.isEmpty() -> "Waiting for the start"
                    else -> following.joinToString(", ")
                },
            )
            delay(POLL_MILLIS)
        }
        finish(rescheduleOnly = true)
    }

    /** True while an India match is live or about to start; null when nothing could be read. */
    private suspend fun pollCricket(settings: AppSettings, now: Long, following: MutableList<String>): Boolean? {
        var readSomething = false
        val fetch = appContainer.fetch
        if (now - indiaCheckedAt > HEADER_EVERY_MILLIS || indiaTargets.isEmpty()) {
            safely {
                val india = parseCricketHeader(fetch(get(CricketRepository.HEADER_URL))).filter { it.isIndia }
                indiaTargets = india.filter { it.state != MatchState.RESULT }.associate { it.id to it.leagueId }
                indiaStarts = india.filter { it.state == MatchState.UPCOMING }.mapNotNull { parseMillis(it.startIso) }
                indiaCheckedAt = now
                // No India match at all is a real answer, not a failure.
                if (indiaTargets.isEmpty()) readSomething = true
            }
        }
        var live = false
        indiaTargets.values.toSet().forEach { leagueId ->
            safely {
                val board = parseCricketLive(fetch(get(leagueBoardUrl(leagueId))))
                readSomething = true
                board.filter { it.matchId in indiaTargets && it.matchId !in settings.mutedMatches }.forEach { m ->
                    AlertEngine.cricket(cricketSeen[m.matchId], m, settings.everyOver)
                        .forEach { AlertNotifier.post(this, it) }
                    cricketSeen[m.matchId] = m
                    if (m.state == MatchState.LIVE) {
                        live = true
                        following += m.batting?.let { b -> "${b.team} ${b.runs}/${b.wickets} (${b.oversText})" } ?: m.title
                    }
                    if (m.state == MatchState.RESULT) indiaTargets = indiaTargets - m.matchId
                }
            }
        }
        return when {
            live -> true
            !readSomething -> null
            else -> indiaStarts.any { it in (now - LATE_START_MILLIS)..(now + EARLY_MILLIS) }
        }
    }

    /** True while a followed EPL match is live or about to kick off; null when the day could not be read. */
    private suspend fun pollFootball(settings: AppSettings, now: Long, following: MutableList<String>): Boolean? {
        var keep = false
        var read = false
        safely {
            val day = parseFootballLive(appContainer.fetch(get(AlertScheduler.footballDayUrl())))
                .filter { AlertScheduler.followsClub(settings, it.home.teamId, it.away.teamId) }
                .filter { it.matchId !in settings.mutedMatches }
            read = true
            day.forEach { m ->
                val prev = footballSeen[m.matchId]
                // A match already finished before this service first saw it is history, not news.
                if (prev != null || m.state != "post") {
                    AlertEngine.football(prev, m).forEach { AlertNotifier.post(this, it) }
                }
                footballSeen[m.matchId] = m
                if (m.state == "in") {
                    keep = true
                    following += m.scoreLine
                }
                if (m.state == "pre" && (m.startMillis ?: Long.MAX_VALUE) in (now - LATE_START_MILLIS)..(now + EARLY_MILLIS)) {
                    keep = true
                }
            }
        }
        return if (read) keep else null
    }

    private fun updateFollowing(text: String) {
        if (!AlertNotifier.canPost(this)) return
        try {
            NotificationManagerCompat.from(this)
                .notify(AlertNotifier.SERVICE_NOTIFICATION_ID, AlertNotifier.followingNotification(this, text))
        } catch (e: SecurityException) {
            // Notifications withdrawn mid-match; the next turn sees canPost false and stops.
        }
    }

    private fun holdWake(millis: Long) {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sportshub:live")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(millis)
    }

    private fun finish(rescheduleOnly: Boolean) {
        // A deliberate stop: the watchdog must not bring the service back.
        AlertScheduler.disarmWatchdog(this)
        wakeLock?.takeIf { it.isHeld }?.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (rescheduleOnly) {
            // The next start's alarm. Run on the app scope: this service's scope ends with it.
            appContainer.scope.launch { AlertScheduler.sync(applicationContext, startIfLive = false) }
        }
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One failed poll is skipped; the next turn tries again. Never ends the service.
        }
    }

    companion object {
        const val ACTION_STOP = "com.pandiyarajk.sportshub.STOP_FOLLOWING"
        const val POLL_MILLIS = 60_000L

        /** Half an hour of failed polls (no network) before giving up to the 30-minute check. */
        private const val MAX_FAILED_TURNS = 30
        private const val WAKE_MILLIS = 3 * 60_000L
        private const val HEADER_EVERY_MILLIS = 5 * 60_000L

        /** Keep waiting this long before a scheduled start, and this long after it for a late one. */
        private const val EARLY_MILLIS = 15 * 60_000L
        private const val LATE_START_MILLIS = 45 * 60_000L

        fun leagueBoardUrl(leagueId: String) =
            "https://site.api.espn.com/apis/site/v2/sports/cricket/$leagueId/scoreboard"
    }
}
