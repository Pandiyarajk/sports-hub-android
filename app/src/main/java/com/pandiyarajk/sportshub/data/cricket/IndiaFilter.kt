// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-31-2026 (international matches ahead of club leagues)

package com.pandiyarajk.sportshub.data.cricket

import com.pandiyarajk.sportshub.data.parseMillis

/** Decides what counts as India and orders the match list: India, then international, then clubs. */
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

    private val countries = Regex(
        "^(India|Australia|England|Pakistan|South Africa|New Zealand|Sri Lanka|Bangladesh|West Indies|Zimbabwe|Afghanistan|" +
            "Ireland|Scotland|Netherlands|Nepal|United Arab Emirates|UAE|Oman|USA|United States|Canada|Namibia|Kenya|Uganda|" +
            "Papua New Guinea|PNG|Hong Kong|Singapore|Malaysia|Thailand|Bermuda|Jersey|Guernsey|Isle of Man|Italy|Germany|" +
            "Denmark|Norway|Sweden|Spain|Gibraltar|Qatar|Kuwait|Saudi Arabia|Bahrain|Tanzania|Nigeria|Botswana|Ghana|Malawi|" +
            "Rwanda|Mozambique|Sierra Leone|Cayman Islands|Argentina|Brazil|Chile|Mexico|Japan|China|Vanuatu|Fiji|Samoa|" +
            "Philippines|Bhutan|Maldives|Myanmar|Cambodia|Indonesia|Lesotho|Eswatini|Austria|France|Belgium|Finland|Romania|" +
            "Bulgaria|Czech|Hungary|Portugal|Malta|Cyprus|Estonia|Serbia|Greece|Costa Rica|Panama|Belize|Korea|Israel)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val clubLeague = Regex(
        "\\bIPL\\b|\\bWPL\\b|Premier League|Big Bash|\\bBBL\\b|\\bWBBL\\b|\\bCPL\\b|\\bPSL\\b|\\bSA20\\b|\\bILT20\\b|\\bMLC\\b|" +
            "The Hundred|T20 Blast|County|Ranji|Vijay Hazare|Mushtaq Ali|Duleep|Irani|Super Smash|Domestic|Franchise",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Country against country: every side is a national team (India A, England Women and
     * Under-19 sides count). With no sides listed, the league name decides.
     */
    fun isInternational(league: String, teams: List<CricketTeam>): Boolean =
        if (teams.isEmpty()) !clubLeague.containsMatchIn(league)
        else teams.all { countries.containsMatchIn(it.name.trim()) }

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
            { if (it.isInternational) 0 else 1 },
            { stateRank(it.state) },
            {
                val t = parseMillis(it.startIso) ?: 0L
                if (it.state == MatchState.UPCOMING) t else -t
            },
        )
        return matches.sortedWith(comparator)
    }
}
