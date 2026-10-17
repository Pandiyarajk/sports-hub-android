// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (full dismissals kept across polls; older ones from the play-by-play)

package com.pandiyarajk.sportshub.data.cricket

import com.pandiyarajk.sportshub.data.Fetcher
import com.pandiyarajk.sportshub.data.Load
import com.pandiyarajk.sportshub.data.get
import com.pandiyarajk.sportshub.data.loadOf
import java.util.concurrent.ConcurrentHashMap

class CricketRepository(private val fetch: Fetcher) {
    // A finished scorecard does not change and a payload is about 380 KB, so keep what was opened.
    private val finished = ConcurrentHashMap<String, Scorecard>()

    /** Matches in display order, India first. One request covers every league. */
    suspend fun matches(): Load<List<CricketMatch>> {
        val result = loadOf { IndiaFilter.order(parseCricketHeader(fetch(get(HEADER_URL)))) }
        return if (result is Load.Ok && result.value.isEmpty()) Load.Empty("No cricket matches right now.") else result
    }

    // Full dismissals per match. The commentary holds only the last few overs, so a wicket's
    // "c Joseph b Paul" is kept here once seen, or later it falls back to "caught".
    private val dismissals = ConcurrentHashMap<String, Map<String, String>>()

    // The gaps already looked up, so one that the play-by-play cannot fill is not fetched again.
    private val triedGaps = ConcurrentHashMap<String, Set<String>>()

    suspend fun scorecard(match: CricketMatch): Load<Scorecard> {
        finished[match.id]?.let { return Load.Ok(it) }
        val result = loadOf {
            val body = fetch(get(summaryUrl(match)))
            var card = parseScorecard(body, dismissals[match.id].orEmpty())
            if (card.unresolved.isNotEmpty() && triedGaps[match.id] != card.unresolved) {
                // Measured live: Abhishek Sharma read just "caught" once his wicket left the
                // summary's last-few-overs window. Walk the play-by-play pages (25 balls each)
                // until every gap is filled; the extra fetches happen once per gap, not per poll.
                triedGaps[match.id] = card.unresolved
                val found = fillFromPlayByPlay(match, card.unresolved)
                if (found.isNotEmpty()) card = parseScorecard(body, card.dismissals + found)
            }
            card.also { dismissals[match.id] = it.dismissals }
        }
        if (result is Load.Ok && match.state == MatchState.RESULT) finished[match.id] = result.value
        return result
    }

    private suspend fun fillFromPlayByPlay(match: CricketMatch, wanted: Set<String>): Map<String, String> {
        val found = mutableMapOf<String, String>()
        var page = 1
        var pages = 1
        while (page <= pages && page <= MAX_PLAY_BY_PLAY_PAGES && !found.keys.containsAll(wanted)) {
            val (wickets, count) = try {
                parsePlayByPlayPage(fetch(get(playByPlayUrl(match, page))))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                break
            }
            wickets.forEach { found[it.batter] = it.how }
            pages = count
            page++
        }
        return found
    }

    companion object {
        const val HEADER_URL = "https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=cricket"

        fun summaryUrl(m: CricketMatch) = summaryUrl(m.leagueId, m.id)

        fun summaryUrl(leagueId: String, eventId: String) =
            "https://site.api.espn.com/apis/site/v2/sports/cricket/$leagueId/summary?event=$eventId"

        fun playByPlayUrl(m: CricketMatch, page: Int) =
            "https://site.api.espn.com/apis/site/v2/sports/cricket/${m.leagueId}/playbyplay?event=${m.id}&page=$page"

        /** A Test innings can run to hundreds of balls; past this, a gap stays a code. */
        const val MAX_PLAY_BY_PLAY_PAGES = 40
    }
}
