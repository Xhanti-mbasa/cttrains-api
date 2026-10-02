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
    /** Candidate documents, not a stop/time join: survey geometry can locate corridors but cannot verify today's operation. */
    fun goldenCandidates(snapshot: BusSnapshot,from: GeoPoint,to: GeoPoint,date: LocalDate,radius: Double=1500.0): List<BusRouteDocument> {
        if(snapshot.service!=TransportService.GOLDEN_ARROW) return emptyList()
        fun terminal(value: String): String {
            val name=value.trim().uppercase()
            return if(name=="CITY" || name.startsWith("CITY GOLDEN ACRE")) "capetown" else stationKey(name)
        }
        val corridors=snapshot.shapes.mapNotNull { shape ->
            val names=shape.name.substringAfter(":",shape.name).split(Regex("\\s+to\\s+",RegexOption.IGNORE_CASE))
            if(names.size!=2 || shape.points.isEmpty()) return@mapNotNull null
            val a=shape.points.minOf { NearbySuggestions.distance(from,it) };val b=shape.points.minOf { NearbySuggestions.distance(to,it) }
            if(a>radius || b>radius) null else Triple(terminal(names[0]),terminal(names[1]),a+b)
        }
        return snapshot.routes.filter { it.validFor(date) }.mapNotNull { route ->
            val title=stationKey(route.title)
            val match=corridors.filter { it.first.isNotBlank() && it.second.isNotBlank() && title.contains(it.first) && title.contains(it.second) }.minOfOrNull { it.third }
            match?.let { route to it }
        }.sortedBy { it.second }.map { it.first }.distinctBy { "${it.id.substringBefore(':')}:${it.validFrom}:${it.validUntil}" }.take(8)
    }
    fun routes(snapshot: BusSnapshot,from: GeoPoint,to: GeoPoint,date: LocalDate,links: List<BusStopLink>,radius: Double=1500.0): List<BusRouteDocument> {
        val a=snapshot.stops.filter { NearbySuggestions.distance(from,it.point)<=radius }.map { it.id }.toSet()
        val b=snapshot.stops.filter { NearbySuggestions.distance(to,it.point)<=radius }.map { it.id }.toSet()
        if(snapshot.service==TransportService.MYCITI) {
            val ar=snapshot.stops.filter { it.id in a }.flatMap { it.routes }.toSet()
            val br=snapshot.stops.filter { it.id in b }.flatMap { it.routes }.toSet()
            return snapshot.routes.filter { it.id in ar && it.id in br && it.validFor(date) }
        }
        val valid=links.filter { it.service==snapshot.service && it.confirmedAt>0 }
        val ar=valid.filter { it.stopId in a }.map { it.routeId }.toSet()
        val br=valid.filter { it.stopId in b }.map { it.routeId }.toSet()
        return snapshot.routes.filter { it.id in ar && it.id in br && it.validFor(date) }
    }
    fun journeys(t: BusTimetable,snapshot: BusSnapshot,from: GeoPoint,to: GeoPoint,date: LocalDate,after: String,links: List<BusStopLink>,radius: Double=1500.0): List<BusJourney> {
        if(!t.day.equals(date.dayOfWeek.name,true) || !t.route.validFor(date)) return emptyList()
        val stops=snapshot.stops.associateBy { it.id }
        val mapping=links.filter { it.service==t.service && it.routeId==t.route.id && it.confirmedAt>0 }.associateBy { it.callId }
        fun stop(call: BusCall)=if(t.service==TransportService.MYCITI) stops[call.id] else mapping[call.id]?.let { stops[it.stopId] }
        return t.trips.filter { it.validFrom==null || date>=LocalDate.parse(it.validFrom) }.flatMap { trip ->
            val a=trip.calls.mapIndexedNotNull { i,c -> stop(c)?.takeIf { NearbySuggestions.distance(from,it.point)<=radius && c.time>=after }?.let { i to it } }
            val b=trip.calls.mapIndexedNotNull { i,c -> stop(c)?.takeIf { NearbySuggestions.distance(to,it.point)<=radius }?.let { i to it } }
            a.flatMap { (i,board) -> b.filter { it.first>i }.map { (j,alight) -> BusJourney(t,trip,i,j,board,alight,t.service==TransportService.GOLDEN_ARROW) } }
        }.distinctBy { "${it.trip.id}:${it.boardIndex}:${it.alightIndex}" }.sortedWith(compareBy({ it.arrival.time },{ NearbySuggestions.distance(from,it.board!!.point)+NearbySuggestions.distance(to,it.alight!!.point) })).take(30)
    }
}
