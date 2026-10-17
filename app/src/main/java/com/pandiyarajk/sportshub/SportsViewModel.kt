// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (launch from the disk cache, updated-at times, saved scorecards first,
//   skip too-fresh automatic refreshes, pull-only spinner, single-flight scorecards; live alert switches;
//   today's football cards and match details)

package com.pandiyarajk.sportshub

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pandiyarajk.sportshub.alerts.AlertScheduler
import com.pandiyarajk.sportshub.data.Load
import com.pandiyarajk.sportshub.data.cricket.CricketMatch
import com.pandiyarajk.sportshub.data.cricket.CricketRepository
import com.pandiyarajk.sportshub.data.cricket.MatchState
import com.pandiyarajk.sportshub.data.cricket.Scorecard
import com.pandiyarajk.sportshub.data.football.FootballData
import com.pandiyarajk.sportshub.data.football.FootballMatchDetail
import com.pandiyarajk.sportshub.data.football.Fixture
import com.pandiyarajk.sportshub.data.football.FootballRepository
import com.pandiyarajk.sportshub.ui.theme.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class SportsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.appContainer

    val settings = container.settings

    private val _cricket = MutableStateFlow<Load<List<CricketMatch>>?>(null)
    val cricket: StateFlow<Load<List<CricketMatch>>?> = _cricket

    private val _football = MutableStateFlow<Load<FootballData>?>(null)
    val football: StateFlow<Load<FootballData>?> = _football

    private val _scorecards = MutableStateFlow<Map<String, Load<Scorecard>>>(emptyMap())
    val scorecards: StateFlow<Map<String, Load<Scorecard>>> = _scorecards

    private val _cricketRefreshing = MutableStateFlow(false)
    val cricketRefreshing: StateFlow<Boolean> = _cricketRefreshing

    private val _footballRefreshing = MutableStateFlow(false)
    val footballRefreshing: StateFlow<Boolean> = _footballRefreshing

    /** When the data on screen was fetched: the cache file time at launch, then each live success. */
    private val _cricketUpdatedAt = MutableStateFlow<Long?>(null)
    val cricketUpdatedAt: StateFlow<Long?> = _cricketUpdatedAt

    private val _footballUpdatedAt = MutableStateFlow<Long?>(null)
    val footballUpdatedAt: StateFlow<Long?> = _footballUpdatedAt

    /** Today's EPL matches for the live score cards, polled at the live rate while one is on. */
    private val _footballToday = MutableStateFlow<Load<List<Fixture>>?>(null)
    val footballToday: StateFlow<Load<List<Fixture>>?> = _footballToday

    private val _footballMatches = MutableStateFlow<Map<String, Load<FootballMatchDetail>>>(emptyMap())
    val footballMatches: StateFlow<Map<String, Load<FootballMatchDetail>>> = _footballMatches

    /** True when the last live refresh failed and the screen is showing older data. */
    private val _cricketOffline = MutableStateFlow(false)
    val cricketOffline: StateFlow<Boolean> = _cricketOffline

    private val _footballOffline = MutableStateFlow(false)
    val footballOffline: StateFlow<Boolean> = _footballOffline

    // Single flight: a refresh already running is joined, never duplicated, so a pull to refresh
    // during an automatic one is still one request.
    private val cricketGate = Mutex()
    private val footballGate = Mutex()

    init {
        // Paint the last saved data at once so the app never opens empty. The live refresh starts
        // in parallel from the screens; whichever lands second must not undo a good first.
        viewModelScope.launch {
            val saved = container.cricketSaved.matches()
            if (saved is Load.Ok && fillIfBlank(_cricket, saved)) {
                _cricketUpdatedAt.update { it ?: container.cache.savedAt(CricketRepository.HEADER_URL) }
            }
        }
        viewModelScope.launch {
            val saved = container.footballSaved.load()
            if (saved is Load.Ok && fillIfBlank(_football, saved)) {
                _footballUpdatedAt.update { it ?: container.cache.savedAt(FootballRepository.STANDINGS_URL) }
            }
        }
        viewModelScope.launch {
            val saved = container.footballSaved.today()
            if (saved is Load.Ok) fillIfBlank(_footballToday, saved)
        }
        viewModelScope.launch { container.cache.prune() }
        // Opening the app refreshes the alert plan: fixtures move, and an alarm may have been lost
        // to a reboot. Returns at once when alerts are off.
        syncAlerts()
    }

    /** True while any shown match is live, which is what sets the faster refresh cadence. */
    fun hasLive(): Boolean =
        (_cricket.value as? Load.Ok)?.value?.any { it.state == MatchState.LIVE } == true

    // Live successes only. The cache file time is shown to the user but must not suppress a
    // refresh: saved data from yesterday is exactly what a launch should replace.
    @Volatile private var cricketLiveAt = 0L
    @Volatile private var footballLiveAt = 0L

    /**
     * [userPull] is a pull to refresh: always fetches, and is the only thing that shows the
     * spinner, so automatic refreshes during a live match do not flash it. An automatic call is
     * skipped when the last live success is younger than [minAgeMillis]; switching tabs, leaving a
     * detail screen and rotating would otherwise each fetch everything again.
     */
    fun refreshCricket(userPull: Boolean = false) {
        val minAge = if (hasLive()) LIVE_MIN_AGE_MILLIS else IDLE_MIN_AGE_MILLIS
        if (!userPull && System.currentTimeMillis() - cricketLiveAt < minAge) return
        viewModelScope.launch {
            if (!cricketGate.tryLock()) return@launch
            try {
                if (userPull) _cricketRefreshing.value = true
                val fresh = container.cricket.matches()
                _cricketOffline.value = fresh is Load.Failed && _cricket.value is Load.Ok
                keepStale(_cricket, fresh)
                if (fresh is Load.Ok) {
                    cricketLiveAt = System.currentTimeMillis()
                    _cricketUpdatedAt.value = cricketLiveAt
                }
            } finally {
                _cricketRefreshing.value = false
                cricketGate.unlock()
            }
        }
    }

    fun refreshFootball(userPull: Boolean = false) {
        if (!userPull && System.currentTimeMillis() - footballLiveAt < IDLE_MIN_AGE_MILLIS) return
        viewModelScope.launch {
            if (!footballGate.tryLock()) return@launch
            try {
                if (userPull) _footballRefreshing.value = true
                val fresh = container.football.load()
                _footballOffline.value = fresh is Load.Failed && _football.value is Load.Ok
                keepStale(_football, fresh)
                if (fresh is Load.Ok) {
                    footballLiveAt = System.currentTimeMillis()
                    _footballUpdatedAt.value = footballLiveAt
                }
            } finally {
                _footballRefreshing.value = false
                footballGate.unlock()
            }
        }
    }

    private val footballTodayGate = Mutex()
    @Volatile private var footballTodayAt = 0L

    /** True while any of today's matches is in play, which sets the live card cadence. */
    fun footballLive(): Boolean = (_footballToday.value as? Load.Ok)?.value?.any { it.live } == true

    /** Today's cards only (about 70 KB); the table keeps its own slower refresh. */
    fun refreshFootballToday(userPull: Boolean = false) {
        val minAge = if (footballLive()) LIVE_MIN_AGE_MILLIS else IDLE_MIN_AGE_MILLIS
        if (!userPull && System.currentTimeMillis() - footballTodayAt < minAge) return
        viewModelScope.launch {
            if (!footballTodayGate.tryLock()) return@launch
            try {
                val fresh = container.football.today()
                keepStale(_footballToday, fresh)
                if (fresh is Load.Ok || fresh is Load.Empty) footballTodayAt = System.currentTimeMillis()
            } finally {
                footballTodayGate.unlock()
            }
        }
    }

    private val footballMatchJobs = mutableMapOf<String, Job>()

    fun loadFootballMatch(eventId: String) {
        if (footballMatchJobs[eventId]?.isActive == true) return
        footballMatchJobs[eventId] = viewModelScope.launch {
            val fresh = container.football.match(eventId)
            _footballMatches.update { old ->
                if (fresh is Load.Failed && old[eventId] is Load.Ok) old else old + (eventId to fresh)
            }
        }
    }

    /** One scorecard fetch per match at a time, so a late reply never overwrites a newer one. */
    private val scorecardJobs = mutableMapOf<String, Job>()

    fun loadScorecard(match: CricketMatch) {
        if (scorecardJobs[match.id]?.isActive == true) return
        scorecardJobs[match.id] = viewModelScope.launch {
            // A scorecard opened before shows at once from disk while the live one is fetched.
            if (_scorecards.value[match.id] == null) {
                val saved = container.cricketSaved.scorecard(match)
                if (saved is Load.Ok) _scorecards.update { if (it[match.id] == null) it + (match.id to saved) else it }
            }
            val fresh = container.cricket.scorecard(match)
            _scorecards.update { old ->
                // A failed refresh must not wipe a scorecard that is already on screen.
                if (fresh is Load.Failed && old[match.id] is Load.Ok) old else old + (match.id to fresh)
            }
        }
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { container.settings.setTheme(mode) }
    fun setPinIndia(value: Boolean) = viewModelScope.launch { container.settings.setPinIndia(value) }
    fun setLiveRefreshSec(value: Int) = viewModelScope.launch { container.settings.setLiveRefreshSec(value) }
    fun setFavouriteClub(id: String) = viewModelScope.launch {
        container.settings.setFavouriteClub(id)
        syncAlertsNow()
    }

    // Each alert switch re-plans straight away: start following if a match is live, else set the
    // alarm for the next start. Turning everything off cancels alarms, the check and the service.
    fun setCricketAlerts(on: Boolean) = viewModelScope.launch {
        container.settings.setCricketAlerts(on)
        syncAlertsNow()
    }

    fun setEveryOver(on: Boolean) = viewModelScope.launch { container.settings.setEveryOver(on) }

    fun setFootballAlerts(on: Boolean) = viewModelScope.launch {
        container.settings.setFootballAlerts(on)
        syncAlertsNow()
    }

    fun setFootballFavouriteOnly(on: Boolean) = viewModelScope.launch {
        container.settings.setFootballFavouriteOnly(on)
        syncAlertsNow()
    }

    /** Also called when notification permission arrives, since a sync without it cancels everything. */
    fun syncAlerts() = viewModelScope.launch { syncAlertsNow() }

    private suspend fun syncAlertsNow() = AlertScheduler.sync(getApplication())

    companion object {
        /** Below the shortest live interval setting (15 s), so live polling is never skipped. */
        const val LIVE_MIN_AGE_MILLIS = 10_000L
        const val IDLE_MIN_AGE_MILLIS = 60_000L
    }

    /** A failed refresh keeps the last good data on screen instead of replacing it with an error. */
    private fun <T> keepStale(target: MutableStateFlow<Load<T>?>, fresh: Load<T>) {
        target.update { old -> if (fresh is Load.Failed && old is Load.Ok) old else fresh }
    }

    /**
     * Saved data only fills a screen that has nothing worth keeping: still loading, or a live
     * failure (no network at launch). It never overwrites a live success or a genuine Empty.
     * Returns whether it filled.
     */
    private fun <T> fillIfBlank(target: MutableStateFlow<Load<T>?>, saved: Load.Ok<T>): Boolean {
        var filled = false
        target.update { old ->
            // update {} may run this more than once under contention; the last run decides.
            filled = old == null || old is Load.Failed
            if (filled) saved else old
        }
        return filled
    }
}
