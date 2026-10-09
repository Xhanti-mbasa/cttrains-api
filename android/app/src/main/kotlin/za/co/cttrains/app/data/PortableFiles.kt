package za.co.cttrains.app.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import za.co.cttrains.core.*

@Serializable data class PortableProfile(val format: String = "cttrains.profile", val version: Int = 1,
    val createdAt: Long, val preferences: Preferences, val plans: List<ScheduledJourney>,
    val searches: List<CachedSearch>, val catalog: StationCatalog?, val values: Map<String,String>,
    val photos: Map<String,String> = emptyMap())

class PortableFiles(private val context: Context, private val features: FeatureStore) {
    private val codec = SecureCodec()
    private val photos = File(context.filesDir,"spot-photos").apply { mkdirs() }
    private fun photo(id: String): File { require(Regex("[a-f0-9-]{36}").matches(id)); return File(photos,id) }
    suspend fun addPhoto(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = readBytes(uri,12*1024*1024)
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000)
        var sample=1; while (bounds.outWidth/sample>1600 || bounds.outHeight/sample>1600) sample*=2
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("Invalid photo")
        val output=ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG,80,output); bitmap.recycle()
        require(output.size()<=1024*1024)
        val id=UUID.randomUUID().toString(); photo(id).writeText(codec.encrypt(PortableCrypto.encode(output.toByteArray())))
        if(uri.authority=="${context.packageName}.files") runCatching { context.contentResolver.delete(uri,null,null) }
        id // Re-encoding omits location and camera metadata.
    }
    suspend fun bitmap(id: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { val b=PortableCrypto.decode(codec.decrypt(photo(id).readText())); BitmapFactory.decodeByteArray(b,0,b.size) }.getOrNull()
    }
    private fun readBytes(uri: Uri, max: Int): ByteArray {
        require(uri.scheme == "content")
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val out=ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while (true) { val n=input.read(buffer); if (n<0) break; require(out.size()+n<=max); out.write(buffer,0,n) }; out.toByteArray()
        } ?: error("File unavailable")
    }
    suspend fun read(uri: Uri,max: Int=TripFiles.MAX_TRIP_BYTES)=withContext(Dispatchers.IO) { readBytes(uri,max).toString(Charsets.UTF_8) }
    suspend fun write(uri: Uri,raw: String)=withContext(Dispatchers.IO) {
        require(uri.scheme == "content")
        context.contentResolver.openOutputStream(uri,"wt")?.use { it.write(raw.toByteArray()) } ?: error("File not writable")
    }
    suspend fun export(password: String): String = withContext(Dispatchers.IO) {
        val profile=features.store.portable()
        val extras=profile.values["extras"]?.let { features.json.decodeFromString<LocalExtras>(it) } ?: LocalExtras()
        val images=extras.spots.mapNotNull { it.photo }.distinct().associateWith { codec.decrypt(photo(it).readText()) }
        val safe=extras.copy(friends=emptyList(),queued=emptyList(),reports=(extras.reports+extras.queued.map { ReceivedReport(it.report,"Unsent local observation",it.report.id,it.report.observedAt,false) }).takeLast(300))
        val values=profile.values+ ("extras" to features.json.encodeToString(safe))
        val plain=features.json.encodeToString(profile.copy(photos=images,values=values))
        val pass=password.toCharArray(); try { PortableCrypto.encrypt(plain,pass) } finally { pass.fill('\u0000') }
    }
    suspend fun inspect(raw: String,password: String): PortableProfile = withContext(Dispatchers.IO) {
        val pass=password.toCharArray()
        val plain=try { PortableCrypto.decrypt(raw,pass) } finally { pass.fill('\u0000') }
        TripFiles.document(plain,24*1024*1024)
        val p=TripFiles.json.decodeFromString<PortableProfile>(plain)
        require(p.format=="cttrains.profile" && p.version==1 && p.createdAt>0 && p.plans.size<=500 && p.searches.size<=100 && p.values.size<=250 && p.photos.size<=100)
        require(p.preferences.routes.size<=100 && p.preferences.favoriteStations.size<=500)
        require(p.preferences.theme in listOf("System","Light","Dark") && p.preferences.mapMode in listOf("online","offline","stations"))
        require(p.plans.map { it.id }.distinct().size==p.plans.size && p.plans.map { it.identity }.distinct().size==p.plans.size)
        fun checkQuery(q: SearchQuery) { require(q.from.length in 1..180 && q.to.length in 1..180);java.time.LocalDate.parse(q.date);java.time.LocalTime.parse(q.time) }
        val now=System.currentTimeMillis()
        p.searches.forEach { e -> checkQuery(e.query);require(e.journeys.size<=200);validateTransitEntry(e,e.query,e.sourceId,now) }
        p.plans.forEach { plan ->
            checkQuery(plan.query);require(plan.id.length in 1..80 && plan.profileId.length<=100 && plan.journey.legs.size in 1..10)
            require(plan.journey.legs.all { it.stops.size in 2..200 });validateTransitEntry(CachedSearch(plan.query,listOf(plan.journey),plan.fetchedAt,plan.fares,plan.sourceId),plan.query,plan.sourceId,now)
            require(plan.accessMinutes in 0..360 && plan.bufferMinutes in 0..120 && plan.backupReminders.size<=3 && plan.leaveFrom.length<=80)
        }
        p.catalog?.let { c -> require(c.stations.size<=2000 && c.stations.all { it.length in 1..180 } && c.holidays.size<=500);c.holidays.forEach(java.time.LocalDate::parse) }
        p.values.forEach { (key,value) ->
            when {
                key=="extras" -> {
                    val e=TripFiles.json.decodeFromString<LocalExtras>(value)
                    require(e.localName.length<=80 && e.spots.size<=100 && e.trips.size<=100 && e.friends.size<=20 && e.reports.size<=300 && e.queued.size<=100)
                    require(e.busStopLinks.size<=200 && e.busStopLinks.all { it.service!=TransportService.PRASA && it.routeId.length in 1..500 && it.callId.length in 1..180 && it.stopId.length in 1..300 && it.confirmedAt in 1..now+30_000 })
                    require(e.walkSamples.size<=200 && e.walkSamples.all { it.key.length<=500 && it.seconds in 20..7200 && it.distanceMeters in 50.0..30000.0 && it.recordedAt in 1..now+30_000 && it.startedAt in 0..it.recordedAt && (it.steps==null || it.steps in 0L..100_000L) && it.stepsCheckedAt in 0..now+30_000 })
                    require(e.routines.size<=30);e.routines.forEach(CommuteRoutines::validate)
                    e.trips.forEach(TripFiles::validate)
                    e.spots.forEach { require(it.point.valid && it.name.length in 1..100 && it.note.length<=500 && it.tag.length<=80 && (it.preferredStation?.length?.let { length -> length in 1..180 } ?: true)); it.photo?.let(::photo) }
                    require(e.spots.mapNotNull { it.photo }.all { it in p.photos })
                    e.reports.forEach { CommunityReports.validate(it.report) }
                    require(e.beta.mapStyle in listOf("streets","light","dark","aerial") && e.beta.busRefreshMinutes in 15..240)
                }
                Regex("walk-[a-f0-9]{64}").matches(key) -> {
                    val leg=TripFiles.json.decodeFromString<WalkingLeg>(value)
                    require(leg.from.point.valid && leg.to.point.valid && leg.from.label.length<=100 && leg.to.label.length<=100 && leg.points.size in 2..15000 && leg.points.all(GeoPoint::valid) && leg.distanceMeters in 0.0..30000.0 && leg.durationSeconds in 0.0..28800.0 && leg.fetchedAt in 1..now+30_000)
                }
                Regex("bus-(GOLDEN_ARROW|MYCITI)").matches(key) -> BusSources.validate(TripFiles.json.decodeFromString<BusSnapshot>(value))
                Regex("bus-time-(GOLDEN_ARROW|MYCITI)-[a-f0-9]{64}").matches(key) -> BusSources.validate(TripFiles.json.decodeFromString<BusTimetable>(value))
                else -> error("Unsupported record")
            }
        }
        p.photos.forEach { (id,image) -> photo(id); val b=PortableCrypto.decode(image); require(b.size<=1024*1024 && b.size>=3 && b[0]==0xff.toByte() && b[1]==0xd8.toByte()) }
        p
    }
    suspend fun restore(p: PortableProfile) = withContext(Dispatchers.IO) {
        // Fresh identifiers keep the current profile's photos intact if the database transaction fails.
        val remap=p.photos.keys.associateWith { UUID.randomUUID().toString() }
        p.photos.forEach { (id,image) -> photo(remap.getValue(id)).writeText(codec.encrypt(image)) }
        val values=p.values.toMutableMap()
        values["extras"]?.let { raw ->
            val e=features.json.decodeFromString<LocalExtras>(raw)
            values["extras"]=features.json.encodeToString(e.copy(beta=e.beta.copy(hostedSharing=false,walkLearning=false,nearbyUsers=false,healthSteps=false),queued=emptyList(),friends=emptyList(),spots=e.spots.map { it.copy(photo=it.photo?.let(remap::getValue)) },reports=e.reports.map { it.copy(hosted=false,sender="Restored observation") }))
        }
        za.co.cttrains.app.whatsapp.WhatsAppReader.configure(context,null)
        za.co.cttrains.app.scheduling.JourneyReminders(context).cancelAll()
        try { features.store.replacePersonalData(p.preferences.copy(offlineMapId=null),p.plans,p.searches,p.catalog,values) }
        catch(e: Exception) { remap.values.forEach { photo(it).delete() };throw e }
        photos.listFiles()?.filter { it.name !in remap.values }?.forEach { it.delete() }
    }
    suspend fun shareFile(raw: String,extension: String,title: String): Intent = withContext(Dispatchers.IO) {
        require(extension in listOf("cttrip","ctcircle","ctreport")); require(raw.toByteArray().size<=TripFiles.MAX_TRIP_BYTES)
        val dir=File(context.cacheDir,"shared").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>24*60*60_000L }?.forEach { it.delete() }
        val file=File(dir,"${UUID.randomUUID()}.$extension").apply { writeText(raw) }
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM,uri)
            .putExtra(Intent.EXTRA_TEXT,title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData=android.content.ClipData.newUri(context.contentResolver,title,uri) },"Share with Quick Share or another app")
    }
    fun clearPrivatePhotos() { photos.listFiles()?.forEach { it.delete() };File(context.cacheDir,"shared").listFiles()?.forEach { it.delete() } }
}
