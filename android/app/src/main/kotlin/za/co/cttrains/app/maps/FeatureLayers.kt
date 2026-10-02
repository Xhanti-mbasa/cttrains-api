package za.co.cttrains.app.maps

import za.co.cttrains.core.*

data class MapPin(val id: String,val name: String,val point: GeoPoint,val service: TransportService?,val detail: String,val spotId: String?=null)
data class FeatureLayers(val pins: List<MapPin> = emptyList(),val shapes: List<BusShape> = emptyList(),val focus: GeoPoint?=null,val fitName: String?=null,val fitPoints: List<GeoPoint> = emptyList(),val walks: List<WalkingLeg> = emptyList(),val badges: List<LegBadge> = emptyList(),val routeStations: List<StationPosition> = emptyList())
object MapStyles {
    val titles=linkedMapOf("streets" to "Streets","light" to "Light","dark" to "Dark","aerial" to "Aerial · 2025")
    fun url(id: String)=when(id) {
        "light" -> "https://tiles.openfreemap.org/styles/positron"
        "dark" -> "https://tiles.openfreemap.org/styles/dark"
        "aerial" -> "asset://cape-town-aerial.json"
        else -> MapPlanning.STYLE_URL
    }
}
