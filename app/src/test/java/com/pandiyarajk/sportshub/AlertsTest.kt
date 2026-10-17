// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub

import com.pandiyarajk.sportshub.alerts.AlertScheduler
import com.pandiyarajk.sportshub.data.AppSettings
import com.pandiyarajk.sportshub.data.alerts.AlertEngine
import com.pandiyarajk.sportshub.data.alerts.AlertKind
import com.pandiyarajk.sportshub.data.alerts.Batting
import com.pandiyarajk.sportshub.data.alerts.CricketLive
import com.pandiyarajk.sportshub.data.alerts.FootballLive
import com.pandiyarajk.sportshub.data.alerts.FootballSide
import com.pandiyarajk.sportshub.data.alerts.Goal
import com.pandiyarajk.sportshub.data.alerts.parseCricketLive
import com.pandiyarajk.sportshub.data.alerts.parseFootballLive
import com.pandiyarajk.sportshub.data.cricket.MatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LiveParseTest {
    @Test
    fun `the live league scoreboard gives the chasing side's runs, wickets and completed overs`() {
        // Captured live: CAN 256 all out, NEP 167/4 after 35.5 overs chasing 257.
        val m = parseCricketLive(fixture("cricket_league_live.json")).single()
        assertEquals("1554419", m.matchId)
        assertEquals("CAN v NEP", m.title)
        assertEquals(MatchState.LIVE, m.state)
        assertEquals("Nepal require 90 runs", m.summary)
        val b = m.batting!!
        assertEquals("NEP", b.team)
        assertEquals(2, b.innings)
        assertEquals(167, b.runs)
        assertEquals(4, b.wickets)
        assertEquals(35, b.oversDone)
        assertEquals("35.5", b.oversText)
    }

    @Test
    fun `the live EPL day gives score, clock and each goal's scorer and minute`() {
        // Captured live: Everton 0-2 Chelsea at 90'+6', both goals Pedro Neto.
        val m = parseFootballLive(fixture("epl_day_live.json")).first { it.matchId == "401878771" }
        assertEquals("in", m.state)
        assertEquals("EVE 0-2 CHE", m.scoreLine)
        assertEquals(listOf("46'", "90'"), m.goals.map { it.minute })
        assertTrue(m.goals.all { it.scorer == "Pedro Neto" && it.teamId == "363" })
    }

    @Test
    fun `a rain delay wins over the stale summary, in the league board and in the header`() {
        // Captured live Oct-17-2026 19:20 IST: IND v WI, "Match delayed by rain" (type 101) while
        // both summary fields still said "Starts at 19:00 local time".
        val live = parseCricketLive(fixture("cricket_league_rain.json")).single()
        assertEquals(MatchState.LIVE, live.state)
        assertEquals("Match delayed by rain", live.interruption)
        assertEquals("Match delayed by rain", live.summary)
        assertEquals(null, live.batting)

        val card = com.pandiyarajk.sportshub.data.cricket.parseCricketHeader(fixture("cricket_header_rain.json"))
            .first { it.id == "1529234" }
        assertEquals("Match delayed by rain", card.status)
    }

    @Test
    fun `routine states are not interruptions`() {
        listOf("Live", "Result", "Scheduled", "", "  live ").forEach {
            assertEquals("", com.pandiyarajk.sportshub.data.cricket.interruption(it))
        }
        assertEquals("Stumps", com.pandiyarajk.sportshub.data.cricket.interruption("Stumps"))
    }

    @Test
    fun `the football day url is the US Eastern date ESPN files matches under`() {
        assertTrue(AlertScheduler.footballDayUrl(LocalDate.of(2026, 10, 17)).endsWith("dates=20261017"))
    }
}

class CricketAlertTest {
    private fun snap(
        runs: Int,
        wickets: Int,
        overs: Int,
        state: MatchState = MatchState.LIVE,
        team: String = "IND",
        innings: Int = 1,
        summary: String = "",
    ) = CricketLive(
        matchId = "m1", title = "IND v WI", state = state, summary = summary,
        batting = Batting(team, innings, runs, wickets, overs, "$overs"), startMillis = null,
    )

    @Test
    fun `first sight of a live match says it is live and replays nothing`() {
        val alerts = AlertEngine.cricket(null, snap(120, 3, 14), everyOver = true)
        assertEquals(listOf(AlertKind.STARTED), alerts.map { it.kind })
        assertTrue(alerts.single().text.startsWith("IND 120/3"))
    }

    @Test
    fun `an over ending gives one update with the runs scored in it`() {
        val alert = AlertEngine.cricket(snap(40, 1, 5), snap(49, 1, 6), everyOver = true).single()
        assertEquals(AlertKind.OVER, alert.kind)
        assertEquals("End of over 6: IND 49/1 (6 ov)", alert.title)
        assertTrue(alert.text.startsWith("9 runs"))
    }

    @Test
    fun `no change in the over gives no alert`() {
        assertTrue(AlertEngine.cricket(snap(40, 1, 5), snap(43, 1, 5), everyOver = true).isEmpty())
    }

    @Test
    fun `with every over off an over ending is silent`() {
        assertTrue(AlertEngine.cricket(snap(40, 1, 5), snap(49, 1, 6), everyOver = false).isEmpty())
    }

    @Test
    fun `a wicket alerts even with every over off, and replaces that over's update`() {
        val alerts = AlertEngine.cricket(snap(40, 1, 5), snap(41, 2, 6), everyOver = false)
        assertEquals(listOf(AlertKind.WICKET), alerts.map { it.kind })
        assertTrue(alerts.single().title.startsWith("WICKET! IND 41/2"))
        assertEquals(listOf(AlertKind.WICKET), AlertEngine.cricket(snap(40, 1, 5), snap(41, 2, 6), everyOver = true).map { it.kind })
    }

    @Test
    fun `two wickets between polls say so`() {
        val alert = AlertEngine.cricket(snap(40, 1, 5), snap(40, 3, 5), everyOver = true).single()
        assertTrue(alert.title.startsWith("2 WICKETS!"))
        assertTrue(alert.kind.important)
    }

    @Test
    fun `several overs between polls are named as a range, not as one over`() {
        val alert = AlertEngine.cricket(snap(40, 1, 5), snap(60, 1, 8), everyOver = true).single()
        assertTrue(alert.title.startsWith("Overs 6-8"))
    }

    @Test
    fun `the other side starting to bat is a new innings, not a wicket or an over`() {
        val alerts = AlertEngine.cricket(snap(180, 7, 20), snap(4, 0, 1, team = "WI", innings = 2), everyOver = true)
        assertEquals(listOf(AlertKind.INNINGS), alerts.map { it.kind })
    }

    @Test
    fun `rain stopping play alerts, and play resuming alerts again`() {
        val delayed = snap(0, 0, 0).copy(batting = null, interruption = "Match delayed by rain", summary = "Match delayed by rain")
        val waiting = snap(0, 0, 0).copy(batting = null)
        val stop = AlertEngine.cricket(waiting, delayed, everyOver = true).single()
        assertEquals(AlertKind.INTERRUPTION, stop.kind)
        assertEquals("IND v WI: Match delayed by rain", stop.title)
        assertEquals("No ball bowled yet", stop.text)
        assertTrue(AlertEngine.cricket(delayed, delayed, everyOver = true).isEmpty())

        val resumed = AlertEngine.cricket(delayed, snap(0, 0, 0), everyOver = true).single()
        assertEquals("IND v WI: play under way", resumed.title)
    }

    @Test
    fun `first sight of a rain-delayed match says so, not the stale start time`() {
        val delayed = snap(0, 0, 0).copy(batting = null, interruption = "Match delayed by rain", summary = "Match delayed by rain")
        assertEquals("Match delayed by rain", AlertEngine.cricket(null, delayed, everyOver = true).single().text)
    }

    @Test
    fun `the result alerts once, with the feed's own words`() {
        val done = snap(181, 5, 19, state = MatchState.RESULT, summary = "India won by 5 wickets")
        val alert = AlertEngine.cricket(snap(175, 5, 19), done, everyOver = true).single()
        assertEquals(AlertKind.RESULT, alert.kind)
        assertEquals("India won by 5 wickets", alert.text)
        assertTrue(AlertEngine.cricket(done, done, everyOver = true).isEmpty())
        assertTrue(AlertEngine.cricket(null, done, everyOver = true).isEmpty())
    }
}

class FootballAlertTest {
    private fun snap(
        home: Int,
        away: Int,
        state: String = "in",
        detail: String = "30'",
        goals: List<Goal> = emptyList(),
    ) = FootballLive(
        matchId = "f1", home = FootballSide("1", "ARS", home), away = FootballSide("2", "CHE", away),
        state = state, detail = detail, goals = goals, startMillis = null,
    )

    private val palmer = Goal("23'", "Cole Palmer", "2", ownGoal = false, penalty = true)

    @Test
    fun `kick-off, goal with scorer, half-time and full-time in order`() {
        assertEquals(listOf(AlertKind.KICK_OFF), AlertEngine.football(snap(0, 0, "pre", "Sat 3:00 PM"), snap(0, 0, detail = "1'")).map { it.kind })

        val goal = AlertEngine.football(snap(0, 0), snap(0, 1, goals = listOf(palmer))).single()
        assertEquals(AlertKind.GOAL, goal.kind)
        assertEquals("GOAL! ARS 0-1 CHE", goal.title)
        assertEquals("Cole Palmer (pen) 23'", goal.text)

        assertEquals(listOf(AlertKind.HALF_TIME), AlertEngine.football(snap(0, 1, goals = listOf(palmer)), snap(0, 1, detail = "HT", goals = listOf(palmer))).map { it.kind })
        val ft = AlertEngine.football(snap(0, 1, detail = "90'+4'"), snap(0, 1, "post", "FT"))
        assertEquals(listOf(AlertKind.FULL_TIME), ft.map { it.kind })
    }

    @Test
    fun `a score that goes down is a disallowed goal, not a goal`() {
        assertEquals(listOf(AlertKind.DISALLOWED), AlertEngine.football(snap(1, 0), snap(0, 0)).map { it.kind })
    }

    @Test
    fun `nothing changing gives nothing, and a finished match first seen gives nothing`() {
        assertTrue(AlertEngine.football(snap(1, 0), snap(1, 0, detail = "31'")).isEmpty())
        assertTrue(AlertEngine.football(null, snap(2, 1, "post", "FT")).isEmpty())
        assertTrue(AlertEngine.football(null, snap(0, 0, "pre", "Sat")).isEmpty())
    }

    @Test
    fun `a goal with no scorer in the feed still alerts`() {
        val goal = AlertEngine.football(snap(0, 0), snap(1, 0)).single()
        assertEquals(AlertKind.GOAL, goal.kind)
        assertEquals("30'", goal.text)
    }
}

class AlertFilterTest {
    @Test
    fun `club filter follows every match until favourite-only is on with a favourite set`() {
        val all = AppSettings(footballAlerts = true)
        assertTrue(AlertScheduler.followsClub(all, "1", "2"))
        val favOnly = AppSettings(footballAlerts = true, footballFavouriteOnly = true, favouriteClub = "359")
        assertTrue(AlertScheduler.followsClub(favOnly, "359", "2"))
        assertTrue(AlertScheduler.followsClub(favOnly, "2", "359"))
        assertFalse(AlertScheduler.followsClub(favOnly, "1", "2"))
        // Favourite-only with no favourite chosen would follow nothing; it follows everything instead.
        assertTrue(AlertScheduler.followsClub(AppSettings(footballFavouriteOnly = true), "1", "2"))
    }
}
