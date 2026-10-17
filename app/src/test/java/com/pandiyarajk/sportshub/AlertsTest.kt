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
        // The feed says "Nepal require 90 runs"; 35.5 of 50 overs bowled leaves 85 balls.
        assertEquals("Nepal require 90 runs from 85 balls", m.summary)
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
    fun `a reduced match shows its new over limit, as the feed writes it`() {
        // Captured live Oct-17-2026: IND v WI cut to 9 overs a side by rain.
        val b = parseCricketLive(fixture("cricket_league_reduced.json")).single().batting!!
        assertEquals("IND 38/1 (2/9 ov)", b.display)
        assertEquals(2, b.oversDone)
    }

    @Test
    fun `without a feed score string the display falls back to the numbers`() {
        assertEquals("IND 14/1 (1 ov)", Batting("IND", 1, 14, 1, 1, "1").display)
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
    fun `a wicket alerts even with every over off, and with it on the over update comes too`() {
        val alerts = AlertEngine.cricket(snap(40, 1, 5), snap(41, 2, 6), everyOver = false)
        assertEquals(listOf(AlertKind.WICKET), alerts.map { it.kind })
        assertTrue(alerts.single().title.startsWith("WICKET! IND 41/2"))
        // Wickets have their own notification now, so the over update no longer hides them.
        assertEquals(listOf(AlertKind.WICKET, AlertKind.OVER), AlertEngine.cricket(snap(40, 1, 5), snap(41, 2, 6), everyOver = true).map { it.kind })
    }

    @Test
    fun `batting second, every over alert is a chase update`() {
        // BAN v ZIM, Oct-17-2026: Bangladesh chasing 272.
        fun bat(runs: Int, wkts: Int, overs: String) = Batting(
            "BAN", 2, runs, wkts, overs.substringBefore('.').toInt(), overs, "$runs/$wkts ($overs/50 ov, target 272)",
        )
        fun live(b: Batting) = CricketLive("bz", "BAN v ZIM", MatchState.LIVE, "Bangladesh require ${272 - b.runs} runs", b, null)
        val over = AlertEngine.cricket(live(bat(46, 0, "6")), live(bat(52, 0, "7")), everyOver = true).single()
        assertEquals(AlertKind.OVER, over.kind)
        assertEquals("End of over 7: BAN 52/0 (7/50 ov, target 272)", over.title)
        assertEquals("6 runs in the over · Need 220 from 258 balls · RRR 5.11", over.text)

        // Mid-over previous poll: the current rate is labelled CRR beside the required one.
        val rated = AlertEngine.cricket(live(bat(48, 0, "6.3")), live(bat(52, 0, "7")), everyOver = true).single()
        assertTrue(rated.text.startsWith("CRR 7.42 · Need 220"))

        // A wicket in the chase carries the same line.
        val wicket = AlertEngine.cricket(live(bat(52, 0, "7")), live(bat(53, 1, "7.2")), everyOver = true).single()
        assertEquals(AlertKind.WICKET, wicket.kind)
        assertEquals("Need 219 from 256 balls · RRR 5.13", wicket.text)
    }

    @Test
    fun `no chase line batting first or once the target is reached`() {
        assertEquals(null, AlertEngine.chaseUpdate(Batting("ZIM", 1, 120, 2, 20, "20", "120/2 (20/50 ov)")))
        assertEquals(null, AlertEngine.chaseUpdate(Batting("BAN", 2, 272, 5, 45, "45", "272/5 (45/50 ov, target 272)")))
        // Rain-reduced chase: 9 overs a side.
        assertEquals("Need 20 from 9 balls · RRR 13.33", AlertEngine.chaseUpdate(Batting("WI", 2, 70, 3, 7, "7.3", "70/3 (7.3/9 ov, target 90)")))
    }

    @Test
    fun `the toss line is left out of overs and wickets once play has started`() {
        // Live, Oct-17-2026: the over 1 alert read "West Indies won toss & fielded".
        val toss = "West Indies won toss & fielded"
        val alerts = AlertEngine.cricket(snap(13, 0, 0, summary = toss), snap(14, 1, 1, summary = toss), everyOver = true)
        assertEquals(listOf(AlertKind.WICKET, AlertKind.OVER), alerts.map { it.kind })
        assertTrue(alerts.none { "toss" in it.text })
        // A chase line is real news and stays.
        val chase = AlertEngine.cricket(snap(40, 1, 5, summary = "India need 30 runs"), snap(49, 1, 6, summary = "India need 21 runs"), everyOver = true)
        assertTrue(chase.single().text.endsWith("India need 21 runs"))
        // At the start, the toss is the news.
        assertTrue(AlertEngine.cricket(null, snap(0, 0, 0, summary = toss), everyOver = true).single().text.contains("toss"))
        // Picked up mid-innings (live: 70/3 after 4.5 overs), it is not.
        val midInnings = AlertEngine.cricket(null, snap(70, 3, 4, summary = toss), everyOver = true).single()
        assertFalse(midInnings.text.contains("toss"))
    }

    @Test
    fun `a wicket in the same poll as play restarting after rain is not lost`() {
        // The live case, Oct-17-2026: rain delay over and a wicket down by the next poll.
        val delayed = snap(4, 0, 0).copy(interruption = "Match delayed by rain")
        val alerts = AlertEngine.cricket(delayed, snap(9, 1, 0), everyOver = true)
        assertEquals(listOf(AlertKind.INTERRUPTION, AlertKind.WICKET), alerts.map { it.kind })
    }

    @Test
    fun `wickets already down when an innings is first seen are alerted`() {
        val noBattingYet = snap(0, 0, 0).copy(batting = null)
        val alerts = AlertEngine.cricket(noBattingYet, snap(9, 1, 0), everyOver = true)
        assertEquals(listOf(AlertKind.INNINGS, AlertKind.WICKET), alerts.map { it.kind })
        assertTrue(alerts[1].title.startsWith("WICKET! IND 9/1"))
    }

    @Test
    fun `runs in the over are exact when its start is known, else the run rate`() {
        fun bat(runs: Int, overs: String) =
            Batting("IND", 1, runs, 1, overs.substringBefore('.').toInt(), overs)
        // Live, Oct-17-2026: 13/1 after 0.5, 14/1 after 1. The first over started at 0: 14 runs,
        // not the "1 run" the old difference-since-last-poll reported.
        assertEquals("14 runs in the over", AlertEngine.overLine(bat(13, "0.5"), bat(14, "1")))
        // The previous poll landed exactly on the boundary: exact.
        assertEquals("9 runs in the over", AlertEngine.overLine(bat(40, "5"), bat(49, "6")))
        // Previous poll mid-over: unknown start, so the rate (49 off 36 balls).
        assertEquals("RR 8.16", AlertEngine.overLine(bat(45, "5.3"), bat(49, "6")))
        // Already into the next over: also the rate.
        assertTrue(AlertEngine.overLine(bat(40, "5"), bat(52, "6.2")).startsWith("RR "))
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

        val resumed = AlertEngine.cricket(delayed, snap(0, 0, 0), everyOver = true)
        assertEquals("IND v WI: play under way", resumed.first().title)
        // The first batting snapshot arrives with the restart, so the innings line comes too.
        assertEquals(listOf(AlertKind.INTERRUPTION, AlertKind.INNINGS), resumed.map { it.kind })
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

class FollowMatchTest {
    @Test
    fun `India alerts cover India matches, the bell covers any one match, a mute beats both`() {
        val indiaOn = AppSettings(cricketAlerts = true)
        assertTrue(AlertScheduler.followsCricket(indiaOn, "ind", isIndia = true))
        assertFalse(AlertScheduler.followsCricket(indiaOn, "banzim", isIndia = false))

        val belled = AppSettings(followedMatches = setOf("banzim"))
        assertTrue(AlertScheduler.followsCricket(belled, "banzim", isIndia = false))
        assertFalse(AlertScheduler.followsCricket(belled, "ind", isIndia = true))
        assertTrue(belled.anyAlerts)
        assertTrue(belled.anyCricket)

        val muted = AppSettings(cricketAlerts = true, followedMatches = setOf("ind"), mutedMatches = setOf("ind"))
        assertFalse(AlertScheduler.followsCricket(muted, "ind", isIndia = true))
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
