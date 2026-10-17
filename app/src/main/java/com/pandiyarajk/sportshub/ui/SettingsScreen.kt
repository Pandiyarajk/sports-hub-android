// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (collected football state; live alerts section)

package com.pandiyarajk.sportshub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pandiyarajk.sportshub.SportsViewModel
import com.pandiyarajk.sportshub.data.AppSettings
import com.pandiyarajk.sportshub.data.Load
import com.pandiyarajk.sportshub.ui.theme.ThemeMode

@Composable
fun SettingsScreen(vm: SportsViewModel, settings: AppSettings, onAbout: () -> Unit) {
    // Collected, not read once: data that lands while Settings is open enables the picker.
    val football by vm.football.collectAsStateWithLifecycle()
    val clubs = ((football as? Load.Ok)?.value?.standings?.rows).orEmpty()
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Section("Theme") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.theme == mode,
                        onClick = { vm.setTheme(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }
        }
        Section("Cricket") {
            SwitchRow(
                title = "Show India first",
                detail = "India's matches in a strip at the top of the list.",
                checked = settings.pinIndia,
            ) { vm.setPinIndia(it) }
            Text("Live refresh", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { sec ->
                    FilterChip(
                        selected = settings.liveRefreshSec == sec,
                        onClick = { vm.setLiveRefreshSec(sec) },
                        label = { Text("$sec s") },
                    )
                }
            }
        }
        AlertsSection(vm, settings, hasFavourite = clubs.any { it.teamId == settings.favouriteClub })
        Section("Football") {
            val current = clubs.firstOrNull { it.teamId == settings.favouriteClub }?.name ?: "None"
            Text("Highlighted club", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { menuOpen = true }, enabled = clubs.isNotEmpty()) {
                Text(if (clubs.isEmpty()) "Open the Football tab first" else current)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("None") }, onClick = { vm.setFavouriteClub(""); menuOpen = false })
                clubs.forEach { club ->
                    DropdownMenuItem(
                        text = { Text(club.name) },
                        onClick = { vm.setFavouriteClub(club.teamId); menuOpen = false },
                    )
                }
            }
        }
        TextButton(onClick = onAbout) { Text("About Sports Hub") }
    }
}

@Composable
private fun AlertsSection(vm: SportsViewModel, settings: AppSettings, hasFavourite: Boolean) {
    val gate = rememberNotificationGate(onGranted = { vm.syncAlerts() })
    val (batteryExempt, askBattery) = rememberBatteryExempt()
    Section("Live alerts") {
        SwitchRow(
            title = "India cricket",
            detail = "Wickets, innings and the result while an India match is live.",
            checked = settings.cricketAlerts,
        ) { on ->
            vm.setCricketAlerts(on)
            if (on) gate.ensure()
        }
        SwitchRow(
            title = "Every over",
            detail = "Also an update at the end of each over.",
            checked = settings.everyOver,
            enabled = settings.cricketAlerts,
        ) { vm.setEveryOver(it) }
        SwitchRow(
            title = "Premier League",
            detail = "Goals with the scorer, kick-off, half-time and full-time.",
            checked = settings.footballAlerts,
        ) { on ->
            vm.setFootballAlerts(on)
            if (on) gate.ensure()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !settings.footballFavouriteOnly,
                enabled = settings.footballAlerts,
                onClick = { vm.setFootballFavouriteOnly(false) },
                label = { Text("All matches") },
            )
            FilterChip(
                selected = settings.footballFavouriteOnly,
                enabled = settings.footballAlerts && hasFavourite,
                onClick = { vm.setFootballFavouriteOnly(true) },
                label = { Text("Highlighted club only") },
            )
        }
        if (settings.anyAlerts && !gate.granted) {
            // The system stops showing the dialog after two refusals and the request then fails
            // instantly, so the route into Settings is always offered beside it.
            Text(
                "Alerts are on but notifications are blocked, so nothing will show. If tapping Allow " +
                    "does nothing, Android has stopped asking; turn notifications on in Settings instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { gate.ensure() }) { Text("Allow notifications") }
                TextButton(onClick = gate.openSettings) { Text("Open settings") }
            }
        }
        if (settings.anyAlerts && !batteryExempt) {
            Text(
                "With the phone idle for a long time, Android may hold back updates until it wakes. " +
                    "Allowing background use keeps alerts on time; it costs battery only while a match is followed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = askBattery) { Text("Allow background use") }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    // The whole row is the switch: a tap on the words toggles it (measured on the phone, a tap on
    // the label alone did nothing), and a screen reader announces one switch, not a text and a switch.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Sports Hub $version", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Cricket scores with India first, and the English Premier League table, fixtures, form and stat leaders.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Data", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Scores and tables come from ESPN's public JSON service, which carries the ESPNcricinfo cricket data. " +
                "This app is not affiliated with or endorsed by ESPN, ESPNcricinfo or the Premier League.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Disclaimer", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Provided AS IS, with no warranty, express or implied. Use it entirely at your own risk. " +
                "Scores can be late or wrong, so do not rely on this app for betting or any decision with consequences. " +
                "The LICENSE file is the governing text and prevails where this summary differs.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
