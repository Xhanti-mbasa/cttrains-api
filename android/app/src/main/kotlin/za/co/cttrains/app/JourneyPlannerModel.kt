package za.co.cttrains.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import za.co.cttrains.app.data.*
import za.co.cttrains.core.*
import java.time.LocalDate
import java.time.LocalTime

data class PlannerRequest(val from: WalkEndpoint,val to: WalkEndpoint,val date: String,val time: String,val services: Set<TransportService>,val walking: Boolean,val maxWalkMeters: Int=1500,
    val boardingStation: String?=null,val arrivalStation: String?=null,val allDay: Boolean=false,val nextAvailable: Boolean=false)
data class PlannerOption(val id: String,val service: TransportService,val query: SearchQuery?,val rail: Journey?,val bus: BusJourney?,val walks: List<WalkingLeg>,val estimate: TripEstimate,val fares: List<RouteFare>,val fetchedAt: Long,val cached: Boolean,val notice: String="",val sourceId: String="cttrains-web") {
    val board get()=rail?.legs?.first()?.departure?.name ?: bus!!.departure.name
    val alight get()=rail?.legs?.last()?.arrival?.name ?: bus!!.arrival.name
    val rideMinutes get()=rail?.durationMinutes ?: bus!!.minutes
}
data class GoldenTimetableCandidate(val timetable: BusTimetable,val cached: Boolean,val departures: List<BusJourney>)
data class PlannerSection(val service: TransportService,val options: List<PlannerOption> = emptyList(),val issue: String?=null,val searchedRoutes: Int=0,val candidates: List<GoldenTimetableCandidate> = emptyList())
data class PlannerState(val request: PlannerRequest?=null,val loading: Boolean=false,val progress: String="",val sections: Map<TransportService,PlannerSection> = emptyMap(),val error: String?=null)

/** Own request state. Comparing one provider never changes another provider's selected timetable. */
class JourneyPlannerModel(app: Application): AndroidViewModel(app) {
    private val trains=TrainRepository(app)
    private val buses=BusRepository(app)
    private val walking=WalkingRepository(app)
    private val mutable=MutableStateFlow(PlannerState())
    val state=mutable.asStateFlow()
    private var job: Job?=null
    fun clear() { job?.cancel();mutable.value=PlannerState() }
    fun find(request: PlannerRequest,train: TrainState) {
        val old=job;old?.cancel()
        job=viewModelScope.launch {
            old?.join()
            mutable.value=PlannerState(request,loading=true)
            try {
                require(request.from.point.valid && request.to.point.valid && request.services.isNotEmpty())
                require(request.from!=request.to && request.maxWalkMeters in 500..5000)
                LocalDate.parse(request.date);LocalTime.parse(request.time)
                for(service in TransportService.values().filter { it in request.services }) {
                    currentCoroutineContext().ensureActive()
                    mutable.update { it.copy(progress="Finding ${service.title} routes…") }
                    val section=try { if(service==TransportService.PRASA) rail(request,train) else bus(request,service) }
                    catch(e: Exception) { if(e is CancellationException) throw e;PlannerSection(service,issue="${service.title} could not be checked. Retry or open its published timetable. Any saved results retain their original dates.") }
                    mutable.update { it.copy(sections=it.sections+(service to section)) }
                }
            } catch(e: Exception) { if(e is CancellationException) throw e;mutable.update { it.copy(error="Choose different mapped places and a valid date/time.") } }
            finally { if(isActive) mutable.update { it.copy(loading=false,progress="") } }
        }
    }
    private suspend fun walks(request: PlannerRequest,a: WalkEndpoint,b: WalkEndpoint): Triple<List<WalkingLeg>,Pair<Int?,Int?>,Boolean> {
        val legs=mutableListOf<WalkingLeg>();var cached=false
        suspend fun leg(from: WalkEndpoint,to: WalkEndpoint): Int? {
            if(from.id==to.id && from.point==to.point) return 0
            if(!request.walking) return null
            return try {
                val result=walking.route(from,to)
                if(result.first.distanceMeters>request.maxWalkMeters) return null
                legs+=result.first;cached=cached || result.second;result.first.minutes
            } catch(e: Exception) { if(e is CancellationException) throw e;null }
        }
        return Triple(legs,leg(request.from,a) to leg(b,request.to),cached)
    }
    private suspend fun rail(r: PlannerRequest,train: TrainState): PlannerSection {
        val origins=StationChoices.candidates(r.from,train.stations,train.catalog.stations,r.maxWalkMeters,r.boardingStation)
        val destinations=StationChoices.candidates(r.to,train.stations,train.catalog.stations,r.maxWalkMeters,r.arrivalStation)
        if(origins.isEmpty() || destinations.isEmpty()) {
            val preferred=if(origins.isEmpty()) r.boardingStation else r.arrivalStation
            return PlannerSection(TransportService.PRASA,issue=if(preferred!=null) "Your selected station, $preferred, is unavailable in this timetable or outside your ${r.maxWalkMeters} m walking limit. Choose another station or increase the limit. Your choice has been kept."
                else "No mapped train station within ${r.maxWalkMeters} m of ${if(origins.isEmpty()) "your start" else "your destination"}. Choose another stop or increase the walking limit.")
        }
        val requested=JourneyComparison.requestedAt(r.date,if(r.allDay) "00:00" else r.time)
        val options=mutableListOf<PlannerOption>();var checked=0;var failed=0;var ruleIssue: String?=null
        for(a in origins) for(b in destinations) {
            if(stationKey(a.name)==stationKey(b.name)) continue
            val minute=LocalTime.parse(if(r.allDay) "03:00" else r.time).toSecondOfDay()/60
            val sourceTime=LocalTime.of((minute/15*15).coerceAtLeast(180)/60,(minute/15*15).coerceAtLeast(180)%60).toString()
            val q=SearchQuery(a.name,b.name,r.date,sourceTime)
            val error=TravelRules.validationError(q,train.catalog,LocalDate.now(CapeTownZone))
            if(error!=null) { ruleIssue=error;continue }
            val result=try { if(r.allDay) trains.store.cached(q,train.preferences.sourceId)?.takeIf { System.currentTimeMillis()-it.fetchedAt in 0..900_000L }?.let { SearchResult(it,true) } ?: trains.search(q,train.preferences.sourceId) else trains.search(q,train.preferences.sourceId) } catch(e: Exception) { if(e is CancellationException) throw e;failed++;continue }
            checked++
            val walk=walks(r,WalkingRoutes.endpoint(a),WalkingRoutes.endpoint(b))
            val prices=TripBudget.sourcePrices(result.entry.fares)
            val personal=TripBudget.correction(train.preferences.fareCorrections,SavedRoute(a.name,b.name),"single")
            val fares=listOfNotNull(personal?.let { RouteFare(it.cents,"Your single fare correction",true) } ?: prices["single"]?.let { RouteFare(it,"Published single fare") })
            result.entry.journeys.filter { r.allDay || it.departure>=r.time }.forEach { j ->
                val timing=JourneyComparison.estimate(requested,r.date,j.departure,j.durationMinutes,walk.second.first,walk.second.second)
                if(r.allDay || timing.canCatch!=false) options+=PlannerOption("rail:${q.from}:${q.to}:${j.legs.joinToString { it.train+it.departure.time }}",TransportService.PRASA,q,j,null,walk.first.toList(),timing,fares,result.entry.fetchedAt,result.offline || walk.third,sourceId=result.entry.sourceId)
            }
        }
        return PlannerSection(TransportService.PRASA,rank(options,r),if(options.isEmpty()) ruleIssue ?: if(failed>0) "Train schedules could not be checked for the selected stations. Try again or open CT Trains." else if(r.allDay) "No train journeys were returned for this date. Missing timetable coverage does not prove there is no service." else "No published train journey you can catch was found for these places and time." else if(failed>0) "Some station pairs could not be checked; this comparison is incomplete." else null,checked)
    }
    private suspend fun bus(r: PlannerRequest,service: TransportService): PlannerSection {
        val extras=buses.store.extras()
        if(!extras.beta.enabled(service)) return PlannerSection(service,issue="Enable ${service.title} independently in Settings → Beta features.")
        val date=LocalDate.parse(r.date)
        val saved=buses.store.snapshot(service)
        val needsCatalogue=service==TransportService.GOLDEN_ARROW && (saved==null || saved.routes.map { it.title.firstOrNull() }.distinct().size<2 || saved.routes.none { it.validFor(date) })
        val snapshot=if(saved==null || needsCatalogue) buses.refresh(service) else saved
        // A saved place's usual stop replaces "nearest stop" for that operator; walks still start at the place itself.
        fun usual(e: WalkEndpoint)=extras.spots.firstOrNull { e.id=="spot:${it.id}" }?.preferredStops?.get(service.name)?.let { id -> snapshot.stops.firstOrNull { it.id==id } }
        val fromPoint=usual(r.from)?.point ?: r.from.point
        val toPoint=usual(r.to)?.point ?: r.to.point
        val day=date.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)
        val routes=withContext(Dispatchers.Default) { BusDiscovery.routes(snapshot,fromPoint,toPoint,date,extras.busStopLinks,r.maxWalkMeters.toDouble()) }
        if(routes.isEmpty() && service==TransportService.MYCITI) {
            return PlannerSection(service,issue="No published direct MyCiTi route joins stops within ${r.maxWalkMeters} m of both places. Transfers can be checked in the operator planner.")
        }
        val after=if(r.allDay) "00:00" else r.time
        val requestAt=JourneyComparison.requestedAt(r.date,after)
        val options=mutableListOf<PlannerOption>();var failed=0
        for(route in routes.take(6)) {
            currentCoroutineContext().ensureActive()
            val pair=try { buses.timetable(service,route,day) } catch(e: Exception) { if(e is CancellationException) throw e;failed++;continue }
            val journeys=withContext(Dispatchers.Default) { BusDiscovery.journeys(pair.first,snapshot,fromPoint,toPoint,date,after,extras.busStopLinks,r.maxWalkMeters.toDouble(),Int.MAX_VALUE) }
            val combinations=journeys.groupBy { it.board!!.id to it.alight!!.id }.entries.sortedBy { (_,v) ->
                NearbySuggestions.distance(fromPoint,v.first().board!!.point)+NearbySuggestions.distance(toPoint,v.first().alight!!.point)
            }.take(3)
            for((_,trips) in combinations) {
                val first=trips.first()
                fun endpoint(stop: BusStopPoint)=WalkEndpoint("bus:${service.name}:${stop.id}",stop.name,stop.point)
                val walk=walks(r,endpoint(first.board!!),endpoint(first.alight!!))
                val fares=JourneyComparison.singleFares(snapshot.fares,route,extras.priceCorrections.filter { it.service==service }.associate { (it.fareId to it.product) to it.cents })
                for(j in trips) {
                    val estimate=JourneyComparison.estimate(requestAt,r.date,j.departure.time,j.minutes,walk.second.first,walk.second.second)
                    if(r.allDay || estimate.canCatch!=false) options+=PlannerOption("${service.name}:${route.id}:${j.trip.id}:${j.boardIndex}:${j.alightIndex}",service,null,null,j,walk.first.toList(),estimate,fares,pair.first.fetchedAt,pair.second || walk.third,
                        if(service==TransportService.GOLDEN_ARROW) "Boarding map points use your personal confirmations; current stop operation remains unverified." else "Published direct route; confirm holiday exceptions.")
                }
            }
        }
        if(service==TransportService.GOLDEN_ARROW && options.isEmpty()) {
            fun area(endpoint: WalkEndpoint)=endpoint.label.takeIf { endpoint.id.startsWith("station:") } ?: usual(endpoint)?.name.orEmpty()
            val origin=area(r.from)
            val destination=area(r.to)
            val routesNearBoth=withContext(Dispatchers.Default) {
                BusDiscovery.goldenCandidates(snapshot,fromPoint,toPoint,date,r.maxWalkMeters.toDouble(),origin,destination)
            }
            val candidates=mutableListOf<GoldenTimetableCandidate>()
            for(corridor in routesNearBoth) {
                val route=corridor.document
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(progress="Checking published timetable · ${route.title}") }
                val (timetable,cached)=try { buses.timetable(service,route,day) }
                    catch(e: Exception) { if(e is CancellationException) throw e;failed++;continue }
                val departures=BusDiscovery.goldenDepartures(timetable,origin,destination,date,after,corridor)
                candidates+=GoldenTimetableCandidate(timetable,cached,departures)
            }
            val timed=candidates.filter { it.departures.isNotEmpty() }.distinctBy { candidate ->
                candidate.timetable.route.title to candidate.departures.map { it.departure to it.arrival }
            }
            val issue=when {
                timed.isNotEmpty() -> "Golden Arrow publishes times at major points. Confirm where to board and get off."
                candidates.isNotEmpty() -> "No timed departures for these places at the selected time. Open the timetable to check stops and other services."
                failed>0 -> "Golden Arrow timetables could not be loaded. Try again or check the operator’s timetables."
                else -> "No matching published Golden Arrow timetable was found for these places."
            }
            return PlannerSection(service,issue=issue,searchedRoutes=routesNearBoth.size,candidates=timed.ifEmpty { candidates })
        }
        val note=buildList { if(routes.size>6) add("Checked 6 of ${routes.size} candidate routes; narrow the stops for wider coverage.");if(failed>0) add("$failed timetable checks failed.");add("Direct trips only; bus transfers are not searched.") }.joinToString(" ")
        return PlannerSection(service,rank(options,r),if(options.isEmpty()) (if(r.allDay) "No supported trips were returned for this date. Missing data does not prove there is no service. $note" else "No supported published trip you can catch was found after ${r.time}. $note") else note,routes.take(6).size)
    }
    private fun rank(options: List<PlannerOption>, request: PlannerRequest): List<PlannerOption> {
        val unique=options.distinctBy { it.id }
        val byDeparture=compareBy<PlannerOption> { it.estimate.departureAt }.thenBy { it.estimate.arrivalAt ?: Long.MAX_VALUE }
        if(request.allDay) return unique.sortedWith(byDeparture)
        if(request.nextAvailable) return unique.sortedWith(byDeparture).take(12)
        return unique.sortedWith(compareBy<PlannerOption> { it.estimate.totalMinutes ?: Int.MAX_VALUE }.thenBy { it.estimate.arrivalAt ?: Long.MAX_VALUE }.thenBy { it.estimate.departureAt }).take(12)
    }
}
