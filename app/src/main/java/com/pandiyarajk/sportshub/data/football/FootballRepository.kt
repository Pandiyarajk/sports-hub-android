// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (two months back for Last 5, keep a failed month, lighter leader lookups;
//   today's matches and match summaries for the live cards)

package com.pandiyarajk.sportshub.data.football

import com.pandiyarajk.sportshub.data.Fetcher
import com.pandiyarajk.sportshub.data.Http
import com.pandiyarajk.sportshub.data.Load
import com.pandiyarajk.sportshub.data.at
import com.pandiyarajk.sportshub.data.get
import com.pandiyarajk.sportshub.data.loadOf
import com.pandiyarajk.sportshub.data.text
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

class FootballRepository(
    private val fetch: Fetcher,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    // Last, so callers and tests can pass the month as a trailing lambda.
    private val clock: () -> YearMonth = { YearMonth.now() },
) {
    @Volatile private var leaders: Leaders? = null
    @Volatile private var leadersAtMillis = 0L
    @Volatile private var leadersFailedAtMillis = 0L

    /** The last good fixtures per month, so one failed month does not wipe form and fixtures. */
    private val months = ConcurrentHashMap<YearMonth, List<Fixture>>()

    /** Athlete names never change, so each is looked up once per process. */
    private val athleteNames = ConcurrentHashMap<String, String>()

    /** Table, fixtures and form from a few calls; stat leaders ride along and may be absent. */
    suspend fun load(): Load<FootballData> = loadOf {
        val standings = parseStandings(fetch(get(STANDINGS_URL)))
        check(standings.rows.isNotEmpty()) { "empty table" }
        val month = clock()
        // Two months back as well as this one: a month holds three or four rounds, and early in a
        // season the previous month alone leaves Last 5 short. The next month is what is coming up.
        val fixtures = (-MONTHS_BACK..1L).map { month.plusMonths(it) }.flatMap { ym ->
            try {
                parseFixtures(fetch(get(scoreboardUrl(ym)))).also { months[ym] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                months[ym].orEmpty()
            }
        }.distinctBy { it.id }
        FootballData(
            standings = standings,
            fixtures = fixtures,
            form = FormBuilder.build(fixtures),
            leaders = leadersFor(standings),
        )
    }

    /**
     * Today's matches for the live score cards, from the day scoreboard (about 70 KB). Polled at
     * the live rate while a match is on; the table and months stay on their slower refresh.
     */
    suspend fun today(): Load<List<Fixture>> {
        val result = loadOf { parseFixtures(fetch(get(dayUrl()))) }
        return if (result is Load.Ok && result.value.isEmpty()) Load.Empty("No Premier League matches today.") else result
    }

    /** One match's timeline, stats and formations for the match screen. */
    suspend fun match(eventId: String): Load<FootballMatchDetail> = loadOf {
        parseFootballSummary(fetch(get(summaryUrl(eventId))))
    }

    /**
     * Cached for six hours, and a failure is not retried for half an hour, so a broken leaders
     * service does not add retries to every refresh. Any failure hides the card, never the table.
     */
    private suspend fun leadersFor(standings: Standings): Leaders? {
        val now = nowMillis()
        leaders?.let { if (now - leadersAtMillis < LEADERS_TTL_MILLIS) return it }
        if (now - leadersFailedAtMillis < LEADERS_FAILURE_BACKOFF_MILLIS) return leaders
        val fresh = try {
            val body = fetch(get(leadersUrl(standings.seasonYear)))
            val teamNames = standings.rows.associate { it.teamId to it.name }
            val goals = parseLeaderRefs(body, "goals")
            val assists = parseLeaderRefs(body, "assists")
            // A player in both lists is looked up once.
            (goals + assists).map { it.athleteUrl }.distinct().forEach { url ->
                // containsKey, not `in`: on a ConcurrentHashMap `in` tests the values.
                if (!athleteNames.containsKey(url)) {
                    val athlete = Http.json.parseToJsonElement(fetch(get(url)))
                    athleteNames[url] = athlete.at("displayName").text()
                }
            }
            fun resolve(refs: List<LeaderRef>) = refs.map { ref ->
                Leader(athleteNames[ref.athleteUrl].orEmpty(), teamNames[ref.teamId].orEmpty(), ref.value)
            }
            Leaders(goals = resolve(goals), assists = resolve(assists))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (fresh != null) {
            leaders = fresh
            leadersAtMillis = now
        } else {
            leadersFailedAtMillis = now
        }
        return fresh ?: leaders
    }

    companion object {
        const val MONTHS_BACK = 2L
        const val LEADERS_TTL_MILLIS = 6 * 60 * 60 * 1000L
        const val LEADERS_FAILURE_BACKOFF_MILLIS = 30 * 60 * 1000L
        const val STANDINGS_URL = "https://site.api.espn.com/apis/v2/sports/soccer/eng.1/standings"

        fun scoreboardUrl(ym: YearMonth) =
            "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard?dates=" +
                "%04d%02d".format(ym.year, ym.monthValue)

        /** ESPN dates its football scoreboard in US Eastern time; an EPL day never crosses its midnight. */
        fun dayUrl(today: LocalDate = LocalDate.now(ZoneId.of("America/New_York"))): String =
            "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard?dates=" +
                "%04d%02d%02d".format(today.year, today.monthValue, today.dayOfMonth)

        fun summaryUrl(eventId: String) =
            "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/summary?event=$eventId"

        fun leadersUrl(year: Int) =
            "https://sports.core.api.espn.com/v2/sports/soccer/leagues/eng.1/seasons/$year/types/1/leaders"
    }
}
