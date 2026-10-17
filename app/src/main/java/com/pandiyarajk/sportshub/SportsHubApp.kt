// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (disk cache: live repositories save, cached ones paint the launch screen;
//   the caching fetcher is shared with the live alert service)

package com.pandiyarajk.sportshub

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.pandiyarajk.sportshub.data.DiskCache
import com.pandiyarajk.sportshub.data.LiveFetcher
import com.pandiyarajk.sportshub.data.SettingsRepository
import com.pandiyarajk.sportshub.data.cachedFetcher
import com.pandiyarajk.sportshub.data.cachingFetcher
import com.pandiyarajk.sportshub.data.cricket.CricketRepository
import com.pandiyarajk.sportshub.data.football.FootballRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Manual DI: the whole object graph, built once per process. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app.settingsStore, scope)

    // filesDir, not cacheDir: the system may clear cacheDir under storage pressure, and the point
    // of this cache is that the app never opens empty.
    val cache = DiskCache(File(app.filesDir, "responses"))

    /** Live fetches that also refresh the cache. The alert service and scheduler use it too. */
    val fetch = cachingFetcher(LiveFetcher, cache)
    private val saved = cachedFetcher(cache)

    val cricket = CricketRepository(fetch)
    val football = FootballRepository(fetch)

    /** Same parsers, reading only the disk. Used once at launch. */
    val cricketSaved = CricketRepository(saved)
    val footballSaved = FootballRepository(saved)
}

class SportsHubApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** Reached through applicationContext on purpose: an Activity context would be held alive. */
val Context.appContainer: AppContainer
    get() = (applicationContext as SportsHubApp).container
