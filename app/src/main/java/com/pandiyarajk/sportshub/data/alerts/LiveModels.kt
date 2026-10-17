// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub.data.alerts

import com.pandiyarajk.sportshub.data.cricket.MatchState

/** The batting side right now: what an over or a wicket is measured against. */
data class Batting(
    val team: String,
    val innings: Int,
    val runs: Int,
    val wickets: Int,
    /** Completed overs: 35.5 is 35, 36.0 is 36. An over ends when this goes up. */
    val oversDone: Int,
    /** The overs exactly as the feed gives them, such as 35.5, for display. */
    val oversText: String,
)

/** One live cricket match as the alert engine sees it, taken from the per-league scoreboard. */
data class CricketLive(
    val matchId: String,
    val title: String,
    val state: MatchState,
    /** The feed's own status line, such as "Nepal require 90 runs" or "India won by 5 wickets". */
    val summary: String,
    val batting: Batting?,
    val startMillis: Long?,
    /** "Match delayed by rain", "Stumps": a stop in play, blank while play is on. */
    val interruption: String = "",
)

data class Goal(val minute: String, val scorer: String, val teamId: String, val ownGoal: Boolean, val penalty: Boolean)

data class FootballSide(val teamId: String, val abbr: String, val score: Int)

/** One EPL match as the alert engine sees it, taken from the day's scoreboard. */
data class FootballLive(
    val matchId: String,
    val home: FootballSide,
    val away: FootballSide,
    /** pre, in or post, as the feed gives it. */
    val state: String,
    /** HT, FT, 23', as the feed gives it. */
    val detail: String,
    val goals: List<Goal>,
    val startMillis: Long?,
) {
    val scoreLine: String get() = "${home.abbr} ${home.score}-${away.score} ${away.abbr}"
}

enum class AlertKind(val important: Boolean) {
    STARTED(false),
    OVER(false),
    WICKET(true),
    INNINGS(false),
    /** Play stopped (rain, bad light, stumps) or resumed. */
    INTERRUPTION(false),
    RESULT(true),
    KICK_OFF(false),
    GOAL(true),
    DISALLOWED(false),
    HALF_TIME(false),
    FULL_TIME(true),
}

enum class AlertSport { CRICKET, FOOTBALL }

/** One notification to post. Each match has one notification that later alerts replace. */
data class Alert(
    val sport: AlertSport,
    val matchId: String,
    val kind: AlertKind,
    val title: String,
    val text: String,
)
