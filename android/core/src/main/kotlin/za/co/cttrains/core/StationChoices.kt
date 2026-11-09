package za.co.cttrains.core

/** Proximity is a suggestion. An explicit station never falls back to a closer one. */
object StationChoices {
    fun ranked(point: GeoPoint, stations: List<StationPosition>, available: List<String>): List<StationPosition> {
        val keys=available.map(::stationKey).toSet()
        return stations.filter { it.mapped && stationKey(it.name) in keys }
            .distinctBy { stationKey(it.name) }
            .sortedBy { NearbySuggestions.distance(point,WalkingRoutes.endpoint(it).point) }
    }
    fun candidates(place: WalkEndpoint, stations: List<StationPosition>, available: List<String>,
                   maxMeters: Int, preferred: String?=null): List<StationPosition> {
        val explicit=if(place.id.startsWith("station:")) place.id.removePrefix("station:") else preferred?.let(::stationKey)
        val ranked=ranked(place.point,stations,available)
        if(explicit!=null) return ranked.filter { stationKey(it.name)==explicit &&
            (place.id=="station:$explicit" || NearbySuggestions.distance(place.point,WalkingRoutes.endpoint(it).point)<=maxMeters) }.take(1)
        return ranked.filter { NearbySuggestions.distance(place.point,WalkingRoutes.endpoint(it).point)<=maxMeters }.take(2)
    }
    fun nearbyPlaces(station: StationPosition, spots: List<MarkedSpot>, maxMeters: Int=5000): List<MarkedSpot> {
        if(!station.mapped) return emptyList()
        val point=WalkingRoutes.endpoint(station).point
        return spots.filter { it.point.valid && NearbySuggestions.distance(point,it.point)<=maxMeters }
            .sortedBy { NearbySuggestions.distance(point,it.point) }
    }
}
