package za.co.cttrains.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import za.co.cttrains.core.*
import za.co.cttrains.app.BuildConfig

private val Context.legacyTrainPreferences by preferencesDataStore("cttrains")

class LocalTransitStore private constructor(private val context: Context) {
    private val database = TransitDatabase.get(context)
    private val dao = database.dao()
    private val codec = SecureCodec()
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var initialized = false

    val preferences = dao.observeValue("profile").map { row ->
        row?.let { json.decodeFromString<Preferences>(codec.decrypt(it.encryptedValue)) } ?: Preferences()
    }
    val stations = dao.stations().map { rows -> rows.map { StationPosition(it.name, it.latitude, it.longitude) } }
    val journeys = dao.journeys().map { rows -> rows.map { decodeJourney(it) } }

    suspend fun initialize() = lock.withLock {
        if (initialized) return@withLock
        withContext(Dispatchers.IO) {
            if (dao.value("profile") == null) {
                val old = context.legacyTrainPreferences.data.first()
                val prefs = runCatching { json.decodeFromString<Preferences>(old[stringPreferencesKey("preferences")].orEmpty()) }.getOrDefault(Preferences())
                val cache = runCatching { json.decodeFromString<List<CachedSearch>>(old[stringPreferencesKey("search_cache")].orEmpty()) }.getOrDefault(emptyList())
                val catalog = old[stringPreferencesKey("catalog")]
                database.withTransaction {
                    val source = if (old[stringPreferencesKey("preferences")] == null && BuildConfig.TRANSIT_BASE_URL.isNotBlank()) "cttrains-api" else prefs.sourceId
                    saveValue("profile", json.encodeToString(prefs.copy(profileId = UUID.randomUUID().toString(), sourceId = source)))
                    CachePolicy.prune(cache, System.currentTimeMillis()).forEach { putCache(it) }
                    if (!catalog.isNullOrBlank()) saveValue("catalog", catalog)
                    val positions = context.assets.open("station-positions.json").bufferedReader().use { json.decodeFromString<List<StationPosition>>(it.readText()) }
                    dao.stations(positions.map { StationEntity(it.name, it.latitude, it.longitude) })
                }
            }
            // Only remove old plaintext records after the Room transaction has committed.
            context.legacyTrainPreferences.edit { it.clear() }
            dao.pruneJourneys(System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000)
            dao.pruneCache(System.currentTimeMillis() - CachePolicy.MAX_AGE_MILLIS, System.currentTimeMillis())
        }
        initialized = true
    }

    suspend fun updatePreferences(transform: (Preferences) -> Preferences) {
        initialize()
        lock.withLock {
            val current = preferences.first()
            saveValue("profile", json.encodeToString(transform(current)))
        }
    }
    suspend fun catalog(): StationCatalog? {
        initialize()
        return dao.value("catalog")?.let { json.decodeFromString<StationCatalog>(codec.decrypt(it.encryptedValue)) }
    }
    suspend fun saveCatalog(value: StationCatalog) { initialize(); saveValue("catalog", json.encodeToString(value)) }
    suspend fun cached(query: SearchQuery, sourceId: String): CachedSearch? {
        initialize()
        val row = dao.cache(cacheKey(query, sourceId)) ?: return null
        val now = System.currentTimeMillis()
        if (row.fetchedAt > now || now - row.fetchedAt > CachePolicy.MAX_AGE_MILLIS) return null
        return json.decodeFromString<CachedSearch>(codec.decrypt(row.encryptedValue))
    }
    suspend fun cache(entry: CachedSearch) { initialize(); lock.withLock { putCache(entry); dao.pruneCache(entry.fetchedAt - CachePolicy.MAX_AGE_MILLIS, System.currentTimeMillis()) } }
    suspend fun clearCache() { initialize(); lock.withLock { dao.clearCache() } }
    private fun cacheKey(query: SearchQuery, sourceId: String) = SecureCodec.digest("$sourceId|${json.encodeToString(query)}")
    private suspend fun putCache(entry: CachedSearch) = dao.cache(CacheEntity(cacheKey(entry.query, entry.sourceId), entry.fetchedAt, codec.encrypt(json.encodeToString(entry))))
    private suspend fun saveValue(key: String, value: String) = dao.value(LocalValue(key, codec.encrypt(value)))
    private fun decodeJourney(row: JourneyEntity) = json.decodeFromString<ScheduledJourney>(codec.decrypt(row.encryptedValue))

    suspend fun saveJourney(item: ScheduledJourney): ScheduledJourney {
        initialize()
        return lock.withLock {
            val old = dao.byIdentity(SecureCodec.digest(item.identity))
            val saved = item.copy(id = old?.id ?: item.id)
            dao.journey(JourneyEntity(saved.id, SecureCodec.digest(saved.identity), saved.departureAt, codec.encrypt(json.encodeToString(saved))))
            saved
        }
    }
    suspend fun journey(id: String): ScheduledJourney? { initialize(); return dao.journey(id)?.let(::decodeJourney) }
    suspend fun updateJourney(id: String, transform: (ScheduledJourney) -> ScheduledJourney?): ScheduledJourney? {
        initialize()
        return lock.withLock {
            val item = dao.journey(id)?.let(::decodeJourney) ?: return@withLock null
            val updated = transform(item) ?: return@withLock null
            dao.journey(JourneyEntity(updated.id, SecureCodec.digest(updated.identity), updated.departureAt, codec.encrypt(json.encodeToString(updated))))
            updated
        }
    }
    suspend fun deleteJourney(id: String) { initialize(); lock.withLock { dao.deleteJourney(id) } }
    suspend fun clearPersonalData() {
        initialize()
        lock.withLock {
            database.withTransaction {
                dao.clearJourneys(); dao.clearCache(); dao.clearValues()
                saveValue("profile", json.encodeToString(Preferences(profileId = UUID.randomUUID().toString(), sourceId = if (BuildConfig.TRANSIT_BASE_URL.isNotBlank()) "cttrains-api" else "cttrains-web")))
            }
        }
    }
    companion object {
        // get() stores only applicationContext, never an Activity or View context.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: LocalTransitStore? = null
        fun get(context: Context): LocalTransitStore = instance ?: synchronized(this) {
            instance ?: LocalTransitStore(context.applicationContext).also { instance = it }
        }
    }
}
