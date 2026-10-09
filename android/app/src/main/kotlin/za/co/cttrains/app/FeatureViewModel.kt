package za.co.cttrains.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID
import za.co.cttrains.app.data.*
import za.co.cttrains.app.maps.*
import za.co.cttrains.app.scheduling.*
import za.co.cttrains.core.*

data class FeatureState(val extras: LocalExtras = LocalExtras(),val snapshots: Map<TransportService,BusSnapshot> = emptyMap(),
    val loading: Set<TransportService> = emptySet(),val bus: TransportService = TransportService.GOLDEN_ARROW,
    val timetable: BusTimetable? = null,val timetableCached: Boolean = false,val busy: Boolean = false,val timetableLoading: Boolean=false,
    val error: String? = null,val message: String? = null,val pin: MapPin? = null,val focus: GeoPoint? = null,
    val importedTrip: SafetyTrip? = null,val importedCircle: CircleLink? = null,val importedReport: CommunityReport? = null,
    val backup: PortableProfile? = null,val session: MatrixSession? = null,val chat: List<RoomMessage> = emptyList(),
    val live: LiveCircle? = null,val watched: List<WatchedFriend> = emptyList(),val mapService: TransportService?=null,val mapRoute: String?=null,
    val ready: Boolean=false,val access: WalkEndpoint?=null,val destination: WalkEndpoint?=null,
    val walks: List<WalkingLeg> = emptyList(),val walkBusy: Boolean=false,val walkError: String?=null,val walksCached: Boolean=false,
    val chatService: TransportService?=null,val syncing: Boolean=false,val nearby: List<RoomMessage> = emptyList(),val nearbySharing: Boolean=false,val selectedBus: BusJourney?=null,
    val boardingStation: String?=null,val arrivalStation: String?=null,val plannerMode: String="PRASA")

@OptIn(kotlinx.coroutines.FlowPreview::class)
class FeatureViewModel(app: Application): AndroidViewModel(app) {
    val store=FeatureStore(app)
    val files=PortableFiles(app,store)
    private val buses=BusRepository(app)
    private val matrix=MatrixClient()
    private val walking=WalkingRepository(app)
    private val places=PlaceSearch()
    private var walkJob: Job?=null
    private var syncJob: Job?=null
    private val mutable=MutableStateFlow(FeatureState())
    val state=mutable.asStateFlow()
    private val jobs=mutableMapOf<TransportService,Job>()
    private val lastAttempts=mutableMapOf<TransportService,Long>()
    private var timetableJob: Job?=null
    private var timetableService: TransportService?=null
    private var lastRoutines: List<CommuteRoutine>?=null
    private val handler=CoroutineExceptionHandler { _,_ -> message("Couldn’t update local data. Try again or restore a backup.") }
    init {
        viewModelScope.launch(handler+Dispatchers.IO) { store.store.cleanLargeValues() }
        viewModelScope.launch(handler) { store.store.initialize(); store.extras.collect { extras ->
            val old=state.value.extras.beta
            mutable.update { it.copy(extras=extras,ready=true) }
            if(old!=extras.beta) BusRefreshWorker.configure(app,extras.beta)
            if(lastRoutines!=extras.routines) { lastRoutines.orEmpty().filter { oldRoutine->extras.routines.firstOrNull { it.id==oldRoutine.id }!=oldRoutine }.forEach { RoutineScheduler.cancelOccurrences(app,it.id) };lastRoutines=extras.routines;RoutineScheduler.configure(app,extras.routines) }
            listOf(TransportService.GOLDEN_ARROW,TransportService.MYCITI).forEach { service ->
                if(!extras.beta.enabled(service)) { if(state.value.selectedBus?.timetable?.service==service) mutable.update { it.copy(selectedBus=null,mapService=null,mapRoute=null,walks=emptyList()) };jobs.remove(service)?.cancel();if(timetableService==service) timetableJob?.cancel() }
                else if(!old.enabled(service)) refresh(service)
            }
        } }
        listOf(TransportService.GOLDEN_ARROW,TransportService.MYCITI).forEach { service ->
            viewModelScope.launch(handler) { store.observeSnapshot(service).collect { value -> mutable.update { s -> s.copy(snapshots=if(value==null) s.snapshots-service else s.snapshots+(service to value)) } } }
        }
        viewModelScope.launch(handler) { store.session.collect { session -> mutable.update { it.copy(session=session) } } }
        viewModelScope.launch(handler) {
            store.liveCircle()?.takeIf { it.sharing && TripLiveService.activeMode==null }?.let { store.saveCircle(it.copy(sharing=false)) }
            store.circle.collect { circle -> mutable.update { it.copy(live=circle) } }
        }
        viewModelScope.launch(handler) { combine(store.watched,TripLiveService.positions) { saved,live ->
            saved.map { row->live.firstOrNull { it.id==row.id && it.update.sequence==row.update.sequence } ?: row }
        }.collect { rows->mutable.update { it.copy(watched=rows) } } }
        viewModelScope.launch(handler) { store.store.changes.debounce(2000).collectLatest {
            val link=store.backupLink() ?: return@collectLatest
            try { files.write(Uri.parse(link.uri),files.export(link.password)) } catch(e: Exception) {
                if(e is CancellationException) throw e
                store.saveBackupLink(null);message("Automatic backup paused: select the backup file again to grant write access.")
            }
        } }
    }
    fun message(text: String)=mutable.update { it.copy(message=text) }
    fun dismissMessage()=mutable.update { it.copy(message=null) }
    fun beta(transform: (BetaPreferences)->BetaPreferences)=viewModelScope.launch(handler) {
        store.update { e ->
            val next=transform(e.beta)
            require(next.mapStyle in MapStyles.titles && next.busRefreshMinutes in 15..240)
            e.copy(beta=next)
        }
        val current=store.extras().beta
        if(!current.hostedSharing) { stopLive();stopNearby() }
        if(!current.nearbyUsers) stopNearby()
        if(!current.walkLearning) WalkLearningService.stop(getApplication())
    }
    fun localName(name: String)=viewModelScope.launch(handler) { require(name.length<=80);store.update { it.copy(localName=name.trim()) } }
    fun refresh(service: TransportService,force: Boolean=false) {
        if(!state.value.extras.beta.enabled(service) || service==TransportService.PRASA || jobs[service]?.isActive==true) return
        val now=System.currentTimeMillis();if(now-(lastAttempts[service] ?: 0)<15_000) return message("Wait a moment before checking this source again.")
        lastAttempts[service]=now
        jobs[service]=viewModelScope.launch(handler) {
            mutable.update { it.copy(loading=it.loading+service,error=null) }
            try { val result=buses.refresh(service,force);if(result.notes.isNotEmpty()) message(result.notes.joinToString(" · ")) }
            catch(e: Exception) { if(e is CancellationException) throw e;mutable.update { it.copy(error="${service.title} could not be refreshed. ${if(store.snapshot(service)!=null) "Using the last saved data; check its dates." else "No saved data yet. Open the published source."}") } }
            finally { mutable.update { it.copy(loading=it.loading-service) } }
        }
    }
    fun chooseBus(service: TransportService) { require(service!=TransportService.PRASA);walkJob?.cancel();timetableJob?.cancel();mutable.update { it.copy(bus=service,timetable=null,error=null,selectedBus=null,walks=emptyList(),walkBusy=false) } }
    fun timetable(route: BusRouteDocument,day: String): Job {
        val previous=timetableJob;previous?.cancel();val service=state.value.bus;timetableService=service
        val job=viewModelScope.launch(handler) {
            previous?.join()
            mutable.update { it.copy(timetableLoading=true,error=null) }
            try { require(state.value.extras.beta.enabled(service));val result=buses.timetable(service,route,day)
                if(state.value.bus==service && state.value.extras.beta.enabled(service)) mutable.update { it.copy(timetable=result.first,timetableCached=result.second) }
            } catch(e: Exception) { if(e is CancellationException) throw e;mutable.update { it.copy(error="Couldn’t load this timetable. Open the operator’s published document.") } }
            finally { mutable.update { it.copy(timetableLoading=false) } }
        }
        timetableJob=job;return job
    }
    fun favoriteBus(route: BusRouteDocument,day: String)=viewModelScope.launch(handler) {
        val f=BusFavorite(state.value.bus,route.id,day)
        store.update { it.copy(busFavorites=if(f in it.busFavorites) it.busFavorites-f else (it.busFavorites+f).takeLast(20)) }
    }
    fun correctFare(service: TransportService,fare: String,product: String,cents: Long?)=viewModelScope.launch(handler) {
        require(cents==null || cents in 0..10_000_000)
        store.update { e -> val old=e.priceCorrections.filterNot { it.service==service && it.fareId==fare && it.product==product };e.copy(priceCorrections=(if(cents==null) old else old+BusPriceCorrection(service,fare,product,cents,System.currentTimeMillis())).takeLast(100)) }
    }
    fun layers(now: Long): FeatureLayers {
        val s=state.value;val visible=s.snapshots.filterKeys { s.extras.beta.visible(it) }.mapValues { (service,value) ->
            if(service==s.mapService && s.mapRoute!=null && service==TransportService.MYCITI) value.copy(stops=value.stops.filter { s.mapRoute in it.routes },shapes=value.shapes.filter { it.route==s.mapRoute }) else value
        }
        val pins=visible.flatMap { (service,data)->data.stops.map { MapPin("${service.name}:${it.id}",it.name,it.point,service,"${it.routes.joinToString()} · ${it.source}") } } +
            s.extras.spots.filter { it.service?.let(s.extras.beta::visible) ?: true }.map { MapPin("spot:${it.id}",it.name,it.point,it.service,"Personal pin · ${it.tag}",it.id) } +
            s.watched.mapNotNull { f -> val u=f.update;val point=u.point;val link=s.extras.friends.firstOrNull { it.id==f.id }; if(link!=null && point!=null && u.expiresAt>now && u.sentAt<=now+30_000 && now-u.sentAt<5*60_000) MapPin("friend:${f.id}",link.trip.owner,point,null,"Last shared: ${u.phase} · accuracy ${u.accuracy?.toInt()} m") else null }
        val selected=visible[s.mapService]?.takeIf { s.mapRoute!=null }
        val fitPoints=selected?.shapes?.flatMap { it.points }.orEmpty().ifEmpty { selected?.stops?.map { it.point }.orEmpty() }
        return FeatureLayers(pins,visible.values.flatMap { it.shapes },s.focus,if(fitPoints.isNotEmpty()) "${s.mapService?.title} ${s.mapRoute}" else if(s.walks.isNotEmpty()) s.walks.joinToString { it.key } else null,if(s.walks.isNotEmpty()) fitPoints+s.walks.flatMap { it.points } else fitPoints,s.walks)
    }
    fun showBusMap(service: TransportService,route: String?) {
        require(service!=TransportService.PRASA && state.value.extras.beta.enabled(service))
        mutable.update { it.copy(mapService=service,mapRoute=if(service==TransportService.MYCITI) route else null,focus=null) }
        beta { if(service==TransportService.GOLDEN_ARROW) it.copy(showGoldenArrow=true) else it.copy(showMyCiti=true) }
    }
    fun allMapStops()=mutable.update { it.copy(mapService=null,mapRoute=null) }
    fun showTrainMap() { clearBusJourney();allMapStops();mutable.update { it.copy(focus=null) };beta { it.copy(showTrains=true) } }
    fun refreshVisible() {
        listOf(TransportService.GOLDEN_ARROW,TransportService.MYCITI).filter { state.value.extras.beta.enabled(it) }.forEach { refresh(it,true) }
        state.value.timetable?.takeIf { state.value.extras.beta.enabled(it.service) }?.let { chooseBus(it.service);timetable(it.route,it.day) }
    }
    fun place(endpoint: WalkEndpoint?,start: Boolean,station: String?=null) {
        walkJob?.cancel()
        val preferred=station ?: state.value.extras.spots.firstOrNull { endpoint?.id=="spot:${it.id}" }?.preferredStation
        mutable.update { if(start) it.copy(access=endpoint,boardingStation=preferred,walks=emptyList(),walkError=null,walkBusy=false) else it.copy(destination=endpoint,arrivalStation=preferred,walks=emptyList(),walkError=null,walkBusy=false) }
    }
    fun chooseStation(name: String?,start: Boolean,rememberForSpot: Boolean=false) {
        require(name==null || name.length in 1..180)
        walkJob?.cancel()
        val endpoint=if(start) state.value.access else state.value.destination
        mutable.update { if(start) it.copy(boardingStation=name,walks=emptyList(),walkError=null,walkBusy=false) else it.copy(arrivalStation=name,walks=emptyList(),walkError=null,walkBusy=false) }
        if(rememberForSpot && endpoint?.id?.startsWith("spot:")==true) viewModelScope.launch(handler) {
            val id=endpoint.id.removePrefix("spot:")
            store.update { e->e.copy(spots=e.spots.map { if(it.id==id) it.copy(preferredStation=name) else it }) }
        }
    }
    fun saveJourneyPlace(endpoint: WalkEndpoint,start: Boolean,name: String,tag: String)=action("Couldn’t save this place.") {
        require(endpoint.point.valid && name.trim().length in 1..100 && tag.length<=80)
        val current=state.value
        val preferred=if(start) current.boardingStation else current.arrivalStation
        val spot=MarkedSpot(UUID.randomUUID().toString(),name.trim(),null,endpoint.point,tag,endpoint.label.take(500),createdAt=System.currentTimeMillis(),preferredStation=preferred)
        store.update { it.copy(spots=SavedAddresses.upsert(it.spots,spot)) }
        val saved=store.extras().spots.first { it.point==spot.point && it.name==spot.name }
        val active=if(start) state.value.access else state.value.destination
        if(active==endpoint) place(WalkEndpoint("spot:${saved.id}",saved.name,saved.point),start,saved.preferredStation)
        message("${spot.name} saved on this device.")
    }
    fun swapPlaces() {
        walkJob?.cancel()
        mutable.update { it.copy(access=it.destination,destination=it.access,boardingStation=it.arrivalStation,arrivalStation=it.boardingStation,walks=emptyList(),walkError=null,walkBusy=false,selectedBus=null) }
    }
    fun walks(train: TrainState) {
        val previous=walkJob;previous?.cancel()
        val s=state.value
        val a=train.stations.firstOrNull { stationKey(it.name)==stationKey(train.from) && it.mapped }
        val b=train.stations.firstOrNull { stationKey(it.name)==stationKey(train.to) && it.mapped }
        val pairs=listOfNotNull(s.access?.let { p->a?.let { p to WalkingRoutes.endpoint(it) } },s.destination?.let { p->b?.let { WalkingRoutes.endpoint(it) to p } })
        if(pairs.isEmpty()) return message("Select a saved place or current location and a mapped station first.")
        walkJob=viewModelScope.launch(handler) {
            previous?.join()
            mutable.update { it.copy(walkBusy=true,walkError=null,walks=emptyList(),walksCached=false) }
            try { for((from,to) in pairs) {
                val (leg,cached)=walking.route(from,to)
                mutable.update { it.copy(walks=it.walks+leg,walksCached=it.walksCached || cached) }
            } } catch(e: Exception) { if(e is CancellationException) throw e
                mutable.update { it.copy(walkError="Walking route unavailable. Straight-line distances are shown separately; walking time is unknown until a route is available.") }
            } finally { mutable.update { it.copy(walkBusy=false) } }
        }
    }
    fun refreshRouteSteps()=action("Steps could not be read. Check Health Connect permission and Samsung Health sync.") {
        require(store.extras().beta.healthSteps)
        val health=RouteSteps(getApplication());require(health.granted())
        val now=System.currentTimeMillis()
        val samples=store.extras().walkSamples.filter { it.startedAt>0 && now-it.recordedAt in 0..30L*24*60*60_000 }.takeLast(20)
        var count=0
        for(sample in samples) {
            if(!store.extras().beta.healthSteps) break
            val steps=health.read(sample.startedAt,sample.startedAt+sample.seconds*1000L)
            if(steps!=null) count++
            store.update { e->e.copy(walkSamples=e.walkSamples.map { if(it.key==sample.key && it.recordedAt==sample.recordedAt) it.copy(steps=steps,stepsCheckedAt=now) else it }) }
        }
        message("Updated $count completed walking segments. Missing counts may need watch sync; try again later.")
    }
    fun selectPin(pin: MapPin?)=mutable.update { it.copy(pin=pin,focus=pin?.point ?: it.focus) }
    fun focus(point: GeoPoint) { require(point.valid);mutable.update { it.copy(focus=point) } }
    fun drop(point: GeoPoint) { require(point.valid);mutable.update { it.copy(pin=MapPin("new","New spot",point,null,"Personal pin"),focus=point) } }
    fun saveSpot(name: String,tag: String,service: TransportService?,note: String,photoUri: Uri?)=action("Couldn’t save this spot or photo.") {
        require(name.trim().length in 1..100 && tag.length<=80 && note.length<=500)
        val pin=state.value.pin ?: error("Choose a point")
        val old=store.extras().spots.firstOrNull { it.id==pin.spotId }
        val photo=photoUri?.let { files.addPhoto(it) } ?: old?.photo
        val spot=MarkedSpot(old?.id ?: UUID.randomUUID().toString(),name.trim(),service,pin.point,tag.trim(),note.trim(),photo,old?.createdAt ?: System.currentTimeMillis(),old?.preferredStation)
        store.update { it.copy(spots=SavedAddresses.upsert(it.spots,spot)) };selectPin(null);message("Spot saved on this device.")
    }
    fun plannerMode(service: TransportService) { mutable.update { it.copy(plannerMode=service.name) } }
    fun saveSpotStation(id: String,station: String?)=viewModelScope.launch(handler) {
        store.update { it.copy(spots=it.spots.map { spot->if(spot.id==id) spot.copy(preferredStation=station) else spot }) }
        mutable.update { it.copy(boardingStation=if(it.access?.id=="spot:$id") station else it.boardingStation,arrivalStation=if(it.destination?.id=="spot:$id") station else it.arrivalStation,walks=emptyList()) }
    }
    fun saveAddress(label: String,endpoint: WalkEndpoint,existingId: String?=null,station: String?=null)=action("Couldn’t save this address.") {
        require(label.length in 1..100 && endpoint.point.valid)
        val old=store.extras().spots.firstOrNull { it.id==existingId }
        val spot=MarkedSpot(old?.id ?: UUID.randomUUID().toString(),label,null,endpoint.point,if(label in SavedAddresses.labels) label else old?.tag ?: "Custom",endpoint.label.take(500),old?.photo,old?.createdAt ?: System.currentTimeMillis(),station ?: old?.preferredStation)
        store.update { it.copy(spots=SavedAddresses.upsert(it.spots,spot)) }
        val saved=store.extras().spots.first { it.point==spot.point && it.name==label }
        mutable.update { value->value.copy(access=if(value.access?.id=="spot:${saved.id}") WalkEndpoint("spot:${saved.id}",saved.name,saved.point) else value.access,destination=if(value.destination?.id=="spot:${saved.id}") WalkEndpoint("spot:${saved.id}",saved.name,saved.point) else value.destination,walks=emptyList()) }
        message("$label address saved.")
    }
    fun saveRoutine(routine: CommuteRoutine)=action("Couldn’t save this recurring journey.") {
        CommuteRoutines.validate(routine)
        store.update { it.copy(routines=(it.routines.filterNot { row->row.id==routine.id }+routine).takeLast(30)) }
        message("Recurring journey saved. Timetables are checked for each travel date.")
    }
    fun toggleRoutine(id: String)=viewModelScope.launch(handler) { store.update { it.copy(routines=it.routines.map { row->if(row.id==id) row.copy(enabled=!row.enabled) else row }) } }
    fun removeRoutine(id: String)=viewModelScope.launch(handler) { store.update { it.copy(routines=it.routines.filterNot { row->row.id==id }) } }
    fun removeSpot(id: String)=viewModelScope.launch(handler) { store.update { it.copy(spots=it.spots.filterNot { s -> s.id==id }) };selectPin(null) }
    suspend fun placeSuggestions(query: String)=places.suggestions(query)
    fun selectPlace(place: PlaceSuggestion) { selectPin(MapPin("new",place.label.take(100),place.point,null,"Photon search result · © OpenStreetMap contributors")) }
    fun searchPlace(query: String)=action("Place search unavailable. Use a map pin or search saved stops.") {
        val list=places.suggestions(query);list.firstOrNull()?.let(::selectPlace) ?: message("No published address matched. Try a street/suburb or mark a map point.")
    }
    fun selectBus(journey: BusJourney) { beta { if(journey.timetable.service==TransportService.MYCITI) it.copy(showMyCiti=true) else it.copy(showGoldenArrow=true) };mutable.update { it.copy(selectedBus=journey,mapService=journey.timetable.service,mapRoute=if(journey.timetable.service==TransportService.MYCITI) journey.timetable.route.id else null,focus=null) } }
    fun clearBusJourney()=mutable.update { it.copy(selectedBus=null) }
    fun usePlannedWalks(legs: List<WalkingLeg>) { walkJob?.cancel();mutable.update { it.copy(walks=legs,walkBusy=false,walkError=null) } }
    fun confirmBusStop(route: BusRouteDocument,call: BusCall,stop: BusStopPoint)=viewModelScope.launch(handler) {
        val service=state.value.bus
        val link=BusStopLink(service,route.id,call.id,stop.id,System.currentTimeMillis())
        store.update { e -> e.copy(busStopLinks=(e.busStopLinks.filterNot { it.service==service && it.routeId==route.id && it.callId==call.id }+link).takeLast(200)) }
        message("Personal timed-stop association saved. It is not an official boarding-point verification.")
    }
    fun busWalks(board: BusStopPoint?,alight: BusStopPoint?) {
        val s=state.value
        val pairs=listOfNotNull(s.access?.let { p->board?.let { p to WalkEndpoint("bus:${s.bus.name}:${it.id}",it.name,it.point) } },s.destination?.let { p->alight?.let { WalkEndpoint("bus:${s.bus.name}:${it.id}",it.name,it.point) to p } })
        calculateWalks(pairs)
    }
    private fun calculateWalks(pairs: List<Pair<WalkEndpoint,WalkEndpoint>>) {
        val previous=walkJob;previous?.cancel()
        if(pairs.isEmpty()) return message("Choose mapped boarding/alighting stops and a saved place first.")
        walkJob=viewModelScope.launch(handler) {
            previous?.join();mutable.update { it.copy(walkBusy=true,walkError=null,walks=emptyList(),walksCached=false) }
            try { for((from,to) in pairs) { val (leg,cached)=walking.route(from,to);mutable.update { it.copy(walks=it.walks+leg,walksCached=it.walksCached || cached) } } }
            catch(e: Exception) { if(e is CancellationException) throw e;mutable.update { it.copy(walkError="Walking route unavailable. Walking time is unknown until a saved or online route is available.") } }
            finally { mutable.update { it.copy(walkBusy=false) } }
        }
    }
    fun saveTrip(trip: SafetyTrip)=viewModelScope.launch(handler) { TripFiles.validate(trip);store.update { it.copy(trips=(it.trips.filterNot { t->t.id==trip.id }+trip).takeLast(100)) };message("Safety trip saved locally.") }
    fun deleteTrip(id: String)=viewModelScope.launch(handler) { store.update { it.copy(trips=it.trips.filterNot { t->t.id==id }) } }
    fun importFile(uri: Uri)=action("This file could not be imported. Use a supported CT Trains trip, report or live-trip invitation.") {
        val raw=files.read(uri);TripFiles.document(raw);val format=store.json.parseToJsonElement(raw).jsonObject["format"]?.jsonPrimitive?.content
        when(format) {
            "cttrains.trip" -> mutable.update { it.copy(importedTrip=TripFiles.parse(raw)) }
            "cttrains.circle" -> mutable.update { it.copy(importedCircle=CircleUpdates.validate(TripFiles.json.decodeFromString(raw),System.currentTimeMillis())) }
            "cttrains.report" -> mutable.update { it.copy(importedReport=CommunityReports.validate(TripFiles.json.decodeFromString(raw))) }
            else -> error("Unsupported file")
        }
    }
    fun importIntent(intent: Intent?) {
        if(intent?.action !in listOf(Intent.ACTION_VIEW,Intent.ACTION_SEND)) return
        @Suppress("DEPRECATION") val uri=intent?.data ?: intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if(uri?.scheme=="content") importFile(uri)
    }
    fun dismissImport()=mutable.update { it.copy(importedTrip=null,importedCircle=null,importedReport=null,backup=null) }
    fun acceptTrip() { state.value.importedTrip?.let(::saveTrip);dismissImport() }
    fun acceptCircle()=viewModelScope.launch(handler) { val link=state.value.importedCircle ?: return@launch;CircleUpdates.validate(link,System.currentTimeMillis());store.update { it.copy(friends=(it.friends.filterNot { f->f.id==link.id }+link).takeLast(20)) };dismissImport();message("Invitation saved. Start watching in Community when ready.") }
    fun acceptReport()=viewModelScope.launch(handler) { val r=state.value.importedReport ?: return@launch;store.update { it.copy(reports=(it.reports+ReceivedReport(r,"Imported file",r.id,System.currentTimeMillis(),false)).takeLast(300)) };dismissImport() }
    fun share(raw: String,extension: String,title: String)=action("Couldn’t create a shared file.") { val intent=files.shareFile(raw,extension,title);getApplication<Application>().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun export(uri: Uri,password: String,automatic: Boolean)=action("Backup could not be written. Choose a writable file and a password of at least ten characters.") {
        files.write(uri,files.export(password));if(automatic) {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            store.saveBackupLink(BackupLink(uri.toString(),password))
        };message("Encrypted backup saved. Keep its password to restore after reinstalling.")
    }
    fun pauseBackup()=viewModelScope.launch(handler) { store.saveBackupLink(null);message("Automatic backup paused.") }
    fun inspectBackup(uri: Uri,password: String)=action("Backup could not be opened. Check the password and file.") { val p=files.inspect(files.read(uri,34*1024*1024),password);mutable.update { it.copy(backup=p) } }
    fun restore()=action("Couldn’t restore the profile.") {
        val p=state.value.backup ?: error("No backup");stopNearby();WalkLearningService.stop(getApplication());walkJob?.cancel();stopLive();files.restore(p);dismissImport();message("Profile restored. Reminders are paused; choose the backup file again to enable automatic saving.")
    }
    fun login(server: String,user: String,password: String,token: Boolean)=action("Sign-in failed. Check your Matrix homeserver and account. Google sign-in is a separate local profile option.") {
        require(state.value.extras.beta.hostedSharing);val s=if(token) matrix.token(server,password) else matrix.login(server,user,password)
        store.saveSession(s);message("Connected to ${s.userId}. Choose or create a service room.")
    }
    fun logout()=action("Disconnected locally; the server session could not be revoked. Revoke it in your Matrix client.") {
        stopLive();stopNearby();syncJob?.cancel();val s=store.session();store.saveSession(null);store.update { it.copy(queued=emptyList()) };mutable.update { it.copy(chat=emptyList(),nearby=emptyList(),chatService=null) };if(s!=null) matrix.logout(s)
    }
    private suspend fun session(): MatrixSession { require(store.extras().beta.hostedSharing);return store.session() ?: error("Sign in to Matrix first") }
    fun room(service: TransportService,room: String,create: Boolean)=action("Couldn’t join or create the room. This client supports unencrypted community rooms; use Element for Matrix-encrypted chats.") {
        val s=session();val id=if(create) matrix.create(s,"CT Trains · ${service.title}",false) else matrix.join(s,room.trim())
        store.update { it.copy(rooms=it.rooms.filterNot { r->r.service==service }+ReportRoom(service,id)) };message("Room connected: $id")
    }
    fun report(report: CommunityReport,online: Boolean)=viewModelScope.launch(handler) {
        CommunityReports.validate(report)
        val room=store.extras().rooms.firstOrNull { it.service==report.service }?.room
        if(!online || room==null) {
            store.update { it.copy(reports=(it.reports+ReceivedReport(report,"This device",report.id,System.currentTimeMillis(),false)).takeLast(300)) };message("Report saved locally. Share its formatted message or file.");return@launch
        }
        val s=runCatching { session() }.getOrNull() ?: return@launch message("Connect Matrix in Settings before posting.")
        store.update { it.copy(queued=(it.queued.filterNot { q->q.report.id==report.id }+QueuedReport(report,room,s.userId)).takeLast(100)) }
        ReportUploadWorker.enqueue(getApplication());message("Report queued for this room. Expired observations will not be posted.")
    }
    fun chat(service: TransportService,text: String,onSent: ()->Unit={})=action("Message could not be sent. The draft is still on this screen.") {
        val s=session();val room=store.extras().rooms.firstOrNull { it.service==service } ?: error("Choose a room")
        matrix.send(s,room.room,text.trim());onSent();sync(service)
    }
    fun sync(service: TransportService) {
        if(syncJob?.isActive==true && state.value.chatService==service) return
        syncJob?.cancel()
        mutable.update { it.copy(chatService=service,chat=if(it.chatService==service) it.chat else emptyList(),nearby=if(it.chatService==service) it.nearby else emptyList()) }
        syncJob=viewModelScope.launch(handler) {
            mutable.update { it.copy(syncing=true) }
            try {
                val s=session();val room=store.extras().rooms.firstOrNull { it.service==service } ?: error("Choose a room")
                val messages=matrix.messages(s,room.room)
                store.update { e -> e.copy(reports=(e.reports+messages.mapNotNull { m -> m.report?.takeIf { it.service==service && CommunityReports.fresh(it,System.currentTimeMillis()) }?.let { ReceivedReport(it,m.sender,m.id,m.at,true) } }).distinctBy { it.eventId }.filter { CommunityReports.fresh(it.report,System.currentTimeMillis()) }.takeLast(300)) }
                if(state.value.chatService==service) mutable.update { it.copy(chat=messages.filter { m->m.update==null && m.presence==null }.sortedBy { m->m.at },nearby=messages.filter { m->m.presence!=null }) }
            } catch(e: Exception) { if(e is CancellationException) throw e;message("Couldn’t refresh this room. Check your account, connection and room settings.") }
            finally { if(state.value.chatService==service) mutable.update { it.copy(syncing=false) } }
        }
    }
    private var nearbySession: Pair<MatrixSession,String>?=null
    private var nearbyDeadline=0L
    private var nearbyJob: Job?=null
    fun nearby(service: TransportService,point: GeoPoint) {
        if(nearbyJob?.isActive==true) return
        nearbyJob=viewModelScope.launch(handler) {
            try {
                val extras=store.extras();require(extras.beta.nearbyUsers && extras.beta.hostedSharing)
                val account=session();val room=extras.rooms.first { it.service==service }.room
                val former=nearbySession
                if(former!=null && former!=(account to room)) {
                    nearbySession=null
                    val at=System.currentTimeMillis()
                    runCatching { matrix.send(former.first,former.second,"CT Trains nearby sharing stopped",presence=NearbyPresence(sentAt=at,expiresAt=at,active=false)) }
                }
                if(nearbySession==null) { nearbySession=account to room;nearbyDeadline=System.currentTimeMillis()+15*60_000L }
                if(System.currentTimeMillis()>=nearbyDeadline) { stopNearby();return@launch }
                matrix.send(account,room,"Approximate CT Trains nearby presence · expires in 5 minutes",presence=NearbyPeople.create(point,System.currentTimeMillis()))
                mutable.update { it.copy(nearbySharing=true) };sync(service)
            } catch(e: Exception) { if(e is CancellationException) throw e;message("Nearby sharing unavailable. Connect a service room and grant foreground location access.");stopNearby() }
        }
    }
    fun stopNearby() {
        nearbyJob?.cancel();nearbyJob=null
        val old=nearbySession;nearbySession=null;nearbyDeadline=0
        mutable.update { it.copy(nearbySharing=false) }
        if(old!=null) viewModelScope.launch { runCatching { val now=System.currentTimeMillis();matrix.send(old.first,old.second,"CT Trains nearby sharing stopped",presence=NearbyPresence(sentAt=now,expiresAt=now,active=false)) } }
    }
    fun createCircle(trip: SafetyTrip,friend: String)=action("Couldn’t create this live trip. Sign in to Matrix and enter your friend’s Matrix ID.") {
        TripFiles.validate(trip);val s=session();val room=matrix.create(s,"Private CT Trains trip",true)
        matrix.invite(s,room,friend.trim())
        val link=CircleLink(id=UUID.randomUUID().toString(),room=room,ownerId=s.userId,secret=PortableCrypto.secret(),expiresAt=System.currentTimeMillis()+2*60*60_000L,trip=trip)
        store.saveCircle(LiveCircle(link));message("Private invitation ready. Share its file only with the invited friend; location sharing is still off.")
    }
    fun live(start: Boolean,watch: Boolean=false) {
        if(start && TripLiveService.activeMode!=null) return message("A live session is already running (${TripLiveService.activeMode}). Stop it before starting another mode.")
        if(start) runCatching { TripLiveService.start(getApplication(),watch) }.onFailure { message("Couldn’t start a live session. Keep the app open and check permissions.") } else stopLive()
    }
    // stopService identifies the explicit Service component, not an object/listener instance.
    @android.annotation.SuppressLint("ImplicitSamInstance")
    fun stopLive() { getApplication<Application>().stopService(Intent(getApplication(),TripLiveService::class.java));viewModelScope.launch(handler) { store.liveCircle()?.let { store.saveCircle(it.copy(sharing=false)) } } }
    fun phase(phase: String)=viewModelScope.launch(handler) { require(phase in CircleUpdates.phases);store.liveCircle()?.let { store.saveCircle(it.copy(phase=phase)) } }
    fun removeFriend(id: String)=viewModelScope.launch(handler) { stopLive();store.update { it.copy(friends=it.friends.filterNot { f->f.id==id }) };store.saveWatched(store.watched().filterNot { it.id==id }) }
    fun reset() { za.co.cttrains.app.directions.DirectionsService.stop(getApplication());za.co.cttrains.app.updates.UpdateWork.reset(getApplication());za.co.cttrains.app.whatsapp.WhatsAppReader.configure(getApplication(),null); jobs.values.forEach { it.cancel() };timetableJob?.cancel();walkJob?.cancel();syncJob?.cancel();stopNearby();WalkLearningService.stop(getApplication());stopLive();files.clearPrivatePhotos();BusRefreshWorker.configure(getApplication(),BetaPreferences());mutable.value=FeatureState() }
    private fun action(failure: String,block: suspend ()->Unit)=viewModelScope.launch(handler) {
        if(state.value.busy) return@launch
        mutable.update { it.copy(busy=true,error=null) }
        try { block() } catch(e: Exception) { if(e is CancellationException) throw e;mutable.update { it.copy(error=failure) } }
        finally { mutable.update { it.copy(busy=false) } }
    }
}
