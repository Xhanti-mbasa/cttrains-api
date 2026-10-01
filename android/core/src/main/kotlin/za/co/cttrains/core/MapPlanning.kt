package za.co.cttrains.core

import kotlin.math.*

data class MapBounds(val west: Double, val south: Double, val east: Double, val north: Double)
data class MapArea(val name: String, val bounds: MapBounds, val minZoom: Int, val maxZoom: Int)

object MapPlanning {
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    const val MAX_TILES = 5000L
    const val MAX_OFFLINE_BYTES = 200L * 1024 * 1024
    const val MAX_REGIONS = 3
    fun area(stations: List<StationPosition>, journey: Journey? = null, trace: JourneyTrace? = null): MapArea? {
        val names = journey?.legs?.flatMap { it.stops }?.map { stationKey(it.name) }?.toSet()
        val points = stations.filter { it.mapped && (names == null || stationKey(it.name) in names) } +
            (if (journey != null) trace?.legs?.flatten()?.flatMap { it.points }?.map { StationPosition("Track", it.latitude, it.longitude) }.orEmpty() else emptyList())
        if (points.size < 2) return null
        val bounds = MapBounds(points.minOf { it.longitude!! } - .015, points.minOf { it.latitude!! } - .015,
            points.maxOf { it.longitude!! } + .015, points.maxOf { it.latitude!! } + .015)
        return MapArea(if (journey == null) "Cape Town rail overview" else "Selected journey streets", bounds, if (journey == null) 8 else 10, if (journey == null) 12 else 14)
    }
    fun estimatedTiles(area: MapArea): Long {
        val b = area.bounds
        require(listOf(b.west, b.east, b.south, b.north).all { it.isFinite() } && b.west >= -180 && b.east <= 180 && b.west < b.east && b.south >= -85 && b.north <= 85 && b.south < b.north)
        require(area.minZoom in 0..14 && area.maxZoom in area.minZoom..14)
        fun x(lon: Double, n: Double) = floor((lon + 180) / 360 * n).toLong()
        fun y(lat: Double, n: Double): Long { val r = Math.toRadians(lat); return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toLong() }
        return (area.minZoom..area.maxZoom).sumOf { z -> val n = 2.0.pow(z); (x(b.east, n) - x(b.west, n) + 1) * (y(b.south, n) - y(b.north, n) + 1) }
    }
}
