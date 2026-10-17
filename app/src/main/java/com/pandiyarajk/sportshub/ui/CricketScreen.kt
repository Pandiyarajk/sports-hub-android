// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (dividers between cards, filter chips, freshness line, even India strip,
//   alert bell on the India title)

package com.pandiyarajk.sportshub.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pandiyarajk.sportshub.SportsViewModel
import com.pandiyarajk.sportshub.data.AppSettings
import com.pandiyarajk.sportshub.data.cricket.CricketMatch
import com.pandiyarajk.sportshub.data.cricket.IndiaFilter
import com.pandiyarajk.sportshub.data.cricket.MatchState
import kotlinx.coroutines.delay

private const val IDLE_REFRESH_MILLIS = 5 * 60 * 1000L

/** The chip row above the list. ALL keeps the India strip; the others are flat filtered lists. */
private enum class CricketFilter(val label: String, val accepts: (CricketMatch) -> Boolean) {
    ALL("All", { true }),
    LIVE("Live", { it.state == MatchState.LIVE }),
    INDIA("India", { it.isIndia }),
    RESULTS("Results", { it.state == MatchState.RESULT }),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CricketScreen(vm: SportsViewModel, settings: AppSettings, onOpen: (CricketMatch) -> Unit) {
    val state by vm.cricket.collectAsStateWithLifecycle()
    val refreshing by vm.cricketRefreshing.collectAsStateWithLifecycle()
    val updatedAt by vm.cricketUpdatedAt.collectAsStateWithLifecycle()
    val offline by vm.cricketOffline.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(CricketFilter.ALL) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val gate = rememberNotificationGate(onGranted = { vm.syncAlerts() })
    val toggleIndiaAlerts: (Boolean) -> Unit = { on ->
        vm.setCricketAlerts(on)
        if (on) gate.ensure()
    }

    // Lifecycle aware: nothing is requested while the app is in the background.
    LaunchedEffect(settings.liveRefreshSec) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refreshCricket()
                delay(3_000)
                delay(if (vm.hasLive()) settings.liveRefreshSec * 1000L else IDLE_REFRESH_MILLIS)
            }
        }
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refreshCricket(userPull = true) }) {
        LoadView(state, onRetry = { vm.refreshCricket(userPull = true) }) { feed ->
            // The feed arrives India first; with the setting off, India sorts like everyone else.
            val matches = if (settings.pinIndia) feed else IndiaFilter.order(feed, indiaFirst = false)
            val shown = matches.filter(filter.accepts)
            val india = if (filter == CricketFilter.ALL && settings.pinIndia) shown.filter { it.isIndia } else emptyList()
            val rest = shown.filterNot { it in india }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "chips") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CricketFilter.entries.forEach { f ->
                                val count = matches.count(f.accepts)
                                FilterChip(
                                    selected = filter == f,
                                    onClick = { filter = f },
                                    label = { Text("${f.label} ($count)") },
                                )
                            }
                        }
                        FreshnessLine(updatedAt, offline)
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "none") {
                        Text(
                            "No ${filter.label.lowercase()} matches right now.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 24.dp),
                        )
                    }
                }
                if (india.isNotEmpty()) {
                    item(key = "india-title") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("India", Modifier.weight(1f))
                            AlertBell(settings.cricketAlerts, "India", toggleIndiaAlerts)
                        }
                    }
                    item(key = "india-row") {
                        if (india.size == 1) {
                            // One match takes the full width, whatever its state.
                            val m = india.single()
                            MatchCard(m, Modifier.fillMaxWidth(), showLeague = true) { onOpen(m) }
                        } else {
                            // Several scroll sideways, each nearly full width so the next one
                            // peeks in. A plain Row, not a LazyRow: there are only ever a few, and
                            // IntrinsicSize.Max gives every card the tallest card's height, which
                            // a lazy row cannot measure.
                            val cardWidth = (LocalConfiguration.current.screenWidthDp - 56).dp
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()).height(IntrinsicSize.Max),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                india.forEach { m ->
                                    MatchCard(m, Modifier.width(cardWidth).fillMaxHeight(), showLeague = true) { onOpen(m) }
                                }
                            }
                        }
                    }
                }
                if (filter == CricketFilter.ALL) {
                    rest.groupBy { it.league }.forEach { (league, group) ->
                        item(key = "league-$league") { SectionTitle(league, Modifier.padding(top = 6.dp)) }
                        items(group, key = { it.id }) { m -> DividedCard(m, showLeague = false, onOpen) }
                    }
                } else {
                    items(rest, key = { it.id }) { m -> DividedCard(m, showLeague = true, onOpen) }
                }
            }
        }
    }
}

@Composable
private fun DividedCard(m: CricketMatch, showLeague: Boolean, onOpen: (CricketMatch) -> Unit) {
    Column {
        MatchCard(m, Modifier.fillMaxWidth(), showLeague = showLeague) { onOpen(m) }
        // The same break the football table draws between its rows.
        HorizontalDivider(
            Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
