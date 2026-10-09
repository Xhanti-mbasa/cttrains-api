package za.co.cttrains.app.data

import android.content.Context
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import za.co.cttrains.core.*

@Serializable data class BusFavorite(val service: TransportService, val routeId: String, val day: String)
@Serializable data class BusPriceCorrection(val service: TransportService, val fareId: String, val product: String, val cents: Long, val changedAt: Long)
@Serializable data class SavedPlace(val label: String, val point: GeoPoint)
@Serializable data class LocalExtras(
    val beta: BetaPreferences = BetaPreferences(), val localName: String = "",
    val spots: List<MarkedSpot> = emptyList(), val trips: List<SafetyTrip> = emptyList(),
    val friends: List<CircleLink> = emptyList(), val rooms: List<ReportRoom> = emptyList(),
    val reports: List<ReceivedReport> = emptyList(), val queued: List<QueuedReport> = emptyList(),
    val busFavorites: List<BusFavorite> = emptyList(), val priceCorrections: List<BusPriceCorrection> = emptyList(),
    val places: List<SavedPlace> = emptyList(),
    val routines: List<CommuteRoutine> = emptyList(),
    val walkSamples: List<WalkSample> = emptyList(),
    val busStopLinks: List<BusStopLink> = emptyList(),
)
@Serializable data class MatrixSession(val server: String, val userId: String, val token: String, val deviceId: String)
@Serializable data class BackupLink(val uri: String, val password: String)
@Serializable data class LiveCircle(val link: CircleLink, val phase: String = "Ready", val sharing: Boolean = false)
@Serializable data class WatchedFriend(val id: String,val update: FriendUpdate)

class FeatureStore(context: Context) {
    val store=LocalTransitStore.get(context)
    val json=Json { ignoreUnknownKeys=true; encodeDefaults=true }
    val extras=store.observeLocal("extras").map { it?.let { json.decodeFromString<LocalExtras>(it) } ?: LocalExtras() }.flowOn(kotlinx.coroutines.Dispatchers.Default)
    val session=store.observeLocal("matrix-session").map { it?.takeIf(String::isNotBlank)?.let { json.decodeFromString<MatrixSession>(it) } }
    val circle=store.observeLocal("live-circle").map { it?.takeIf(String::isNotBlank)?.let { json.decodeFromString<LiveCircle>(it) } }
    suspend fun extras()=store.readLocal("extras")?.let { json.decodeFromString<LocalExtras>(it) } ?: LocalExtras()
    suspend fun update(transform: (LocalExtras)->LocalExtras) = store.updateLocal("extras",json.encodeToString(LocalExtras())) { json.encodeToString(transform(json.decodeFromString(it))) }
    suspend fun session()=store.readLocal("matrix-session")?.takeIf(String::isNotBlank)?.let { json.decodeFromString<MatrixSession>(it) }
    suspend fun saveSession(session: MatrixSession?)=store.writeLocal("matrix-session",session?.let { json.encodeToString(it) }.orEmpty())
    suspend fun liveCircle()=store.readLocal("live-circle")?.takeIf(String::isNotBlank)?.let { json.decodeFromString<LiveCircle>(it) }
    suspend fun saveCircle(circle: LiveCircle?)=store.writeLocal("live-circle",circle?.let { json.encodeToString(it) }.orEmpty())
    suspend fun snapshot(service: TransportService)=store.readLocal("bus-${service.name}")?.let { json.decodeFromString<BusSnapshot>(it) }
    suspend fun saveSnapshot(value: BusSnapshot)=store.writeLocal("bus-${value.service.name}",json.encodeToString(value))
    fun observeSnapshot(service: TransportService)=store.observeLocal("bus-${service.name}").map { it?.let { raw -> json.decodeFromString<BusSnapshot>(raw) } }.flowOn(kotlinx.coroutines.Dispatchers.Default)
    val watched=store.observeLocal("live-friends").map { it?.takeIf(String::isNotBlank)?.let { raw -> json.decodeFromString<List<WatchedFriend>>(raw) } ?: emptyList() }
    suspend fun watched()=store.readLocal("live-friends")?.takeIf(String::isNotBlank)?.let { json.decodeFromString<List<WatchedFriend>>(it) } ?: emptyList()
    suspend fun saveWatched(rows: List<WatchedFriend>)=store.writeLocal("live-friends",json.encodeToString(rows.map { it.copy(update=it.update.copy(point=null,accuracy=null)) }))
    private fun timeKey(service: TransportService,route: String,day: String)="bus-time-${service.name}-${SecureCodec.digest("$route|$day")}" 
    suspend fun timetable(service: TransportService,route: String,day: String)=store.readLocal(timeKey(service,route,day))?.let { json.decodeFromString<BusTimetable>(it) }
    suspend fun saveTimetable(value: BusTimetable)=store.writeLocal(timeKey(value.service,value.route.id,value.day),json.encodeToString(value))
    suspend fun backupLink()=store.readLocal("backup-link")?.takeIf(String::isNotBlank)?.let { json.decodeFromString<BackupLink>(it) }
    suspend fun saveBackupLink(link: BackupLink?)=store.writeLocal("backup-link",link?.let { json.encodeToString(it) }.orEmpty())
}
