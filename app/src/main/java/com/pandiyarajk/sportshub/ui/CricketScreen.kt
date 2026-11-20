// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (dividers between cards, filter chips, freshness line, even India strip,
//   alert bell on the India title)
// Modified: Nov-20-2026 (India strip cards sized from the row's own width, not the screen's;
//   two tiles a row on wide screens such as a phone in landscape)

package com.pandiyarajk.sportshub.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
            // Wide screens (landscape) put two tiles side by side, so a turned phone shows twice
            // as many matches instead of stretched cards with an empty middle.
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val twoUp = maxWidth >= TWO_UP_MIN_WIDTH
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "chips") {
                    val chips: @Composable (Modifier) -> Unit = { modifier ->
                        Row(
                            modifier.horizontalScroll(rememberScrollState()),
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
                    }
                    if (twoUp) {
                        // Wide screens have room for the freshness line beside the chips, which
                        // gives a short landscape screen one more line of matches.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            chips(Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(16.dp))
                            FreshnessLine(updatedAt, offline)
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            chips(Modifier)
                            FreshnessLine(updatedAt, offline)
                        }
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
                        if (twoUp) {
                            // Two tiles a view; a third and more scroll in sideways.
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val cardWidth = (maxWidth - TILE_GAP) / 2
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()).height(IntrinsicSize.Max),
                                    horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
                                ) {
                                    india.forEach { m ->
                                        MatchCard(m, Modifier.width(cardWidth).fillMaxHeight(), showLeague = true) { onOpen(m) }
                                    }
                                }
                            }
                        } else if (india.size == 1) {
                            // One match takes the full width, whatever its state.
                            val m = india.single()
                            MatchCard(m, Modifier.fillMaxWidth(), showLeague = true) { onOpen(m) }
                        } else {
                            // Several scroll sideways, each nearly full width so the next one
                            // peeks in. A plain Row, not a LazyRow: there are only ever a few, and
                            // IntrinsicSize.Max gives every card the tallest card's height, which
                            // a lazy row cannot measure.
                            // Sized from the width this row really has, not the screen's: in
                            // landscape the nav bar and camera cutout take a side each.
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val cardWidth = maxWidth - 24.dp
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
                }
                if (filter == CricketFilter.ALL) {
                    rest.groupBy { it.league }.forEach { (league, group) ->
                        item(key = "league-$league") { SectionTitle(league, Modifier.padding(top = 6.dp)) }
                        cardItems(group, twoUp, showLeague = false, onOpen)
                    }
                } else {
                    cardItems(rest, twoUp, showLeague = true, onOpen)
                }
            }
            }
        }
    }
}

/** Screens at least this wide (a phone in landscape, a tablet) show two tiles a row. */
private val TWO_UP_MIN_WIDTH = 600.dp

/** The gap between two tiles in a row, the same as the gap between rows. */
private val TILE_GAP = 10.dp

/**
 * Adds [matches] to the list, one card a row, or two a row when [twoUp] is set.
 *
 * @param matches the cards to add, in order.
 * @param twoUp true to pair them side by side.
 * @param showLeague true to print each card's series name.
 * @param onOpen called with the tapped match.
 */
private fun LazyListScope.cardItems(
    matches: List<CricketMatch>,
    twoUp: Boolean,
    showLeague: Boolean,
    onOpen: (CricketMatch) -> Unit,
) {
    if (twoUp) {
        items(matches.chunked(2), key = { pair -> pair.joinToString("+") { it.id } }) { pair ->
            DividedPair(pair, showLeague, onOpen)
        }
    } else {
        items(matches, key = { it.id }) { m -> DividedCard(m, showLeague = showLeague, onOpen) }
    }
}

/**
 * Two cards side by side, equal width and the taller one's height, with the list's divider
 * under them. A lone last card keeps half the width so it lines up with the pairs above.
 *
 * @param pair one or two matches.
 * @param showLeague true to print each card's series name.
 * @param onOpen called with the tapped match.
 */
@Composable
private fun DividedPair(pair: List<CricketMatch>, showLeague: Boolean, onOpen: (CricketMatch) -> Unit) {
    Column {
        Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            pair.forEach { m ->
                MatchCard(m, Modifier.weight(1f).fillMaxHeight(), showLeague = showLeague) { onOpen(m) }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
        HorizontalDivider(
            Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
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
