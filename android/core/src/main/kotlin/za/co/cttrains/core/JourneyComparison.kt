package za.co.cttrains.core

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.ceil

/** A personal confirmation binds a published timed call to one survey point, never a guessed name match. */
@Serializable data class BusStopLink(val service: TransportService,val routeId: String,val callId: String,val stopId: String,val confirmedAt: Long)
data class RouteFare(val cents: Long,val label: String,val personal: Boolean=false)
data class TripEstimate(val departureAt: Long,val arrivalAt: Long?,val totalMinutes: Int?,val walkingMinutes: Int?,val waitingMinutes: Int?,val canCatch: Boolean?)

object JourneyComparison {
    fun requestedAt(date: String,time: String)=LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(CapeTownZone).toInstant().toEpochMilli()
    fun estimate(requestedAt: Long,date: String,departure: String,rideMinutes: Int,access: Int?,egress: Int?,buffer: Int=5): TripEstimate {
        require(rideMinutes in 0..1440 && buffer in 0..120)
        require(access==null || access in 0..480);require(egress==null || egress in 0..480)
        val depart=JourneyComparison.requestedAt(date,departure)
        val arrive=egress?.let { depart+(rideMinutes+it)*60_000L }
        val wait=access?.let { ((depart-requestedAt)/60_000L-it-buffer).toInt() }
        val catch=wait?.let { it>=0 && depart>=requestedAt+(access!!+buffer)*60_000L }
        return TripEstimate(depart,arrive,if(catch==true && arrive!=null) ceil((arrive-requestedAt)/60_000.0).toInt() else null,
            if(access!=null && egress!=null) access+egress else null,wait?.takeIf { it>=0 },catch)
    }
    /** Cash / one-way fares only. Weekly passes are not silently divided into daily fares. */
    fun singleFares(rows: List<BusFareRow>,route: BusRouteDocument,corrections: Map<Pair<String,String>,Long> = emptyMap()): List<RouteFare> {
        val key=stationKey(route.title)
        return rows.filter { stationKey(it.title)==key || stationKey(it.title.substringAfter("→").trim())==key }.flatMap { row ->
            row.products.filterKeys { Regex("\\b(cash|single|one[ -]way)\\b",RegexOption.IGNORE_CASE).containsMatchIn(it) && !Regex("weekly|monthly|ride",RegexOption.IGNORE_CASE).containsMatchIn(it) }
                .map { product -> val correction=corrections[row.id to product.key];RouteFare(correction ?: product.value,"${if(correction!=null) "Your correction · " else ""}${row.title} · ${product.key}",correction!=null) }
        }
    }
}

object BusDiscovery {
    // PRASA locates Unibell station in Belhar. Area names help find PDFs without assigning a boarding pin.
    private fun goldenArea(name: String): String = when (val key = stationKey(name)) {
        "unibell" -> "belhar"
        "city", "citygoldenacre", "goldenacreterminus" -> "capetown"
        else -> key
    }

    /** Find published corridor documents by known area names or nearby survey geometry. */
    fun goldenCandidates(snapshot: BusSnapshot,from: GeoPoint,to: GeoPoint,date: LocalDate,radius: Double=1500.0,fromName: String="",toName: String=""): List<BusRouteDocument> {
        if(snapshot.service!=TransportService.GOLDEN_ARROW) return emptyList()
        val start = goldenArea(fromName)
        val end = goldenArea(toName)
        val corridors=snapshot.shapes.mapNotNull { shape ->
            val names=shape.name.substringAfter(":",shape.name).split(Regex("\\s+to\\s+",RegexOption.IGNORE_CASE))
            if(names.size!=2 || shape.points.isEmpty()) return@mapNotNull null
            val a=shape.points.minOf { NearbySuggestions.distance(from,it) };val b=shape.points.minOf { NearbySuggestions.distance(to,it) }
            if(a>radius || b>radius) null else Triple(goldenArea(names[0]),goldenArea(names[1]),a+b)
        }
        return snapshot.routes.filter { it.validFor(date) }.mapNotNull { route ->
            val areas=route.title.split(Regex("\\s*[-–—]\\s*")).map(::goldenArea)
            if(start.isNotBlank() && end.isNotBlank() && start!=end && start in areas && end in areas)
                return@mapNotNull route to -1.0
            val title=stationKey(route.title)
            val match=corridors.filter { it.first.isNotBlank() && it.second.isNotBlank() && title.contains(it.first) && title.contains(it.second) }.minOfOrNull { it.third }
            match?.let { route to it }
        }.sortedBy { it.second }.map { it.first }.distinctBy { "${it.id.substringBefore(':')}:${it.validFrom}:${it.validUntil}" }.take(8)
    }
    /** Published times on a nearby corridor, without joining PDF names to surveyed stops. */
    fun goldenDepartures(timetable: BusTimetable, fromName: String, toName: String, date: LocalDate, after: String): List<BusJourney> {
        if(timetable.service!=TransportService.GOLDEN_ARROW || !timetable.day.equals(date.dayOfWeek.name,true) || !timetable.route.validFor(date))
            return emptyList()
        val names = timetable.trips.flatMap { trip -> trip.calls.map { goldenArea(it.name) } }.toSet()
        val origin = goldenArea(fromName).takeIf { it in names }
        val destination = goldenArea(toName).takeIf { it in names }
        return timetable.trips.mapNotNull { trip ->
            if (trip.validFrom != null && date < LocalDate.parse(trip.validFrom)) return@mapNotNull null
            val end = if (destination == null) trip.calls.lastIndex else trip.calls.indexOfLast { goldenArea(it.name) == destination }
            val start = (0 until end).firstOrNull { i ->
                val call = trip.calls[i]
                call.time >= after && (origin == null || goldenArea(call.name) == origin)
            } ?: return@mapNotNull null
            BusJourney(timetable, trip, start, end, null, null)
        }.distinctBy { it.departure.id to it.arrival.id to (it.departure.time to it.arrival.time) }
            .sortedWith(compareBy({ it.departure.time }, { it.arrival.time }))
    }

    fun routes(snapshot: BusSnapshot,from: GeoPoint,to: GeoPoint,date: LocalDate,links: List<BusStopLink>,radius: Double=1500.0): List<BusRouteDocument> {
        val a=snapshot.stops.mapNotNull { stop -> NearbySuggestions.distance(from,stop.point).takeIf { it<=radius }?.let { stop to it } }
        val b=snapshot.stops.mapNotNull { stop -> NearbySuggestions.distance(to,stop.point).takeIf { it<=radius }?.let { stop to it } }
        if(snapshot.service==TransportService.MYCITI) {
            fun byRoute(stops: List<Pair<BusStopPoint,Double>>) =
                stops.sortedBy { it.second }.flatMap { entry -> entry.first.routes.map { it to entry } }.groupBy({ it.first },{ it.second })
            val origins=byRoute(a)
            val destinations=byRoute(b)
            return snapshot.routes.filter { it.validFor(date) }.mapNotNull { route ->
                val start=origins[route.id]?.take(2) ?: return@mapNotNull null
                val end=destinations[route.id]?.take(2) ?: return@mapNotNull null
                val distance=start.minOfOrNull { (board,access) ->
                    end.firstOrNull { (alight,_) -> alight.id!=board.id }?.let { access+it.second } ?: Double.POSITIVE_INFINITY
                }
                distance?.takeIf { it.isFinite() }?.let { route to it }
            }.sortedBy { it.second }.map { it.first }
        }
        val originIds=a.map { it.first.id }.toSet()
        val destinationIds=b.map { it.first.id }.toSet()
        val valid=links.filter { it.service==snapshot.service && it.confirmedAt>0 }
        val ar=valid.filter { it.stopId in originIds }.map { it.routeId }.toSet()
        val br=valid.filter { it.stopId in destinationIds }.map { it.routeId }.toSet()
        return snapshot.routes.filter { it.id in ar && it.id in br && it.validFor(date) }
    }
    fun journeys(t: BusTimetable,snapshot: BusSnapshot,from: GeoPoint,to: GeoPoint,date: LocalDate,after: String,links: List<BusStopLink>,radius: Double=1500.0,limit: Int=30): List<BusJourney> {
        if(!t.day.equals(date.dayOfWeek.name,true) || !t.route.validFor(date)) return emptyList()
        val stops=snapshot.stops.associateBy { it.id }
        val access=mutableMapOf<String,Double>()
        val egress=mutableMapOf<String,Double>()
        fun accessTo(stop: BusStopPoint)=access.getOrPut(stop.id) { NearbySuggestions.distance(from,stop.point) }
        fun egressFrom(stop: BusStopPoint)=egress.getOrPut(stop.id) { NearbySuggestions.distance(to,stop.point) }
        val mapping=links.filter { it.service==t.service && it.routeId==t.route.id && it.confirmedAt>0 }.associateBy { it.callId }
        fun stop(call: BusCall)=if(t.service==TransportService.MYCITI) stops[call.id] else mapping[call.id]?.let { stops[it.stopId] }
        return t.trips.filter { it.validFrom==null || date>=LocalDate.parse(it.validFrom) }.flatMap { trip ->
            val a=trip.calls.mapIndexedNotNull { i,c -> stop(c)?.takeIf { accessTo(it)<=radius && c.time>=after }?.let { i to it } }
            val b=trip.calls.mapIndexedNotNull { i,c -> stop(c)?.takeIf { egressFrom(it)<=radius }?.let { i to it } }
            a.flatMap { (i,board) -> b.filter { it.first>i && it.second.id!=board.id }.map { (j,alight) -> BusJourney(t,trip,i,j,board,alight,t.service==TransportService.GOLDEN_ARROW) } }
        }.distinctBy { "${it.trip.id}:${it.boardIndex}:${it.alightIndex}" }.sortedWith(compareBy({ it.arrival.time },{ accessTo(it.board!!)+egressFrom(it.alight!!) })).take(limit)
    }
}
