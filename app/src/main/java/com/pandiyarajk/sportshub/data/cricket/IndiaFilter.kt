// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (order can leave India unpinned when the setting is off)

package com.pandiyarajk.sportshub.data.cricket

import com.pandiyarajk.sportshub.data.parseMillis

/** Decides what counts as India and orders the match list India first. */
object IndiaFilter {
    private val indiaAbbrs = setOf("IND", "IND-W", "INDA", "IND-A", "IND19", "IND-U19", "INDW", "INDU19")

    private val indiaLeague = Regex(
        "\\bIndia(n)?\\b|\\bIPL\\b|\\bWPL\\b|Ranji|Vijay Hazare|Mushtaq Ali|Duleep|Irani",
        RegexOption.IGNORE_CASE,
    )

    /** India is playing, or the series is Indian (a tour of India, the IPL, the Ranji Trophy). */
    fun isIndia(league: String, teams: List<CricketTeam>): Boolean =
        teams.any { it.abbr.uppercase() in indiaAbbrs || it.name.startsWith("India", ignoreCase = true) } ||
            indiaLeague.containsMatchIn(league)

    private fun stateRank(s: MatchState) = when (s) {
        MatchState.LIVE -> 0
        MatchState.UPCOMING -> 1
        MatchState.RESULT -> 2
    }

    /**
     * India live, upcoming, result, then everything else the same way. Upcoming runs soonest
     * first. With [indiaFirst] off (the setting), India is sorted like any other match.
     */
    fun order(matches: List<CricketMatch>, indiaFirst: Boolean = true): List<CricketMatch> {
        val comparator = compareBy<CricketMatch>(
            { if (indiaFirst && it.isIndia) 0 else 1 },
            { stateRank(it.state) },
            {
                val t = parseMillis(it.startIso) ?: 0L
                if (it.state == MatchState.UPCOMING) t else -t
            },
        )
        return matches.sortedWith(comparator)
    }
}
