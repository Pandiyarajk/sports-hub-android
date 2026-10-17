// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** A failure whose message is a finished sentence for the user, never a URL or a status code. */
class ApiException(
    message: String,
    val status: Int = 0,
    val retryable: Boolean = true,
    val retryAfterMillis: Long = 0,
) : IOException(message)

/** What a screen shows: three states, not Result, because nothing to show is not a failure. */
sealed interface Load<out T> {
    data class Ok<T>(val value: T) : Load<T>
    data class Empty(val reason: String) : Load<Nothing>
    data class Failed(val message: String, val retryable: Boolean = true) : Load<Nothing>
}

/** Injectable so parsers and paging can be tested on a plain JVM without a socket. */
typealias Fetcher = suspend (Request) -> String

val LiveFetcher: Fetcher = { Http.body(it) }

fun get(url: String): Request = Request.Builder().url(url).header("Accept", "application/json").build()

object Http {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    const val MAX_ATTEMPTS = 4
    const val BASE_BACKOFF_MILLIS = 800L
    const val MAX_BACKOFF_MILLIS = 20_000L

    /** Spacing between outbound request starts. ESPN answers bursts with ordinary-looking failures. */
    const val MIN_SPACING_MILLIS = 120L

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // Retries are hand-rolled below, so OkHttp must not replay on its own.
            .retryOnConnectionFailure(false)
            .build()
    }

    private val gate = Mutex()
    private var lastStartMillis = 0L

    fun isRetryable(status: Int): Boolean = status == 429 || status in 500..599

    /** Exponential backoff with jitter; a server-supplied Retry-After wins, clamped to the cap. */
    fun backoffMillis(attempt: Int, retryAfterMillis: Long = 0, jitter: Long = Random.nextLong(0, 250)): Long {
        val exp = BASE_BACKOFF_MILLIS shl (attempt - 1).coerceIn(0, 10)
        return maxOf(retryAfterMillis, exp + jitter).coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    private suspend fun pace() {
        val wait = gate.withLock {
            val now = System.currentTimeMillis()
            val next = maxOf(now, lastStartMillis + MIN_SPACING_MILLIS)
            lastStartMillis = next
            next - now
        }
        if (wait > 0) delay(wait)
    }

    private fun sentence(status: Int): String = when {
        status == 404 -> "That data is not available right now."
        status == 429 -> "The score service is busy. Try again in a moment."
        status in 500..599 -> "The score service is having trouble. Try again shortly."
        else -> "The score service refused the request."
    }

    /** Dispatch to IO happens here once, never at call sites. */
    suspend fun body(request: Request): String = withContext(Dispatchers.IO) {
        var last: ApiException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            pace()
            try {
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) return@withContext resp.body?.string().orEmpty()
                    val retryAfter = resp.header("Retry-After")?.toLongOrNull()?.times(1000) ?: 0L
                    throw ApiException(sentence(resp.code), resp.code, isRetryable(resp.code), retryAfter)
                }
            } catch (e: ApiException) {
                last = e
            } catch (e: SocketTimeoutException) {
                last = ApiException("The score service took too long to answer.")
            } catch (e: UnknownHostException) {
                last = ApiException("No connection. Check your network and try again.")
            } catch (e: IOException) {
                last = ApiException("Could not reach the score service.")
            }
            val failure = last ?: break
            if (!failure.retryable || attempt == MAX_ATTEMPTS) throw failure
            delay(backoffMillis(attempt, failure.retryAfterMillis))
        }
        throw last ?: ApiException("Could not reach the score service.")
    }
}

/** Turns any thrown failure into a Load.Failed without losing a finished user-facing message. */
inline fun <T> loadOf(block: () -> T): Load<T> = try {
    Load.Ok(block())
} catch (e: ApiException) {
    Load.Failed(e.message.orEmpty(), e.retryable)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Load.Failed("The score data was not in the expected form.", true)
}
