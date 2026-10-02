package za.co.cttrains.app.maps

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.*
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import za.co.cttrains.core.*

object MapEngine {
    @Volatile private var networkAllowed = true
    private var initialized = false
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            if (!networkAllowed) throw IOException("Offline map mode")
            chain.proceed(chain.request().newBuilder().header("User-Agent", "CTTrains-Android/1.2 (MapLibre; community app)").build())
        }.build()
    fun initialize(context: Context) {
        if (initialized) return
        MapLibre.getInstance(context.applicationContext)
        HttpRequestUtil.setOkHttpClient(client)
        HttpRequestUtil.setLogEnabled(false)
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        initialized = true
    }
    fun mode(mode: String) {
        networkAllowed = mode == "online"
        if (!networkAllowed) client.dispatcher.cancelAll()
        MapLibre.setConnected(if (networkAllowed) null else false)
    }
    fun online(): Boolean = networkAllowed
}

data class MapDownload(val id: Long, val name: String, val bytes: Long = 0, val completed: Long = 0, val required: Long = 0,
    val complete: Boolean = false, val active: Boolean = false, val error: String? = null, val deleting: Boolean = false)
data class MapDownloadsState(val loading: Boolean = true, val creating: Boolean = false, val regions: List<MapDownload> = emptyList(), val error: String? = null)

/** Foreground-only downloads with explicit resume, progress, storage bounds and deletion. */
class OfflineMaps(context: Context) {
    private val app = context.applicationContext
    private val mutable = MutableStateFlow(MapDownloadsState())
    val state = mutable.asStateFlow()
    private val regions = mutableMapOf<Long, OfflineRegion>()
    private val manager: OfflineManager
    private var foreground = true
    init {
        MapEngine.initialize(app)
        manager = OfflineManager.getInstance(app)
        manager.setMaximumAmbientCacheSize(32L * 1024 * 1024, null)
        refresh()
    }
    fun refresh() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                regions.clear()
                val rows = offlineRegions.orEmpty().map { region ->
                    regions[region.id] = region
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    val name = runCatching { JSONObject(String(region.metadata)).getString("name") }.getOrDefault("Saved map area")
                    observe(region)
                    MapDownload(region.id, name)
                }
                mutable.update { it.copy(loading = false, regions = rows, error = null) }
                offlineRegions.orEmpty().forEach { region -> region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status: OfflineRegionStatus?) { if (status != null) status(region, status) }
                    override fun onError(error: String?) = regionError(region, "Couldn’t read this saved map.")
                }) }
            }
            override fun onError(error: String) { mutable.update { it.copy(loading = false, error = "Couldn’t open downloaded maps.") } }
        })
    }
    private fun observe(region: OfflineRegion) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) = status(region, status)
            override fun onError(error: OfflineRegionError) { region.setDownloadState(OfflineRegion.STATE_INACTIVE); regionError(region, "Download paused. Check your connection and resume.") }
            override fun mapboxTileCountLimitExceeded(limit: Long) { region.setDownloadState(OfflineRegion.STATE_INACTIVE); regionError(region, "Map tile limit reached. Delete an area or use a smaller map.") }
        })
    }
    private fun status(region: OfflineRegion, status: OfflineRegionStatus) {
        if (status.isComplete) region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        mutable.update { it.copy(regions = it.regions.map { row -> if (row.id == region.id) row.copy(bytes = status.completedResourceSize, completed = status.completedResourceCount,
            required = status.requiredResourceCount, complete = status.isComplete, active = !status.isComplete && status.downloadState == OfflineRegion.STATE_ACTIVE) else row }) }
        if (mutable.value.regions.sumOf { it.bytes } >= MapPlanning.MAX_OFFLINE_BYTES && !status.isComplete) {
            pauseAll(); regionError(region, "200 MB download budget reached. Delete an area before resuming.")
        }
    }
    private fun regionError(region: OfflineRegion, message: String) { mutable.update { it.copy(regions = it.regions.map { row -> if (row.id == region.id) row.copy(active = false, error = message) else row }) } }
    private fun connected(): Boolean {
        val service = app.getSystemService(ConnectivityManager::class.java)
        return service.getNetworkCapabilities(service.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    fun create(area: MapArea, style: String = "streets") {
        val error = when {
            !foreground || !connected() -> "Open the app with an internet connection to download maps."
            state.value.loading || state.value.creating -> "Wait for the current map operation."
            state.value.regions.size >= MapPlanning.MAX_REGIONS -> "Keep up to three map areas. Delete one first."
            state.value.regions.sumOf { it.bytes } >= MapPlanning.MAX_OFFLINE_BYTES -> "Delete a saved map to free download space."
            runCatching { MapPlanning.estimatedTiles(area) }.getOrDefault(Long.MAX_VALUE) > MapPlanning.MAX_TILES -> "This area is too large. Download the rail overview or a shorter journey."
            else -> null
        }
        if (error != null) { mutable.update { it.copy(error = error) }; return }
        pauseAll()
        mutable.update { it.copy(creating = true, error = null) }
        val b = area.bounds
        val name = "${area.name} · ${MapStyles.titles[style] ?: "Streets"}"
        val definition = OfflineTilePyramidRegionDefinition(MapStyles.url(style), LatLngBounds.from(b.north, b.east, b.south, b.west), area.minZoom.toDouble(), area.maxZoom.toDouble(), 1f, false)
        manager.createOfflineRegion(definition, JSONObject().put("name", name).put("style",style).toString().toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(region: OfflineRegion) {
                regions[region.id] = region; observe(region)
                mutable.update { it.copy(creating = false, regions = it.regions + MapDownload(region.id, name)) }
                if (foreground && MapEngine.online()) resume(region.id)
            }
            override fun onError(error: String) { mutable.update { it.copy(creating = false, error = "Couldn’t create this map download.") } }
        })
    }
    fun resume(id: Long) {
        if (!MapEngine.online()) { mutable.update { it.copy(error = "Switch to Online to resume downloads.") }; return }
        if (!foreground || !connected()) { mutable.update { it.copy(error = "Connect to resume this download.") }; return }
        if (mutable.value.regions.sumOf { it.bytes } >= MapPlanning.MAX_OFFLINE_BYTES) { mutable.update { it.copy(error = "Delete an area before resuming downloads.") }; return }
        pauseAll()
        val region = regions[id] ?: return
        mutable.update { it.copy(error = null, regions = it.regions.map { row -> if (row.id == id) row.copy(error = null, active = true) else row }) }
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }
    fun pause(id: Long) { regions[id]?.setDownloadState(OfflineRegion.STATE_INACTIVE); mutable.update { it.copy(regions = it.regions.map { row -> if (row.id == id) row.copy(active = false) else row }) } }
    fun pauseAll() { regions.keys.toList().forEach(::pause) }
    fun definition(id: Long?): OfflineRegionDefinition? = id?.let { regions[it]?.definition }
    fun foreground(value: Boolean) { foreground = value; if (!value) pauseAll() }
    fun delete(id: Long) {
        val region = regions[id] ?: return
        pause(id)
        mutable.update { it.copy(regions = it.regions.map { row -> if (row.id == id) row.copy(deleting = true) else row }) }
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { regions.remove(id); mutable.update { it.copy(regions = it.regions.filter { row -> row.id != id }) } }
            override fun onError(error: String) { regionError(region, "Couldn’t delete this map. Try again."); mutable.update { it.copy(regions = it.regions.map { row -> if (row.id == id) row.copy(deleting = false) else row }) } }
        })
    }
    fun clearCache() { pauseAll(); manager.clearAmbientCache(object : OfflineManager.FileSourceCallback {
        override fun onSuccess() { mutable.update { it.copy(error = null) } }
        override fun onError(error: String) { mutable.update { it.copy(error = "Couldn’t clear temporary map tiles.") } }
    }) }
}
