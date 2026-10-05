package za.co.cttrains.app.maps

import android.content.Context
import android.graphics.RectF
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
fun StreetMap(state: TrainState, region: OfflineRegionDefinition?, viewport: MapViewport, transfers: List<TransferPoint>, onStation: (String) -> Unit, onTransfer: (Int) -> Unit, onError: () -> Unit, modifier: Modifier = Modifier, styleId: String = "streets", features: FeatureLayers = FeatureLayers(), onPin: (MapPin) -> Unit = {}, onDrop: (GeoPoint) -> Unit = {}) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val select by rememberUpdatedState(onStation)
    val transfer by rememberUpdatedState(onTransfer)
    val fail by rememberUpdatedState(onError)
    val pin by rememberUpdatedState(onPin)
    val drop by rememberUpdatedState(onDrop)
    val modeKey = if (region == null) "online:$styleId" else "offline:${state.preferences.offlineMapId}:${region.bounds}"
    val view = remember(context,modeKey) { TransitStreetView(context, region, viewport, modeKey, { select(it) }, { transfer(it) }, { fail() },styleId,{ pin(it) },{ drop(it) }) }
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
        onDispose { owner.lifecycle.removeObserver(observer); view.captureViewport(); if (resumed) view.onPause(); if (started) view.onStop(); view.onDestroy() }
    }
    val extra by produceState<ExtraFrame?>(null,features.pins,features.shapes,features.walks,features.badges) {
        value=withContext(Dispatchers.Default) { prepareExtra(features) }
    }
    val rail by produceState<RailFrame?>(null,state.stations,state.selectedJourney,state.routeTrace,state.currentPosition,state.from,state.to,transfers,features.routeStations) {
        value=withContext(Dispatchers.Default) { prepareRail(state,transfers,features.routeStations) }
    }
    AndroidView(factory = { view }, modifier = modifier, update = { it.features=features;it.extraFrame=extra;it.railFrame=rail;it.render(state, transfers) })
}

private class TransitStreetView(context: Context, private val region: OfflineRegionDefinition?, private val viewport: MapViewport, private val modeKey: String, private val select: (String) -> Unit, private val transfer: (Int) -> Unit, private val fail: () -> Unit, private val styleId: String, private val pin: (MapPin)->Unit, private val drop: (GeoPoint)->Unit) : MapView(context) {
    var features=FeatureLayers()
    private var focused: GeoPoint?=viewport.featureFocus
    var extraFrame: ExtraFrame?=null
    var railFrame: RailFrame?=null
    private var renderedExtra: ExtraFrame?=null
    private var renderedRail: RailFrame?=null
    private var fittedFeature: String?=viewport.featureFit
    private var map: MapLibreMap? = null
    private var current: TrainState? = null
    private var transfers: List<TransferPoint> = emptyList()
    private var fitted: Journey? = null
    private var fittedTrace: JourneyTrace? = null
    private var closed = false
    init {
        if (viewport.modeKey != modeKey) { val retain=viewport.modeKey?.startsWith("online:")==true && modeKey.startsWith("online:");viewport.modeKey = modeKey; if(!retain) viewport.camera = null; viewport.fittedJourney = null; viewport.fittedTrace = null;viewport.featureFocus=null;focused=null;viewport.featureFit=null;fittedFeature=null }
        fitted = viewport.fittedJourney; fittedTrace = viewport.fittedTrace
        val initialFit = viewport.camera == null
        onCreate(null)
        addOnDidFailLoadingMapListener { if (!closed) fail() }
        getMapAsync { ready ->
            if (closed) return@getMapAsync
            map = ready
            ready.uiSettings.isLogoEnabled = false
            ready.uiSettings.isAttributionEnabled = true
            ready.uiSettings.isTiltGesturesEnabled = false
            ready.cameraPosition = viewport.camera ?: CameraPosition.Builder().target(LatLng(-33.95, 18.58)).zoom(10.0).build()
            ready.addOnCameraIdleListener { captureViewport() }
            ready.addOnMapLongClickListener { point -> drop(GeoPoint(point.latitude,point.longitude));true }
            if (region != null) {
                ready.setMinZoomPreference(region.minZoom)
                ready.setMaxZoomPreference(region.maxZoom)
                ready.setLatLngBoundsForCameraTarget(region.bounds)
            }
            ready.addOnMapClickListener { point ->
                val p = ready.projection.toScreenLocation(point)
                val changes = ready.queryRenderedFeatures(RectF(p.x - 24, p.y - 24, p.x + 24, p.y + 24), "rail-transfers")
                if (changes.isNotEmpty()) { transfer(changes.first().getNumberProperty("number").toInt()); return@addOnMapClickListener true }
                val extra=ready.queryRenderedFeatures(RectF(p.x-20,p.y-20,p.x+20,p.y+20),"extra-pins").firstOrNull()
                features.pins.firstOrNull { it.id==extra?.getStringProperty("id") }?.let { pin(it);return@addOnMapClickListener true }
                val hits = ready.queryRenderedFeatures(RectF(p.x - 20, p.y - 20, p.x + 20, p.y + 20), "rail-stations")
                hits.firstOrNull()?.getStringProperty("name")?.let { select(it); true } ?: false
            }
            val loaded = Style.OnStyleLoaded { style ->
                if (closed) return@OnStyleLoaded
                style.addSource(GeoJsonSource("rail-station-data", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addSource(GeoJsonSource("rail-route-data", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addSource(GeoJsonSource("rail-location-data", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addSource(GeoJsonSource("rail-transfer-data", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addSource(GeoJsonSource("extra-pin-data",FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addSource(GeoJsonSource("extra-shape-data",FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addSource(GeoJsonSource("walk-data",FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addSource(GeoJsonSource("leg-label-data",FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addLayer(LineLayer("walking-route","walk-data").withProperties(lineWidth(4f),lineColor("#8b5bc2"),lineDasharray(arrayOf(1f,1.5f))))
                style.addLayer(LineLayer("extra-shapes","extra-shape-data").withProperties(lineWidth(2f),lineColor(get("color")),lineOpacity(.65f)))
                style.addLayer(CircleLayer("extra-pins","extra-pin-data").withProperties(circleRadius(6f),circleColor(get("color")),circleStrokeColor("#ffffff"),circleStrokeWidth(2f)))
                style.addLayer(SymbolLayer("extra-labels","extra-pin-data").withProperties(textField(get("name")),textFont(arrayOf("Noto Sans Regular")),textSize(11f),textOffset(arrayOf(0f,1.3f)),textColor("#0a304b"),textHaloColor("#ffffff"),textHaloWidth(1.5f)))
                style.addLayer(LineLayer("rail-route", "rail-route-data").withFilter(eq(get("rail"), literal(true))).withProperties(lineWidth(4f), lineColor(get("color"))))
                style.addLayer(LineLayer("rail-route-gaps", "rail-route-data").withFilter(eq(get("rail"), literal(false))).withProperties(lineWidth(3f), lineColor(get("color")), lineDasharray(arrayOf(2f, 1f))))
                style.addLayer(CircleLayer("rail-stations", "rail-station-data").withProperties(circleRadius(6f), circleColor(get("color")), circleStrokeColor("#ffffff"), circleStrokeWidth(2f)))
                style.addLayer(SymbolLayer("rail-labels", "rail-station-data").withFilter(eq(get("transfer"), literal(false))).withProperties(textField(get("name")), textFont(arrayOf("Noto Sans Regular")), textSize(11f), textOffset(arrayOf(0f, 1.3f)), textColor("#0a304b"), textHaloColor("#ffffff"), textHaloWidth(1.5f)))
                style.addLayer(CircleLayer("rail-location", "rail-location-data").withProperties(circleRadius(8f), circleColor("#1478B8"), circleStrokeColor("#ffffff"), circleStrokeWidth(3f)))
                style.addLayer(CircleLayer("rail-transfers", "rail-transfer-data").withProperties(circleRadius(13f), circleColor("#713B00"), circleStrokeColor("#ffffff"), circleStrokeWidth(3f)))
                style.addLayer(SymbolLayer("rail-transfer-numbers", "rail-transfer-data").withProperties(textField(get("numberText")), textFont(arrayOf("Noto Sans Bold")), textSize(14f), textColor("#ffffff"), textAllowOverlap(true), textIgnorePlacement(true)))
                style.addLayer(SymbolLayer("rail-transfer-labels", "rail-transfer-data").withProperties(textField(get("label")), textFont(arrayOf("Noto Sans Bold")), textSize(12f), textOffset(arrayOf(0f, 2.3f)), textColor("#713B00"), textHaloColor("#ffffff"), textHaloWidth(2f), textMaxWidth(24f)))
                val bubble=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888).apply {
                    val canvas=android.graphics.Canvas(this)
                    canvas.drawRoundRect(0f,0f,32f,32f,7f,7f,android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.WHITE })
                }
                style.addImage("leg-bubble",bubble)
                style.addLayer(SymbolLayer("journey-leg-labels","leg-label-data").withProperties(
                    iconImage("leg-bubble"),iconTextFit(Property.ICON_TEXT_FIT_BOTH),iconTextFitPadding(arrayOf(3f,5f,3f,5f)),
                    textField(get("label")),textFont(arrayOf("Noto Sans Regular")),
                    textSize(interpolate(linear(),zoom(),stop(9.0,9),stop(12.0,11),stop(16.0,12))),
                    textOffset(arrayOf(0f,-1.7f)),textColor("#172724"),textMaxWidth(12f),
                    textAllowOverlap(false),iconAllowOverlap(false),textIgnorePlacement(false),iconIgnorePlacement(false)
                ).apply { minZoom=9f })
                if (initialFit) region?.bounds?.let { bounds -> post { if (!closed && current?.selectedJourney == null && width > 0 && height > 0) ready.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 24)) } }
                current?.let { render(it, transfers) }
            }
            if (region == null) ready.setStyle(Style.Builder().fromUri(MapStyles.url(styleId)), loaded)
            else ready.setOfflineRegionDefinition(region, loaded)
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> current?.let { render(it, transfers) } }
    }
    fun captureViewport() {
        if (closed || viewport.modeKey != modeKey) return
        map?.let { viewport.camera = it.cameraPosition; viewport.fittedJourney = fitted; viewport.fittedTrace = fittedTrace }
    }
    override fun onDestroy() { closed = true; super.onDestroy() }
    fun render(state: TrainState, changes: List<TransferPoint>) {
        current = state
        transfers = changes
        if (state.selectedJourney == null) { fitted = null; fittedTrace = null }
        val ready = map ?: return
        val style = ready.style?.takeIf { it.isFullyLoaded } ?: return
        extraFrame?.let { frame -> if(frame!==renderedExtra) {
            style.getSourceAs<GeoJsonSource>("extra-pin-data")?.setGeoJson(frame.pins)
            style.getSourceAs<GeoJsonSource>("extra-shape-data")?.setGeoJson(frame.shapes)
            style.getSourceAs<GeoJsonSource>("walk-data")?.setGeoJson(frame.walks)
            style.getSourceAs<GeoJsonSource>("leg-label-data")?.setGeoJson(frame.badges)
            renderedExtra=frame
        } }
        railFrame?.let { frame -> if(frame!==renderedRail) {
            style.getSourceAs<GeoJsonSource>("rail-station-data")?.setGeoJson(frame.stations)
            style.getSourceAs<GeoJsonSource>("rail-route-data")?.setGeoJson(frame.routes)
            style.getSourceAs<GeoJsonSource>("rail-location-data")?.setGeoJson(frame.location)
            style.getSourceAs<GeoJsonSource>("rail-transfer-data")?.setGeoJson(frame.transfers)
            renderedRail=frame
        } }
        if(features.focus!=null && features.focus!=focused) { val p=features.focus!!;ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude,p.longitude),if(region==null) 15.0 else region.maxZoom.coerceAtMost(15.0)));focused=p;viewport.featureFocus=p }
        if (state.selectedJourney != null && (state.selectedJourney != fitted || state.routeTrace != fittedTrace) && width > 0 && height > 0) {
            MapPlanning.area(state.stations, state.selectedJourney, state.routeTrace)?.bounds?.let { b ->
                ready.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(b.north, b.east, b.south, b.west), 70))
            }
            fitted = state.selectedJourney
            fittedTrace = state.routeTrace
        }
        if(features.fitName==null) { fittedFeature=null;viewport.featureFit=null }
        if(features.fitName!=null && features.fitName!=fittedFeature && features.fitPoints.size>=2 && width>0 && height>0) {
            val p=features.fitPoints
            ready.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(p.maxOf { it.latitude }+.003,p.maxOf { it.longitude }+.003,p.minOf { it.latitude }-.003,p.minOf { it.longitude }-.003),50))
            fittedFeature=features.fitName;viewport.featureFit=features.fitName
        }
    }
}

private data class ExtraFrame(val pins: String,val shapes: String,val walks: String,val badges: String)
private data class RailFrame(val stations: String,val routes: String,val location: String,val transfers: String)
private fun prepareExtra(features: FeatureLayers): ExtraFrame {
    val pins=features.pins.map { p -> Feature.fromGeometry(Point.fromLngLat(p.point.longitude,p.point.latitude)).apply { addStringProperty("id",p.id);addStringProperty("name",p.name);addStringProperty("color",p.service?.color ?: "#8b5bc2") } }
    val shapes=features.shapes.filter { it.points.size>=2 }.map { shape -> Feature.fromGeometry(LineString.fromLngLats(shape.points.map { Point.fromLngLat(it.longitude,it.latitude) })).apply { addStringProperty("color",if(shape.source.startsWith("Saved directions")) "#1478B8" else if(shape.source.contains("myciti")) TransportService.MYCITI.color else TransportService.GOLDEN_ARROW.color) } }
    val walks=features.walks.map { leg -> Feature.fromGeometry(LineString.fromLngLats(leg.points.map { Point.fromLngLat(it.longitude,it.latitude) })) }
    val badges=features.badges.map { b -> Feature.fromGeometry(Point.fromLngLat(b.point.longitude,b.point.latitude)).apply { addStringProperty("label",b.compact) } }
    return ExtraFrame(FeatureCollection.fromFeatures(pins).toJson(),FeatureCollection.fromFeatures(shapes).toJson(),FeatureCollection.fromFeatures(walks).toJson(),FeatureCollection.fromFeatures(badges).toJson())
}
private fun prepareRail(state: TrainState,changes: List<TransferPoint>,routeStations: List<StationPosition>): RailFrame {
        val stations = state.stations.filter { it.mapped }.map { p -> Feature.fromGeometry(Point.fromLngLat(p.longitude!!, p.latitude!!)).apply {
            addStringProperty("name", p.name); addStringProperty("color", if (p.name == state.to) "#d77721" else if (p.name == state.from) "#1478B8" else "#148777")
            addBooleanProperty("transfer", changes.any { stationKey(it.station) == stationKey(p.name) })
        } }
        val routes = state.selectedJourney?.legs?.flatMapIndexed { i, leg ->
            val sections = state.routeTrace?.legs?.getOrNull(i) ?: JourneyPlanning.mappedSegments(leg, routeStations.ifEmpty { state.stations }).map { points -> RouteSection(points.map { TrackPoint(it.latitude!!, it.longitude!!) }, false) }
            sections.map { section ->
            Feature.fromGeometry(LineString.fromLngLats(section.points.map { Point.fromLngLat(it.longitude, it.latitude) })).apply { addStringProperty("color", listOf("#1478B8", "#d77721", "#148777")[i % 3]); addBooleanProperty("rail", section.followsRail) }
        } }.orEmpty()
        val location = state.currentPosition?.let { listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }.orEmpty()
        val pins = changes.mapNotNull { change -> change.position?.let { p -> Feature.fromGeometry(Point.fromLngLat(p.longitude!!, p.latitude!!)).apply {
            addNumberProperty("number", change.number); addStringProperty("numberText", "${change.number}")
            addStringProperty("label", "Change ${change.number} · ${change.station}\nTrain ${change.nextTrain} · ${change.boardingLabel}")
        } } }
    return RailFrame(FeatureCollection.fromFeatures(stations).toJson(),FeatureCollection.fromFeatures(routes).toJson(),FeatureCollection.fromFeatures(location).toJson(),FeatureCollection.fromFeatures(pins).toJson())
}
