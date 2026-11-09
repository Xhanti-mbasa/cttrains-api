package za.co.cttrains.core

import java.time.LocalDate
import java.time.DayOfWeek
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import kotlin.math.*

@Serializable enum class TransportService(val title: String, val color: String) {
    PRASA("Trains", "#198D84"), GOLDEN_ARROW("Golden Arrow", "#B17600"), MYCITI("MyCiTi", "#1478B8")
}
@Serializable data class BetaPreferences(
    val goldenArrow: Boolean = false, val myCiti: Boolean = false,
    val showTrains: Boolean = true, val showGoldenArrow: Boolean = true, val showMyCiti: Boolean = true,
    val autoNearby: Boolean = true, val mapStyle: String = "streets", val busRefreshMinutes: Int = 60, val hostedSharing: Boolean = false,
    val walkLearning: Boolean = false,val nearbyUsers: Boolean = false,
    val journeyFocus: Boolean = false,val healthSteps: Boolean = false,
) {
    fun enabled(service: TransportService) = when (service) { TransportService.PRASA -> true; TransportService.GOLDEN_ARROW -> goldenArrow; TransportService.MYCITI -> myCiti }
    fun visible(service: TransportService) = enabled(service) && when (service) { TransportService.PRASA -> showTrains; TransportService.GOLDEN_ARROW -> showGoldenArrow; TransportService.MYCITI -> showMyCiti }
}
@Serializable data class GeoPoint(val latitude: Double, val longitude: Double) {
    val valid: Boolean get() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}
@Serializable data class BusStopPoint(val id: String, val name: String, val point: GeoPoint, val routes: List<String>, val source: String)
@Serializable data class BusShape(val route: String, val name: String, val points: List<GeoPoint>, val source: String)
@Serializable data class BusRouteDocument(val id: String, val title: String, val url: String, val validFrom: String? = null, val validUntil: String? = null, val pdf: Boolean = false) {
    fun validFor(day: LocalDate) = (validFrom == null || day >= LocalDate.parse(validFrom)) && (validUntil == null || day <= LocalDate.parse(validUntil))
}
@Serializable data class BusFareRow(val id: String, val title: String, val products: Map<String, Long>, val rules: String = "")
@Serializable data class BusSnapshot(val service: TransportService, val fetchedAt: Long, val routes: List<BusRouteDocument>, val stops: List<BusStopPoint>, val shapes: List<BusShape>, val fares: List<BusFareRow>, val mapNote: String, val fareDate: String? = null,
    val mapFetchedAt: Long = fetchedAt, val catalogueCheckedAt: Long = fetchedAt, val faresCheckedAt: Long = fetchedAt, val notes: List<String> = emptyList())
@Serializable data class BusCall(val id: String, val name: String, val time: String, val platform: String? = null, val status: String = "Scheduled")
@Serializable data class BusTrip(val id: String, val route: String, val direction: String, val day: String, val calls: List<BusCall>, val notes: String = "",val validFrom: String? = null)
@Serializable data class BusTimetable(val service: TransportService, val route: BusRouteDocument, val day: String, val fetchedAt: Long, val trips: List<BusTrip>, val sourceFile: String? = null)

object BusSources {
    const val GABS = "https://www.gabs.co.za/Timetable.aspx"
    const val GABS_FARES = "https://www.gabs.co.za/ProductDetails.aspx"
    const val GABS_SURVEY = "https://gis.westerncape.gov.za/server2/rest/services/SpatialDataWarehouse/Transportation/MapServer"
    const val MYCITI_MAP = "https://www.myciti.org.za/en/routes-stops/route-stop-station-map/"
    const val MYCITI_DOWNLOADS = "https://www.myciti.org.za/en/timetables/timetable-downloads/"
    const val MYCITI_FARES = "https://www.myciti.org.za/en/myconnect-fares/pay-as-you-go/"
    fun officialUrl(url: String, service: TransportService): Boolean = runCatching {
        val u = URI(url); val hosts = if (service == TransportService.GOLDEN_ARROW) setOf("gabs.co.za", "www.gabs.co.za") else setOf("myciti.org.za", "www.myciti.org.za")
        u.scheme == "https" && u.host in hosts && u.userInfo == null && u.fragment == null && (u.port == -1 || u.port == 443)
    }.getOrDefault(false)
    /** Extract a JSON assignment without executing any source-page JavaScript. */
    fun array(html: String, name: String): JsonArray {
        val start = Regex("\\bvar\\s+${Regex.escape(name)}\\s*=\\s*\\[").find(html)?.range?.last
            ?: throw ScheduleFormatException("The operator's data format changed. Open its source timetable.")
        var depth = 0; var quoted = false; var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '[' -> depth++; ']' -> { depth--; if (depth == 0) return Json.parseToJsonElement(html.substring(start, i + 1)).jsonArray } }
        }
        throw ScheduleFormatException("The operator returned incomplete data.")
    }
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.list(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
    fun myCitiMap(html: String, now: Long): BusSnapshot {
        val routeData = array(html, "map_routes")
        val stops = array(html, "map_stops").map { el -> val x = el.jsonObject
            BusStopPoint(x.text("id"), x.text("name"), GeoPoint(x["latitude"]!!.jsonPrimitive.double, x["longitude"]!!.jsonPrimitive.double), x.list("routes").map { it.jsonPrimitive.content }, MYCITI_MAP)
        }
        val shapes = array(html, "map_shapes").map { el -> val x = el.jsonObject
            BusShape(x.text("name"), x.text("long_name"), x.list("points").map { p -> GeoPoint(p.jsonArray[0].jsonPrimitive.double, p.jsonArray[1].jsonPrimitive.double) }, MYCITI_MAP)
        }
        val routes = routeData.map { el -> val x = el.jsonObject; val id = x.text("id")
            require(Regex("[A-Za-z0-9_-]{1,40}").matches(id))
            BusRouteDocument(id, "${x.text("name")} · ${x.text("long_name")}", "https://www.myciti.org.za/en/timetables/route-timetables/?route=$id")
        }
        return validate(BusSnapshot(TransportService.MYCITI, now, routes, stops, shapes, emptyList(), "Stops and route shapes published by MyCiTi. Download time is not a guarantee of current operation."))
    }
    fun goldenCatalogue(html: String): List<BusRouteDocument> {
        val doc = Jsoup.parse(html, GABS)
        val result = doc.select(".TimeTableDownloadContainer").mapNotNull { row ->
            val button = row.selectFirst("button[onclick*='window.open']") ?: return@mapNotNull null
            val relative = Regex("window\\.open\\('([^']+\\.pdf)'").find(button.attr("onclick"))?.groupValues?.get(1) ?: return@mapNotNull null
            val url = URI(GABS).resolve(relative).toString()
            if (!officialUrl(url, TransportService.GOLDEN_ARROW)) return@mapNotNull null
            val dates = Regex("_from_(\\d{8})_to_(\\d{8})_(\\d+)\\.pdf$").find(relative) ?: return@mapNotNull null
            fun day(s: String) = LocalDate.parse("${s.take(4)}-${s.substring(4,6)}-${s.takeLast(2)}").toString()
            BusRouteDocument(dates.groupValues[3] + ":" + relative.substringAfterLast('/'), row.children().first()?.text().orEmpty(), url, day(dates.groupValues[1]), dates.groupValues[2].takeUnless { it == "99999999" }?.let(::day), true)
        }.distinctBy { it.url }
        if (result.isEmpty()) throw ScheduleFormatException("No recognizable Golden Arrow timetable links were returned.")
        return result
    }
    fun goldenStops(pages: List<String>): List<BusStopPoint> {
        val items = pages.flatMap { raw ->
            val root = Json.parseToJsonElement(raw).jsonObject
            require(root["error"] == null && root["spatialReference"]?.jsonObject?.get("wkid")?.jsonPrimitive?.int == 4326)
            root.list("features").map { e -> val a = e.jsonObject["attributes"]!!.jsonObject; val g = e.jsonObject["geometry"]!!.jsonObject
                val point = GeoPoint(g["y"]!!.jsonPrimitive.double, g["x"]!!.jsonPrimitive.double)
                val number = a.text("BUSSTOPNO")
                BusStopPoint("$number:${point.latitude}:${point.longitude}", a.text("BUSSTOPDES"), point, listOf(a.text("ROUTECODE")), "$GABS_SURVEY/6")
            }
        }
        return items.groupBy { it.id }.map { (_, values) -> values.first().copy(routes = values.flatMap { it.routes }.distinct()) }
    }
    fun goldenShapes(pages: List<String>): List<BusShape> = pages.flatMap { raw ->
        val root = Json.parseToJsonElement(raw).jsonObject; require(root["error"] == null && root["spatialReference"]?.jsonObject?.get("wkid")?.jsonPrimitive?.int == 4326)
        root.list("features").flatMap { e -> val a = e.jsonObject["attributes"]!!.jsonObject
            e.jsonObject["geometry"]!!.jsonObject.list("paths").map { p -> BusShape(a.text("Route_No"), a.text("Route_name"), p.jsonArray.map { c -> GeoPoint(c.jsonArray[1].jsonPrimitive.double, c.jsonArray[0].jsonPrimitive.double) }, "$GABS_SURVEY/5") }
        }
    }
    fun myCitiTimetable(html: String, route: BusRouteDocument, day: String, now: Long): BusTimetable {
        val groups = array(html, "timetable")
        val trips = groups.flatMap { el -> val group = el.jsonObject
            require(group.text("route") == route.id && group.text("day").equals(day, true)) { "The operator returned a different route or day." }
            group.list("trips").map { t -> val x = t.jsonObject
                val calls = x.list("stops").map { c -> val s = c.jsonObject; BusCall(s.text("id"), s.text("name"), s.text("static_time").ifBlank { s.text("time") }, status = s.text("delay").ifBlank { "Scheduled" }) }
                BusTrip(x.text("trip_id"), route.id, group.text("direction"), day, calls, group.text("service_advisory"))
            }
        }
        return validate(BusTimetable(TransportService.MYCITI, route, day, now, trips))
    }
    fun fares(html: String, service: TransportService): Pair<List<BusFareRow>, String?> {
        val document = Jsoup.parse(html)
        val rows = mutableListOf<BusFareRow>(); var from = ""; var columns = emptyList<String>()
        var fareKind=""
        document.select("h1,h2,h3,h4,h5,h6,table").forEach { table ->
            if(table.tagName()!="table") { if(table.text().contains("Mover Fares",true)) fareKind="Mover" else if(table.text().contains("Standard Fares",true)) fareKind="Standard";return@forEach }
            columns = emptyList(); from = ""
            val heading=if(service==TransportService.MYCITI) fareKind else ""
            if(service==TransportService.MYCITI && heading.isBlank()) return@forEach
            table.select("tr").forEach { row -> val cells = row.select("th,td").map { it.text().trim() }
                if (cells.isEmpty()) return@forEach
                if (row.select("th").isNotEmpty() || cells.any { it.contains("Peak", true) || it.contains("Five Ride", true) || it.contains("5 Ride", true) || it.contains("Monthly", true) }) { columns = cells; return@forEach }
                if (cells.size == 1) { from = cells[0]; return@forEach }
                val prices = cells.mapIndexedNotNull { i, cell -> val cleaned = cell.replace("R", "").replace(" ", "").replace(",", "")
                    val column=columns.getOrNull(i)?.takeIf { it.isNotBlank() }
                    if (i > 0 && column!=null && Regex("\\d+\\.\\d{2}").matches(cleaned)) listOf(heading,column).filter(String::isNotBlank).joinToString(" · ") to (cleaned.toBigDecimal() * 100.toBigDecimal()).longValueExact() else null
                }.toMap()
                if (prices.isNotEmpty()) { val title = listOf(heading,from, cells[0]).filter { it.isNotBlank() }.joinToString(" → "); rows += BusFareRow("${service.name}:$title", title, prices, cells.lastOrNull().takeIf { it?.contains("transfer",true)==true || it in listOf("Zero","One","Two") }.orEmpty()) }
            }
        }
                if (rows.isEmpty()) throw ScheduleFormatException("The fare table could not be read. Prices remain unknown.")
        val date = Regex("Prices are effective[^.]+", RegexOption.IGNORE_CASE).find(document.text())?.value
        return rows to date
    }
    fun validate(snapshot: BusSnapshot): BusSnapshot {
        require(snapshot.service != TransportService.PRASA && snapshot.routes.size <= 5000 && (snapshot.routes.isNotEmpty() || snapshot.stops.isNotEmpty()) && snapshot.stops.size <= 20000 && snapshot.shapes.size <= 5000)
        require(snapshot.routes.all { it.title.isNotBlank() && it.title.length <= 240 && officialUrl(it.url,snapshot.service) })
        require(snapshot.stops.all { it.id.isNotBlank() && it.name.length in 1..180 && it.point.valid && it.routes.size <= 300 })
        require(snapshot.shapes.sumOf { it.points.size } <= 300000 && snapshot.shapes.all { it.points.size >= 2 && it.points.all(GeoPoint::valid) })
        return snapshot
    }
    fun validate(timetable: BusTimetable): BusTimetable {
        require(timetable.trips.size <= 3000)
        require(timetable.trips.all { it.calls.size in 2..200 && it.calls.all { c -> c.name.length in 1..180 && Regex("(?:[01]\\d|2[0-3]):[0-5]\\d").matches(c.time) } })
        return timetable
    }
}

object NearbySuggestions {
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val p = Math.toRadians(b.latitude-a.latitude); val l = Math.toRadians(b.longitude-a.longitude)
        val h = sin(p/2).pow(2) + cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(l/2).pow(2)
        return 6371000 * 2 * atan2(sqrt(h.coerceIn(0.0,1.0)), sqrt((1-h).coerceIn(0.0,1.0)))
    }
    fun station(position: GeoPoint, accuracy: Float, stations: List<StationPosition>): StationPosition? {
        if (!position.valid || !accuracy.isFinite() || accuracy > 500 || accuracy < 0) return null
        return stations.filter { it.mapped }.minByOrNull { distance(position,GeoPoint(it.latitude!!,it.longitude!!)) }
            ?.takeIf { distance(position,GeoPoint(it.latitude!!,it.longitude!!)) <= 1500 }
    }
    fun destinations(origin: String, routes: List<SavedRoute>, favorites: List<String>): List<String> =
        (routes.asReversed().flatMap { listOf(it.to,it.from) } + favorites).filter { stationKey(it) != stationKey(origin) && it.isNotBlank() }.distinctBy(::stationKey).take(4)
}
