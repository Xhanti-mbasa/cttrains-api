package za.co.cttrains.app.maps

import org.maplibre.android.camera.CameraPosition
import za.co.cttrains.core.Journey
import za.co.cttrains.core.JourneyTrace

/** Memory only: shared by mini/full-screen views, never written to a profile. */
class MapViewport {
    var modeKey: String? = null
    var camera: CameraPosition? = null
    var fittedJourney: Journey? = null
    var fittedTrace: JourneyTrace? = null
    var stationJourney: Journey? = null
    var stationZoom: Float = 1f
    var stationPanX: Float = 0f
    var stationPanY: Float = 0f
    var featureFocus: za.co.cttrains.core.GeoPoint? = null
    var stationCenter: za.co.cttrains.core.GeoPoint? = null
    var stationFocus: za.co.cttrains.core.GeoPoint? = null
    var featureFit: String? = null
    fun reset() { modeKey = null; camera = null; fittedJourney = null; fittedTrace = null; stationJourney = null; stationZoom = 1f; stationPanX = 0f; stationPanY = 0f;featureFocus=null;stationFocus=null;stationCenter=null;featureFit=null }
}
