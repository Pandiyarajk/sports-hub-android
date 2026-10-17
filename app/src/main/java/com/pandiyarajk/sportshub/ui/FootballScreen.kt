// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (short club names, freshness line, pull-only spinner, alert bell, live match cards)

package com.pandiyarajk.sportshub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pandiyarajk.sportshub.SportsViewModel
import com.pandiyarajk.sportshub.data.AppSettings
import com.pandiyarajk.sportshub.data.football.Fixture
import com.pandiyarajk.sportshub.data.football.FootballData
import com.pandiyarajk.sportshub.data.football.Leader
import com.pandiyarajk.sportshub.data.football.Leaders
import com.pandiyarajk.sportshub.data.football.TableRow
import com.pandiyarajk.sportshub.ui.theme.SportColors
import kotlinx.coroutines.delay

private val RankWidth = 22.dp
private val NumWidth = 22.dp
private val GdWidth = 30.dp
private val PtsWidth = 28.dp
private val FormWidth = 48.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FootballScreen(
    vm: SportsViewModel,
    settings: AppSettings,
    onClub: (TableRow) -> Unit,
    onMatch: (Fixture) -> Unit,
) {
    val state by vm.football.collectAsStateWithLifecycle()
    val refreshing by vm.footballRefreshing.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refreshFootball()
                delay(3_000)
                val live = (vm.football.value as? com.pandiyarajk.sportshub.data.Load.Ok)
                    ?.value?.fixtures?.any { it.live } == true
                delay(if (live) 2 * 60_000L else 10 * 60_000L)
            }
        }
    }

    // The score cards run on their own, faster loop: the small day board at the live rate while
    // a match is on, against the table's heavy refresh every few minutes.
    LaunchedEffect(settings.liveRefreshSec) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refreshFootballToday()
                delay(3_000)
                delay(if (vm.footballLive()) settings.liveRefreshSec * 1000L else 5 * 60_000L)
            }
        }
    }
    val today by vm.footballToday.collectAsStateWithLifecycle()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            vm.refreshFootball(userPull = true)
            vm.refreshFootballToday(userPull = true)
        },
    ) {
        val updatedAt by vm.footballUpdatedAt.collectAsStateWithLifecycle()
        val offline by vm.footballOffline.collectAsStateWithLifecycle()
        val gate = rememberNotificationGate(onGranted = { vm.syncAlerts() })
        val toggleAlerts: (Boolean) -> Unit = { on ->
            vm.setFootballAlerts(on)
            if (on) gate.ensure()
        }
        LoadView(state, onRetry = { vm.refreshFootball(userPull = true) }) { data ->
            // Today's matches when there are any; otherwise the next matchday from the month boards.
            val todays = (today as? com.pandiyarajk.sportshub.data.Load.Ok)?.value.orEmpty()
            val (title, cards) = if (todays.isNotEmpty()) {
                "Today" to todays.sortedWith(compareBy({ !it.live }, { it.finished }, { it.dateIso }))
            } else {
                "Next matches" to nextMatchday(data.fixtures)
            }
            TableList(data, settings.favouriteClub, updatedAt, offline, settings.footballAlerts, toggleAlerts, onClub, title, cards, onMatch)
        }
    }
}

/** The earliest upcoming date's fixtures, in kick-off order. */
private fun nextMatchday(fixtures: List<Fixture>): List<Fixture> {
    val upcoming = fixtures.filter { !it.finished && !it.live }.sortedBy { it.dateIso }
    val firstDay = upcoming.firstOrNull()?.let { startDay(it.dateIso) } ?: return emptyList()
    return upcoming.filter { startDay(it.dateIso) == firstDay }
}

@Composable
private fun TableList(
    data: FootballData,
    favourite: String,
    updatedAt: Long?,
    offline: Boolean,
    alertsOn: Boolean,
    onAlerts: (Boolean) -> Unit,
    onClub: (TableRow) -> Unit,
    matchesTitle: String,
    matches: List<Fixture>,
    onMatch: (Fixture) -> Unit,
) {
    val cardWidth = (LocalConfiguration.current.screenWidthDp - 56).dp
    LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp)) {
        if (matches.isNotEmpty()) {
            item(key = "matches-title") {
                SectionTitle(matchesTitle, Modifier.padding(start = 8.dp, bottom = 8.dp))
            }
            item(key = "matches") {
                // A plain scrolling Row, as the India strip: few cards, and every card takes the
                // tallest one's height, which a lazy row cannot measure.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).height(IntrinsicSize.Max)
                        .padding(start = 8.dp, end = 8.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    matches.forEach { f ->
                        FootballMatchCard(f, Modifier.width(cardWidth).fillMaxHeight()) { onMatch(f) }
                    }
                }
            }
        }
        item(key = "season") {
            Row(Modifier.padding(start = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        data.standings.season.ifBlank { "Premier League" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FreshnessLine(updatedAt, offline)
                }
                AlertBell(alertsOn, "Premier League", onAlerts)
            }
        }
        item(key = "header") { HeaderLine() }
        items(data.standings.rows, key = { it.teamId }) { row ->
            TableLine(
                row = row,
                form = data.form[row.teamId].orEmpty(),
                highlighted = row.teamId == favourite,
                total = data.standings.rows.size,
                onClick = { onClub(row) },
            )
        }
        data.leaders?.let { leaders ->
            item(key = "leaders") { LeadersCard(leaders, Modifier.padding(top = 20.dp, start = 8.dp, end = 8.dp)) }
        }
    }
}

@Composable
private fun HeaderLine() {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(3.dp))
        HeaderText("#", RankWidth)
        Text(
            "Club",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        HeaderText("MP", NumWidth + 2.dp)
        HeaderText("W", NumWidth - 2.dp)
        HeaderText("D", NumWidth - 2.dp)
        HeaderText("L", NumWidth - 2.dp)
        HeaderText("GD", GdWidth)
        HeaderText("Pts", PtsWidth)
        HeaderText("Last 5", FormWidth)
    }
    HorizontalDivider()
}

@Composable
private fun HeaderText(text: String, width: Dp) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.width(width),
    )
}

@Composable
private fun TableLine(row: TableRow, form: String, highlighted: Boolean, total: Int, onClick: () -> Unit) {
    // Blue for the top four, orange for fifth, red for the bottom three, as on the web table.
    val accent: Color = when {
        row.rank in 1..4 -> SportColors.Champions
        row.rank == 5 -> SportColors.Europa
        row.rank > total - 3 -> SportColors.Loss
        else -> Color.Transparent
    }
    val background = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Column {
        Row(
            Modifier.fillMaxWidth().background(background).clickable(onClick = onClick).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().padding(vertical = 4.dp).background(accent))
            NumText(row.rank.toString(), RankWidth)
            Row(Modifier.weight(1f).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Logo(row.logo, 22.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    row.shortName.ifBlank { row.name },
                    fontSize = 13.sp,
                    fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NumText(row.played.toString(), NumWidth + 2.dp)
            NumText(row.won.toString(), NumWidth - 2.dp)
            NumText(row.drawn.toString(), NumWidth - 2.dp)
            NumText(row.lost.toString(), NumWidth - 2.dp)
            NumText(row.goalDiff, GdWidth)
            NumText(row.points.toString(), PtsWidth, bold = true)
            Box(Modifier.width(FormWidth), contentAlignment = Alignment.Center) { FormDots(form) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun NumText(text: String, width: Dp, bold: Boolean = false) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.width(width),
    )
}

@Composable
private fun LeadersCard(leaders: Leaders, modifier: Modifier = Modifier) {
    var showAssists by rememberSaveable { mutableStateOf(false) }
    Card(modifier, colors = cardColors()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stat leaders", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showAssists, onClick = { showAssists = false }, label = { Text("Goals") })
                FilterChip(selected = showAssists, onClick = { showAssists = true }, label = { Text("Assists") })
            }
            val rows: List<Leader> = if (showAssists) leaders.assists else leaders.goals
            rows.forEachIndexed { i, l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(22.dp), style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.weight(1f)) {
                        Text(l.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (l.teamName.isNotBlank()) {
                            Text(
                                l.teamName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(l.value, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
