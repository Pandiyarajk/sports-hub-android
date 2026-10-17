// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

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

    suspend fun scorecard(match: CricketMatch): Load<Scorecard> {
        finished[match.id]?.let { return Load.Ok(it) }
        val result = loadOf { parseScorecard(fetch(get(summaryUrl(match)))) }
        if (result is Load.Ok && match.state == MatchState.RESULT) finished[match.id] = result.value
        return result
    }

    companion object {
        const val HEADER_URL = "https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=cricket"

        fun summaryUrl(m: CricketMatch) =
            "https://site.api.espn.com/apis/site/v2/sports/cricket/${m.leagueId}/summary?event=${m.id}"
    }
}
