// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (rain delays and other stops in play, and their end)

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
        val score = b?.let { "${it.team} ${it.runs}/${it.wickets} (${it.oversText} ov)" }.orEmpty()

        if (now.state == MatchState.RESULT) {
            return if (prev != null && prev.state != MatchState.RESULT) {
                listOf(alert(AlertKind.RESULT, "Result: ${now.title}"))
            } else {
                emptyList()
            }
        }
        if (now.state != MatchState.LIVE) return emptyList()
        if (prev == null || prev.state != MatchState.LIVE) {
            return listOf(alert(AlertKind.STARTED, "Live: ${now.title}", listOf(score, now.summary).filter { it.isNotBlank() }.joinToString(" · ")))
        }

        // A stop or restart in play is news on its own, with or without a ball bowled yet.
        if (now.interruption != prev.interruption) {
            return if (now.interruption.isNotBlank()) {
                listOf(alert(AlertKind.INTERRUPTION, "${now.title}: ${now.interruption}", score.ifBlank { "No ball bowled yet" }))
            } else {
                listOf(alert(AlertKind.INTERRUPTION, "${now.title}: play under way", listOf(score, now.summary).filter { it.isNotBlank() }.joinToString(" · ")))
            }
        }

        val p = prev.batting
        if (b == null) return emptyList()
        if (p == null || p.innings != b.innings || p.team != b.team) {
            return listOf(alert(AlertKind.INNINGS, "New innings: ${b.team} batting", listOf(score, now.summary).filter { it.isNotBlank() }.joinToString(" · ")))
        }

        val out = mutableListOf<Alert>()
        val fell = b.wickets - p.wickets
        if (fell > 0) {
            val head = if (fell == 1) "WICKET!" else "$fell WICKETS!"
            out += alert(AlertKind.WICKET, "$head $score", now.summary)
        }
        val overs = b.oversDone - p.oversDone
        if (overs > 0 && everyOver && fell == 0) {
            // Runs since the last snapshot. With a 60 s poll that is one over; after a gap it is
            // several, and the title says so rather than pretending it was one.
            val runs = b.runs - p.runs
            val which = if (overs == 1) "End of over ${b.oversDone}" else "Overs ${p.oversDone + 1}-${b.oversDone}"
            out += alert(AlertKind.OVER, "$which: $score", "${runs} run${if (runs == 1) "" else "s"} · ${now.summary}")
        }
        return out
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
