// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (raised cards, match and city header, dimmed loser, freshness line,
//   upcoming card: start time beside the teams and a ticking countdown)

package com.pandiyarajk.sportshub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pandiyarajk.sportshub.data.Load
import com.pandiyarajk.sportshub.data.cricket.CricketMatch
import com.pandiyarajk.sportshub.data.cricket.CricketTeam
import com.pandiyarajk.sportshub.data.cricket.MatchState
import com.pandiyarajk.sportshub.ui.theme.SportColors
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import java.util.Locale

/** A team logo, or an empty disc when the feed has none, so rows stay aligned. */
@Composable
fun Logo(url: String?, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    if (url.isNullOrBlank()) {
        Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Cards sit one tone above the page so they read as cards in dark mode too. */
@Composable
fun cardColors() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)

@Composable
fun StateBadge(state: MatchState) {
    val (label, color) = when (state) {
        MatchState.LIVE -> "LIVE" to SportColors.Live
        MatchState.UPCOMING -> "UPCOMING" to MaterialTheme.colorScheme.onSurfaceVariant
        MatchState.RESULT -> "RESULT" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state == MatchState.LIVE) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** One side of a match. A losing side on a finished match is dimmed, as Cricinfo does. */
@Composable
fun TeamRow(team: CricketTeam, dimmed: Boolean = false) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dimmed) 0.55f else 1f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${team.name} ${team.score}".trim() },
    ) {
        Logo(team.logo, 24.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = team.abbr.ifBlank { team.name },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (team.winner) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(team.headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = color)
            if (team.detail.isNotBlank()) {
                Text(
                    team.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The line under the teams. Before the match the start time already sits beside the teams, so
 * this is the countdown, or the feed's own note when the start is more than a day away.
 */
fun statusLine(m: CricketMatch, now: ZonedDateTime = ZonedDateTime.now()): String = when (m.state) {
    MatchState.UPCOMING -> startsIn(m.startIso, now).ifBlank { m.status }
    else -> m.status
}

/**
 * The current time, ticking every 30 s while composed. Data refreshes only every few minutes
 * before a match, and a countdown that moves only then would read wrong in between.
 */
@Composable
private fun tickingNow(): ZonedDateTime {
    val now by produceState(ZonedDateTime.now()) {
        while (true) {
            delay(30_000)
            value = ZonedDateTime.now()
        }
    }
    return now
}

/** Day over time, right-aligned beside the teams of an upcoming match, as Cricinfo lays it out. */
@Composable
private fun StartBlock(iso: String) {
    val day = startDay(iso)
    val time = startTime(iso)
    if (day.isBlank() && time.isBlank()) return
    Column(horizontalAlignment = Alignment.End) {
        Text(day, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(time, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/**
 * A match card. [showLeague] adds the series name, for places with no league heading above the
 * card (the India strip); under a league heading it would only repeat the heading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchCard(
    match: CricketMatch,
    modifier: Modifier = Modifier,
    showLeague: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateBadge(match.state)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = match.labelAndCity.ifBlank { match.league },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
            if (showLeague && match.league.isNotBlank()) {
                Text(
                    match.league,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (match.state == MatchState.UPCOMING) {
                // No scores yet, so the start time takes the score column.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        match.teams.forEach { TeamRow(it) }
                    }
                    Spacer(Modifier.width(12.dp))
                    StartBlock(match.startIso)
                }
            } else {
                match.teams.forEach { TeamRow(it, dimmed = match.hasWinner && !it.winner) }
            }
            val status = if (match.state == MatchState.UPCOMING) statusLine(match, tickingNow()) else statusLine(match)
            if (status.isNotBlank()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (match.state == MatchState.UPCOMING) FontWeight.SemiBold else FontWeight.Normal,
                    color = when (match.state) {
                        MatchState.RESULT -> MaterialTheme.colorScheme.primary
                        MatchState.LIVE -> MaterialTheme.colorScheme.onSurface
                        MatchState.UPCOMING -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, colors = cardColors()) { body() }
    } else {
        Card(modifier = modifier, colors = cardColors()) { body() }
    }
}

/**
 * When the data on screen was fetched, and whether the last refresh failed. Saved data from a
 * previous launch is shown rather than an empty screen, so the user needs to know how old it is.
 */
@Composable
fun FreshnessLine(updatedAt: Long?, offline: Boolean, modifier: Modifier = Modifier) {
    if (updatedAt == null && !offline) return
    val time = updatedAt?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault()))
    }
    val text = when {
        offline && time != null -> "Offline. Showing scores from $time"
        offline -> "Offline. Showing saved scores"
        else -> "Updated $time"
    }
    val color = if (offline) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val background = if (offline) MaterialTheme.colorScheme.errorContainer else Color.Transparent
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = if (offline) 8.dp else 0.dp, vertical = 2.dp),
    )
}

/**
 * A message with a way out. It scrolls, so pull to refresh works on it as well (the gesture only
 * reaches a scrollable child), and it always offers Try again: "no matches right now" and a
 * refused request both change on their own, and waiting minutes for the next poll is no answer.
 */
@Composable
fun ErrorBox(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(120.dp))
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(16.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

/** Renders the three load states; the content lambda only ever sees real data. */
@Composable
fun <T> LoadView(state: Load<T>?, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Load.Failed -> ErrorBox(state.message, onRetry)
        is Load.Empty -> ErrorBox(state.reason, onRetry)
        is Load.Ok -> content(state.value)
    }
}

/**
 * One result per mark: W a green disc, D a grey disc, L a red ring. The ring keeps a win and a
 * loss apart without relying on red and green, which many people cannot tell apart. Spoken as
 * the letters for screen readers.
 */
@Composable
fun FormDots(form: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = "Last ${form.length}: $form" },
    ) {
        form.forEach { c ->
            val mark = Modifier.size(8.dp).clip(CircleShape)
            when (c) {
                'W' -> Box(mark.background(SportColors.Win))
                'L' -> Box(mark.border(1.5.dp, SportColors.Loss, CircleShape))
                else -> Box(mark.background(SportColors.Draw))
            }
        }
    }
}
