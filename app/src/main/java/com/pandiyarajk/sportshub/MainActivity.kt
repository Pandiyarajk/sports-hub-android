// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (detail screens survive process death until their data loads; a tapped
//   alert opens its match; football match screen)

@file:OptIn(ExperimentalMaterial3Api::class)

package com.pandiyarajk.sportshub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pandiyarajk.sportshub.alerts.AlertNotifier
import com.pandiyarajk.sportshub.data.AppSettings
import com.pandiyarajk.sportshub.data.alerts.AlertSport
import com.pandiyarajk.sportshub.data.Load
import com.pandiyarajk.sportshub.data.SportTab
import com.pandiyarajk.sportshub.ui.AboutScreen
import com.pandiyarajk.sportshub.ui.ClubScreen
import com.pandiyarajk.sportshub.ui.CricketScreen
import com.pandiyarajk.sportshub.ui.FootballMatchScreen
import com.pandiyarajk.sportshub.ui.FootballScreen
import com.pandiyarajk.sportshub.ui.MatchDetailScreen
import com.pandiyarajk.sportshub.ui.SettingsScreen
import com.pandiyarajk.sportshub.ui.theme.SportsHubTheme

@Composable
private fun Waiting() {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) { CircularProgressIndicator() }
}

/** Which screen is showing. The tab bar belongs to HOME only. */
private enum class Screen { HOME, MATCH, FOOTBALL_MATCH, CLUB, SETTINGS, ABOUT }

/** Where a tapped alert asks to go: a sport, and for cricket the match. */
private data class OpenRequest(val sport: AlertSport, val matchId: String?)

class MainActivity : ComponentActivity() {
    private val openRequest = mutableStateOf<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not on a restore: the saved screen wins over a stale launch intent.
        if (savedInstanceState == null) readOpenRequest(intent)
        setContent { Root(openRequest.value, onOpened = { openRequest.value = null }) }
    }

    // singleTop: a tap on an alert while the app is open arrives here, not as a new activity.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readOpenRequest(intent)
    }

    private fun readOpenRequest(intent: Intent?) {
        val sport = intent?.getStringExtra(AlertNotifier.EXTRA_SPORT)
            ?.let { name -> AlertSport.entries.firstOrNull { it.name == name } } ?: return
        openRequest.value = OpenRequest(sport, intent.getStringExtra(AlertNotifier.EXTRA_MATCH))
    }
}

@Composable
private fun Root(open: OpenRequest?, onOpened: () -> Unit, vm: SportsViewModel = viewModel()) {
    val loaded by vm.settings.settings.collectAsStateWithLifecycle()
    val settings = loaded ?: AppSettings()
    SportsHubTheme(settings.theme) {
        Surface(color = MaterialTheme.colorScheme.background) { App(vm, settings, open, onOpened) }
    }
}

@Composable
private fun App(vm: SportsViewModel, settings: AppSettings, open: OpenRequest?, onOpened: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var tab by rememberSaveable { mutableStateOf(SportTab.CRICKET) }
    var matchId by rememberSaveable { mutableStateOf("") }
    var clubId by rememberSaveable { mutableStateOf("") }
    var footballMatchId by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(open) {
        val request = open ?: return@LaunchedEffect
        when (request.sport) {
            AlertSport.CRICKET -> {
                tab = SportTab.CRICKET
                if (request.matchId != null) {
                    matchId = request.matchId
                    screen = Screen.MATCH
                } else {
                    screen = Screen.HOME
                }
            }
            AlertSport.FOOTBALL -> {
                tab = SportTab.FOOTBALL
                // A goal alert opens that match, not just the tab.
                if (request.matchId != null) {
                    footballMatchId = request.matchId
                    screen = Screen.FOOTBALL_MATCH
                } else {
                    screen = Screen.HOME
                }
            }
        }
        onOpened()
    }

    val cricket by vm.cricket.collectAsStateWithLifecycle()
    val football by vm.football.collectAsStateWithLifecycle()
    val match = (cricket as? Load.Ok)?.value?.firstOrNull { it.id == matchId }
    val footballData = (football as? Load.Ok)?.value
    val club = footballData?.standings?.rows?.firstOrNull { it.teamId == clubId }
    val footballToday by vm.footballToday.collectAsStateWithLifecycle()
    // Today's board first (it moves at the live rate), then the month boards.
    val footballMatch = (footballToday as? Load.Ok)?.value?.firstOrNull { it.id == footballMatchId }
        ?: footballData?.fixtures?.firstOrNull { it.id == footballMatchId }

    // A detail screen whose subject has gone from the feed falls back to the list. Only once data
    // has arrived: after process death the restored screen briefly has no data at all, and
    // falling back then would always throw the user out of the screen they were on.
    LaunchedEffect(screen, match, club, footballMatch, cricket, football, footballToday) {
        if ((screen == Screen.MATCH && cricket != null && match == null) ||
            (screen == Screen.CLUB && football != null && club == null) ||
            (screen == Screen.FOOTBALL_MATCH && football != null && footballToday != null && footballMatch == null)
        ) {
            screen = Screen.HOME
        }
    }

    BackHandler(enabled = screen != Screen.HOME) {
        screen = if (screen == Screen.ABOUT) Screen.SETTINGS else Screen.HOME
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (screen) {
                            Screen.HOME -> "Sports Hub"
                            Screen.MATCH -> match?.teams?.joinToString(" v ") { it.abbr.ifBlank { it.name } }.orEmpty()
                            Screen.CLUB -> club?.name.orEmpty()
                            Screen.FOOTBALL_MATCH -> footballMatch?.let { "${it.home.abbr} v ${it.away.abbr}" }.orEmpty()
                            Screen.SETTINGS -> "Settings"
                            Screen.ABOUT -> "About"
                        },
                    )
                },
                navigationIcon = {
                    if (screen != Screen.HOME) {
                        IconButton(onClick = {
                            screen = if (screen == Screen.ABOUT) Screen.SETTINGS else Screen.HOME
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = {
                    if (screen == Screen.HOME) {
                        IconButton(onClick = { screen = Screen.SETTINGS }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (screen == Screen.HOME) {
                NavigationBar {
                    SportTab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Text(t.glyph, fontSize = 20.sp) },
                            label = { Text(t.title) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val content = Modifier.padding(padding)
        androidx.compose.foundation.layout.Box(content) {
            when (screen) {
                Screen.HOME -> when (tab) {
                    SportTab.CRICKET -> CricketScreen(vm, settings) { m ->
                        matchId = m.id
                        screen = Screen.MATCH
                    }
                    SportTab.FOOTBALL -> FootballScreen(
                        vm, settings,
                        onClub = { row ->
                            clubId = row.teamId
                            screen = Screen.CLUB
                        },
                        onMatch = { f ->
                            footballMatchId = f.id
                            screen = Screen.FOOTBALL_MATCH
                        },
                    )
                }
                Screen.MATCH -> if (match != null) MatchDetailScreen(match, vm) else Waiting()
                Screen.CLUB -> if (club != null && footballData != null) ClubScreen(club, footballData) else Waiting()
                Screen.FOOTBALL_MATCH -> if (footballMatch != null) FootballMatchScreen(footballMatch, vm) else Waiting()
                Screen.SETTINGS -> SettingsScreen(vm, settings) { screen = Screen.ABOUT }
                Screen.ABOUT -> AboutScreen()
            }
        }
    }
}
