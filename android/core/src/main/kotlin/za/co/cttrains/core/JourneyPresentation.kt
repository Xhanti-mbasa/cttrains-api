package za.co.cttrains.core

import kotlinx.serialization.json.*

data class LegBadge(val id: String,val point: GeoPoint,val text: String,val compact: String=text)
data class BusJourney(val timetable: BusTimetable,val trip: BusTrip,val boardIndex: Int,val alightIndex: Int,val board: BusStopPoint?,val alight: BusStopPoint?,val userMapped: Boolean=false) {
    val departure get()=trip.calls[boardIndex]
    val arrival get()=trip.calls[alightIndex]
    val minutes get()=minutesBetween(departure.time,arrival.time)
}
object BusPlanning {
    // Exact operator IDs only. Survey route codes and similar names are not timetable IDs.
    fun choices(t: BusTimetable,fromId: String,toId: String,after: String,date: java.time.LocalDate?=null): List<Pair<BusTrip,Pair<Int,Int>>> = t.trips.mapNotNull { trip ->
        val a=trip.calls.indexOfFirst { it.id==fromId };val b=trip.calls.indexOfFirst { it.id==toId }
        if(a>=0 && b>a && trip.calls[a].time>=after && (date==null || t.route.validFor(date) && (trip.validFrom==null || date>=java.time.LocalDate.parse(trip.validFrom)))) trip to (a to b) else null
    }.sortedBy { it.first.calls[it.second.first].time }
    fun stop(call: BusCall,snapshot: BusSnapshot)=snapshot.stops.firstOrNull { it.id==call.id }
}
object JourneyPresentation {
    fun railNames(journey: Journey?,from: String,to: String): Set<String> = (if(journey==null) listOf(from,to) else journey.legs.flatMap { listOf(it.departure.name,it.arrival.name) }).filter(String::isNotBlank).map(::stationKey).toSet()
    fun walking(legs: List<WalkingLeg>)=legs.map { leg -> LegBadge(leg.key,midpoint(leg.points),"Walk · ${leg.distanceMeters.toInt()} m · ${leg.minutes} min","${leg.minutes} min · ${leg.distanceMeters.toInt()} m") }
    fun rail(journey: Journey?,trace: JourneyTrace?,stations: List<StationPosition>): List<LegBadge> = journey?.legs?.mapIndexedNotNull { i,leg ->
        val points=trace?.legs?.getOrNull(i)?.flatMap { it.points }?.map { GeoPoint(it.latitude,it.longitude) }.orEmpty().ifEmpty {
            leg.stops.mapNotNull { stop->stations.firstOrNull { it.mapped && stationKey(it.name)==stationKey(stop.name) }?.let { GeoPoint(it.latitude!!,it.longitude!!) } }
        }
        points.takeIf(List<GeoPoint>::isNotEmpty)?.let { LegBadge("rail:$i",midpoint(it),"Train ${leg.train} · ${minutesBetween(leg.departure.time,leg.arrival.time)} min","${minutesBetween(leg.departure.time,leg.arrival.time)} min") }
    }.orEmpty()
    fun midpoint(points: List<GeoPoint>): GeoPoint {
        require(points.isNotEmpty())
        val distances=points.zipWithNext { a,b->NearbySuggestions.distance(a,b) };val halfway=distances.sum()/2
        var soFar=0.0
        distances.forEachIndexed { i,d -> if(soFar+d>=halfway && d>0) { val f=(halfway-soFar)/d;val a=points[i];val b=points[i+1];return GeoPoint(a.latitude+(b.latitude-a.latitude)*f,a.longitude+(b.longitude-a.longitude)*f) };soFar+=d }
        return points.first()
    }
}
data class PlaceSuggestion(val id: String,val label: String,val point: GeoPoint)
object PhotonPlaces {
    fun parse(raw: String): List<PlaceSuggestion> {
        require(raw.toByteArray().size<=200_000)
        return Json.parseToJsonElement(raw).jsonObject["features"]?.jsonArray.orEmpty().take(8).mapNotNull { f ->
            runCatching {
                val obj=f.jsonObject;val g=obj.getValue("geometry").jsonObject;require(g["type"]?.jsonPrimitive?.content=="Point")
                val coords=g.getValue("coordinates").jsonArray;val point=GeoPoint(coords[1].jsonPrimitive.double,coords[0].jsonPrimitive.double)
                require(point.valid && point.latitude in -34.5..-33.4 && point.longitude in 18.1..19.2)
                val p=obj.getValue("properties").jsonObject
                fun text(key: String)=p[key]?.jsonPrimitive?.content.orEmpty()
                val address=listOf(text("housenumber"),text("street")).filter(String::isNotBlank).joinToString(" ")
                val label=listOf(text("name"),address,text("district"),text("city"),text("postcode")).filter(String::isNotBlank).distinct().joinToString(", ").take(240)
                require(label.isNotBlank())
                PlaceSuggestion("${text("osm_type")}:${text("osm_id")}:${point.latitude}:${point.longitude}",label,point)
            }.getOrNull()
        }.distinctBy { it.id }
    }
}
