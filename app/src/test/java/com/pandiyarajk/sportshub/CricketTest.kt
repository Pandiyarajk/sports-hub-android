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
    fun `international matches sit above club leagues, even a live club match`() {
        val clubLive = match("club", "Big Bash League", MatchState.LIVE, listOf(team("Sixers", "SYS"), team("Heat", "BRH")))
        val intlResult = match("intl", "ICC Men's T20 World Cup", MatchState.RESULT, listOf(team("Canada", "CAN"), team("Nepal", "NEP")))
        val intlUpcoming = match("soon", "Zimbabwe tour of Bangladesh", MatchState.UPCOMING, listOf(team("Zimbabwe", "ZIM"), team("Bangladesh", "BAN")))
        assertEquals(listOf("soon", "intl", "club"), IndiaFilter.order(listOf(clubLive, intlResult, intlUpcoming), indiaFirst = false).map { it.id })
        assertFalse(clubLive.isInternational)
        assertTrue(match("wtc", "World Test Championship", MatchState.LIVE, emptyList()).isInternational)
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
    fun `a catch names the fielder and the bowler, not just c`() {
        // Captured live Oct-17-2026: the roster said only "c" for Abhishek Sharma.
        val wicket = com.pandiyarajk.sportshub.data.cricket.wicketBalls(fixture("cricket_summary_wicket.json")).single()
        assertEquals("Abhishek Sharma", wicket.batter)
        assertEquals("c Joseph b Paul", wicket.how)
        assertEquals("Abhishek Sharma c Joseph b Paul 6 (4b 0x4 1x6)", wicket.line)

        val row = parseScorecard(fixture("cricket_summary_wicket.json")).innings.single()
            .batting.first { it.name == "Abhishek Sharma" }
        assertEquals("c Joseph b Paul", row.dismissal)
    }

    @Test
    fun `a dismissal seen once is kept after it leaves the commentary window`() {
        // Same match later: the commentary no longer has the wicket ball.
        val body = fixture("cricket_summary_wicket.json").replace("\"dismissal\": true", "\"dismissal\": false")
        val card = parseScorecard(body, known = mapOf("Abhishek Sharma" to "c Joseph b Paul"))
        assertEquals("c Joseph b Paul", card.innings.single().batting.first { it.name == "Abhishek Sharma" }.dismissal)
        // And with nothing remembered, the code in words rather than a bare letter.
        val cold = parseScorecard(body)
        assertEquals("caught", cold.innings.single().batting.first { it.name == "Abhishek Sharma" }.dismissal)
    }

    @Test
    fun `the wicket alert names batter, catcher and bowler in words`() {
        // Live, Oct-17-2026: Abhishek Sharma c Joseph b Paul 6 (4b), bowled by Keemo Paul.
        val w = com.pandiyarajk.sportshub.data.cricket.wicketBalls(fixture("cricket_summary_wicket.json")).single()
        assertEquals("Keemo Paul", w.bowler)
        assertEquals("Abhishek Sharma 6 (4) caught by Joseph, bowled by Keemo Paul", w.alertText)
    }

    @Test
    fun `the dismissal is found even when the line uses a different name for the batter`() {
        // Live, Oct-17-2026: "SV Samson b Springer 16 (7b 2x4 1x6) SR: 228.57" for Sanju Samson.
        fun ball(batter: String, text: String) = """{"commentary":{"pageCount":1,"items":[{"sequence":1,
            "dismissal":{"dismissal":true,"text":"$text","batsman":{"athlete":{"displayName":"$batter"}}},
            "bowler":{"athlete":{"displayName":"Shamar Springer"}}}]}}"""
        val samson = com.pandiyarajk.sportshub.data.cricket.parsePlayByPlayPage(
            ball("Sanju Samson", "SV Samson b Springer 16 (7b 2x4 1x6) SR: 228.57"),
        ).first.single()
        assertEquals("Sanju Samson", samson.batter)
        assertEquals("b Springer", samson.how)
        assertEquals("Sanju Samson 16 (7) bowled by Shamar Springer", samson.alertText)

        val green = com.pandiyarajk.sportshub.data.cricket.parsePlayByPlayPage(
            ball("Cameron Green", "C Green c Smith b Starc 4 (6b 1x4 0x6) SR: 66.66"),
        ).first.single()
        assertEquals("c Smith b Starc", green.how)

        val runOut = com.pandiyarajk.sportshub.data.cricket.parsePlayByPlayPage(
            ball("Tilak Varma", "NT Tilak Varma run out (Joseph/†Hope) 2 (3b 0x4 0x6) SR: 66.66"),
        ).first.single()
        assertEquals("run out (Joseph/†Hope)", runOut.how)
        assertEquals("Tilak Varma 2 (3) run out by Joseph and Hope", runOut.alertText)
    }

    @Test
    fun `every kind of dismissal reads in words`() {
        fun d(how: String, bowler: String = "") = com.pandiyarajk.sportshub.data.cricket.describeDismissal(how, bowler)
        assertEquals("caught by Joseph, bowled by Keemo Paul", d("c Joseph b Paul", "Keemo Paul"))
        assertEquals("caught by Hope (keeper), bowled by Holder", d("c †Hope b Holder"))
        assertEquals("caught and bowled by Roston Chase", d("c & b Chase", "Roston Chase"))
        assertEquals("caught and bowled by Chase", d("c and b Chase"))
        assertEquals("stumped by Hope, bowled by Akeal Hosein", d("st †Hope b Hosein", "Akeal Hosein"))
        assertEquals("run out by Joseph", d("run out (Joseph)"))
        assertEquals("run out by Joseph and Hope", d("run out (Joseph/†Hope)"))
        assertEquals("run out", d("run out"))
        assertEquals("lbw, bowled by Holder", d("lbw b Holder"))
        assertEquals("bowled by Jason Holder", d("b Holder", "Jason Holder"))
        assertEquals("hit wicket, bowled by Holder", d("hit wicket b Holder"))
        assertEquals("retired hurt", d("retired hurt"))
    }

    @Test
    fun `not out stays not out and unknown codes stay as given`() {
        assertEquals("not out", com.pandiyarajk.sportshub.data.cricket.fullDismissal("A", "not out", mapOf("A" to "c X b Y")))
        assertEquals("obstructing the field", com.pandiyarajk.sportshub.data.cricket.fullDismissal("A", "obstructing the field", emptyMap()))
        assertEquals("stumped", com.pandiyarajk.sportshub.data.cricket.fullDismissal("A", "st", emptyMap()))
    }

    @Test
    fun `a chase shows both innings, with the first one's real total, not stale matchcards`() {
        // Live, Oct-17-2026: BAN 37/0 chasing 272. Matchcards held only ZIM, frozen at 236/8 (46.3).
        val card = parseScorecard(fixture("cricket_summary_chase.json"))
        assertEquals(listOf(1, 2), card.innings.map { it.number })
        val zim = card.innings[0]
        assertEquals("Zimbabwe", zim.team)
        assertEquals("271/8", zim.runs)
        assertEquals("(50 ov)", zim.total.replace(".0", ""))
        assertEquals("7 (lb 2, w 5)", zim.extras)
        val ban = card.innings[1]
        assertEquals("Bangladesh", ban.team)
        assertTrue(ban.batting.isNotEmpty())
        assertTrue(ban.extras.startsWith("8 ("))
        assertTrue(card.live!!.requiredRate.isNotBlank())
    }

    @Test
    fun `a chase line gets the balls left from the feed's own score string`() {
        fun c(summary: String, score: String) = com.pandiyarajk.sportshub.data.cricket.chaseLine(summary, score)
        assertEquals(
            "Bangladesh require 235 runs from 268 balls",
            c("Bangladesh require 235 runs", "37/0 (5.2/50 ov, target 272)"),
        )
        // Rain-reduced to 9 overs.
        assertEquals("India need 20 runs from 9 balls", c("India need 20 runs", "70/3 (7.3/9 ov, target 90)"))
        assertEquals("West Indies won toss & fielded", c("West Indies won toss & fielded", "37/0 (5.2/50 ov, target 272)"))
        assertEquals("Need 5 from 3 balls", c("Need 5 from 3 balls", "1/0 (0.3/1 ov, target 6)"))
        assertEquals("Bangladesh require 235 runs", c("Bangladesh require 235 runs", "37/0 (5.2/50 ov)"))
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
        val repo = CricketRepository { req ->
            // Summary fetches only: a play-by-play lookup for unresolved dismissals is separate.
            if ("summary" in req.url.toString()) calls++
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
    fun `a wicket gone from the commentary window is filled from the play-by-play, once`() = runBlocking {
        // Live, Oct-17-2026: after a restart, Abhishek Sharma read only "caught".
        val summaryWithoutWicketBall = fixture("cricket_summary_wicket.json").replace("\"dismissal\": true", "\"dismissal\": false")
        var pages = 0
        val repo = CricketRepository { req ->
            val url = req.url.toString()
            when {
                "playbyplay" in url -> { pages++; fixture("cricket_playbyplay_page1.json") }
                else -> summaryWithoutWicketBall
            }
        }
        val live = match("1529234", "West Indies tour of India", MatchState.LIVE, listOf(team("India", "IND")))
        val card = (repo.scorecard(live) as Load.Ok).value
        assertEquals("c Joseph b Paul", card.innings.single().batting.first { it.name == "Abhishek Sharma" }.dismissal)
        assertTrue(pages >= 1)
        val after = pages
        repo.scorecard(live)
        assertEquals("the gap is filled, so no more pages", after, pages)
    }

    @Test
    fun `the play-by-play page gives every wicket on it with the full text`() {
        val (wickets, pageCount) = com.pandiyarajk.sportshub.data.cricket.parsePlayByPlayPage(fixture("cricket_playbyplay_page1.json"))
        assertEquals(2, pageCount)
        assertEquals(listOf("Abhishek Sharma" to "c Joseph b Paul", "Ishan Kishan" to "c Powell b Joseph"), wickets.map { it.batter to it.how })
        assertEquals("Ishan Kishan 30 (10) caught by Powell, bowled by Shamar Joseph", wickets[1].alertText)
    }

    @Test
    fun `an empty feed is Empty, not Ok with nothing`() = runBlocking {
        val repo = CricketRepository { "{}" }
        assertTrue(repo.matches() is Load.Empty)
    }
}
