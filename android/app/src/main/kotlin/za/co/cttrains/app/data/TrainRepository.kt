package za.co.cttrains.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import za.co.cttrains.core.*

private val Context.trainPreferences by preferencesDataStore("cttrains")

@Serializable
data class Preferences(
    val routes: List<SavedRoute> = emptyList(),
    val theme: String = "System",
    val dynamicColor: Boolean = false,
    val lastQuery: SearchQuery? = null,
)

data class SearchResult(val entry: CachedSearch, val offline: Boolean)

/** Direct HTTPS access means the Android app needs no API server or credentials. */
class TrainRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val preferencesKey = stringPreferencesKey("preferences")
    private val cacheKey = stringPreferencesKey("search_cache")
    private val catalogKey = stringPreferencesKey("catalog")
    private val cacheMutex = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

    val preferences = context.trainPreferences.data.map { values ->
        runCatching { json.decodeFromString<Preferences>(values[preferencesKey].orEmpty()) }.getOrDefault(Preferences())
    }

    suspend fun updatePreferences(transform: (Preferences) -> Preferences) {
        context.trainPreferences.edit { values ->
            val before = runCatching { json.decodeFromString<Preferences>(values[preferencesKey].orEmpty()) }.getOrDefault(Preferences())
            values[preferencesKey] = json.encodeToString(transform(before))
        }
    }

    suspend fun bundledCatalog(): StationCatalog = withContext(Dispatchers.IO) {
        context.assets.open("stations.json").bufferedReader().use { json.decodeFromString(it.readText()) }
    }

    suspend fun storedCatalog(): StationCatalog? {
        val values = context.trainPreferences.data.first()
        return runCatching { json.decodeFromString<StationCatalog>(values[catalogKey].orEmpty()) }.getOrNull()
    }

    suspend fun refreshCatalog(): StationCatalog {
        val catalog = withContext(Dispatchers.Default) { CtTrainsParser.catalog(get("https://cttrains.co.za/train-form.php")) }
        context.trainPreferences.edit { it[catalogKey] = json.encodeToString(catalog) }
        return catalog
    }

    fun websiteUrl(query: SearchQuery): String = "https://cttrains.co.za/train-schedule.php".toHttpUrl().newBuilder()
        .addQueryParameter("fromStation", query.from).addQueryParameter("toStation", query.to)
        .addQueryParameter("travelDate", query.websiteDate).addQueryParameter("departureTime", query.time).build().toString()

    suspend fun search(query: SearchQuery): SearchResult {
        try {
            val html = get(websiteUrl(query))
            val entry = withContext(Dispatchers.Default) {
                CachedSearch(query, CtTrainsParser.journeys(html, query.time), System.currentTimeMillis(), CtTrainsParser.fares(html))
            }
            cacheMutex.withLock {
                context.trainPreferences.edit { values ->
                    val entries = readCache(values[cacheKey])
                    values[cacheKey] = json.encodeToString(CachePolicy.prune(listOf(entry) + entries, entry.fetchedAt))
                }
            }
            return SearchResult(entry, false)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            val cached = cacheMutex.withLock {
                val values = context.trainPreferences.data.first()
                CachePolicy.prune(readCache(values[cacheKey]), System.currentTimeMillis()).find { it.query == query }
            }
            return cached?.let { SearchResult(it, true) } ?: throw error
        }
    }

    suspend fun clearCache() = cacheMutex.withLock { context.trainPreferences.edit { it.remove(cacheKey) }; Unit }

    private fun readCache(value: String?): List<CachedSearch> = runCatching {
        json.decodeFromString<List<CachedSearch>>(value.orEmpty())
    }.getOrDefault(emptyList())

    private suspend fun get(url: String): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url)
            .header("User-Agent", "CTTrains-Android/1.0 (https://github.com/Xhanti-mbasa/cttrains-api; unofficial community app)")
            .header("Accept", "text/html").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val html = response.use {
                        if (!it.isSuccessful) throw IOException("CT Trains is unavailable (HTTP ${it.code}).")
                        val body = it.body ?: throw IOException("CT Trains returned an empty response.")
                        // Bound memory use even when Content-Length is absent or incorrect.
                        val bytes = body.byteStream().use { stream ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (output.size() <= 2_000_000) {
                                val read = stream.read(buffer, 0, minOf(buffer.size, 2_000_001 - output.size()))
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                            output.toByteArray()
                        }
                        if (bytes.size > 2_000_000) throw IOException("The timetable response is too large.")
                        bytes.toString(Charsets.UTF_8)
                    }
                    if (continuation.isActive) continuation.resume(html)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}
