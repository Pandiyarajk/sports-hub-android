// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub

import com.pandiyarajk.sportshub.data.Http
import com.pandiyarajk.sportshub.data.Load
import com.pandiyarajk.sportshub.data.football.Fixture
import com.pandiyarajk.sportshub.data.football.FootballRepository
import com.pandiyarajk.sportshub.data.football.FormBuilder
import com.pandiyarajk.sportshub.data.football.Side
import com.pandiyarajk.sportshub.data.football.parseFixtures
import com.pandiyarajk.sportshub.data.football.parseLeaderRefs
import com.pandiyarajk.sportshub.data.football.parseStandings
import com.pandiyarajk.sportshub.ui.startDay
import com.pandiyarajk.sportshub.ui.startLabel
import com.pandiyarajk.sportshub.ui.startTime
import com.pandiyarajk.sportshub.ui.startsIn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class StandingsParseTest {
    private val table = parseStandings(fixture("epl_standings.json"))

    @Test
    fun `the captured standings yield twenty clubs in rank order`() {
        assertEquals(20, table.rows.size)
        assertEquals((1..20).toList(), table.rows.map { it.rank })
    }

    @Test
    fun `the leader row carries every table column`() {
        val city = table.rows.first()
        assertEquals("Manchester City", city.name)
        assertEquals(6, city.played)
        assertEquals(6, city.won)
        assertEquals(0, city.drawn)
        assertEquals(0, city.lost)
        assertEquals(16, city.goalsFor)
        assertEquals(5, city.goalsAgainst)
        assertEquals("+11", city.goalDiff)
        assertEquals(18, city.points)
        assertNotNull(city.logo)
    }

    @Test
    fun `the season label and year come from the payload`() {
        assertEquals("2026-27 English Premier League", table.season)
        assertEquals(2026, table.seasonYear)
    }

    @Test
    fun `a payload with no entries is an empty table, not a crash`() {
        assertTrue(parseStandings("{}").rows.isEmpty())
    }
}

class FixtureParseTest {
    private val fixtures = parseFixtures(fixture("epl_scoreboard_202609.json"))

    @Test
    fun `the captured month yields home and away sides with scores`() {
        assertEquals(10, fixtures.size)
        val f = fixtures.first { it.finished }
        assertTrue(f.home.score.toIntOrNull() != null && f.away.score.toIntOrNull() != null)
        assertTrue(f.home.name.isNotBlank() && f.away.name.isNotBlank())
    }

    @Test
    fun `an event with no competition is skipped rather than failing the month`() {
        assertEquals(0, parseFixtures("""{"events":[{"id":"1"}]}""").size)
    }
}

class LiveMatchCardTest {
    @Test
    fun `the day board gives the cards live state, short names and each goal`() {
        // Captured live: Everton 0-2 Chelsea at 90'+6', both goals Pedro Neto (46', 90').
        val f = parseFixtures(fixture("epl_day_live.json")).first { it.id == "401878771" }
        assertTrue(f.live)
        assertEquals("90'+6'", f.detail)
        assertEquals("Everton", f.home.shortName.ifBlank { f.home.name }.substringBefore(" "))
        assertEquals(listOf("46'", "90'"), f.goals.map { it.minute })
        assertTrue(f.goals.all { it.scorer == "Pedro Neto" && it.teamId == f.away.teamId })
    }

    @Test
    fun `the match summary gives a timeline without delay noise, stats and formations`() {
        // Captured live: Brentford 0-0 Liverpool at 34'.
        val d = com.pandiyarajk.sportshub.data.football.parseFootballSummary(fixture("epl_match_live.json"))
        assertFalse(d.events.any { it.type in setOf("Kickoff", "Start Delay", "End Delay") })
        val card = d.events.first { it.type == "Yellow Card" }
        assertEquals("31'", card.minute)
        assertEquals("Bradley Barcola", card.text)
        val sub = d.events.first { it.type == "Substitution" }
        assertEquals("On: Trey Nyoni, off: Dominik Szoboszlai", sub.text)

        val possession = d.stats.first { it.label == "Possession %" }
        assertEquals("45.8", possession.home)
        assertEquals("54.2", possession.away)
        assertEquals("3", d.stats.first { it.label == "Shots" }.home)
        assertEquals("4-2-3-1", d.homeFormation)
        assertEquals("4-2-3-1", d.awayFormation)
    }

    @Test
    fun `a goal event names the scorer and the assist`() {
        val body = """{"keyEvents":[{"type":{"text":"Goal"},"scoringPlay":true,"clock":{"displayValue":"57'"},
            "team":{"id":"364"},"participants":[{"athlete":{"displayName":"Alexander Isak"}},
            {"athlete":{"displayName":"Mohamed Salah"}}]}]}"""
        val e = com.pandiyarajk.sportshub.data.football.parseFootballSummary(body).events.single()
        assertTrue(e.isGoal)
        assertEquals("Alexander Isak (assist Mohamed Salah)", e.text)
    }
}

class FormBuilderTest {
    private fun fx(id: String, date: String, home: String, hs: Int, away: String, a: Int) = Fixture(
        id = id, dateIso = date, finished = true, live = false, detail = "FT",
        home = Side(home, home, home, null, hs.toString()),
        away = Side(away, away, away, null, a.toString()),
    )

    @Test
    fun `results are taken in date order, oldest first, whatever order they arrive in`() {
        val form = FormBuilder.build(
            listOf(
                fx("3", "2026-09-27T14:00Z", "A", 0, "B", 0),
                fx("1", "2026-09-13T14:00Z", "A", 2, "B", 1),
                fx("2", "2026-09-20T14:00Z", "C", 3, "A", 1),
            ),
        )
        assertEquals("WLD", form["A"])
        assertEquals("LD", form["B"])
        assertEquals("W", form["C"])
    }

    @Test
    fun `only the last five count and early in a season it is not padded`() {
        val many = (1..7).map { fx("$it", "2026-09-%02dT14:00Z".format(it), "A", 1, "B", 0) }
        assertEquals("WWWWW", FormBuilder.build(many)["A"])
        assertEquals("LLLLL", FormBuilder.build(many)["B"])
        assertEquals("W", FormBuilder.build(many.take(1))["A"])
    }

    @Test
    fun `unfinished fixtures and unscored ones do not count`() {
        val upcoming = fx("1", "2026-09-13T14:00Z", "A", 0, "B", 0).copy(finished = false)
        val noScore = fx("2", "2026-09-14T14:00Z", "A", 0, "B", 0)
            .copy(home = Side("A", "A", "A", null, ""), away = Side("B", "B", "B", null, ""))
        assertTrue(FormBuilder.build(listOf(upcoming, noScore)).isEmpty())
    }

    @Test
    fun `the captured month produces a form string of only W, D and L`() {
        val form = FormBuilder.build(parseFixtures(fixture("epl_scoreboard_202609.json")))
        assertTrue(form.isNotEmpty())
        assertTrue(form.values.all { s -> s.all { it in "WDL" } && s.length in 1..5 })
    }
}

class LeadersTest {
    @Test
    fun `the top five goal scorers come out with their athlete and team references`() {
        val refs = parseLeaderRefs(fixture("epl_leaders.json"), "goals")
        assertEquals(5, refs.size)
        assertEquals("6", refs.first().value)
        assertTrue(refs.first().athleteUrl.startsWith("https://"))
        assertEquals("382", refs.first().teamId)
    }

    @Test
    fun `an unknown category is an empty list`() {
        assertTrue(parseLeaderRefs(fixture("epl_leaders.json"), "nope").isEmpty())
    }
}

class FootballRepositoryTest {
    private fun fakeFetch(failLeaders: Boolean): suspend (okhttp3.Request) -> String = { req ->
        val url = req.url.toString()
        when {
            "standings" in url -> fixture("epl_standings.json")
            "scoreboard" in url -> fixture("epl_scoreboard_202609.json")
            "leaders" in url -> if (failLeaders) throw java.io.IOException("down") else fixture("epl_leaders.json")
            "athletes" in url -> """{"displayName":"Erling Haaland"}"""
            else -> error("unexpected url $url")
        }
    }

    @Test
    fun `table, fixtures, form and leaders arrive together`() = runBlocking {
        val repo = FootballRepository(fakeFetch(failLeaders = false)) { YearMonth.of(2026, 9) }
        val data = (repo.load() as Load.Ok).value
        assertEquals(20, data.standings.rows.size)
        assertTrue(data.fixtures.isNotEmpty())
        assertTrue(data.form.isNotEmpty())
        assertEquals(5, data.leaders!!.goals.size)
        assertEquals("Erling Haaland", data.leaders!!.goals.first().name)
        assertEquals("Manchester City", data.leaders!!.goals.first().teamName)
    }

    @Test
    fun `a failed leaders lookup hides the card but the table still renders`() = runBlocking {
        val repo = FootballRepository(fakeFetch(failLeaders = true)) { YearMonth.of(2026, 9) }
        val data = (repo.load() as Load.Ok).value
        assertNull(data.leaders)
        assertEquals(20, data.standings.rows.size)
    }

    @Test
    fun `a failed month does not fail the table`() = runBlocking {
        val base = fakeFetch(failLeaders = true)
        val repo = FootballRepository({ req ->
            if ("scoreboard" in req.url.toString()) throw java.io.IOException("down") else base(req)
        }) { YearMonth.of(2026, 9) }
        val data = (repo.load() as Load.Ok).value
        assertEquals(20, data.standings.rows.size)
        assertTrue(data.fixtures.isEmpty())
    }

    @Test
    fun `a month that fails keeps its last good fixtures instead of dropping them`() = runBlocking {
        var monthDown = false
        val base = fakeFetch(failLeaders = true)
        val repo = FootballRepository({ req ->
            if (monthDown && "scoreboard" in req.url.toString()) throw java.io.IOException("down") else base(req)
        }) { YearMonth.of(2026, 9) }
        val before = (repo.load() as Load.Ok).value
        monthDown = true
        val after = (repo.load() as Load.Ok).value
        assertEquals(before.fixtures.map { it.id }.toSet(), after.fixtures.map { it.id }.toSet())
        assertEquals(before.form, after.form)
    }

    @Test
    fun `form looks two months back as well as this one and the next`() = runBlocking {
        val asked = mutableListOf<String>()
        val base = fakeFetch(failLeaders = true)
        FootballRepository({ req -> asked += req.url.toString(); base(req) }) { YearMonth.of(2026, 10) }.load()
        val months = asked.filter { "scoreboard" in it }.map { it.substringAfter("dates=") }
        assertEquals(listOf("202608", "202609", "202610", "202611"), months)
    }

    @Test
    fun `a failed leaders lookup is not retried on the next refresh`() = runBlocking {
        var leaderCalls = 0
        val base = fakeFetch(failLeaders = true)
        val repo = FootballRepository({ req ->
            if ("leaders" in req.url.toString()) leaderCalls++
            base(req)
        }) { YearMonth.of(2026, 9) }
        repo.load()
        repo.load()
        assertEquals(1, leaderCalls)
    }

    @Test
    fun `a player in both leader lists is looked up once`() = runBlocking {
        // The captured top fives share no player, so this payload makes one appear in both.
        val ref = "http://sports.core.api.espn.com/v2/sports/soccer/leagues/eng.1/seasons/2026/athletes/9"
        val team = "http://sports.core.api.espn.com/v2/sports/soccer/leagues/eng.1/seasons/2026/teams/382?lang=en"
        fun entry(v: String) = """{"displayValue":"$v","athlete":{"${'$'}ref":"$ref"},"team":{"${'$'}ref":"$team"}}"""
        val leadersBody = """{"categories":[{"name":"goals","leaders":[${entry("6")}]},""" +
            """{"name":"assists","leaders":[${entry("3")}]}]}"""
        val athleteCalls = mutableListOf<String>()
        val base = fakeFetch(failLeaders = false)
        val repo = FootballRepository({ req ->
            val url = req.url.toString()
            when {
                "leaders" in url -> leadersBody
                "athletes" in url -> { athleteCalls += url; base(req) }
                else -> base(req)
            }
        }) { YearMonth.of(2026, 9) }
        val leaders = (repo.load() as Load.Ok).value.leaders!!
        assertEquals(1, athleteCalls.size)
        assertEquals("Erling Haaland", leaders.goals.single().name)
        assertEquals("Erling Haaland", leaders.assists.single().name)
    }

    @Test
    fun `an unreachable table is a failed load`() = runBlocking {
        val repo = FootballRepository({ throw java.io.IOException("down") }) { YearMonth.of(2026, 9) }
        assertTrue(repo.load() is Load.Failed)
    }

    @Test
    fun `the scoreboard url asks for one month, never a range`() {
        val url = FootballRepository.scoreboardUrl(YearMonth.of(2026, 9))
        assertTrue(url.endsWith("dates=202609"))
        assertFalse("-" in url.substringAfter("dates="))
    }
}

class HttpPolicyTest {
    @Test
    fun `429 and 5xx retry, other 4xx do not`() {
        assertTrue(Http.isRetryable(429))
        assertTrue(Http.isRetryable(500))
        assertTrue(Http.isRetryable(503))
        assertFalse(Http.isRetryable(404))
        assertFalse(Http.isRetryable(403))
        assertFalse(Http.isRetryable(400))
    }

    @Test
    fun `backoff doubles per attempt and is capped`() {
        assertEquals(800L, Http.backoffMillis(1, jitter = 0))
        assertEquals(1_600L, Http.backoffMillis(2, jitter = 0))
        assertEquals(3_200L, Http.backoffMillis(3, jitter = 0))
        assertEquals(Http.MAX_BACKOFF_MILLIS, Http.backoffMillis(10, jitter = 0))
    }

    @Test
    fun `a server Retry-After wins over the computed backoff, up to the cap`() {
        assertEquals(5_000L, Http.backoffMillis(1, retryAfterMillis = 5_000, jitter = 0))
        assertEquals(Http.MAX_BACKOFF_MILLIS, Http.backoffMillis(1, retryAfterMillis = 600_000, jitter = 0))
    }
}

class StartLabelTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 10, 17, 12, 0, 0, 0, zone)

    @Test
    fun `today and tomorrow are named, later dates are spelled out`() {
        assertTrue(startLabel("2026-10-17T13:30:00Z", now).startsWith("Today"))
        assertTrue(startLabel("2026-10-18T13:30:00Z", now).startsWith("Tomorrow"))
        assertFalse(startLabel("2026-10-25T13:30:00Z", now).startsWith("Today"))
    }

    @Test
    fun `an unparseable date is blank, not a crash`() {
        assertEquals("", startLabel("soon", now))
        assertEquals("", startsIn("soon", now))
        assertEquals("", startDay("soon", now))
    }

    @Test
    fun `the countdown reads in hours and minutes within a day`() {
        // now is 12:00 IST = 06:30Z; the India match starts 13:30Z, seven hours later.
        assertEquals("Starts in 7 hr 0 min", startsIn("2026-10-17T13:30:00Z", now))
        assertEquals("Starts in 45 min", startsIn("2026-10-17T07:15:00Z", now))
        assertEquals("Starting soon", startsIn("2026-10-17T06:00:00Z", now))
        assertEquals("", startsIn("2026-10-20T13:30:00Z", now))
    }

    @Test
    fun `day and time split the start for the card's right-hand block`() {
        assertEquals("Today", startDay("2026-10-17T13:30:00Z", now))
        assertEquals("Tomorrow", startDay("2026-10-18T13:30:00Z", now))
        assertTrue(startTime("2026-10-17T13:30:00Z", now).startsWith("7:00"))
    }
}
