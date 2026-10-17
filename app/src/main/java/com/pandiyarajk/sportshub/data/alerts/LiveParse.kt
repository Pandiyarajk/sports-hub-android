// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub.data.alerts

import com.pandiyarajk.sportshub.data.Http
import com.pandiyarajk.sportshub.data.at
import com.pandiyarajk.sportshub.data.cricket.MatchState
import com.pandiyarajk.sportshub.data.cricket.interruption
import com.pandiyarajk.sportshub.data.int
import com.pandiyarajk.sportshub.data.list
import com.pandiyarajk.sportshub.data.parseMillis
import com.pandiyarajk.sportshub.data.text

/**
 * The per-league cricket scoreboard (about 12 KB, against 80 KB for the all-matches header),
 * polled every minute while an India match is live. Its line scores carry runs, wickets and overs
 * as numbers, which is what over and wicket detection needs; the score strings stay for display.
 */
fun parseCricketLive(body: String): List<CricketLive> =
    Http.json.parseToJsonElement(body).at("events").list().mapNotNull { e ->
        val id = e.at("id").text().ifBlank { return@mapNotNull null }
        val comp = e.at("competitions").list().firstOrNull() ?: return@mapNotNull null
        val status = comp.at("status")
        val state = when (status.at("type").at("state").text()) {
            "in" -> MatchState.LIVE
            "post" -> MatchState.RESULT
            else -> MatchState.UPCOMING
        }
        // Each side has one line per innings. A side that batted earlier keeps isBatting on that
        // old line, so the batting side is the one whose CURRENT line is a batting line. Between
        // innings no line may be current; then the latest batting line by period stands.
        val lines = comp.at("competitors").list().flatMap { c ->
            val team = c.at("team").at("abbreviation").text()
            c.at("linescores").list().filter { it.at("isBatting").text() == "true" }.map { team to it }
        }
        val pick = lines.firstOrNull { (_, l) -> l.at("isCurrent").int() == 1 }
            ?: lines.maxByOrNull { (_, l) -> l.at("period").int() }
        val batting = pick?.let { (team, l) ->
            val oversText = l.at("overs").text()
            Batting(
                team = team,
                innings = l.at("period").int(),
                runs = l.at("runs").int(),
                wickets = l.at("wickets").int(),
                oversDone = oversText.substringBefore('.').toIntOrNull() ?: 0,
                oversText = oversText.removeSuffix(".0"),
            )
        }
        val stop = interruption(status.at("type").at("description").text())
        CricketLive(
            matchId = id,
            title = e.at("shortName").text(),
            state = state,
            summary = stop.ifBlank { status.at("summary").text() }.ifBlank { status.at("type").at("detail").text() },
            interruption = stop,
            batting = batting,
            startMillis = parseMillis(e.at("date").text()),
        )
    }

/** The day's EPL scoreboard: scores, clock and the goal list with scorer and minute. */
fun parseFootballLive(body: String): List<FootballLive> =
    Http.json.parseToJsonElement(body).at("events").list().mapNotNull { e ->
        val id = e.at("id").text().ifBlank { return@mapNotNull null }
        val comp = e.at("competitions").list().firstOrNull() ?: return@mapNotNull null
        fun side(homeAway: String): FootballSide? {
            val c = comp.at("competitors").list().firstOrNull { it.at("homeAway").text() == homeAway } ?: return null
            return FootballSide(
                teamId = c.at("team").at("id").text(),
                abbr = c.at("team").at("abbreviation").text(),
                score = c.at("score").int(),
            )
        }
        val type = comp.at("status").at("type")
        FootballLive(
            matchId = id,
            home = side("home") ?: return@mapNotNull null,
            away = side("away") ?: return@mapNotNull null,
            state = type.at("state").text(),
            detail = type.at("shortDetail").text(),
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
            startMillis = parseMillis(e.at("date").text()),
        )
    }
