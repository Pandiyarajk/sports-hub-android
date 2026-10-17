// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pandiyarajk.sportshub.data.football.FootballData
import com.pandiyarajk.sportshub.data.football.Fixture
import com.pandiyarajk.sportshub.data.football.TableRow
import com.pandiyarajk.sportshub.data.parseMillis

/** One club: its record, then results (newest first) and what is coming up (soonest first). */
@Composable
fun ClubScreen(row: TableRow, data: FootballData) {
    val mine = data.fixtures.filter { it.home.teamId == row.teamId || it.away.teamId == row.teamId }
    fun time(f: Fixture) = parseMillis(f.dateIso) ?: 0L
    val results = mine.filter { it.finished }.sortedByDescending(::time)
    val upcoming = mine.filter { !it.finished }.sortedBy(::time)

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "summary") {
            Card(colors = cardColors()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Logo(row.logo, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "#${row.rank}  ${row.points} pts  P${row.played} W${row.won} D${row.drawn} L${row.lost}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Goals ${row.goalsFor} for, ${row.goalsAgainst} against (${row.goalDiff})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FormDots(data.form[row.teamId].orEmpty())
                }
            }
        }
        if (upcoming.isNotEmpty()) {
            item(key = "upcoming-title") { SectionTitle("Upcoming") }
            items(upcoming, key = { "u" + it.id }) { FixtureLine(it) }
        }
        if (results.isNotEmpty()) {
            item(key = "results-title") { SectionTitle("Results") }
            items(results, key = { "r" + it.id }) { FixtureLine(it) }
        }
        if (mine.isEmpty()) {
            item(key = "none") { Text("No fixtures found for this club.", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun FixtureLine(f: Fixture) {
    Card(colors = cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (f.finished || f.live) f.detail else startLabel(f.dateIso),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    f.home.name,
                    Modifier.weight(1f),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Logo(f.home.logo, 20.dp, Modifier.padding(horizontal = 6.dp))
                Text(
                    if (f.finished || f.live) "${f.home.score} - ${f.away.score}" else "v",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(52.dp),
                )
                Logo(f.away.logo, 20.dp, Modifier.padding(horizontal = 6.dp))
                Text(f.away.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
