package za.co.cttrains.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.*
import kotlin.math.*

@Serializable data class WalkEndpoint(val id: String,val label: String,val point: GeoPoint)
@Serializable data class WalkingLeg(val from: WalkEndpoint,val to: WalkEndpoint,val distanceMeters: Double,val durationSeconds: Double,val points: List<GeoPoint>,val fetchedAt: Long,val source: String="FOSSGIS / OSRM · OpenStreetMap",val instructions: List<WalkInstruction> = emptyList()) {
    val minutes: Int get()=ceil(durationSeconds/60).toInt()
    val key: String get()=WalkingRoutes.key(from,to)
}
@Serializable data class WalkSample(val key: String,val distanceMeters: Double,val seconds: Int,val recordedAt: Long,val startedAt: Long=0,val steps: Long?=null,val stepsCheckedAt: Long=0)
data class DoorTimes(val leaveAt: Long,val arriveAt: Long)

object WalkingRoutes {
    const val BASE="https://routing.openstreetmap.de/routed-foot/route/v1/foot/"
    fun key(a: WalkEndpoint,b: WalkEndpoint)="${a.id}:${a.point.latitude},${a.point.longitude}>${b.id}:${b.point.latitude},${b.point.longitude}"
    fun url(a: WalkEndpoint,b: WalkEndpoint): String {
        require(a.point.valid && b.point.valid && NearbySuggestions.distance(a.point,b.point)<=20000)
        return BASE+"${a.point.longitude},${a.point.latitude};${b.point.longitude},${b.point.latitude}?overview=full&geometries=geojson&steps=true&radiuses=75;75"
    }
    fun parse(raw: String,a: WalkEndpoint,b: WalkEndpoint,now: Long): WalkingLeg {
        require(raw.toByteArray().size<=1024*1024)
        val root=Json.parseToJsonElement(raw).jsonObject
        require(root["code"]?.jsonPrimitive?.content=="Ok") { "No mapped walking route was returned." }
        val waypoints=root.getValue("waypoints").jsonArray
        require(waypoints.size==2 && waypoints.all { it.jsonObject.getValue("distance").jsonPrimitive.double in 0.0..75.0 }) { "The mapped path is too far from a selected point." }
        val r=root.getValue("routes").jsonArray.first().jsonObject
        require(r.getValue("geometry").jsonObject["type"]?.jsonPrimitive?.content=="LineString")
        val points=r.getValue("geometry").jsonObject.getValue("coordinates").jsonArray.map { p->GeoPoint(p.jsonArray[1].jsonPrimitive.double,p.jsonArray[0].jsonPrimitive.double) }
        val meters=r.getValue("distance").jsonPrimitive.double;val seconds=r.getValue("duration").jsonPrimitive.double
        require(points.size in 2..15000 && points.all(GeoPoint::valid) && meters.isFinite() && seconds.isFinite() && meters in 0.0..30000.0 && seconds in 0.0..28800.0)
        require(NearbySuggestions.distance(points.first(),a.point)<=120 && NearbySuggestions.distance(points.last(),b.point)<=120)
        val direct=NearbySuggestions.distance(a.point,b.point)
        require(meters>=max(0.0,direct-150) && meters<=direct*8+1500)
        var covered=0.0
        val instructions=r["legs"]?.jsonArray.orEmpty().flatMap { it.jsonObject["steps"]?.jsonArray.orEmpty() }.take(1000).mapNotNull { value ->
            val step=value.jsonObject;val maneuver=step["maneuver"]?.jsonObject ?: return@mapNotNull null
            val loc=maneuver["location"]?.jsonArray ?: return@mapNotNull null
            if(loc.size!=2) return@mapNotNull null
            val point=GeoPoint(loc[1].jsonPrimitive.double,loc[0].jsonPrimitive.double)
            val type=maneuver["type"]?.jsonPrimitive?.content.orEmpty();val modifier=maneuver["modifier"]?.jsonPrimitive?.content.orEmpty()
            val road=step["name"]?.jsonPrimitive?.content.orEmpty().take(150)
            val text=when(type) { "arrive"->"Arrive at ${b.label}";"depart"->"Head ${modifier.ifBlank { "along the path" }}";"turn","end of road"->"Turn ${modifier.ifBlank { "onto the next path" }}";"fork"->"Keep ${modifier.ifBlank { "on the path" }}";"roundabout","rotary"->"Follow the roundabout";else->"Continue ${modifier.ifBlank { "on the path" }}" }+(if(road.isNotBlank() && type!="arrive") " on $road" else "")
            val at=covered;val segment=step["distance"]?.jsonPrimitive?.doubleOrNull
            if(segment!=null && segment.isFinite() && segment>=0) covered+=segment
            if(!point.valid || at>meters+150) null else WalkInstruction(point,text.take(300),at)
        }
        return WalkingLeg(a,b,meters,seconds,points,now,instructions=instructions)
    }
    fun nearest(point: GeoPoint,stations: List<StationPosition>): StationPosition?=stations.filter { it.mapped }.minByOrNull { NearbySuggestions.distance(point,GeoPoint(it.latitude!!,it.longitude!!)) }
    fun endpoint(station: StationPosition)=WalkEndpoint("station:${stationKey(station.name)}",station.name,GeoPoint(station.latitude!!,station.longitude!!))
    fun times(query: SearchQuery,journey: Journey,access: Int,egress: Int,buffer: Int): DoorTimes {
        require(access in 0..480 && egress in 0..480 && buffer in 0..120)
        val day=LocalDate.parse(query.date);val start=day.atTime(LocalTime.parse(journey.departure)).atZone(CapeTownZone)
        var end=day.atTime(LocalTime.parse(journey.arrival)).atZone(CapeTownZone);if(end<start) end=end.plusDays(1)
        return DoorTimes(start.minusMinutes((access+buffer).toLong()).toInstant().toEpochMilli(),end.plusMinutes(egress.toLong()).toInstant().toEpochMilli())
    }
    fun learned(leg: WalkingLeg,samples: List<WalkSample>): Int? {
        val values=samples.filter { it.key==leg.key && abs(it.distanceMeters-leg.distanceMeters)<=max(200.0,leg.distanceMeters*.2) }.map { it.seconds }.sorted()
        if(values.size<3) return null
        val median=if(values.size%2==1) values[values.size/2].toDouble() else (values[values.size/2-1].toDouble()+values[values.size/2])/2
        return ceil(median/60).toInt()
    }
    fun sample(leg: WalkingLeg,seconds: Int,now: Long): WalkSample {
        require(seconds in 20..7200 && leg.distanceMeters>=50)
        val kph=leg.distanceMeters/seconds*3.6;require(kph in .3..8.0) { "This segment does not look like a walking observation." }
        return WalkSample(leg.key,leg.distanceMeters,seconds,now)
    }
}

data class BusConnectionCheck(val routes: List<BusRouteDocument>,val message: String)
object BusConnections {
    fun direct(snapshot: BusSnapshot?,from: GeoPoint?,to: GeoPoint?): BusConnectionCheck {
        if(snapshot==null || snapshot.stops.isEmpty()) return BusConnectionCheck(emptyList(),"Bus stop data is unavailable. Refresh the operator or plan with published train schedules.")
        if(from==null || to==null) return BusConnectionCheck(emptyList(),"Select mapped stations or saved places to check nearby bus stops.")
        val a=snapshot.stops.filter { NearbySuggestions.distance(from,it.point)<=1000 }
        val b=snapshot.stops.filter { NearbySuggestions.distance(to,it.point)<=1000 }
        if(a.isEmpty() || b.isEmpty()) return BusConnectionCheck(emptyList(),"No published ${snapshot.service.title} stop within 1 km of ${if(a.isEmpty() && b.isEmpty()) "either selected point" else if(a.isEmpty()) "your start" else "your destination"}. Try the train alternative below.")
        if(snapshot.service==TransportService.GOLDEN_ARROW) return BusConnectionCheck(emptyList(),"Golden Arrow survey codes cannot verify a direct timetable connection. Search its published route PDFs, or use the train alternative.")
        val ids=a.flatMap { it.routes }.toSet().intersect(b.flatMap { it.routes }.toSet())
        val routes=snapshot.routes.filter { it.id in ids }
        return BusConnectionCheck(routes,if(routes.isEmpty()) "No published direct ${snapshot.service.title} route joins the nearby stops. Bus transfers are not verified here; try the train alternative." else "Possible direct routes from published stop membership. Open a timetable to verify direction, boarding stops and times.")
    }
}
