// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (corrupt settings fall back to defaults; live alert settings; follow one match)

package com.pandiyarajk.sportshub.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.pandiyarajk.sportshub.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import kotlinx.coroutines.flow.stateIn

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val pinIndia: Boolean = true,
    val liveRefreshSec: Int = 30,
    /** Team id of the club highlighted in the table, or blank for none. */
    val favouriteClub: String = "",
    /** Live alerts for India cricket matches. Off until the user turns them on. */
    val cricketAlerts: Boolean = false,
    /** An alert at the end of every over; off means wickets, innings and the result only. */
    val everyOver: Boolean = true,
    /** Goal, kick-off, half-time and full-time alerts for live EPL matches. */
    val footballAlerts: Boolean = false,
    /** Football alerts only for [favouriteClub]'s matches, not every EPL match. */
    val footballFavouriteOnly: Boolean = false,
    /** Matches the user stopped following from the notification; the next match alerts again. */
    val mutedMatches: Set<String> = emptySet(),
    /** Cricket matches followed one by one (the bell on a match screen), India or not. */
    val followedMatches: Set<String> = emptySet(),
) {
    val anyAlerts: Boolean get() = cricketAlerts || footballAlerts || followedMatches.isNotEmpty()

    /** Whether cricket needs polling at all: India alerts on, or any match followed. */
    val anyCricket: Boolean get() = cricketAlerts || followedMatches.isNotEmpty()
}

class SettingsRepository(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    /** Null until DataStore has been read once, so nothing acts on defaults the user changed. */
    // A corrupt or unreadable settings file falls back to defaults instead of crashing on launch.
    val settings: StateFlow<AppSettings?> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }.map { p ->
        AppSettings(
            theme = p[THEME]?.let { n -> ThemeMode.entries.firstOrNull { it.name == n } } ?: ThemeMode.SYSTEM,
            pinIndia = p[PIN_INDIA] ?: true,
            liveRefreshSec = (p[LIVE_SEC] ?: 30).coerceIn(15, 120),
            favouriteClub = p[FAVOURITE].orEmpty(),
            cricketAlerts = p[CRICKET_ALERTS] ?: false,
            everyOver = p[EVERY_OVER] ?: true,
            footballAlerts = p[FOOTBALL_ALERTS] ?: false,
            footballFavouriteOnly = p[FOOTBALL_FAVOURITE_ONLY] ?: false,
            mutedMatches = p[MUTED].orEmpty(),
            followedMatches = p[FOLLOWED].orEmpty(),
        )
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val current: AppSettings get() = settings.value ?: AppSettings()

    suspend fun setTheme(mode: ThemeMode) = store.edit { it[THEME] = mode.name }
    suspend fun setPinIndia(value: Boolean) = store.edit { it[PIN_INDIA] = value }
    suspend fun setLiveRefreshSec(value: Int) = store.edit { it[LIVE_SEC] = value.coerceIn(15, 120) }
    suspend fun setFavouriteClub(teamId: String) = store.edit { it[FAVOURITE] = teamId }
    suspend fun setCricketAlerts(value: Boolean) = store.edit { it[CRICKET_ALERTS] = value }
    suspend fun setEveryOver(value: Boolean) = store.edit { it[EVERY_OVER] = value }
    suspend fun setFootballAlerts(value: Boolean) = store.edit { it[FOOTBALL_ALERTS] = value }
    suspend fun setFootballFavouriteOnly(value: Boolean) = store.edit { it[FOOTBALL_FAVOURITE_ONLY] = value }

    /** Keeps only the newest ids, so the set cannot grow for as long as the app is installed. */
    suspend fun mute(matchIds: Collection<String>) = store.edit { p ->
        p[MUTED] = (p[MUTED].orEmpty() + matchIds).toList().takeLast(MAX_MUTED).toSet()
    }

    /** Follow or stop following one match; following also lifts a mute on it. Kept short like mutes. */
    suspend fun follow(matchId: String, on: Boolean) = store.edit { p ->
        val now = p[FOLLOWED].orEmpty()
        p[FOLLOWED] = if (on) (now + matchId).toList().takeLast(MAX_MUTED).toSet() else now - matchId
        if (on) p[MUTED] = p[MUTED].orEmpty() - matchId
    }

    /** The current settings for code outside the UI (the alert service), waiting for the first read. */
    suspend fun read(): AppSettings = settings.first { it != null } ?: AppSettings()

    private companion object {
        const val MAX_MUTED = 40
        val MUTED = stringSetPreferencesKey("alerts.muted")
        val FOLLOWED = stringSetPreferencesKey("alerts.followed")
        val CRICKET_ALERTS = booleanPreferencesKey("alerts.cricket")
        val EVERY_OVER = booleanPreferencesKey("alerts.cricket.every_over")
        val FOOTBALL_ALERTS = booleanPreferencesKey("alerts.football")
        val FOOTBALL_FAVOURITE_ONLY = booleanPreferencesKey("alerts.football.favourite_only")
        val THEME = stringPreferencesKey("theme")
        val PIN_INDIA = booleanPreferencesKey("pin.india")
        val LIVE_SEC = intPreferencesKey("live.refresh.sec")
        val FAVOURITE = stringPreferencesKey("favourite.club")
    }
}
