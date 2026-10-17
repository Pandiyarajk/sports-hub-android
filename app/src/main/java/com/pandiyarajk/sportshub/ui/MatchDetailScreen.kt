// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (raised cards; keeps the match list fresh while a live match is open;
//   live panel with batters, bowlers, rates and this over)

package com.pandiyarajk.sportshub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pandiyarajk.sportshub.SportsViewModel
import com.pandiyarajk.sportshub.data.cricket.CricketMatch
import com.pandiyarajk.sportshub.data.cricket.Innings
import com.pandiyarajk.sportshub.data.cricket.LivePanel
import com.pandiyarajk.sportshub.data.cricket.MatchState
import com.pandiyarajk.sportshub.ui.theme.SportColors
import kotlinx.coroutines.delay

@Composable
fun MatchDetailScreen(match: CricketMatch, vm: SportsViewModel) {
    val cards by vm.scorecards.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(match.id, match.state) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.loadScorecard(match)
                // A finished or scheduled match does not change, so it is fetched once per visit.
                if (match.state != MatchState.LIVE) break
                delay(30_000)
                // The list poll lives on the list screen, which is not composed while this one is
                // open. Refreshing it here updates the header score and, when the match ends,
                // its state, which restarts this effect and stops the 30 s scorecard polling.
                vm.refreshCricket()
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MatchCard(match, Modifier.fillMaxWidth())
                if (match.title.isNotBlank()) {
                    Text(match.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (match.venue.isNotBlank()) {
                    Text(match.venue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key = "scorecard") {
            LoadView(
                state = cards[match.id],
                onRetry = { vm.loadScorecard(match) },
            ) { card ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Only while live: after the match the "current" batters and bowlers are history.
                    if (match.state == MatchState.LIVE) card.live?.let { LivePanelCard(it) }
                    if (card.innings.isEmpty()) {
                        Text("The scorecard is not available yet.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        card.innings.forEach { InningsCard(it) }
                    }
                }
            }
        }
    }
}

/** Cricinfo's "Live" panel: batters in, bowlers on, rates and this over's balls. */
@Composable
private fun LivePanelCard(live: LivePanel) {
    Card(colors = cardColors()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Live", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = SportColors.Live)
            val rates = buildList {
                if (live.runRate.isNotBlank()) add("Current RR: ${live.runRate}")
                if (live.requiredRate.isNotBlank()) add("Required RR: ${live.requiredRate}")
                if (live.recent.isNotBlank()) add("Last ${live.recentOvers} ov (RR): ${live.recent}")
            }
            if (rates.isNotEmpty()) {
                Text(rates.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (live.batters.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                LiveLine("Batters", "R", "B", "4s", "6s", "SR", header = true)
                live.batters.forEach { b ->
                    LiveLine(b.name + if (b.onStrike) " *" else "", "${b.runs}", "${b.balls}", "${b.fours}", "${b.sixes}", b.strikeRate, bold = b.onStrike)
                }
            }
            if (live.bowlers.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                LiveLine("Bowlers", "O", "M", "R", "W", "Econ", header = true)
                live.bowlers.forEachIndexed { i, w ->
                    // The first is bowling this over.
                    LiveLine(w.name + if (i == 0) " *" else "", w.overs, "${w.maidens}", "${w.conceded}", "${w.wickets}", w.economy, bold = i == 0)
                }
            }
            if (live.thisOver.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("This over", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    live.thisOver.forEach { BallChip(it) }
                }
            }
        }
    }
}

@Composable
private fun LiveLine(name: String, a: String, b: String, c: String, d: String, e: String, header: Boolean = false, bold: Boolean = false) {
    val weight = if (header || bold) FontWeight.SemiBold else FontWeight.Normal
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(3f))
        Cell(a, weight)
        Cell(b, weight)
        Cell(c, weight)
        Cell(d, weight)
        Text(e, style = MaterialTheme.typography.bodySmall, fontWeight = weight, textAlign = TextAlign.End, modifier = Modifier.weight(1.4f))
    }
}

/** One ball: a wicket in red, a boundary in the accent colour, everything else plain. */
@Composable
private fun BallChip(label: String) {
    val (bg, fg) = when {
        label == "W" -> SportColors.Live to Color.White
        label == "4" || label == "6" -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier.size(26.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
    }
}

@Composable
private fun InningsCard(inn: Innings) {
    Card(colors = cardColors()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${inn.team}, innings ${inn.number}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${inn.runs} ${inn.total}".trim(), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            TableLine("Batter", "R", "B", "4s", "6s", header = true)
            inn.batting.forEach { b ->
                Column {
                    TableLine(b.name, b.runs, b.balls, b.fours, b.sixes)
                    if (b.dismissal.isNotBlank()) {
                        Text(
                            b.dismissal,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (inn.extras.isNotBlank()) {
                Text("Extras ${inn.extras}", style = MaterialTheme.typography.bodySmall)
            }
            if (inn.bowling.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                TableLine("Bowler", "O", "M", "R", "W", header = true)
                inn.bowling.forEach { w -> TableLine(w.name, w.overs, w.maidens, w.runs, w.wickets) }
            }
        }
    }
}

@Composable
private fun TableLine(name: String, a: String, b: String, c: String, d: String, header: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        val weight = if (header) FontWeight.SemiBold else FontWeight.Normal
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = weight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(3f),
        )
        Cell(a, weight)
        Cell(b, weight)
        Cell(c, weight)
        Cell(d, weight)
    }
}

@Composable
private fun RowScope.Cell(text: String, weight: FontWeight) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = weight,
        textAlign = TextAlign.End,
        modifier = Modifier.weight(1f),
    )
}
