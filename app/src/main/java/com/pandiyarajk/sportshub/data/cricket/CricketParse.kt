// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (match label, city and winner; skip matches with no id;
//   scorecard falls back to per-player roster stats when matchcards is empty; a rain delay or
//   other interruption wins over the stale summary)

package com.pandiyarajk.sportshub.data.cricket

import com.pandiyarajk.sportshub.data.Http
import com.pandiyarajk.sportshub.data.at
import com.pandiyarajk.sportshub.data.int
import com.pandiyarajk.sportshub.data.list
import com.pandiyarajk.sportshub.data.text
import kotlinx.serialization.json.JsonElement

/** ESPN's status descriptions for the routine states (measured: type 0 Live or Scheduled, type 1 Result). */
private val ROUTINE_STATUS = setOf("live", "result", "scheduled", "")

/**
 * A status worth showing over the summary: "Match delayed by rain" (type 101), stumps, an innings
 * break. ESPN updates the type description and leaves the summary fields stale, so during a rain
 * delay the summary still reads "Starts at 19:00 local time". Blank for the routine states.
 */
fun interruption(description: String): String =
    description.trim().takeUnless { it.lowercase() in ROUTINE_STATUS }.orEmpty()

/**
 * "Bangladesh require 235 runs from 268 balls". The feed says only the runs; the balls left come
 * from its own score string, "37/0 (5.2/50 ov, target 272)", so a rain-reduced limit (9 overs) is
 * right too. Anything that is not a chase, or already names the balls, is returned as given.
 */
fun chaseLine(summary: String, scoreText: String): String {
    if (summary.isBlank() || summary.contains("ball", ignoreCase = true)) return summary
    if (!Regex("""\b(require|requires|need|needs)\b""", RegexOption.IGNORE_CASE).containsMatchIn(summary)) return summary
    val m = Regex("""\((\d+)(?:\.(\d))?/(\d+) ov, target \d+\)""").find(scoreText) ?: return summary
    val bowled = m.groupValues[1].toInt() * 6 + (m.groupValues[2].toIntOrNull() ?: 0)
    val left = m.groupValues[3].toInt() * 6 - bowled
    if (left <= 0) return summary
    return "$summary from $left ball${if (left == 1) "" else "s"}"
}

/** Parses the single scoreboard header call: every league and match in one payload. */
fun parseCricketHeader(body: String): List<CricketMatch> {
    val root = Http.json.parseToJsonElement(body)
    val leagues = root.at("sports").list().firstOrNull().at("leagues").list()
    return leagues.flatMap { league ->
        val leagueId = league.at("id").text()
        val leagueName = league.at("name").text()
        league.at("events").list().map { e ->
            val teams = e.at("competitors").list().map { c ->
                CricketTeam(
                    name = c.at("displayName").text(),
                    abbr = c.at("abbreviation").text(),
                    logo = c.at("logo").text().ifBlank { null },
                    score = c.at("score").text(),
                    winner = c.at("winner").text() == "true",
                )
            }
            val location = e.at("location").text()
            val state = when (e.at("status").text()) {
                "in" -> MatchState.LIVE
                "post" -> MatchState.RESULT
                else -> MatchState.UPCOMING
            }
            val full = e.at("fullStatus")
            CricketMatch(
                id = e.at("id").text(),
                leagueId = leagueId,
                league = leagueName,
                title = e.at("description").text().ifBlank { e.at("name").text() },
                venue = location,
                startIso = e.at("date").text(),
                state = state,
                status = chaseLine(
                    interruption(full.at("type").at("description").text())
                        .ifBlank { full.at("longSummary").text() }
                        .ifBlank { full.at("summary").text() },
                    teams.firstOrNull { "target" in it.score }?.score.orEmpty(),
                ),
                teams = teams,
                isIndia = IndiaFilter.isIndia(leagueName, teams),
                label = e.at("title").text().removeSuffix(" (D/N)").trim(),
                city = location.substringAfterLast(", ").trim(),
            )
        }
        // The id keys every list row; two blank ids would be a duplicate key and crash the list.
    }.filter { it.id.isNotBlank() }.distinctBy { it.id }
}

/**
 * Parses the per-match summary: one batting card and one bowling card per innings. ESPN fills
 * `matchcards` for some matches and leaves it empty for others, live ones included, while the
 * same figures sit per player under `rosters`; the rosters are the fallback.
 */
fun parseScorecard(body: String, known: Map<String, String> = emptyMap()): Scorecard {
    val root = Http.json.parseToJsonElement(body)
    val fromCards = parseMatchcards(root)
    val fromRosters = parseRosterInnings(root)
    val dismissals = known + runCatching { wicketBalls(root).associate { it.batter to it.how } }.getOrDefault(emptyMap())
    // Per-player stats first, matchcards only for an innings the stats lack. Measured live on
    // BAN v ZIM: matchcards held only Zimbabwe's innings, frozen at 236/8 after 46.3 overs while
    // the real total was 271/8 off 50, and nothing of the chase. The stats had both, current.
    val byNumber = fromCards.associateBy { it.number } + fromRosters.associateBy { it.number }
    val innings = byNumber.toSortedMap().values.map { inn ->
        inn.copy(batting = inn.batting.map { r -> r.copy(dismissal = fullDismissal(r.name, r.dismissal, dismissals)) })
    }
    // The live panel is extra: a failure to read it must never cost the scorecard.
    val live = runCatching { parseLivePanel(root)?.let { reconcile(it, innings) } }.getOrNull()
    // Out, but only the code is known: the repository looks these up in the play-by-play.
    val unresolved = innings.flatMap { it.batting }
        .filter { r -> !r.dismissal.equals("not out", ignoreCase = true) && r.dismissal.isNotBlank() && r.name !in dismissals }
        .map { it.name }.toSet()
    return Scorecard(innings, live, dismissals, unresolved)
}

/** One wicket from the ball-by-ball: who, how ("c Joseph b Paul") and the full line. */
data class WicketBall(
    val batter: String,
    val how: String,
    val line: String,
    val sequence: Long,
    /** The bowler's full name from the ball, "Keemo Paul"; the dismissal text has only "Paul". */
    val bowler: String = "",
    val runs: String = "",
    val balls: String = "",
) {
    /** "Abhishek Sharma 6 (4) caught by Joseph, bowled by Keemo Paul", for the wicket alert. */
    val alertText: String
        get() {
            val score = if (runs.isNotBlank()) " $runs" + (if (balls.isNotBlank()) " ($balls)" else "") else ""
            return "$batter$score ${describeDismissal(how, bowler)}".trim()
        }
}

/**
 * Cricket notation in words, for an alert read at a glance:
 * "c Joseph b Paul" is caught by Joseph, bowled by Paul; "c †Hope b Holder" is caught by the
 * keeper; "c & b Paul" is caught and bowled; "st †Hope b Chase" is stumped; "run out (Joseph/Hope)"
 * names both fielders; lbw, bowled and hit wicket name the bowler. The bowler's full name is used
 * when known. Anything else is returned as given.
 */
fun describeDismissal(how: String, bowlerFull: String = ""): String {
    val h = how.trim()
    fun bowler(short: String) = bowlerFull.ifBlank { short.trim() }
    fun fielder(name: String) = name.trim().removePrefix("†").trim()
    fun keeper(name: String) = if (name.trim().startsWith("†")) " (keeper)" else ""

    Regex("""^c\s*(?:&|and)\s*b\s+(.+)$""", RegexOption.IGNORE_CASE).find(h)?.let {
        return "caught and bowled by ${bowler(it.groupValues[1])}"
    }
    Regex("""^c\s+(.+?)\s+b\s+(.+)$""").find(h)?.let {
        val f = it.groupValues[1]
        return "caught by ${fielder(f)}${keeper(f)}, bowled by ${bowler(it.groupValues[2])}"
    }
    Regex("""^st\s+(.+?)\s+b\s+(.+)$""").find(h)?.let {
        return "stumped by ${fielder(it.groupValues[1])}, bowled by ${bowler(it.groupValues[2])}"
    }
    Regex("""^run out\s*\((.+)\)$""", RegexOption.IGNORE_CASE).find(h)?.let {
        return "run out by " + it.groupValues[1].split("/").map(::fielder).filter { n -> n.isNotBlank() }.joinToString(" and ")
    }
    if (h.equals("run out", ignoreCase = true)) return "run out"
    Regex("""^lbw\s+b\s+(.+)$""", RegexOption.IGNORE_CASE).find(h)?.let { return "lbw, bowled by ${bowler(it.groupValues[1])}" }
    Regex("""^hit wicket\s+b\s+(.+)$""", RegexOption.IGNORE_CASE).find(h)?.let { return "hit wicket, bowled by ${bowler(it.groupValues[1])}" }
    Regex("""^b\s+(.+)$""").find(h)?.let { return "bowled by ${bowler(it.groupValues[1])}" }
    return h
}

/**
 * Wicket balls from header.competitions[0].commentaries, oldest first. The roster stats only
 * carry a code ("c"); the commentary says who caught it and who bowled it:
 * "Abhishek Sharma c Joseph b Paul 6 (4b 0x4 1x6) SR: 150". The commentary holds only the
 * last few overs, so callers keep what they have seen.
 */
fun wicketBalls(root: JsonElement): List<WicketBall> =
    wicketBallsIn(
        (root.at("header").at("competitions").list().firstOrNull().at("commentaries")
            as? kotlinx.serialization.json.JsonObject)?.values.orEmpty(),
    )

/**
 * One page of the play-by-play (25 balls, oldest first): its wickets and how many pages there
 * are. The summary's commentary keeps only the last few overs; this reaches the earlier ones,
 * so a wicket from the first over still reads "c Joseph b Paul" an hour later.
 */
fun parsePlayByPlayPage(body: String): Pair<List<WicketBall>, Int> {
    val c = Http.json.parseToJsonElement(body).at("commentary")
    return wicketBallsIn(c.at("items").list()) to c.at("pageCount").int()
}

private fun wicketBallsIn(balls: Collection<JsonElement>): List<WicketBall> {
    return balls.mapNotNull { b ->
        val d = b.at("dismissal")
        if (d.at("dismissal").text() != "true") return@mapNotNull null
        val text = d.at("text").text().ifBlank { return@mapNotNull null }
        val athlete = d.at("batsman").at("athlete")
        val name = athlete.at("displayName").text().ifBlank { athlete.at("name").text() }
        // "Abhishek Sharma c Joseph b Paul 6 (4b 0x4 1x6) SR: 150". The name at the front is not
        // always the display name (seen live: "SV Samson b Springer" for Sanju Samson), so the how
        // is found by where the dismissal starts, not by stripping the name.
        val figures = Regex("""^(.*?)\s+(\d+)\s+\((\d+)b""").find(text)
        val head = figures?.groupValues?.get(1) ?: text.substringBefore(" SR:")
        val start = DISMISSAL_START.find(head)?.range?.first
        val how = if (start != null) head.substring(start) else head.removePrefix(name).trim()
        WicketBall(
            batter = name.ifBlank { text.substringBefore(" $how") },
            how = how.trim(),
            line = text.substringBefore(" SR:").trim(),
            sequence = b.at("sequence").text().toLongOrNull() ?: 0L,
            bowler = b.at("bowler").at("athlete").at("displayName").text(),
            runs = figures?.groupValues?.get(2).orEmpty(),
            balls = figures?.groupValues?.get(3).orEmpty(),
        )
    }.sortedBy { it.sequence }
}

fun wicketBalls(body: String): List<WicketBall> = wicketBalls(Http.json.parseToJsonElement(body))

/**
 * Where a dismissal begins inside "SV Samson b Springer": a lowercase keyword after a space.
 * Case matters, so an initial in a name ("C Green c Smith b Starc") is not taken for "c".
 */
private val DISMISSAL_START = Regex(
    """(?<=\s)(c\s|st\s|run out|lbw\s|b\s|hit wicket|retired|obstructing|handled|timed out|absent)""",
)

/** The roster's dismissal codes in words, for wickets that fell before the commentary window. */
private val DISMISSAL_CODES = mapOf(
    "c" to "caught", "b" to "bowled", "lbw" to "lbw", "st" to "stumped", "ro" to "run out",
    "run out" to "run out", "hw" to "hit wicket", "ct" to "caught",
)

/** The full "c Joseph b Paul" when known; else the code in words; else as given. */
internal fun fullDismissal(name: String, given: String, known: Map<String, String>): String {
    if (given.equals("not out", ignoreCase = true)) return given
    known[name]?.let { return it }
    return DISMISSAL_CODES[given.trim().lowercase()] ?: given
}

/**
 * The ball-by-ball commentary can lag the per-player stats in the same response by a ball (seen
 * live: the panel said 72 off 81 while the scorecard below it said 73 off 82). The commentary
 * decides who is in and who is bowling; the figures come from the current innings of the
 * scorecard when the names match, so the panel and the scorecard never disagree on screen.
 */
private fun reconcile(live: LivePanel, innings: List<Innings>): LivePanel {
    val current = innings.lastOrNull() ?: return live
    val bats = current.batting.associateBy { it.name }
    val bowls = current.bowling.associateBy { it.name }
    return live.copy(
        batters = live.batters.map { b ->
            val r = bats[b.name] ?: return@map b
            b.copy(
                runs = r.runs.toIntOrNull() ?: b.runs,
                balls = r.balls.toIntOrNull() ?: b.balls,
                fours = r.fours.toIntOrNull() ?: b.fours,
                sixes = r.sixes.toIntOrNull() ?: b.sixes,
            )
        },
        bowlers = live.bowlers.map { w ->
            val r = bowls[w.name] ?: return@map w
            val balls = oversToBalls(r.overs) ?: return@map w
            w.copy(
                overs = r.overs.removeSuffix(".0"),
                maidens = r.maidens.toIntOrNull() ?: w.maidens,
                conceded = r.runs.toIntOrNull() ?: w.conceded,
                wickets = r.wickets.toIntOrNull() ?: w.wickets,
                balls = balls,
            )
        },
    )
}

/** "5.4" overs is 34 balls. Null for anything that is not overs.balls. */
internal fun oversToBalls(overs: String): Int? {
    val whole = overs.substringBefore('.').toIntOrNull() ?: return null
    val part = if ('.' in overs) overs.substringAfter('.').toIntOrNull() ?: return null else 0
    return whole * 6 + part
}

/**
 * Builds the live panel from the latest ball in header.competitions[0].commentaries (keyed by
 * id, ordered by `sequence`): batsman is on strike, otherBatsman is not; bowler is the one
 * bowling now. Rates come from the batting side's current line score statistics. Null when there
 * is no ball-by-ball (a scheduled match, a finished one without commentary).
 */
private fun parseLivePanel(root: JsonElement): LivePanel? {
    val comp = root.at("header").at("competitions").list().firstOrNull()
    val balls = (comp.at("commentaries") as? kotlinx.serialization.json.JsonObject)?.values
        ?.sortedBy { it.at("sequence").text().toLongOrNull() ?: 0L }
        .orEmpty()
    val last = balls.lastOrNull() ?: return null

    fun batter(node: JsonElement?, onStrike: Boolean): LiveBatter? {
        val name = node.at("athlete").at("displayName").text().ifBlank { return null }
        return LiveBatter(
            name, node.at("totalRuns").int(), node.at("faced").int(),
            node.at("fours").int(), node.at("sixes").int(), onStrike,
        )
    }
    fun bowler(node: JsonElement?): LiveBowler? {
        val name = node.at("athlete").at("displayName").text().ifBlank { return null }
        return LiveBowler(
            name, node.at("overs").text().removeSuffix(".0"), node.at("maidens").int(),
            node.at("conceded").int(), node.at("wickets").int(), node.at("balls").int(),
        )
    }

    // A batter just dismissed is still named on the ball that got him; leave him out of "in".
    val outId = last.at("dismissal").takeIf { it.at("dismissal").text() == "true" }
        .at("batsman").at("athlete").at("id").text()
    val batters = listOfNotNull(
        batter(last.at("batsman"), onStrike = true).takeIf { last.at("batsman").at("athlete").at("id").text() != outId },
        batter(last.at("otherBatsman"), onStrike = false).takeIf { last.at("otherBatsman").at("athlete").at("id").text() != outId },
    )
    val bowlers = listOfNotNull(bowler(last.at("bowler")), bowler(last.at("otherBowler")))

    val line = comp.at("competitors").list()
        .flatMap { c -> c.at("linescores").list() }
        .firstOrNull { it.at("isCurrent").int() == 1 && it.at("isBatting").text() == "true" }
    val st = line.at("statistics").at("categories").list()
        .flatMap { it.at("stats").list() }
        .associate { it.at("name").text() to it.at("displayValue").text() }

    val innings = last.at("innings")
    val target = innings.at("target").int()
    val needed = target - innings.at("runs").int()
    val left = innings.at("remainingBalls").int()
    val required = if (target > 0 && needed > 0 && left > 0) twoPlaces(needed * 6.0 / left) else ""

    val overNumber = last.at("over").at("number").int()
    val thisOver = balls.filter { it.at("over").at("number").int() == overNumber }.map { b ->
        val short = b.at("shortText").text().lowercase()
        val runs = b.at("scoreValue").int()
        when {
            b.at("dismissal").at("dismissal").text() == "true" -> "W"
            "wide" in short -> "${runs}wd"
            "no ball" in short -> "${runs}nb"
            else -> runs.toString()
        }
    }

    val recentOvers = st["recentOvers"]?.toIntOrNull() ?: 0
    val recentRate = st["runRateInRecentOvers"]?.toDoubleOrNull()
    val recent = if (recentOvers > 0 && recentRate != null) {
        "${st["runInRecentOvers"].orEmpty()}/${st["wicketInRecentOvers"].orEmpty()} (${twoPlaces(recentRate)})"
    } else {
        ""
    }

    return LivePanel(
        batters = batters,
        bowlers = bowlers,
        runRate = st["runRate"].orEmpty().ifBlank { innings.at("runRate").text() },
        requiredRate = required,
        recent = recent,
        recentOvers = recentOvers,
        thisOver = thisOver,
    )
}

private fun parseMatchcards(root: JsonElement): List<Innings> {
    val cards = root.at("matchcards").list()
    val byInnings = cards.groupBy { it.at("inningsNumber").int() }.toSortedMap()
    val innings = byInnings.map { (number, group) ->
        val batting = group.firstOrNull { it.at("headline").text() == "Batting" }
        val bowling = group.firstOrNull { it.at("headline").text() == "Bowling" }
        Innings(
            number = number,
            team = batting.at("teamName").text(),
            runs = batting.at("runs").text(),
            total = batting.at("total").text(),
            extras = batting.at("extras").text(),
            batting = batting.at("playerDetails").list().map {
                BattingRow(
                    it.at("playerName").text(), it.at("dismissal").text(), it.at("runs").text(),
                    it.at("ballsFaced").text(), it.at("fours").text(), it.at("sixes").text(),
                )
            },
            bowling = bowling.at("playerDetails").list().map {
                BowlingRow(
                    it.at("playerName").text(), it.at("overs").text(), it.at("maidens").text(),
                    it.at("conceded").text(), it.at("wickets").text(), it.at("economyRate").text(),
                )
            },
        )
    }
    return innings.filter { it.batting.isNotEmpty() || it.bowling.isNotEmpty() }
}

/**
 * Builds innings from per-player statistics: rosters[].roster[].linescores[] (one per innings,
 * keyed by period) holding linescores[].statistics.categories[].stats. A player with batted = 1
 * is a batting row for his own team's innings; a player with balls bowled is a bowling row.
 * The innings total comes from the header line score of the side batting in that period.
 */
/**
 * "8 (b 2, w 5, nb 1)" from an innings' header line statistics, as a scorecard writes extras,
 * leaving out the kinds that are zero. Blank when the line has no statistics yet.
 */
internal fun extrasFrom(line: JsonElement?): String {
    val st = line.at("statistics").at("categories").list()
        .flatMap { it.at("stats").list() }
        .associate { it.at("name").text() to it.at("displayValue").text() }
    val total = st["extras"]?.takeIf { it.isNotBlank() } ?: return ""
    val parts = listOf("byes" to "b", "legbyes" to "lb", "wides" to "w", "noballs" to "nb", "penalties" to "pen")
        .mapNotNull { (key, short) -> st[key]?.toIntOrNull()?.takeIf { it > 0 }?.let { "$short $it" } }
    return if (parts.isEmpty()) total else "$total (${parts.joinToString(", ")})"
}

private fun parseRosterInnings(root: JsonElement): List<Innings> {
    class Bat(val innings: Int, val team: String, val position: Int, val row: BattingRow)
    class Bowl(val innings: Int, val position: Int, val row: BowlingRow)
    val bats = mutableListOf<Bat>()
    val bowls = mutableListOf<Bowl>()

    root.at("rosters").list().forEach { side ->
        val team = side.at("team").at("displayName").text()
        side.at("roster").list().forEach { player ->
            val name = player.at("athlete").at("displayName").text()
            player.at("linescores").list().forEach { inn ->
                val number = inn.at("period").int()
                inn.at("linescores").list().forEach { line ->
                    val st = line.at("statistics").at("categories").list()
                        .flatMap { it.at("stats").list() }
                        .associate { it.at("name").text() to it.at("displayValue").text() }
                    if (st["batted"] == "1") {
                        bats += Bat(
                            number, team, st["battingPosition"]?.toIntOrNull() ?: Int.MAX_VALUE,
                            BattingRow(
                                name, st["dismissalCard"].orEmpty(), st["runs"].orEmpty(),
                                st["ballsFaced"].orEmpty(), st["fours"].orEmpty(), st["sixes"].orEmpty(),
                            ),
                        )
                    }
                    if ((st["balls"]?.toIntOrNull() ?: 0) > 0) {
                        bowls += Bowl(
                            number, st["bowlingPosition"]?.toIntOrNull() ?: Int.MAX_VALUE,
                            BowlingRow(
                                name, st["overs"].orEmpty(), st["maidens"].orEmpty(),
                                st["conceded"].orEmpty(), st["wickets"].orEmpty(), st["economyRate"].orEmpty(),
                            ),
                        )
                    }
                }
            }
        }
    }

    val competitors = root.at("header").at("competitions").list().firstOrNull().at("competitors").list()
    val numbers = (bats.map { it.innings } + bowls.map { it.innings }).toSortedSet()
    return numbers.map { n ->
        val batting = bats.filter { it.innings == n }.sortedBy { it.position }
        val headerLine = competitors.firstNotNullOfOrNull { c ->
            c.at("linescores").list()
                .firstOrNull { it.at("period").int() == n && it.at("isBatting").text() == "true" }
                ?.let { c to it }
        }
        val runs = headerLine?.second?.let { line ->
            val wickets = line.at("wickets").int()
            if (wickets >= 10) line.at("runs").text() else "${line.at("runs").int()}/$wickets"
        }.orEmpty()
        val overs = headerLine?.second.at("overs").text()
        Innings(
            number = n,
            team = batting.firstOrNull()?.team
                ?: headerLine?.first.at("team").at("displayName").text(),
            runs = runs,
            total = if (overs.isNotBlank()) "($overs ov)" else "",
            extras = extrasFrom(headerLine?.second),
            batting = batting.map { it.row },
            bowling = bowls.filter { it.innings == n }.sortedBy { it.position }.map { it.row },
        )
    }
}
