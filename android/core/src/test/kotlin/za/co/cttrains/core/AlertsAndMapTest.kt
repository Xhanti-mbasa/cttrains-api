package za.co.cttrains.core

import java.io.File
import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AlertsAndMapTest {
    private val now = LocalDate.parse("2026-10-01").atTime(20, 10).atZone(CapeTownZone).toInstant().toEpochMilli()
    private fun fixture(name: String) = javaClass.getResourceAsStream("/$name")!!.bufferedReader().use { it.readText() }
    private fun alerts() = ServiceAlerts.parse(fixture("status2.html"), now)
    private val query = SearchQuery("A", "C", "2026-10-01", "20:00")
    private fun journey(arrival: String? = null, boarding: String? = null) = Journey(listOf(
        Leg("Central", "9006", listOf(Stop("A", "20:00"), Stop("B", "20:20", arrival))),
        Leg("Northern", "3204", listOf(Stop("B", "20:25", boarding), Stop("C", "20:40")))))
    @Test fun `real alert markup preserves groups source date and report times`() {
        val snapshot = alerts(); assertEquals(9, snapshot.alerts.size); assertEquals("2026-10-01", snapshot.sourceDate)
        assertEquals(setOf("Advisories", "Central Line", "Northern Line"), snapshot.alerts.map { it.group }.toSet())
        assertEquals("20:07", snapshot.alerts.first().time); assertTrue(snapshot.alerts.first().reportedAt < snapshot.fetchedAt)
    }
    @Test fun `alert identities are stable across refresh and duplicate rows collapse`() {
        assertEquals(alerts().alerts.map { it.id }, ServiceAlerts.parse(fixture("status2.html"), now + 60_000).alerts.map { it.id })
        val text = fixture("status2.html"); val row = Regex("<div class=\"msg-row\">.*?</div>").find(text)!!.value
        assertEquals(9, ServiceAlerts.parse(text.replaceFirst("</section>", "$row</section>"), now).alerts.size)
    }
    @Test fun `advertisements and scripts do not become alerts`() {
        assertFalse(alerts().alerts.any { it.message.contains("advertisements") })
        assertThrows(ScheduleFormatException::class.java) { ServiceAlerts.parse("<html><p>ad</p></html>", now) }
    }
    @Test fun `malformed times or missing message structure are explicit errors`() {
        assertThrows(ScheduleFormatException::class.java) { ServiceAlerts.parse(fixture("status2.html").replace("20:07", "25:07"), now) }
        assertThrows(ScheduleFormatException::class.java) { ServiceAlerts.parse(fixture("status2.html").replace("msg-text", "new-markup"), now) }
    }
    @Test fun `empty service notices require an explicit source empty state`() {
        val html = "<span>Thursday, October 1 2026</span><p>No service updates available.</p>"
        assertTrue(ServiceAlerts.parse(html, now).alerts.isEmpty())
        assertThrows(ScheduleFormatException::class.java) { ServiceAlerts.parse(html.replace("No service updates available.", "Service portal"), now) }
    }
    @Test fun `cached alerts expire and future fetch dates are rejected`() {
        assertTrue(ServiceAlerts.usable(alerts(), now + 1000)); assertFalse(ServiceAlerts.usable(alerts(), now - 1))
        assertFalse(ServiceAlerts.usable(alerts(), now + ServiceAlerts.CACHE_AGE + 1))
    }
    @Test fun `platform notice is tied to its boarding train and station`() {
        val notices = ServiceAlerts.platformNotices(alerts()); assertEquals(1, notices.size)
        assertEquals("2521", notices.single().train); assertEquals("Bellville", notices.single().station); assertEquals("4", notices.single().platform)
        val q = SearchQuery("Bellville", "Wellington", "2026-10-01", "20:00"); val stop = Stop("Bellville", "20:30")
        assertNotNull(ServiceAlerts.boardingNotice(alerts(), q, stop, "T2521", now))
        assertNull(ServiceAlerts.boardingNotice(alerts(), q, stop, "T3517", now))
        assertNull(ServiceAlerts.boardingNotice(alerts(), q, stop.copy(name = "Kraaifontein"), "T2521", now))
    }
    @Test fun `stale and different-date platform notices never become assignments`() {
        val q = SearchQuery("Bellville", "Wellington", "2026-10-01", "20:00"); val stop = Stop("Bellville", "20:30")
        assertNull(ServiceAlerts.boardingNotice(alerts(), q.copy(date = "2026-10-02"), stop, "2521", now))
        assertNull(ServiceAlerts.boardingNotice(alerts(), q, stop, "2521", now + 11 * 60_000))
        assertNull(ServiceAlerts.boardingNotice(alerts(), q, stop.copy(time = "23:00"), "2521", now))
    }
    @Test fun `transfer instructions identify trains times and actual platform changes`() {
        val p = TransferGuide.points(query, journey("2", "4"), emptyList(), null, now).single()
        assertEquals(1, p.number); assertEquals("B", p.station); assertEquals("9006", p.arrivingTrain); assertEquals("3204", p.nextTrain)
        assertEquals(5, p.minutes); assertEquals(true, p.differentPlatform); assertEquals("Platform 4", p.boardingLabel)
    }
    @Test fun `same platform still requires changing trains and unknown is not guessed`() {
        assertEquals(false, TransferGuide.points(query, journey("2", "2"), emptyList(), null, now).single().differentPlatform)
        val p = TransferGuide.points(query, journey(), emptyList(), null, now).single(); assertNull(p.differentPlatform); assertEquals("Platform not published", p.boardingLabel)
    }
    @Test fun `old stored stops decode without a platform`() { assertNull(Json.decodeFromString<Stop>("{\"name\":\"A\",\"time\":\"07:00\"}").platform) }
    @Test fun `direct parser reads per-stop platform fields without leaking to other stops`() {
        val html = fixture("transfer.html").replaceFirst("class=\"flex items-center\"", "class=\"flex items-center\" data-platform=\"2\"")
        val stops = CtTrainsParser.journeys(html).first().legs.flatMap { it.stops }
        assertEquals("2", stops.first().platform); assertTrue(stops.drop(1).all { it.platform == null })
    }
    private fun network(edges: List<List<Int>> = listOf(listOf(0, 1), listOf(1, 2))) = RailNetwork("snapshot", listOf(listOf(18.0, -34.0), listOf(18.005, -34.003), listOf(18.01, -34.0)), edges, mapOf("a" to 0, "c" to 2))
    private fun direct() = Journey(listOf(Leg("Line", "1", listOf(Stop("A", "07:00"), Stop("C", "07:10")))))
    private val stations = listOf(StationPosition("A", -34.0, 18.0), StationPosition("C", -34.0, 18.01))
    @Test fun `rail trace follows a real bend instead of a straight stop connection`() {
        val trace = RailRouter(network()).trace(direct(), stations); assertEquals(1, trace.railPairs)
        assertEquals(3, trace.legs.single().single().points.size); assertTrue(trace.legs.single().single().followsRail)
    }
    @Test fun `reverse rail path preserves bend order`() {
        val router = RailRouter(network()); val forward = router.trace(direct(), stations).legs.single().single().points
        val reverse = router.trace(Journey(listOf(Leg("Line", "1", direct().legs.single().stops.reversed()))), stations)
        assertEquals(forward.reversed(), reverse.legs.single().single().points)
    }
    @Test fun `disconnected geometry keeps an explicitly approximate link`() {
        val trace = RailRouter(network(emptyList())).trace(direct(), stations)
        assertEquals(0, trace.railPairs); assertFalse(trace.legs.single().single().followsRail)
    }
    @Test fun `unknown positions do not create a bridge or synthetic station`() {
        val j = Journey(listOf(Leg("Line", "1", listOf(Stop("A", "07:00"), Stop("Unknown", "07:05"), Stop("C", "07:10")))))
        assertTrue(RailRouter(network()).trace(j, stations).legs.single().isEmpty())
    }
    @Test fun `invalid graph coordinates and edges are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { RailRouter(network().copy(edges = listOf(listOf(0, 99)))) }
        assertThrows(IllegalArgumentException::class.java) { RailRouter(network().copy(nodes = listOf(listOf(Double.NaN, 0.0), listOf(0.0, 0.0)))) }
    }
    @Test fun `nearby parallel track candidates avoid a false long loop`() {
        val n = RailNetwork("snapshot", listOf(listOf(18.0,-34.0), listOf(18.0,-34.00005), listOf(18.005,-34.003), listOf(18.01,-34.00005), listOf(18.01,-34.0)),
            listOf(listOf(1,2),listOf(2,3)), mapOf("a" to 0,"c" to 4), mapOf("a" to listOf(0,1), "c" to listOf(4,3)))
        val section = RailRouter(n).trace(direct(), stations).legs.single().single()
        assertTrue(section.followsRail); assertEquals(3, section.points.size)
    }
    @Test fun `long detours are not presented as mapped train paths`() {
        val n = network().copy(nodes = listOf(listOf(18.0,-34.0),listOf(19.0,-34.0),listOf(18.01,-34.0)))
        assertFalse(RailRouter(n).trace(direct(), stations).legs.single().single().followsRail)
    }
    @Test fun `bundled rail dataset covers all stations and the captured transfer journey`() {
        val assets = File(System.getProperty("cttrains.assets")); val net = Json.decodeFromString<RailNetwork>(File(assets, "rail-network.json").readText())
        val positions = Json.decodeFromString<List<StationPosition>>(File(assets, "station-positions.json").readText())
        assertEquals(102, net.anchors.size)
        val trace = RailRouter(net).trace(CtTrainsParser.journeys(fixture("transfer.html")).first(), positions)
        assertTrue("Expected most captured stop pairs to follow mapped railways: ${trace.railPairs}/${trace.totalPairs}", trace.railPairs >= trace.totalPairs * .7)
        assertTrue(trace.legs.flatten().filter { it.followsRail }.any { it.points.size > 2 })
    }
}
