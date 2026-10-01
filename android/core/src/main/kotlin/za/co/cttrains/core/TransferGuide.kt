package za.co.cttrains.core

data class TransferPoint(val number: Int, val station: String, val arrivingTrain: String, val nextTrain: String,
    val nextLine: String, val arrival: String, val departure: String, val minutes: Int,
    val arrivalPlatform: String?, val departurePlatform: String?, val noticeTime: String?, val position: StationPosition?) {
    val differentPlatform: Boolean? get() = if (arrivalPlatform == null || departurePlatform == null) null else arrivalPlatform != departurePlatform
    val boardingLabel: String get() = departurePlatform?.let { "Platform $it" } ?: "Platform not published"
}
object TransferGuide {
    fun points(query: SearchQuery, journey: Journey, stations: List<StationPosition>, alerts: AlertSnapshot?, now: Long): List<TransferPoint> =
        journey.legs.zipWithNext().mapIndexed { index, (previous, next) ->
            val notice = if (next.departure.platform == null) ServiceAlerts.boardingNotice(alerts, query, next.departure, next.train, now) else null
            TransferPoint(index + 1, next.departure.name, previous.train, next.train, next.line,
                previous.arrival.time, next.departure.time, journey.connectionMinutes(index + 1), previous.arrival.platform,
                next.departure.platform ?: notice?.platform, notice?.let { java.time.Instant.ofEpochMilli(it.reportedAt).atZone(CapeTownZone).toLocalTime().toString() },
                stations.firstOrNull { stationKey(it.name) == stationKey(next.departure.name) && it.mapped })
        }
}
