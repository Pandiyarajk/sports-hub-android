// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub

import com.pandiyarajk.sportshub.data.ApiException
import com.pandiyarajk.sportshub.data.DiskCache
import com.pandiyarajk.sportshub.data.Load
import com.pandiyarajk.sportshub.data.cachedFetcher
import com.pandiyarajk.sportshub.data.cachingFetcher
import com.pandiyarajk.sportshub.data.cricket.CricketRepository
import com.pandiyarajk.sportshub.data.cricket.parseCricketHeader
import com.pandiyarajk.sportshub.data.football.parseStandings
import com.pandiyarajk.sportshub.data.get
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiskCacheTest {
    private fun tempCache(): Pair<DiskCache, File> {
        val dir = Files.createTempDirectory("sportshub-cache").toFile()
        dir.deleteOnExit()
        return DiskCache(dir) to dir
    }

    @Test
    fun `a live body is saved and the saved fetcher returns it with no network`() = runBlocking {
        val (cache, _) = tempCache()
        val live = cachingFetcher({ fixture("cricket_header.json") }, cache)
        CricketRepository(live).matches()

        val offline = CricketRepository(cachedFetcher(cache)).matches()
        assertTrue(offline is Load.Ok)
        assertEquals(16, (offline as Load.Ok).value.size)
    }

    @Test
    fun `nothing saved is a failed load that does not offer a retry`() = runBlocking {
        val (cache, _) = tempCache()
        val result = CricketRepository(cachedFetcher(cache)).matches()
        assertTrue(result is Load.Failed)
        assertFalse((result as Load.Failed).retryable)
    }

    @Test
    fun `a failed live fetch keeps the earlier body on disk`() = runBlocking {
        val (cache, _) = tempCache()
        val url = CricketRepository.HEADER_URL
        cache.write(url, "first")
        val failing = cachingFetcher({ throw ApiException("down") }, cache)
        runCatching { failing(get(url)) }
        assertEquals("first", cache.read(url))
    }

    @Test
    fun `savedAt is null until something is written`() = runBlocking {
        val (cache, _) = tempCache()
        assertNull(cache.savedAt("https://example.invalid/a"))
        cache.write("https://example.invalid/a", "x")
        assertTrue(cache.savedAt("https://example.invalid/a") != null)
    }

    @Test
    fun `prune keeps only the newest files`() = runBlocking {
        val (cache, dir) = tempCache()
        repeat(5) { i ->
            cache.write("https://example.invalid/$i", "body $i")
            dir.listFiles()!!.first { it.readText() == "body $i" }.setLastModified(1_000_000L + i * 1000)
        }
        cache.prune(maxFiles = 2)
        assertEquals(setOf("body 3", "body 4"), dir.listFiles()!!.map { it.readText() }.toSet())
    }
}

class CardFieldsTest {
    private val matches = parseCricketHeader(fixture("cricket_header.json"))

    @Test
    fun `the card header is the match label and city, without the day-night suffix`() {
        val india = matches.first { it.id == "1529234" }
        assertEquals("5th T20I", india.label)
        assertEquals("Bengaluru", india.city)
        assertEquals("5th T20I · Bengaluru", india.labelAndCity)
    }

    @Test
    fun `a ground with no comma is used whole as the city`() {
        val m = matches.first { it.id == "1558249" }
        assertEquals("Moara Vlasiei Cricket Ground", m.city)
    }

    @Test
    fun `a finished match knows its winner, a live one does not`() {
        val result = matches.first { it.id == "1544851" }
        assertTrue(result.hasWinner)
        assertTrue(result.teams.first { it.abbr == "SOA" }.winner)
        assertFalse(result.teams.first { it.abbr == "QLD" }.winner)
        assertFalse(matches.first { it.id == "1554419" }.hasWinner)
    }

    @Test
    fun `the table uses the short club name`() {
        val rows = parseStandings(fixture("epl_standings.json")).rows
        assertEquals("Man City", rows.first().shortName)
        assertEquals("Brighton", rows.first { it.name.startsWith("Brighton") }.shortName)
    }
}
