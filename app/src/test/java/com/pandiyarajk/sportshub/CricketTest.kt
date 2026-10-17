// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub

import com.pandiyarajk.sportshub.data.Load
import com.pandiyarajk.sportshub.data.cricket.CricketMatch
import com.pandiyarajk.sportshub.data.cricket.CricketRepository
import com.pandiyarajk.sportshub.data.cricket.CricketTeam
import com.pandiyarajk.sportshub.data.cricket.IndiaFilter
import com.pandiyarajk.sportshub.data.cricket.MatchState
import com.pandiyarajk.sportshub.data.cricket.parseCricketHeader
import com.pandiyarajk.sportshub.data.cricket.parseScorecard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun fixture(name: String): String =
    checkNotNull(object {}.javaClass.classLoader?.getResource(name)) { "missing fixture $name" }.readText()

private fun team(name: String, abbr: String, score: String = "") = CricketTeam(name, abbr, null, score)

private fun match(
    id: String,
    league: String,
    state: MatchState,
    teams: List<CricketTeam>,
    start: String = "2026-10-17T10:00:00Z",
) = CricketMatch(id, "1", league, "", "", start, state, "", teams, IndiaFilter.isIndia(league, teams))

class IndiaFilterTest {
    @Test
    fun `india playing makes a match india whatever the series is`() {
        assertTrue(IndiaFilter.isIndia("Some Tri-Series", listOf(team("India", "IND"), team("Nepal", "NEP"))))
        assertTrue(IndiaFilter.isIndia("Asia Cup", listOf(team("India Women", "IND-W"), team("Nepal Women", "NEP-W"))))
    }

    @Test
    fun `an indian series qualifies with neither side named india`() {
        val other = listOf(team("Mumbai", "MUM"), team("Delhi", "DEL"))
        assertTrue(IndiaFilter.isIndia("Ranji Trophy", other))
        assertTrue(IndiaFilter.isIndia("Indian Premier League", other))
        assertTrue(IndiaFilter.isIndia("West Indies tour of India 2026/27", listOf(team("West Indies", "WI"))))
    }

    @Test
    fun `west indies alone is not india`() {
        assertFalse(IndiaFilter.isIndia("Caribbean Premier League", listOf(team("West Indies", "WI"), team("Nepal", "NEP"))))
        assertFalse(IndiaFilter.isIndia("ICC World Cup League 2", listOf(team("Canada", "CAN"), team("Nepal", "NEP"))))
    }

    @Test
    fun `india upcoming sorts above a live match elsewhere, and live above result within a group`() {
        val liveElsewhere = match("a", "League 2", MatchState.LIVE, listOf(team("Canada", "CAN"), team("Nepal", "NEP")))
        val indiaUpcoming = match("b", "Tour", MatchState.UPCOMING, listOf(team("India", "IND"), team("West Indies", "WI")))
        val resultElsewhere = match("c", "League 3", MatchState.RESULT, listOf(team("Qld", "QLD"), team("SA", "SOA")))
        val indiaResult = match("d", "Tour", MatchState.RESULT, listOf(team("India", "IND"), team("Nepal", "NEP")))
        val ordered = IndiaFilter.order(listOf(resultElsewhere, liveElsewhere, indiaResult, indiaUpcoming)).map { it.id }
        assertEquals(listOf("b", "d", "a", "c"), ordered)
    }

    @Test
    fun `with india first turned off, india sorts like any other match`() {
        val liveElsewhere = match("a", "League 2", MatchState.LIVE, listOf(team("Canada", "CAN"), team("Nepal", "NEP")))
        val indiaUpcoming = match("b", "Tour", MatchState.UPCOMING, listOf(team("India", "IND"), team("West Indies", "WI")))
        assertEquals(listOf("a", "b"), IndiaFilter.order(listOf(indiaUpcoming, liveElsewhere), indiaFirst = false).map { it.id })
    }

    @Test
    fun `matches with no id are dropped so list keys stay unique`() {
        val body = """{"sports":[{"leagues":[{"id":"1","name":"L","events":[{"id":""},{"id":""},{"id":"7"}]}]}]}"""
        assertEquals(listOf("7"), parseCricketHeader(body).map { it.id })
    }

    @Test
    fun `upcoming matches run soonest first and results newest first`() {
        val late = match("late", "L", MatchState.UPCOMING, listOf(team("A", "A")), "2026-10-20T10:00:00Z")
        val soon = match("soon", "L", MatchState.UPCOMING, listOf(team("B", "B")), "2026-10-18T10:00:00Z")
        assertEquals(listOf("soon", "late"), IndiaFilter.order(listOf(late, soon)).map { it.id })
        val old = match("old", "L", MatchState.RESULT, listOf(team("C", "C")), "2026-10-10T10:00:00Z")
        val recent = match("recent", "L", MatchState.RESULT, listOf(team("D", "D")), "2026-10-16T10:00:00Z")
        assertEquals(listOf("recent", "old"), IndiaFilter.order(listOf(old, recent)).map { it.id })
    }
}

class CricketFeedParseTest {
    private val matches = parseCricketHeader(fixture("cricket_header.json"))

    @Test
    fun `the captured header yields every match across every league`() {
        assertEquals(16, matches.size)
        assertEquals(10, matches.map { it.leagueId }.toSet().size)
    }

    @Test
    fun `a live chase keeps ESPN's score string exactly as given`() {
        val nepal = matches.first { it.id == "1554419" }.teams.first { it.abbr == "NEP" }
        assertEquals("166/4 (35.5/50 ov, target 257)", nepal.score)
        assertEquals("166/4", nepal.headline)
        assertEquals("(35.5/50 ov, target 257)", nepal.detail)
    }

    @Test
    fun `states map from the feed and an unscored side has a blank score`() {
        assertEquals(MatchState.LIVE, matches.first { it.id == "1554419" }.state)
        assertEquals(MatchState.RESULT, matches.first { it.id == "1544851" }.state)
        val india = matches.first { it.id == "1529234" }
        assertEquals(MatchState.UPCOMING, india.state)
        assertTrue(india.teams.all { it.score.isBlank() })
        assertEquals("Match scheduled to begin at 19:00 local time (13:30 GMT)", india.status)
    }

    @Test
    fun `india is flagged and the ordered list leads with it`() {
        val ordered = IndiaFilter.order(matches)
        assertEquals("1529234", ordered.first().id)
        assertTrue(ordered.first().isIndia)
        assertEquals(1, matches.count { it.isIndia })
    }

    @Test
    fun `a payload with nothing in it parses to an empty list, not an error`() {
        assertEquals(0, parseCricketHeader("{}").size)
        assertEquals(0, parseCricketHeader("""{"sports":[{"leagues":[]}]}""").size)
    }
}

class ScorecardParseTest {
    private val card = parseScorecard(fixture("cricket_summary.json"))

    @Test
    fun `the captured summary yields the first innings with batting and bowling rows`() {
        val first = card.innings.first()
        assertEquals(1, first.number)
        assertEquals("Canada", first.team)
        assertEquals("256", first.runs)
        assertEquals("(b 1, lb 7, w 3)", first.extras)
        assertEquals(11, first.batting.size)
        assertEquals(6, first.bowling.size)
    }

    @Test
    fun `batting rows carry the fields a scorecard shows`() {
        val samra = card.innings.first().batting.first()
        assertEquals("YS Samra", samra.name)
        assertEquals("105", samra.runs)
        assertEquals("117", samra.balls)
        assertEquals("11", samra.fours)
        assertEquals("4", samra.sixes)
    }

    @Test
    fun `an empty matchcards falls back to the per-player roster stats`() {
        // Captured live: BAN v ZIM, ZIM 72/3 after 16.3 overs, matchcards empty.
        val inn = parseScorecard(fixture("cricket_summary_rosters.json")).innings.single()
        assertEquals(1, inn.number)
        assertEquals("Zimbabwe", inn.team)
        assertEquals("72/3", inn.runs)
        assertEquals("(16.3 ov)", inn.total)
        assertEquals(listOf("Brian Bennett", "Ben Curran", "Tafadzwa Tsiga", "Brendan Taylor", "Craig Ervine"),
            inn.batting.map { it.name })
        assertEquals("35", inn.batting[3].runs)
        assertEquals("not out", inn.batting[3].dismissal)
        assertEquals(listOf("Taskin Ahmed", "Shoriful Islam", "Mustafizur Rahman", "Mosaddek Hossain"),
            inn.bowling.map { it.name })
        assertEquals("3", inn.bowling.first().wickets)
    }

    @Test
    fun `the live panel matches Cricinfo's for the same moment`() {
        // Captured live Oct-17-2026: ZIM 142/3 (28.5 ov) v BAN. Every expected value below is read
        // off Cricinfo's own live panel at that ball, not from our output.
        val live = parseScorecard(fixture("cricket_summary_live_panel.json")).live!!
        assertEquals(listOf("Craig Ervine", "Brendan Taylor"), live.batters.map { it.name })
        assertTrue(live.batters[0].onStrike)
        assertEquals(listOf(46, 61, 4, 0), live.batters[0].let { listOf(it.runs, it.balls, it.fours, it.sixes) })
        assertEquals("75.40", live.batters[0].strikeRate)
        assertEquals(listOf(71, 80, 10, 0), live.batters[1].let { listOf(it.runs, it.balls, it.fours, it.sixes) })
        assertEquals("88.75", live.batters[1].strikeRate)

        assertEquals(listOf("Shoriful Islam", "Rishad Hossain"), live.bowlers.map { it.name })
        assertEquals(listOf("5.5", "1", "30", "0", "5.14"), live.bowlers[0].let { listOf(it.overs, "${it.maidens}", "${it.conceded}", "${it.wickets}", it.economy) })
        assertEquals(listOf("5", "0", "31", "0", "6.20"), live.bowlers[1].let { listOf(it.overs, "${it.maidens}", "${it.conceded}", "${it.wickets}", it.economy) })

        assertEquals("4.92", live.runRate)
        assertEquals("", live.requiredRate)
        assertEquals("34/0 (6.80)", live.recent)
        assertEquals(5, live.recentOvers)
        assertEquals(listOf("1", "1", "0", "1", "0"), live.thisOver)
    }

    @Test
    fun `the panel takes its figures from the scorecard when the commentary lags a ball`() {
        val body = fixture("cricket_summary_live_panel.json")
            // Make the per-player stats one ball ahead of the commentary, as seen live.
            .replace(Regex("(\"name\": ?\"runs\"[^}]*?\"displayValue\": ?\")71(\")"), "$172$2")
        val card = parseScorecard(body)
        val taylorCard = card.innings.last().batting.first { it.name == "Brendan Taylor" }.runs
        // Guard: without this the test passes on an unchanged fixture and proves nothing.
        assertEquals("72", taylorCard)
        assertEquals(72, card.live!!.batters.first { it.name == "Brendan Taylor" }.runs)
    }

    @Test
    fun `overs convert to balls`() {
        assertEquals(34, com.pandiyarajk.sportshub.data.cricket.oversToBalls("5.4"))
        assertEquals(30, com.pandiyarajk.sportshub.data.cricket.oversToBalls("5"))
        assertEquals(null, com.pandiyarajk.sportshub.data.cricket.oversToBalls("abc"))
    }

    @Test
    fun `rates are cut to two places, not rounded, and never use a decimal comma`() {
        assertEquals("75.40", com.pandiyarajk.sportshub.data.cricket.twoPlaces(46 * 100.0 / 61))
        assertEquals("0.29", com.pandiyarajk.sportshub.data.cricket.twoPlaces(0.29))
        assertEquals("6.20", com.pandiyarajk.sportshub.data.cricket.twoPlaces(6.2))
    }

    @Test
    fun `a summary without ball-by-ball has no live panel but keeps its scorecard`() {
        val card = parseScorecard(fixture("cricket_summary.json"))
        assertEquals(null, card.live)
        assertTrue(card.innings.isNotEmpty())
    }

    @Test
    fun `a summary with no scorecard is an empty scorecard, not a failure`() {
        assertTrue(parseScorecard("{}").innings.isEmpty())
    }
}

class CricketRepositoryTest {
    @Test
    fun `a failed fetch becomes a failed load with the message intact`() = runBlocking {
        val repo = CricketRepository { throw com.pandiyarajk.sportshub.data.ApiException("No connection.") }
        val result = repo.matches()
        assertTrue(result is Load.Failed)
        assertEquals("No connection.", (result as Load.Failed).message)
    }

    @Test
    fun `garbage from the server is a failed load, never a crash`() = runBlocking {
        val repo = CricketRepository { "<html>not json</html>" }
        assertTrue(repo.matches() is Load.Failed)
    }

    @Test
    fun `a finished scorecard is fetched once, a live one every time`() = runBlocking {
        var calls = 0
        val repo = CricketRepository {
            calls++
            fixture("cricket_summary.json")
        }
        val done = match("done", "L", MatchState.RESULT, listOf(team("A", "A")))
        repo.scorecard(done)
        repo.scorecard(done)
        assertEquals(1, calls)
        val live = match("live", "L", MatchState.LIVE, listOf(team("B", "B")))
        repo.scorecard(live)
        repo.scorecard(live)
        assertEquals(3, calls)
    }

    @Test
    fun `an empty feed is Empty, not Ok with nothing`() = runBlocking {
        val repo = CricketRepository { "{}" }
        assertTrue(repo.matches() is Load.Empty)
    }
}
