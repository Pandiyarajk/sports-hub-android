// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.pandiyarajk.sportshub.data.football.Fixture
import com.pandiyarajk.sportshub.data.football.FootballMatchDetail
import com.pandiyarajk.sportshub.data.football.KeyEvent
import com.pandiyarajk.sportshub.data.football.Side
import com.pandiyarajk.sportshub.data.football.StatLine
import com.pandiyarajk.sportshub.ui.theme.SportColors
import kotlinx.coroutines.delay

private fun Side.label() = shortName.ifBlank { name }

/** LIVE 34', HT, FT, or nothing before kick-off. */
@Composable
private fun FootballBadge(f: Fixture) {
    when {
        f.live -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(SportColors.Live))
            Spacer(Modifier.width(5.dp))
            Text("LIVE ${f.detail}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = SportColors.Live)
        }
        f.finished -> Text("FULL-TIME", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Text("UPCOMING", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Neto 46', 90'": a side's scorers, grouped by player, in goal order. */
private fun scorersFor(f: Fixture, side: Side): String =
    f.goals.filter { it.teamId == side.teamId }
        .groupBy { it.scorer.substringAfterLast(' ').ifBlank { it.scorer } }
        .entries.joinToString(", ") { (name, goals) ->
            name + " " + goals.joinToString(", ") { g ->
                g.minute + when {
                    g.ownGoal -> " og"
                    g.penalty -> " pen"
                    else -> ""
                }
            }
        }

@Composable
private fun SideRow(f: Fixture, side: Side, other: Side) {
    val started = f.live || f.finished
    val lost = f.finished && (side.score.toIntOrNull() ?: 0) < (other.score.toIntOrNull() ?: 0)
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (lost) 0.55f else 1f)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Logo(side.logo, 24.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                side.label(), style = MaterialTheme.typography.bodyLarge, color = color,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (started) Text(side.score, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        }
        val scorers = scorersFor(f, side)
        if (scorers.isNotBlank()) {
            Text(
                "⚽ $scorers", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 34.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FootballMatchCard(f: Fixture, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FootballBadge(f)
                Spacer(Modifier.weight(1f))
                Text("Premier League", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SideRow(f, f.home, f.away)
            SideRow(f, f.away, f.home)
            val status = when {
                f.live && f.detail == "HT" -> "Half-time"
                f.live -> ""
                f.finished -> f.detail.replace("FT", "Full-time")
                else -> "Kick-off ${startLabel(f.dateIso)}"
            }
            if (status.isNotBlank()) {
                Text(
                    status, style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (!f.live && !f.finished) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (f.finished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (onClick != null) Card(onClick = onClick, modifier = modifier, colors = cardColors()) { body() }
    else Card(modifier = modifier, colors = cardColors()) { body() }
}

/** The match screen: the card, formations, the timeline and head-to-head stats. */
@Composable
fun FootballMatchScreen(f: Fixture, vm: SportsViewModel) {
    val details by vm.footballMatches.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(f.id, f.live) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.loadFootballMatch(f.id)
                if (!f.live) break
                delay(30_000)
                // The score on the card comes from the day board; keep it moving while open.
                vm.refreshFootballToday()
            }
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "card") { FootballMatchCard(f, Modifier.fillMaxWidth()) }
        item(key = "detail") {
            LoadView(details[f.id], onRetry = { vm.loadFootballMatch(f.id) }) { d ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (d.homeFormation.isNotBlank() || d.awayFormation.isNotBlank()) {
                        Text(
                            "Formations: ${f.home.label()} ${d.homeFormation.ifBlank { "?" }} v ${d.awayFormation.ifBlank { "?" }} ${f.away.label()}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TimelineCard(d, f)
                    if (d.stats.isNotEmpty()) StatsCard(d.stats, f)
                }
            }
        }
    }
}

@Composable
private fun TimelineCard(d: FootballMatchDetail, f: Fixture) {
    Card(colors = cardColors()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Timeline", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (d.events.isEmpty()) {
                Text("Nothing yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Newest first while live, as a live feed reads; oldest first once it is over.
            val events = if (f.live) d.events.asReversed() else d.events
            events.forEach { EventRow(it, f) }
        }
    }
}

@Composable
private fun EventRow(e: KeyEvent, f: Fixture) {
    val lower = e.type.lowercase()
    val (mark, tint) = when {
        e.isGoal -> "⚽" to MaterialTheme.colorScheme.primary
        "red" in lower -> "■" to SportColors.Loss
        "yellow" in lower -> "■" to Color(0xFFF2C200)
        "substitution" in lower -> "⇄" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "•" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val team = when (e.teamId) {
        f.home.teamId -> f.home.abbr
        f.away.teamId -> f.away.abbr
        else -> ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(e.minute, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(48.dp))
        Text(mark, color = tint, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (e.isGoal) "Goal: ${e.text}" else e.text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (e.isGoal) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (!e.isGoal && e.type.isNotBlank() && e.text != e.type) {
                Text(e.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (team.isNotBlank()) Text(team, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatsCard(stats: List<StatLine>, f: Fixture) {
    Card(colors = cardColors()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                Text(f.home.label(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Team stats", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(f.away.label(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
            HorizontalDivider()
            stats.forEach { StatRow(it) }
        }
    }
}

/** Values either side of the label, and a bar split by each side's share. */
@Composable
private fun StatRow(s: StatLine) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.home, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp))
            Text(s.label, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text(s.away, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
        }
        val h = s.home.toFloatOrNull() ?: 0f
        val a = s.away.toFloatOrNull() ?: 0f
        if (h + a > 0f) {
            Row(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))) {
                if (h > 0f) Box(Modifier.weight(h).height(4.dp).background(MaterialTheme.colorScheme.primary))
                if (a > 0f) Box(Modifier.weight(a).height(4.dp).background(MaterialTheme.colorScheme.tertiary))
            }
        }
    }
}
