package za.co.cttrains.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import java.util.UUID
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
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
    private val largeValues=File(context.filesDir,"large-values").apply { mkdirs() }
    private val blobPrefix="cttrains.encrypted-blob.v1:"
    private fun content(encrypted: String): String {
        val value=codec.decrypt(encrypted)
        if(!value.startsWith(blobPrefix)) return value
        val id=value.removePrefix(blobPrefix);require(Regex("[a-f0-9-]{36}").matches(id))
        return codec.decrypt(File(largeValues,id).readText())
    }

    val preferences = dao.observeValue("profile").distinctUntilChanged().map { row ->
        row?.let { json.decodeFromString<Preferences>(content(it.encryptedValue)) } ?: Preferences()
    }
    val stations = dao.stations().map { rows -> rows.map { StationPosition(it.name, it.latitude, it.longitude) } }
    val journeys = dao.journeys().map { rows -> rows.map { decodeJourney(it) } }
    val changes = kotlinx.coroutines.flow.combine(dao.observeValues(), dao.journeys(), dao.observeCaches()) { values, journeys, caches ->
        listOf(values.filter { it.key == "profile" || it.key == "extras" || it.key == "catalog" || (it.key.startsWith("bus-") || it.key.startsWith("walk-")) }, journeys, caches).hashCode()
    }.distinctUntilChanged()
    fun observeLocal(key: String) = dao.observeValue(key).distinctUntilChanged().map { row ->
        if(row==null) null else lock.withLock { dao.value(key)?.let { content(it.encryptedValue) } }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)
    suspend fun readLocal(key: String): String? { initialize(); return lock.withLock { dao.value(key)?.let { content(it.encryptedValue) } } }
    suspend fun updateLocal(key: String, initial: String, transform: (String) -> String) {
        initialize(); lock.withLock { saveValue(key, transform(dao.value(key)?.let { content(it.encryptedValue) } ?: initial)) }
    }
    suspend fun writeLocal(key: String, value: String) = withContext(Dispatchers.IO) { initialize(); lock.withLock { saveValue(key, value) } }
    suspend fun trimWalkCaches() = withContext(Dispatchers.IO) {
        initialize();lock.withLock {
            dao.allValues().filter { it.key.startsWith("walk-") }.dropLast(20).forEach { dao.deleteValue(it.key) }
        }
    }
    suspend fun searches(): List<CachedSearch> { initialize(); return dao.caches().map { json.decodeFromString<CachedSearch>(codec.decrypt(it.encryptedValue)) } }
    suspend fun portable(): PortableProfile {
        initialize()
        return lock.withLock { database.withTransaction {
            val values = dao.allValues().associate { it.key to content(it.encryptedValue) }
            PortableProfile(createdAt = System.currentTimeMillis(), preferences = json.decodeFromString(values.getValue("profile")),
                plans = dao.allJourneys().map(::decodeJourney), searches = dao.caches().map { json.decodeFromString(codec.decrypt(it.encryptedValue)) },
                catalog = values["catalog"]?.let { json.decodeFromString(it) }, values = values.filterKeys { it == "extras" || (it.startsWith("bus-") || it.startsWith("walk-")) })
        } }
    }
    suspend fun replacePersonalData(prefs: Preferences, plans: List<ScheduledJourney>, searches: List<CachedSearch>, catalog: StationCatalog?, values: Map<String, String>) {
        initialize(); lock.withLock { database.withTransaction {
            dao.clearValues(); dao.clearJourneys(); dao.clearCache()
            saveValue("profile", json.encodeToString(prefs))
            if (catalog != null) saveValue("catalog", json.encodeToString(catalog))
            values.forEach { (key, value) -> require(key == "extras" || (key.startsWith("bus-") || key.startsWith("walk-"))); saveValue(key,value) }
            plans.forEach { item -> val stopped = item.copy(remindersStopped = true, nextReminderAt = null)
                dao.journey(JourneyEntity(stopped.id,SecureCodec.digest(stopped.identity),stopped.departureAt,codec.encrypt(json.encodeToString(stopped)))) }
            CachePolicy.prune(searches,System.currentTimeMillis()).forEach { putCache(it) }
        } }
    }

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
        return dao.value("catalog")?.let { json.decodeFromString<StationCatalog>(content(it.encryptedValue)) }
    }
    suspend fun saveCatalog(value: StationCatalog) { initialize(); saveValue("catalog", json.encodeToString(value)) }
    suspend fun alerts(): AlertSnapshot? {
        initialize()
        return dao.value("service-alerts")?.let { json.decodeFromString<AlertSnapshot>(content(it.encryptedValue)) }
            ?.takeIf { ServiceAlerts.usable(it, System.currentTimeMillis()) }
    }
    suspend fun saveAlerts(snapshot: AlertSnapshot) { initialize(); lock.withLock { saveValue("service-alerts", json.encodeToString(snapshot)) } }
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
    private suspend fun saveValue(key: String, value: String) {
        val payload=if(value.toByteArray().size>128*1024) {
            val id=UUID.randomUUID().toString();val file=File(largeValues,id)
            java.io.FileOutputStream(file).use { stream -> stream.write(codec.encrypt(value).toByteArray());stream.fd.sync() };blobPrefix+id
        } else value
        dao.value(LocalValue(key,codec.encrypt(payload)))
    }
    suspend fun cleanLargeValues() {
        initialize();lock.withLock {
            val referenced=dao.allValues().mapNotNull { row -> codec.decrypt(row.encryptedValue).takeIf { it.startsWith(blobPrefix) }?.removePrefix(blobPrefix) }.toSet()
            // Keep recent old versions for in-flight observers; never delete a referenced payload.
            largeValues.listFiles()?.filter { it.name !in referenced && System.currentTimeMillis()-it.lastModified()>60*60_000L }?.forEach { it.delete() }
        }
    }
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
            largeValues.listFiles()?.forEach { it.delete() }
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
