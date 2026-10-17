// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (short club names, skip rows with no id; goals on fixtures; match summary)

package com.pandiyarajk.sportshub.data.football

import com.pandiyarajk.sportshub.data.Http
import com.pandiyarajk.sportshub.data.alerts.Goal
import com.pandiyarajk.sportshub.data.at
import com.pandiyarajk.sportshub.data.int
import com.pandiyarajk.sportshub.data.list
import com.pandiyarajk.sportshub.data.parseMillis
import com.pandiyarajk.sportshub.data.text

/** Entries live at children[0].standings.entries, not at the top level. */
fun parseStandings(body: String): Standings {
    val root = Http.json.parseToJsonElement(body)
    val entries = root.at("children").list().firstOrNull().at("standings").at("entries").list()
    val rows = entries.map { e ->
        val team = e.at("team")
        val stats = e.at("stats").list().associate { it.at("name").text() to it }
        fun n(key: String) = stats[key].at("value").int()
        TableRow(
            rank = n("rank"),
            teamId = team.at("id").text(),
            name = team.at("displayName").text(),
            abbr = team.at("abbreviation").text(),
            logo = team.at("logos").list().firstOrNull().at("href").text().ifBlank { null },
            played = n("gamesPlayed"),
            won = n("wins"),
            drawn = n("ties"),
            lost = n("losses"),
            goalsFor = n("pointsFor"),
            goalsAgainst = n("pointsAgainst"),
            goalDiff = stats["pointDifferential"].at("displayValue").text(),
            points = n("points"),
            shortName = team.at("shortDisplayName").text(),
        )
    }.filter { it.teamId.isNotBlank() }.distinctBy { it.teamId }.sortedBy { it.rank }
    return Standings(
        season = root.at("season").at("displayName").text(),
        seasonYear = root.at("season").at("year").int(),
        rows = rows,
    )
}

/** Parses one month of the scoreboard (dates=YYYYMM). A date range is rejected by the API. */
fun parseFixtures(body: String): List<Fixture> =
    Http.json.parseToJsonElement(body).at("events").list().mapNotNull { e ->
        // The id keys list rows, so an event without one is skipped rather than shown.
        if (e.at("id").text().isBlank()) return@mapNotNull null
        val comp = e.at("competitions").list().firstOrNull() ?: return@mapNotNull null
        val type = comp.at("status").at("type")
        fun side(homeAway: String): Side? {
            val c = comp.at("competitors").list().firstOrNull { it.at("homeAway").text() == homeAway }
                ?: return null
            val t = c.at("team")
            return Side(
                teamId = t.at("id").text(),
                name = t.at("displayName").text(),
                abbr = t.at("abbreviation").text(),
                logo = t.at("logo").text().ifBlank { null },
                score = c.at("score").text(),
                shortName = t.at("shortDisplayName").text(),
            )
        }
        val home = side("home") ?: return@mapNotNull null
        val away = side("away") ?: return@mapNotNull null
        val state = type.at("state").text()
        Fixture(
            id = e.at("id").text(),
            dateIso = e.at("date").text(),
            finished = state == "post",
            live = state == "in",
            detail = type.at("shortDetail").text(),
            home = home,
            away = away,
            goals = comp.at("details").list()
                .filter { it.at("scoringPlay").text() == "true" }
                .map {
                    Goal(
                        minute = it.at("clock").at("displayValue").text(),
                        scorer = it.at("athletesInvolved").list().firstOrNull().at("displayName").text(),
                        teamId = it.at("team").at("id").text(),
                        ownGoal = it.at("ownGoal").text() == "true",
                        penalty = it.at("penaltyKick").text() == "true",
                    )
                },
        )
    }

/** Event types that are noise on a timeline: the kick-off line and injury-stoppage markers. */
private val TIMELINE_NOISE = setOf("kickoff", "start delay", "end delay")

/** The stats the match screen compares, in order, with the labels it shows. */
private val MATCH_STATS = listOf(
    "possessionPct" to "Possession %",
    "totalShots" to "Shots",
    "shotsOnTarget" to "Shots on target",
    "wonCorners" to "Corners",
    "foulsCommitted" to "Fouls",
    "yellowCards" to "Yellow cards",
    "redCards" to "Red cards",
    "offsides" to "Offsides",
    "saves" to "Saves",
    "totalPasses" to "Passes",
)

/**
 * The match summary: key events as a timeline, the boxscore as head-to-head stats, and each
 * side's formation. Unknown event types are kept with their own name, never dropped as errors.
 */
fun parseFootballSummary(body: String): FootballMatchDetail {
    val root = Http.json.parseToJsonElement(body)
    val events = root.at("keyEvents").list().mapNotNull { e ->
        val type = e.at("type").at("text").text()
        if (type.lowercase() in TIMELINE_NOISE) return@mapNotNull null
        val who = e.at("participants").list().map { it.at("athlete").at("displayName").text() }.filter { it.isNotBlank() }
        val isGoal = e.at("scoringPlay").text() == "true"
        val lower = type.lowercase()
        val text = when {
            isGoal -> who.firstOrNull()?.let { s -> s + (who.getOrNull(1)?.let { " (assist $it)" } ?: "") } ?: type
            "substitution" in lower && who.size >= 2 -> "On: ${who[0]}, off: ${who[1]}"
            who.isNotEmpty() -> who.joinToString(", ")
            else -> e.at("text").text().ifBlank { type }
        }
        KeyEvent(
            minute = e.at("clock").at("displayValue").text(),
            type = type,
            teamId = e.at("team").at("id").text(),
            text = text,
            isGoal = isGoal,
        )
    }

    val teams = root.at("boxscore").at("teams").list()
    fun statsOf(homeAway: String) = teams.firstOrNull { it.at("homeAway").text() == homeAway }
        .at("statistics").list().associate { it.at("name").text() to it.at("displayValue").text() }
    val home = statsOf("home")
    val away = statsOf("away")
    val stats = MATCH_STATS.mapNotNull { (key, label) ->
        val h = home[key] ?: return@mapNotNull null
        val a = away[key] ?: return@mapNotNull null
        StatLine(label, h, a)
    }

    fun formation(homeAway: String) =
        root.at("rosters").list().firstOrNull { it.at("homeAway").text() == homeAway }.at("formation").text()

    return FootballMatchDetail(events, stats, formation("home"), formation("away"))
}

/** Team id to its last five finished results, oldest first. Fewer than five early in a season, not padded. */
object FormBuilder {
    fun build(fixtures: List<Fixture>): Map<String, String> {
        val results = mutableMapOf<String, MutableList<Char>>()
        fixtures.filter { it.finished }.sortedBy { parseMillis(it.dateIso) ?: 0L }.forEach { f ->
            val h = f.home.score.toIntOrNull() ?: return@forEach
            val a = f.away.score.toIntOrNull() ?: return@forEach
            val homeChar = if (h > a) 'W' else if (h < a) 'L' else 'D'
            val awayChar = if (a > h) 'W' else if (a < h) 'L' else 'D'
            results.getOrPut(f.home.teamId) { mutableListOf() }.add(homeChar)
            results.getOrPut(f.away.teamId) { mutableListOf() }.add(awayChar)
        }
        return results.mapValues { (_, v) -> v.takeLast(5).joinToString("") }
    }
}

/** The leaders payload lists athletes behind references; this keeps the top entries per category. */
data class LeaderRef(val value: String, val athleteUrl: String, val teamId: String)

fun parseLeaderRefs(body: String, category: String, limit: Int = 5): List<LeaderRef> {
    val cat = Http.json.parseToJsonElement(body).at("categories").list()
        .firstOrNull { it.at("name").text() == category }
    return cat.at("leaders").list().take(limit).map { l ->
        LeaderRef(
            value = l.at("displayValue").text(),
            athleteUrl = l.at("athlete").at("\$ref").text().replace("http://", "https://"),
            teamId = l.at("team").at("\$ref").text().substringAfter("/teams/").substringBefore("?"),
        )
    }
}
