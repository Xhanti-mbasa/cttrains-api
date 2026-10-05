package za.co.cttrains.app.directions

import za.co.cttrains.app.*
import za.co.cttrains.core.*
import java.time.*
import java.util.UUID

object DirectionsPlans {
    fun option(o: PlannerOption,train: TrainState,s: FeatureState): DirectionsPlan =
        if(o.rail!=null) rail(o.query!!,o.rail,train.stations,o.walks,o.fetchedAt,s.access,s.destination)
        else bus(o.bus!!,o.estimate.departureAt,o.walks,o.fetchedAt)
    fun selected(train: TrainState,s: FeatureState): DirectionsPlan {
        s.selectedBus?.let { return bus(it,JourneyComparison.requestedAt(train.date,it.departure.time),s.walks,it.timetable.fetchedAt) }
        val j=train.selectedJourney ?: error("Choose a journey in Plan first.")
        return rail(train.result?.entry?.query ?: train.query,j,train.stations,s.walks,train.result?.entry?.fetchedAt ?: 0,s.access,s.destination)
    }
    private fun walk(leg: WalkingLeg,id: String)=DirectionStep(id,DirectionKind.WALK,"Walk to ${leg.to.label}","${leg.from.label} → ${leg.to.label} · mapped walking estimate",
        leg.to.point,if(leg.points.size<=10000) leg.points else leg.points.filterIndexed { i,_->i%2==0 || i==leg.points.lastIndex },leg.instructions,leg.distanceMeters,leg.durationSeconds)
    fun rail(q: SearchQuery,j: Journey,stations: List<StationPosition>,walks: List<WalkingLeg>,checked: Long,from: WalkEndpoint?=null,to: WalkEndpoint?=null): DirectionsPlan {
        val steps=mutableListOf<DirectionStep>()
        fun point(name: String)=stations.firstOrNull { stationKey(it.name)==stationKey(name) && it.mapped }?.let { GeoPoint(it.latitude!!,it.longitude!!) }
        val boardId="station:${stationKey(j.legs.first().departure.name)}";val endId="station:${stationKey(j.legs.last().arrival.name)}"
        val access=walks.firstOrNull { it.to.id==boardId && (from==null || it.from==from) }
        val egress=walks.firstOrNull { it.from.id==endId && (to==null || it.to==to) }
        access?.let { steps+=walk(it,"access") }
        var at=JourneyComparison.requestedAt(q.date,j.departure)
        j.legs.forEachIndexed { i,leg ->
            val depart=if(i==0) at else at+minutesBetween(j.legs[i-1].arrival.time,leg.departure.time)*60_000L
            val platform=leg.departure.platform?.let { "Platform $it" } ?: "Platform not published; check the station display"
            steps+=DirectionStep("board:$i",DirectionKind.BOARD,if(i==0) "Board train ${leg.train}" else "Change to train ${leg.train}","${leg.departure.name} · $platform · ${leg.line}",point(leg.departure.name),scheduledAt=depart)
            at=depart+minutesBetween(leg.departure.time,leg.arrival.time)*60_000L
            steps+=DirectionStep("ride:$i",DirectionKind.RIDE,"Get off at ${leg.arrival.name}","Train ${leg.train} · ${leg.departure.name} → ${leg.arrival.name}",point(leg.arrival.name),leg.stops.mapNotNull { point(it.name) },scheduledAt=at)
        }
        egress?.let { steps+=walk(it,"final-walk") }
        val missing=buildList { if(from!=null && access==null) add(from.label);if(to!=null && egress==null) add(to.label) }
        val note="Timetable times are scheduled, not live vehicle tracking. Confirm boarding and getting off yourself."+
            if(missing.isNotEmpty()) " No mapped walking directions for ${missing.joinToString()}; enable walking estimates in Plan." else ""
        return DirectionsEngine.validate(DirectionsPlan(UUID.randomUUID().toString(),"${access?.from?.label ?: q.from} → ${egress?.to?.label ?: q.to}",steps,System.currentTimeMillis(),checked,note))
    }
    fun bus(j: BusJourney,depart: Long,walks: List<WalkingLeg>,checked: Long): DirectionsPlan {
        val prefix="bus:${j.timetable.service.name}:"
        val access=walks.firstOrNull { it.to.id==prefix+j.board?.id }
        val egress=walks.firstOrNull { it.from.id==prefix+j.alight?.id }
        val steps=buildList {
            access?.let { add(walk(it,"access")) }
            add(DirectionStep("board",DirectionKind.BOARD,"Board ${j.timetable.service.title}","${j.departure.name} · ${j.timetable.route.title}",j.board?.point,scheduledAt=depart))
            add(DirectionStep("ride",DirectionKind.RIDE,"Get off at ${j.arrival.name}",j.timetable.route.title,j.alight?.point,listOfNotNull(j.board?.point,j.alight?.point),scheduledAt=depart+j.minutes*60_000L))
            egress?.let { add(walk(it,"final-walk")) }
        }
        return DirectionsEngine.validate(DirectionsPlan(UUID.randomUUID().toString(),"${access?.from?.label ?: j.departure.name} → ${egress?.to?.label ?: j.arrival.name}",steps,System.currentTimeMillis(),checked,
            "Published times are scheduled. ${if(j.userMapped) "Golden Arrow map points are your unverified personal associations. " else ""}Confirm boarding and getting off yourself."))
    }
}
