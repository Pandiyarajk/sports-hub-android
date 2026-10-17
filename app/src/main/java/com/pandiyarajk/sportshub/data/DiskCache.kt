// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * The last good response body for each URL, kept on disk so the app opens on the data it showed
 * last time instead of an empty screen. Raw bodies are stored rather than parsed objects, so the
 * same parsers read the cache and the network and the two can never disagree about shape.
 */
class DiskCache(private val dir: File) {

    private fun fileFor(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    suspend fun read(url: String): String? = withContext(Dispatchers.IO) {
        runCatching { fileFor(url).takeIf { it.isFile }?.readText() }.getOrNull()
    }

    /** Millis of the last write for this URL, or null when nothing is saved. */
    suspend fun savedAt(url: String): Long? = withContext(Dispatchers.IO) {
        fileFor(url).takeIf { it.isFile }?.lastModified()
    }

    /**
     * Keeps the newest [maxFiles] bodies. Every opened scorecard is saved, so without this the
     * directory would grow for as long as the app is installed.
     */
    suspend fun prune(maxFiles: Int = MAX_FILES) = withContext(Dispatchers.IO) {
        runCatching {
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(maxFiles)?.forEach { it.delete() }
        }
        Unit
    }

    /** Written to a temporary file and renamed, so a crash mid-write never leaves half a body. */
    suspend fun write(url: String, body: String) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val target = fileFor(url)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
        Unit
    }

    companion object {
        const val MAX_FILES = 80
    }
}

/** Every successful live body is saved before it is returned. A failed save never fails the fetch. */
fun cachingFetcher(live: Fetcher, cache: DiskCache): Fetcher = { request ->
    live(request).also { cache.write(request.url.toString(), it) }
}

/** Reads only what was saved; used to paint the screen at launch before the network answers. */
fun cachedFetcher(cache: DiskCache): Fetcher = { request ->
    cache.read(request.url.toString()) ?: throw ApiException("No saved data yet.", retryable = false)
}
