package za.co.cttrains.app.maps

import android.content.Context
import android.graphics.RectF
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineRegionDefinition
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*
import za.co.cttrains.app.TrainState
import za.co.cttrains.core.*

@Composable
fun StreetMap(state: TrainState, region: OfflineRegionDefinition?, onStation: (String) -> Unit, onError: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val select by rememberUpdatedState(onStation)
    val fail by rememberUpdatedState(onError)
    val view = remember(context) { TransitStreetView(context, region, { select(it) }, { fail() }) }
    DisposableEffect(view, owner) {
        var started = false; var resumed = false
        fun synchronize() {
            val level = owner.lifecycle.currentState
            if (level.isAtLeast(Lifecycle.State.STARTED) && !started) { view.onStart(); started = true }
            if (level.isAtLeast(Lifecycle.State.RESUMED) && !resumed) { view.onResume(); resumed = true }
            if (!level.isAtLeast(Lifecycle.State.RESUMED) && resumed) { view.onPause(); resumed = false }
            if (!level.isAtLeast(Lifecycle.State.STARTED) && started) { view.onStop(); started = false }
        }
        val observer = LifecycleEventObserver { _, _ -> synchronize() }
        owner.lifecycle.addObserver(observer); synchronize()
        onDispose { owner.lifecycle.removeObserver(observer); if (resumed) view.onPause(); if (started) view.onStop(); view.onDestroy() }
    }
    AndroidView(factory = { view }, modifier = modifier, update = { it.render(state) })
}

private class TransitStreetView(context: Context, private val region: OfflineRegionDefinition?, private val select: (String) -> Unit, private val fail: () -> Unit) : MapView(context) {
    private var map: MapLibreMap? = null
    private var current: TrainState? = null
    private var fitted: Journey? = null
    init {
        onCreate(null)
        addOnDidFailLoadingMapListener { fail() }
        getMapAsync { ready ->
            map = ready
            ready.uiSettings.isLogoEnabled = false
            ready.uiSettings.isAttributionEnabled = true
            ready.uiSettings.isTiltGesturesEnabled = false
            ready.cameraPosition = CameraPosition.Builder().target(LatLng(-33.95, 18.58)).zoom(10.0).build()
            if (region != null) {
                ready.setMinZoomPreference(region.minZoom)
                ready.setMaxZoomPreference(region.maxZoom)
                ready.setLatLngBoundsForCameraTarget(region.bounds)
            }
            ready.addOnMapClickListener { point ->
                val p = ready.projection.toScreenLocation(point)
                val hits = ready.queryRenderedFeatures(RectF(p.x - 20, p.y - 20, p.x + 20, p.y + 20), "rail-stations")
                hits.firstOrNull()?.getStringProperty("name")?.let { select(it); true } ?: false
            }
            val loaded = Style.OnStyleLoaded { style ->
                style.addSource(GeoJsonSource("rail-station-data", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addSource(GeoJsonSource("rail-route-data", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addSource(GeoJsonSource("rail-location-data", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addLayer(LineLayer("rail-route", "rail-route-data").withProperties(lineWidth(4f), lineColor(get("color")), lineDasharray(arrayOf(2f, 1f))))
                style.addLayer(CircleLayer("rail-stations", "rail-station-data").withProperties(circleRadius(6f), circleColor(get("color")), circleStrokeColor("#ffffff"), circleStrokeWidth(2f)))
                style.addLayer(SymbolLayer("rail-labels", "rail-station-data").withProperties(textField(get("name")), textFont(arrayOf("Noto Sans Regular")), textSize(11f), textOffset(arrayOf(0f, 1.3f)), textColor("#0a304b"), textHaloColor("#ffffff"), textHaloWidth(1.5f)))
                style.addLayer(CircleLayer("rail-location", "rail-location-data").withProperties(circleRadius(8f), circleColor("#1478B8"), circleStrokeColor("#ffffff"), circleStrokeWidth(3f)))
                region?.bounds?.let { bounds -> post { if (width > 0 && height > 0) ready.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 24)) } }
                current?.let(::render)
            }
            if (region == null) ready.setStyle(Style.Builder().fromUri(MapPlanning.STYLE_URL), loaded)
            else ready.setOfflineRegionDefinition(region, loaded)
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> current?.let(::render) }
    }
    fun render(state: TrainState) {
        current = state
        val ready = map ?: return
        val style = ready.style?.takeIf { it.isFullyLoaded } ?: return
        val stations = state.stations.filter { it.mapped }.map { p -> Feature.fromGeometry(Point.fromLngLat(p.longitude!!, p.latitude!!)).apply {
            addStringProperty("name", p.name); addStringProperty("color", if (p.name == state.to) "#d77721" else if (p.name == state.from) "#1478B8" else "#148777")
        } }
        style.getSourceAs<GeoJsonSource>("rail-station-data")?.setGeoJson(FeatureCollection.fromFeatures(stations))
        val routes = state.selectedJourney?.legs?.flatMapIndexed { i, leg -> JourneyPlanning.mappedSegments(leg, state.stations).map { points ->
            Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude!!, it.latitude!!) })).apply { addStringProperty("color", listOf("#1478B8", "#d77721", "#148777")[i % 3]) }
        } }.orEmpty()
        style.getSourceAs<GeoJsonSource>("rail-route-data")?.setGeoJson(FeatureCollection.fromFeatures(routes))
        val location = state.currentPosition?.let { listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }.orEmpty()
        style.getSourceAs<GeoJsonSource>("rail-location-data")?.setGeoJson(FeatureCollection.fromFeatures(location))
        if (state.selectedJourney != null && state.selectedJourney != fitted && width > 0 && height > 0) {
            MapPlanning.area(state.stations, state.selectedJourney)?.bounds?.let { b ->
                ready.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(b.north, b.east, b.south, b.west), 70))
            }
            fitted = state.selectedJourney
        }
    }
}
