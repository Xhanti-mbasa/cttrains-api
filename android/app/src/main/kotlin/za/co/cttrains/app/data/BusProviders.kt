package za.co.cttrains.app.data

import android.content.Context
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import za.co.cttrains.core.*

interface BusProvider {
    val service: TransportService
    suspend fun snapshot(previous: BusSnapshot?,forceMap: Boolean=false): BusSnapshot
    suspend fun timetable(route: BusRouteDocument,day: String): BusTimetable
}

class MyCitiProvider: BusProvider {
    override val service=TransportService.MYCITI
    private val http=SourceHttp()
    private val hosts=setOf("myciti.org.za","www.myciti.org.za")
    override suspend fun snapshot(previous: BusSnapshot?,forceMap: Boolean)=withContext(Dispatchers.Default) {
        val now=System.currentTimeMillis()
        val base=BusSources.myCitiMap(http.get(BusSources.MYCITI_MAP,hosts=hosts),now)
        try { val (fares,date)=BusSources.fares(http.get(BusSources.MYCITI_FARES,hosts=hosts),service);base.copy(fares=fares,fareDate=date) }
        catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e
            base.copy(fares=previous?.fares.orEmpty(),fareDate=previous?.fareDate,faresCheckedAt=previous?.faresCheckedAt ?: 0,notes=listOf("Fare refresh unavailable. Any retained prices use the previous check date.")) }
    }
    override suspend fun timetable(route: BusRouteDocument,day: String)=withContext(Dispatchers.Default) {
        require(BusSources.officialUrl(route.url,service) && day in listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"))
        val url=route.url.toHttpUrl().newBuilder().setQueryParameter("day",day).build().toString()
        BusSources.myCitiTimetable(http.get(url,hosts=hosts),route,day,System.currentTimeMillis())
    }
}

class GoldenArrowProvider(context: Context): BusProvider {
    override val service=TransportService.GOLDEN_ARROW
    private val app=context.applicationContext
    private val http=SourceHttp(cookies=true)
    private val hosts=setOf("gabs.co.za","www.gabs.co.za")
    suspend fun catalogue(letter: String="All"): List<BusRouteDocument> = withContext(Dispatchers.Default) {
        require(letter=="All" || Regex("[A-Z]").matches(letter))
        val html=http.get(BusSources.GABS,hosts=hosts)
        val doc=Jsoup.parse(html)
        val fields=doc.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }
        if(fields["__VIEWSTATE"].isNullOrBlank()) throw ScheduleFormatException("Golden Arrow's search format changed.")
        suspend fun page(target: String): List<BusRouteDocument> {
            val form=FormBody.Builder()
            fields.filterKeys { it !in setOf("__EVENTTARGET","__EVENTARGUMENT","select_from") }.forEach { (name,value)->form.add(name,value) }
            // Initial HTML does not render these hidden inputs; add them explicitly.
            form.add("__EVENTTARGET",target).add("__EVENTARGUMENT","").add("select_from","")
            val response=http.bytes(Request.Builder().url(BusSources.GABS).post(form.build()).build(),8*1024*1024,hosts).toString(Charsets.UTF_8)
            return BusSources.goldenCatalogue(response)
        }
        if(letter!="All") return@withContext page(letter)
        val first=page("All")
        val letters=doc.select("button.letters[id]").map { it.id() }.filter { Regex("[A-Z]").matches(it) }.distinct()
        // Some publisher versions answer All with only A; load the remaining letter pages.
        if(first.map { it.title.firstOrNull() }.distinct().size>1 || letters.size<2) return@withContext first
        val result=first.toMutableList()
        for(target in letters.filter { it!=first.first().title.firstOrNull()?.toString() }) result+=page(target)
        result.distinctBy { it.url }
    }
    // The stop layer contains 100k+ route memberships. Group first, then request one
    // WGS84 geometry per surveyed stop; the sampled route code is not route membership.
    private suspend fun uniqueStops(): List<String> {
        val pages=mutableListOf<String>();var after: String?=null;var count=0
        val base="${BusSources.GABS_SURVEY}/6/query".toHttpUrl()
        while(true) {
            val grouped=base.newBuilder().addQueryParameter("where",GoldenSurvey.where(after))
                .addQueryParameter("groupByFieldsForStatistics","BUSSTOPNO")
                .addQueryParameter("outStatistics","[{\"statisticType\":\"min\",\"onStatisticField\":\"OBJECTID\",\"outStatisticFieldName\":\"sample_id\"}]")
                .addQueryParameter("orderByFields","BUSSTOPNO").addQueryParameter("returnGeometry","false")
                .addQueryParameter("resultRecordCount","2000").addQueryParameter("f","json").build().toString()
            val page=GoldenSurvey.ids(http.get(grouped,2*1024*1024,setOf("gis.westerncape.gov.za")),after)
            page.ids.chunked(400).forEach { batch ->
                val query=base.newBuilder().addQueryParameter("objectIds",batch.joinToString(","))
                    .addQueryParameter("outFields","OBJECTID,BUSSTOPNO,BUSSTOPDES,ROUTECODE")
                    .addQueryParameter("outSR","4326").addQueryParameter("returnGeometry","true").addQueryParameter("f","json").build().toString()
                val raw=http.get(query,3*1024*1024,setOf("gis.westerncape.gov.za"))
                val root=Json.parseToJsonElement(raw).jsonObject
                require(root["error"]==null && root["exceededTransferLimit"]?.jsonPrimitive?.booleanOrNull!=true)
                pages+=raw
            }
            count+=page.ids.size;require(count<=20000)
            after=page.next ?: break
        }
        // Numberless records cannot be grouped by their shared '-' code.
        pages+=survey(6,"BUSSTOPNO = '-' OR BUSSTOPNO IS NULL OR BUSSTOPNO = ''")
        return pages
    }
    private suspend fun survey(layer: Int,where: String="1=1"): List<String> {
        val pages=mutableListOf<String>();var offset=0
        while(true) {
            val url="${BusSources.GABS_SURVEY}/$layer/query".toHttpUrl().newBuilder().addQueryParameter("where",where).addQueryParameter("outFields",if(layer==6) "OBJECTID,ROUTECODE,BUSSTOPNO,BUSSTOPDES" else "OBJECTID,Route_No,Route_name")
                .addQueryParameter("outSR","4326").addQueryParameter("maxAllowableOffset","0.00015").addQueryParameter("orderByFields","OBJECTID").addQueryParameter("resultRecordCount","2000").addQueryParameter("resultOffset",offset.toString()).addQueryParameter("f","json").build().toString()
            val raw=http.get(url,12*1024*1024,setOf("gis.westerncape.gov.za"));val root=Json.parseToJsonElement(raw).jsonObject
            require(root["error"]==null);pages+=raw
            val count=root["features"]?.jsonArray?.size ?: error("Missing survey features.")
            if(root["exceededTransferLimit"]?.jsonPrimitive?.booleanOrNull!=true) break
            require(count>0 && offset<18000);offset+=count
        }
        return pages
    }
    override suspend fun snapshot(previous: BusSnapshot?,forceMap: Boolean)=withContext(Dispatchers.Default) {
        val now=System.currentTimeMillis();val notes=mutableListOf<String>()
        var catalogueAt=previous?.catalogueCheckedAt ?: 0L;var faresAt=previous?.faresCheckedAt ?: 0L;var mapAt=previous?.mapFetchedAt ?: 0L
        suspend fun <T> retain(label: String,fallback: T,block: suspend ()->T): T = try { block() } catch(e: Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e;notes+="$label refresh unavailable. Retained data, if present, uses its previous check date.";fallback }
        val routes=retain("Official timetable catalogue",previous?.routes.orEmpty()) { catalogue().also { catalogueAt=now } }
        val fare=retain("Official fares",previous?.fares.orEmpty() to previous?.fareDate) { BusSources.fares(http.get(BusSources.GABS_FARES,hosts=hosts),service).also { faresAt=now } }
        val reuse=!forceMap && previous?.stops?.isNotEmpty()==true && now-previous.mapFetchedAt<24*60*60_000L
        val stops=if(reuse) previous!!.stops else retain("Surveyed stops",previous?.stops.orEmpty()) { BusSources.goldenStops(uniqueStops()).map { it.copy(routes=emptyList()) }.also { mapAt=now } }
        val shapes=if(reuse && previous!!.shapes.isNotEmpty()) previous.shapes else retain("Surveyed routes",previous?.shapes.orEmpty()) { BusSources.goldenShapes(survey(5)) }
        BusSources.validate(BusSnapshot(service,now,routes,stops,shapes,fare.first,"Government survey: one representative survey record per stop code; unnumbered stops use name/position, not separate platform/direction positions; capture date not supplied. Current service at a surveyed stop is unverified. Map shapes are simplified by the source for display. Survey route codes are not automatically matched to timetable numbers.",fare.second,mapAt,catalogueAt,faresAt,notes))
    }
    override suspend fun timetable(route: BusRouteDocument,day: String)=withContext(Dispatchers.IO) {
        require(BusSources.officialUrl(route.url,service) && route.pdf)
        val bytes=http.bytes(Request.Builder().url(route.url).build(),4*1024*1024,hosts)
        require(bytes.take(5).toByteArray().toString(Charsets.US_ASCII)=="%PDF-")
        require(!bytes.toString(Charsets.ISO_8859_1).contains("/Encrypt")) { "Encrypted PDF timetables are not supported." }
        val directory=File(app.filesDir,"source-timetables").apply { mkdirs() }
        val file=File(directory,SecureCodec.digest(route.url)+".pdf");file.writeBytes(bytes)
        PDFBoxResourceLoader.init(app)
        val text=PDDocument.load(bytes).use { doc -> require(doc.numberOfPages in 1..24);PDFTextStripper().apply { sortByPosition=true; pageEnd="\u000c" }.getText(doc) }
        GoldenTimetables.parse(text,route,day,System.currentTimeMillis()).copy(sourceFile=file.name)
    }
}

class BusRepository(context: Context) {
    private val app=context.applicationContext
    val store=FeatureStore(context)
    // Each provider has its own parser, network client and cache identity.
    private val providers: Map<TransportService,BusProvider> = listOf(GoldenArrowProvider(context),MyCitiProvider()).associateBy { it.service }
    suspend fun refresh(service: TransportService,forceMap: Boolean=false): BusSnapshot {
        require(store.extras().beta.enabled(service) && service!=TransportService.PRASA)
        var previous=store.snapshot(service)
        if(previous==null && service==TransportService.GOLDEN_ARROW) {
            previous=withContext(Dispatchers.Default) { app.assets.open("golden-arrow-survey-stops.json").bufferedReader().use { GoldenSeed.parse(it.readText()) } }
            if(store.extras().beta.enabled(service)) store.saveSnapshot(previous)
        }
        val snapshot=providers.getValue(service).snapshot(previous,forceMap)
        if(store.extras().beta.enabled(service)) store.saveSnapshot(snapshot)
        return snapshot
    }
    suspend fun timetable(service: TransportService,route: BusRouteDocument,day: String): Pair<BusTimetable,Boolean> {
        require(store.extras().beta.enabled(service))
        try { val result=providers.getValue(service).timetable(route,day);if(!store.extras().beta.enabled(service)) throw kotlinx.coroutines.CancellationException("Operator disabled");store.saveTimetable(result);return result to false }
        catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e
            return store.timetable(service,route.id,day)?.let { it to true } ?: throw e }
    }
}
