package za.co.cttrains.core

import java.net.URLEncoder

/** A place handed to Uber. A null point means "use the rider's current location in Uber". */
data class UberPlace(val name: String, val point: GeoPoint?)

/**
 * Builds Uber's official universal link (https://m.uber.com/ul/?action=setPickup...).
 * This is a handoff, not an API call: Uber opens with the places filled in and the rider
 * reviews, edits and confirms (or cancels) the ride in Uber. Nothing is booked, priced
 * or paid inside CT Trains, and no Uber account or token is involved.
 */
object UberHandoff {
    private const val BASE = "https://m.uber.com/ul/"
    private val CLIENT_ID = Regex("[A-Za-z0-9_-]{8,64}")

    fun link(pickup: UberPlace, dropoff: UberPlace, clientId: String = ""): String {
        val drop = requireNotNull(dropoff.point) { "Uber needs a drop-off location." }
        require(drop.valid) { "Invalid drop-off location." }
        pickup.point?.let { require(it.valid) { "Invalid pickup location." } }
        val params = buildList {
            add("action" to "setPickup")
            if (clientId.isNotBlank() && CLIENT_ID.matches(clientId)) add("client_id" to clientId)
            val start = pickup.point
            if (start == null) add("pickup" to "my_location") else {
                add("pickup[latitude]" to coordinate(start.latitude))
                add("pickup[longitude]" to coordinate(start.longitude))
                add("pickup[nickname]" to nickname(pickup.name, "Pickup"))
            }
            add("dropoff[latitude]" to coordinate(drop.latitude))
            add("dropoff[longitude]" to coordinate(drop.longitude))
            add("dropoff[nickname]" to nickname(dropoff.name, "Drop-off"))
        }
        return BASE + "?" + params.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
    }

    /** Uber gets coordinates to ~1 m; extra precision adds nothing. */
    private fun coordinate(value: Double) = "%.6f".format(java.util.Locale.ROOT, value)
    private fun nickname(value: String, fallback: String) = value.filter { !it.isISOControl() }.trim().take(80).ifBlank { fallback }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Station names from timetables are matched to the bundled station positions. */
    fun station(name: String, stations: List<StationPosition>): UberPlace? {
        val key = stationKey(name)
        val match = stations.firstOrNull { stationKey(it.name) == key } ?: return null
        val lat = match.latitude ?: return null
        val lon = match.longitude ?: return null
        return UberPlace("$name station", GeoPoint(lat, lon)).takeIf { it.point!!.valid }
    }
}
