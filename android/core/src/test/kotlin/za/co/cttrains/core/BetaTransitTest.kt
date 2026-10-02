package za.co.cttrains.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class BetaTransitTest {
    private val now=LocalDate.parse("2026-10-02").atTime(8,0).atZone(CapeTownZone).toInstant().toEpochMilli()
    private fun fixture(name: String)=javaClass.getResourceAsStream("/$name")!!.bufferedReader().use { it.readText() }
    private val pdf=BusRouteDocument("004401","Airport Industria","https://www.gabs.co.za/Pdf/Apdf/sample.pdf","2026-09-28",pdf=true)
    @Test fun `operator beta and map filters are independent`() {
        val b=BetaPreferences(goldenArrow=true,myCiti=false,showGoldenArrow=false,showMyCiti=true)
        assertTrue(b.enabled(TransportService.GOLDEN_ARROW));assertFalse(b.enabled(TransportService.MYCITI));assertFalse(b.visible(TransportService.GOLDEN_ARROW));assertFalse(b.visible(TransportService.MYCITI));assertTrue(b.visible(TransportService.PRASA))
        assertTrue(b.copy(myCiti=true).visible(TransportService.MYCITI))
    }
    @Test fun `published map positions use latitude longitude and exact stop IDs`() {
        val s=BusSources.myCitiMap(fixture("myciti-map.html"),now)
        assertEquals(TransportService.MYCITI,s.service);assertEquals(3,s.stops.size);assertTrue(s.stops.first().point.latitude<0);assertTrue(s.stops.first().point.longitude>18);assertTrue(s.shapes.first().points.first().latitude<0)
        assertTrue(s.routes.all { BusSources.officialUrl(it.url,s.service) })
    }
    @Test fun `source script is parsed as data without executing it`() {
        assertEquals("bracket ] in name",BusSources.array("var data=[{\"name\":\"bracket ] in name\"}];alert('ignored');","data").first().toString().let { kotlinx.serialization.json.Json.parseToJsonElement(it).let { e -> (e as kotlinx.serialization.json.JsonObject)["name"].toString().trim('"') } })
        assertThrows(ScheduleFormatException::class.java) { BusSources.array("var data=[{","data") }
    }
    @Test fun `MyCiTi trip times preserve scheduled values and reject different days`() {
        val r=BusRouteDocument("101","101","https://www.myciti.org.za/en/timetables/route-timetables/?route=101")
        val html=fixture("myciti-101.html");val t=BusSources.myCitiTimetable(html,r,"Friday",now)
        assertTrue(t.trips.isNotEmpty());assertEquals("05:56",t.trips.first().calls.first().time)
        assertThrows(IllegalArgumentException::class.java) { BusSources.myCitiTimetable(html,r,"Monday",now) }
        assertThrows(IllegalArgumentException::class.java) { BusSources.myCitiTimetable(html,r.copy(id="102"),"Friday",now) }
    }
    @Test fun `Golden Arrow catalogue preserves source date range`() {
        val r=BusSources.goldenCatalogue(fixture("gabs-catalogue.html"))
        assertTrue(r.isNotEmpty());assertTrue(r.all { it.pdf && it.validFrom!=null });assertTrue(r.all { BusSources.officialUrl(it.url,TransportService.GOLDEN_ARROW) })
        assertFalse(pdf.validFor(LocalDate.parse("2026-09-27")));assertTrue(pdf.validFor(LocalDate.parse("2026-10-02")))
    }
    @Test fun `Golden Arrow columns honor weekday suffixes and untimed via points`() {
        val text=fixture("gabs-timetable.txt");val monday=GoldenTimetables.parse(text,pdf,"Monday",now);val friday=GoldenTimetables.parse(text,pdf,"Friday",now)
        assertTrue(monday.trips.any { it.calls.first().time=="05:30" && it.calls.last().time=="06:30" })
        assertTrue(friday.trips.any { it.calls.first().time=="05:30" && it.calls.last().time=="06:20" })
        assertFalse(friday.trips.any { it.calls.first().time=="05:30" && it.calls.last().time=="06:30" })
        assertTrue(monday.trips.none { t->t.calls.any { it.name=="ROBERT SOBUKWE RD" } })
        assertTrue(monday.trips.all { it.validFrom!=null })
    }
    @Test fun `column clocks determine travel order when PDF rows are out of order`() {
        val t=GoldenTimetables.parse(fixture("gabs-timetable.txt"),pdf,"Monday",now)
        val trip=t.trips.first { it.calls.any { c->c.name=="PHILIPPI EAST SAP" } }
        assertEquals("PHILIPPI EAST SAP",trip.calls.first().name);assertEquals("06:10",trip.calls.first().time);assertEquals("08:40",trip.calls.last().time)
    }
    @Test fun `unknown suffixes and incomplete weekdays never create assumed trips`() {
        val text=fixture("gabs-timetable.txt").replace("a   - Mondays,Tuesdays,Wednesdays,Thursdays","a   - Mondays,Special school days")
        val t=GoldenTimetables.parse(text,pdf,"Monday",now)
        assertTrue(t.trips.none { it.calls.first().time=="05:30" })
        assertTrue(GoldenTimetables.parse(fixture("gabs-timetable.txt"),pdf,"Sunday",now).trips.isEmpty())
    }
    @Test fun `survey route codes never become timetable identifiers or fuzzy stop matches`() {
        val s=BusSources.goldenStops(listOf(fixture("gabs-survey.json")))
        assertEquals(3,s.size);assertEquals("Golden Acre",s.first().name);assertTrue(s.first().routes.contains("AAA6"));assertFalse(s.first().routes.contains("004401"))
        assertEquals(s,BusSources.goldenStops(listOf(fixture("gabs-survey.json"),fixture("gabs-survey.json"))))
    }
    @Test fun `wrong spatial reference and operator hosts are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { BusSources.goldenStops(listOf(fixture("gabs-survey.json").replace("4326","3857"))) }
        assertFalse(BusSources.officialUrl("https://www.gabs.co.za.evil.test/f.pdf",TransportService.GOLDEN_ARROW));assertFalse(BusSources.officialUrl("https://user:pass@www.gabs.co.za/f.pdf",TransportService.GOLDEN_ARROW))
    }
    @Test fun `fare products keep their published units and stable identities`() {
        val (g,date)=BusSources.fares(fixture("gabs-fares.html"),TransportService.GOLDEN_ARROW)
        assertEquals(11450L,g.first().products["Five Ride"]);assertEquals(21200L,g.first().products["Weekly"]);assertEquals(93300L,g.first().products["Monthly"]);assertFalse(g.first().products.keys.any { it.contains("Single") });assertTrue(date!!.contains("2025"))
        val (c,_)=BusSources.fares(fixture("myciti-fares.html"),TransportService.MYCITI)
        assertEquals(1950L,c.first().products["Mover · Peak Fare"]);assertEquals(1500L,c.first().products["Mover · Off-Peak (saver) Fare"])
        assertThrows(ScheduleFormatException::class.java) { BusSources.fares("<table><tr><td>0-5km</td><td>19.50</td></tr></table>",TransportService.MYCITI) }
    }
    @Test fun `nearby origin is bounded by accuracy and distance`() {
        val stations=listOf(StationPosition("Bellville",-33.904,18.629),StationPosition("Salt River",-33.929,18.464))
        assertEquals("Bellville",NearbySuggestions.station(GeoPoint(-33.904,18.629),30f,stations)?.name)
        assertNull(NearbySuggestions.station(GeoPoint(-33.904,18.629),900f,stations));assertNull(NearbySuggestions.station(GeoPoint(-34.2,18.8),30f,stations))
        assertEquals(listOf("Salt River","Unibell"),NearbySuggestions.destinations("Bellville",listOf(SavedRoute("Unibell","Salt River")),listOf("Salt River","Bellville")))
    }
}
