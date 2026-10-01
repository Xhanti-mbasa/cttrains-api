package za.co.cttrains.app.data

import android.content.Context
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import za.co.cttrains.app.BuildConfig
import za.co.cttrains.core.*

@Serializable
data class Preferences(
    val routes: List<SavedRoute> = emptyList(), val favoriteStations: List<String> = emptyList(),
    val theme: String = "System", val dynamicColor: Boolean = false, val lastQuery: SearchQuery? = null,
    val profileId: String = "", val sourceId: String = "cttrains-web", val mapMode: String = "online",
    val offlineMapId: Long? = null,
    val fareCorrections: List<FareCorrection> = emptyList(),
)
data class SearchResult(val entry: CachedSearch, val offline: Boolean, val restoredPlan: Boolean = false)
data class TransitSource(val id: String, val label: String, val description: String)

/** A normalized data boundary; UI never scrapes or mixes sources. */
class TrainRepository(private val context: Context) {
    val store = LocalTransitStore.get(context)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    val sources = buildList {
        add(TransitSource("cttrains-web", "CT Trains", "Direct community timetable"))
        if (BuildConfig.TRANSIT_BASE_URL.isNotBlank()) add(TransitSource("cttrains-api", "Transit API", "CT Trains through your configured server"))
    }
    val preferences = flow { store.initialize(); emitAll(store.preferences) }
    suspend fun updatePreferences(transform: (Preferences) -> Preferences) = store.updatePreferences(transform)
    suspend fun bundledCatalog(): StationCatalog = withContext(Dispatchers.IO) {
        context.assets.open("stations.json").bufferedReader().use { json.decodeFromString(it.readText()) }
    }
    suspend fun storedCatalog(): StationCatalog? = store.catalog()
    suspend fun refreshCatalog(sourceId: String = "cttrains-web"): StationCatalog {
        val catalog = if (sourceId == "cttrains-api" && BuildConfig.TRANSIT_BASE_URL.isNotBlank()) {
            json.decodeFromString<StationCatalog>(get("${BuildConfig.TRANSIT_BASE_URL}/mobile/v1/catalog"))
        } else withContext(Dispatchers.Default) { CtTrainsParser.catalog(get("https://cttrains.co.za/train-form.php")) }
        require(catalog.stations.size >= 2) { "Transit source returned an empty station list." }
        store.saveCatalog(catalog)
        return catalog
    }
    fun websiteUrl(query: SearchQuery): String = "https://cttrains.co.za/train-schedule.php".toHttpUrl().newBuilder()
        .addQueryParameter("fromStation", query.from).addQueryParameter("toStation", query.to)
        .addQueryParameter("travelDate", query.websiteDate).addQueryParameter("departureTime", query.time).build().toString()
    suspend fun search(query: SearchQuery, sourceId: String): SearchResult {
        require(sources.any { it.id == sourceId }) { "This transit source is unavailable." }
        try {
            val entry = if (sourceId == "cttrains-api") {
                val url = "${BuildConfig.TRANSIT_BASE_URL}/mobile/v1/journeys".toHttpUrl().newBuilder()
                    .addQueryParameter("from", query.from).addQueryParameter("to", query.to)
                    .addQueryParameter("date", query.date).addQueryParameter("time", query.time).build().toString()
                validateTransitEntry(json.decodeFromString<CachedSearch>(get(url)), query, sourceId, System.currentTimeMillis())
            } else {
                val html = get(websiteUrl(query))
                withContext(Dispatchers.Default) { CachedSearch(query, CtTrainsParser.journeys(html, query.time), System.currentTimeMillis(), CtTrainsParser.fares(html), sourceId) }
            }
            store.cache(entry)
            return SearchResult(entry, false)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return store.cached(query, sourceId)?.let { SearchResult(it, true) } ?: throw error
        }
    }
    suspend fun clearCache() = store.clearCache()
    suspend fun savedAlerts(): AlertSnapshot? = store.alerts()
    suspend fun refreshAlerts(): AlertSnapshot {
        val snapshot = withContext(Dispatchers.Default) { ServiceAlerts.parse(get(ServiceAlerts.URL), System.currentTimeMillis()) }
        store.saveAlerts(snapshot)
        return snapshot
    }
    private var railRouter: RailRouter? = null
    suspend fun trace(journey: Journey, stations: List<StationPosition>): JourneyTrace = withContext(Dispatchers.Default) {
        val router = railRouter ?: context.assets.open("rail-network.json").bufferedReader().use { RailRouter(json.decodeFromString<RailNetwork>(it.readText())) }.also { railRouter = it }
        router.trace(journey, stations)
    }
    private suspend fun get(url: String): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url)
            .header("User-Agent", "CTTrains-Android/1.0 (https://github.com/Xhanti-mbasa/cttrains-api; unofficial community app)")
            .header("Accept", "text/html, application/json").build()
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
