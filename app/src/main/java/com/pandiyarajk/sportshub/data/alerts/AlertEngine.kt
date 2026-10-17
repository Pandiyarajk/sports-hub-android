// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (rain delays and other stops in play, and their end; no early returns,
//   so a wicket beside a restart or a new innings is never lost; exact runs per over or the rate)

package com.pandiyarajk.sportshub.data.alerts

import com.pandiyarajk.sportshub.data.cricket.MatchState

/**
 * Turns two successive snapshots of a match into the alerts the change deserves. Pure: no
 * clock, no Android, no network, so every rule is unit tested.
 *
 * A first sighting (no previous snapshot) of a live match says it is live and nothing else, so a
 * service restarting mid-match does not replay the whole innings as alerts.
 */
object AlertEngine {

    fun cricket(prev: CricketLive?, now: CricketLive, everyOver: Boolean): List<Alert> {
        fun alert(kind: AlertKind, title: String, text: String = now.summary) =
            Alert(AlertSport.CRICKET, now.matchId, kind, title, text)

        val b = now.batting
        // The feed's own string, so a reduced match reads "IND 14/1 (1/9 ov)" with its new limit.
        val score = b?.display.orEmpty()

        if (now.state == MatchState.RESULT) {
            return if (prev != null && prev.state != MatchState.RESULT) {
                listOf(alert(AlertKind.RESULT, "Result: ${now.title}"))
            } else {
                emptyList()
            }
        }
        if (now.state != MatchState.LIVE) return emptyList()
        if (prev == null || prev.state != MatchState.LIVE) {
            // The toss is news only before a ball is bowled. Seen live: following a match picked up
            // at 70/3 still read "West Indies won toss & fielded".
            val ballBowled = b != null && b.oversText.isNotBlank() && b.oversText != "0"
            val line = if (ballBowled) afterToss(now.summary) else now.summary
            return listOf(alert(AlertKind.STARTED, "Live: ${now.title}", listOf(score, line).filter { it.isNotBlank() }.joinToString(" · ")))
        }

        // No early returns below. Measured live on Oct-17-2026: play restarted after rain and a
        // wicket fell between the same two polls, and returning after the restart alert lost the
        // wicket. Every rule looks at every turn.
        val out = mutableListOf<Alert>()
        // The feed's status line says who won the toss until a chase begins; after the start that
        // is noise on a wicket or an over (seen live on the over 1 alert), so it is left out.
        val news = afterToss(now.summary)

        // A stop or restart in play is news on its own, with or without a ball bowled yet.
        if (now.interruption != prev.interruption) {
            out += if (now.interruption.isNotBlank()) {
                alert(AlertKind.INTERRUPTION, "${now.title}: ${now.interruption}", score.ifBlank { "No ball bowled yet" })
            } else {
                alert(AlertKind.INTERRUPTION, "${now.title}: play under way", listOf(score, news).filter { it.isNotBlank() }.joinToString(" · "))
            }
        }

        if (b == null) return out
        val p = prev.batting
        if (p == null || p.innings != b.innings || p.team != b.team) {
            out += alert(AlertKind.INNINGS, "New innings: ${b.team} batting", listOf(score, news).filter { it.isNotBlank() }.joinToString(" · "))
            // Wickets already down when the innings is first seen fell in this innings, between polls.
            if (b.wickets > 0) out += wicket(b.wickets, score, news, now.matchId)
            return out
        }

        val fell = b.wickets - p.wickets
        if (fell > 0) out += wicket(fell, score, news, now.matchId)

        val overs = b.oversDone - p.oversDone
        if (overs > 0 && everyOver) {
            val which = if (overs == 1) "End of over ${b.oversDone}" else "Overs ${p.oversDone + 1}-${b.oversDone}"
            out += alert(AlertKind.OVER, "$which: $score", listOf(overLine(p, b), news).filter { it.isNotBlank() }.joinToString(" · "))
        }
        return out
    }

    /** The status line unless it is still the toss ("West Indies won toss & fielded"). */
    internal fun afterToss(summary: String): String =
        if (summary.contains("toss", ignoreCase = true)) "" else summary

    /**
     * The wicket alert as the scoreboard knows it: how many fell and the score. Who was out and
     * how (caught by, stumped by, run out by, bowled by) is added by the service from the
     * ball-by-ball, which the scoreboard does not carry; see [WicketBall.alertText].
     */
    private fun wicket(fell: Int, score: String, news: String, matchId: String): Alert {
        val head = if (fell == 1) "WICKET!" else "$fell WICKETS!"
        return Alert(AlertSport.CRICKET, matchId, AlertKind.WICKET, "$head $score", news)
    }

    /**
     * Runs in the over just ended, only when its start is known: the innings' first over (it
     * started at 0), or a previous poll that landed exactly on the over boundary. Otherwise the
     * difference since the last poll includes balls of the previous over (measured live: an over
     * of 14 reported as "1 run"), so the run rate is shown instead, which is always right.
     */
    internal fun overLine(p: Batting, b: Batting): String {
        val atBoundary = '.' !in b.oversText
        val runsInOver = when {
            b.oversDone - p.oversDone != 1 || !atBoundary -> null
            b.oversDone == 1 -> b.runs
            '.' !in p.oversText -> b.runs - p.runs
            else -> null
        }
        if (runsInOver != null) return "$runsInOver run${if (runsInOver == 1) "" else "s"} in the over"
        val balls = com.pandiyarajk.sportshub.data.cricket.oversToBalls(b.oversText) ?: return ""
        if (balls == 0) return ""
        return "RR " + com.pandiyarajk.sportshub.data.cricket.twoPlaces(b.runs * 6.0 / balls)
    }

    fun football(prev: FootballLive?, now: FootballLive): List<Alert> {
        fun alert(kind: AlertKind, title: String, text: String) =
            Alert(AlertSport.FOOTBALL, now.matchId, kind, title, text)

        if (prev == null) {
            // First sighting: only a match already under way is worth a line, as a baseline.
            return if (now.state == "in") listOf(alert(AlertKind.KICK_OFF, now.scoreLine, "Live · ${now.detail}")) else emptyList()
        }
        val out = mutableListOf<Alert>()
        if (prev.state == "pre" && now.state == "in") {
            out += alert(AlertKind.KICK_OFF, "Kick-off: ${now.home.abbr} v ${now.away.abbr}", now.scoreLine)
        }
        val before = prev.home.score + prev.away.score
        val after = now.home.score + now.away.score
        if (after > before) {
            // Name the scorers of the goals the previous snapshot had not seen.
            val newGoals = now.goals.drop(prev.goals.size)
            val who = newGoals.joinToString(", ") { g ->
                val tag = when {
                    g.ownGoal -> " (og)"
                    g.penalty -> " (pen)"
                    else -> ""
                }
                "${g.scorer}$tag ${g.minute}".trim()
            }
            out += alert(AlertKind.GOAL, "GOAL! ${now.scoreLine}", who.ifBlank { now.detail })
        } else if (after < before) {
            out += alert(AlertKind.DISALLOWED, "Goal disallowed: ${now.scoreLine}", now.detail)
        }
        if (now.detail == "HT" && prev.detail != "HT") {
            out += alert(AlertKind.HALF_TIME, "Half-time: ${now.scoreLine}", "")
        }
        if (now.state == "post" && prev.state != "post") {
            out += alert(AlertKind.FULL_TIME, "Full-time: ${now.scoreLine}", now.detail)
        }
        return out
    }
}
