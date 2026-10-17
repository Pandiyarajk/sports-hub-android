// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (live match cards: short names, goals; match timeline and stats)

package com.pandiyarajk.sportshub.data.football

data class TableRow(
    val rank: Int,
    val teamId: String,
    val name: String,
    val abbr: String,
    val logo: String?,
    val played: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val goalDiff: String,
    val points: Int,
    /** Man City, Brighton: fits a 360 dp table where the full name is cut off. */
    val shortName: String = "",
)

data class Standings(val season: String, val seasonYear: Int, val rows: List<TableRow>)

data class Side(
    val teamId: String,
    val name: String,
    val abbr: String,
    val logo: String?,
    val score: String,
    /** Man City, Brighton: fits a card where the full name would be cut off. */
    val shortName: String = "",
)

data class Fixture(
    val id: String,
    val dateIso: String,
    val finished: Boolean,
    val live: Boolean,
    /** The feed's short status: 34', HT, FT, or the kick-off time. */
    val detail: String,
    val home: Side,
    val away: Side,
    /** Goals in order, each with scorer, minute and team, for the scorers under each side. */
    val goals: List<com.pandiyarajk.sportshub.data.alerts.Goal> = emptyList(),
)

/** One line of the match timeline: a goal, a card, a substitution, half-time. */
data class KeyEvent(
    val minute: String,
    val type: String,
    /** The team it belongs to, blank for match-wide events such as half-time. */
    val teamId: String,
    /** "Pedro Neto (assist Cole Palmer)", "On: Trey Nyoni, off: Dominik Szoboszlai". */
    val text: String,
    val isGoal: Boolean,
)

/** A team statistic for both sides, as the match screen compares them. */
data class StatLine(val label: String, val home: String, val away: String)

data class FootballMatchDetail(
    val events: List<KeyEvent>,
    val stats: List<StatLine>,
    val homeFormation: String,
    val awayFormation: String,
)

data class Leader(val name: String, val teamName: String, val value: String)

data class Leaders(val goals: List<Leader>, val assists: List<Leader>)

data class FootballData(
    val standings: Standings,
    val fixtures: List<Fixture>,
    /** Team id to its last five results, oldest first, such as WWDLW. */
    val form: Map<String, String>,
    /** Null when the lookup failed: the card is hidden, the table still renders. */
    val leaders: Leaders?,
)
