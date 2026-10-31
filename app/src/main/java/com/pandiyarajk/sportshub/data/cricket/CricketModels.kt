// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-31-2026 (isInternational)

package com.pandiyarajk.sportshub.data.cricket

enum class MatchState { LIVE, UPCOMING, RESULT }

data class CricketTeam(
    val name: String,
    val abbr: String,
    val logo: String?,
    val score: String,
    val winner: Boolean = false,
) {
    /** The part before the bracket, such as 166/4. The score string itself is never rebuilt. */
    val headline: String get() = score.substringBefore(" (")

    /** The bracketed part, such as (35.5/50 ov, target 257), or blank when there is none. */
    val detail: String get() = if (" (" in score) "(" + score.substringAfter(" (") else ""
}

data class CricketMatch(
    val id: String,
    val leagueId: String,
    val league: String,
    val title: String,
    val venue: String,
    val startIso: String,
    val state: MatchState,
    val status: String,
    val teams: List<CricketTeam>,
    val isIndia: Boolean,
    /** The match label, such as 5th T20I, without the (D/N) suffix. */
    val label: String = "",
    /** The city, such as Bengaluru, or the ground name when the feed gives no city. */
    val city: String = "",
) {
    /** 5th T20I . Bengaluru, as the card header shows it. */
    val labelAndCity: String get() = listOf(label, city).filter { it.isNotBlank() }.joinToString(" · ")

    /** National teams against each other, not a franchise or domestic side. */
    val isInternational: Boolean get() = IndiaFilter.isInternational(league, teams)

    /** A finished match with a declared winner, so the other side can be shown dimmed. */
    val hasWinner: Boolean get() = state == MatchState.RESULT && teams.any { it.winner }
}

data class BattingRow(
    val name: String,
    val dismissal: String,
    val runs: String,
    val balls: String,
    val fours: String,
    val sixes: String,
)

data class BowlingRow(
    val name: String,
    val overs: String,
    val maidens: String,
    val runs: String,
    val wickets: String,
    val economy: String,
)

data class Innings(
    val number: Int,
    val team: String,
    val runs: String,
    val total: String,
    val extras: String,
    val batting: List<BattingRow>,
    val bowling: List<BowlingRow>,
)

/**
 * Two decimal places cut, not rounded, as Cricinfo shows rates: 46 off 61 is 75.409..., shown as
 * 75.40. The tiny nudge stops 0.29 * 100 = 28.999... from cutting to 0.28. Always a point, never
 * the locale's comma.
 */
fun twoPlaces(x: Double): String =
    String.format(java.util.Locale.US, "%.2f", kotlin.math.floor(x * 100 + 1e-9) / 100)

/** A batter at the crease, as Cricinfo's live panel shows them. */
data class LiveBatter(val name: String, val runs: Int, val balls: Int, val fours: Int, val sixes: Int, val onStrike: Boolean) {
    val strikeRate: String get() = if (balls == 0) "-" else twoPlaces(runs * 100.0 / balls)
}

/** A bowler in the current spell. */
data class LiveBowler(val name: String, val overs: String, val maidens: Int, val conceded: Int, val wickets: Int, val balls: Int) {
    val economy: String get() = if (balls == 0) "-" else twoPlaces(conceded * 6.0 / balls)
}

/**
 * The "Live" panel above the scorecard: who is in, who is bowling, the rates and the balls of
 * the current over. From the summary's ball-by-ball commentary and the batting line score.
 */
data class LivePanel(
    val batters: List<LiveBatter>,
    val bowlers: List<LiveBowler>,
    /** "4.92" */
    val runRate: String,
    /** Required rate in a chase, blank otherwise. */
    val requiredRate: String,
    /** "34/0 (6.80)" for the last 5 overs, with the over count in [recentOvers]. */
    val recent: String,
    val recentOvers: Int,
    /** The current over's balls, oldest first: "0", "1", "4", "W", "1wd". */
    val thisOver: List<String>,
)

data class Scorecard(
    val innings: List<Innings>,
    val live: LivePanel? = null,
    /** Batter name to full dismissal ("c Joseph b Paul"), kept across polls by the repository. */
    val dismissals: Map<String, String> = emptyMap(),
    /** Batters out with only a code ("c") known; filled from the play-by-play. */
    val unresolved: Set<String> = emptySet(),
)
