package za.co.cttrains.core

import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SmartCardsTest {
    private val now = LocalDateTime.parse("2026-11-11T18:00:00").atZone(CapeTownZone).toInstant().toEpochMilli()
    private val stations = listOf(StationPosition("A", -33.90, 18.40), StationPosition("B", -33.95, 18.45), StationPosition("C", -33.91, 18.41))
    private val available = stations.map { it.name }
    private val current = GeoPoint(-33.90, 18.40)
    private fun journey(from: String, train: String, departure: String, arrival: String) =
        Journey(listOf(Leg("line", train, listOf(Stop(from, departure), Stop("B", arrival)))))
    private fun card(from: String = "A", train: String = "1", departure: String = "18:20", arrival: String = "19:00", access: Int = 5) =
        SmartJourney(SearchQuery(from, "B", "2026-11-11", "18:00"), journey(from, train, departure, arrival),
            "Home", "cttrains-web", now, false, access)
    private fun plan(destination: String, outcome: RideOutcome?, date: String = "2026-11-04") =
        ScheduledJourney("$destination:$date", "profile", SearchQuery("A", destination, date, "18:00"),
            Journey(listOf(Leg("line", "1", listOf(Stop("A", "18:00"), Stop(destination, "19:00"))))),
            "cttrains-web", now - 1, now - 1, rideOutcome = outcome)

    @Test fun homeTakesPriorityOverHistory() {
        val home = MarkedSpot("home", "Home", null, GeoPoint(-33.95, 18.45), "Home", createdAt = 1, preferredStation = "B")
        assertEquals("B", SmartCards.destination(current, stations, available, listOf(home),
            listOf(plan("C", RideOutcome.CONFIRMED)), emptyList(), emptyList(), now)?.station?.name)
    }

    @Test fun beingAtHomeSuggestsWork() {
        val home = MarkedSpot("home", "Home", null, current, "Home", createdAt = 1, preferredStation = "A")
        val work = MarkedSpot("work", "Work", null, GeoPoint(-33.95, 18.45), "Work", createdAt = 1, preferredStation = "B")
        assertEquals("Work", SmartCards.destination(current, stations, available, listOf(home, work),
            emptyList(), emptyList(), emptyList(), now)?.label)
    }

    @Test fun unavailableExplicitHomeStationDoesNotSilentlyChange() {
        val home = MarkedSpot("home", "Home", null, GeoPoint(-33.95, 18.45), "Home", createdAt = 1, preferredStation = "Unknown")
        assertNull(SmartCards.destination(current, stations, available, listOf(home),
            listOf(plan("C", RideOutcome.CONFIRMED)), emptyList(), emptyList(), now))
    }

    @Test fun frequencyUsesConfirmedPastRides() {
        val plans = listOf(plan("C", RideOutcome.MISSED), plan("C", RideOutcome.LIKELY_ONBOARD),
            plan("C", RideOutcome.CONFIRMED, "2026-11-18"), plan("B", RideOutcome.CONFIRMED))
        assertEquals("B", SmartCards.destination(current, stations, available, emptyList(), plans,
            emptyList(), emptyList(), now)?.station?.name)
    }

    @Test fun expiredAndUncatchableDeparturesAreRemoved() {
        val options = listOf(card(departure = "17:59"), card(departure = "18:05"), card(departure = "18:20"))
        assertEquals(listOf("18:20"), SmartCards.rank(options, now).map { it.journey.departure })
        assertTrue(SmartCards.rank(options, now + 30 * 60_000).isEmpty())
    }

    @Test fun sameTrainFromTwoStationsCountsOnceAndResultsAreCapped() {
        val options = listOf(card(access = 10), card(from = "C", access = 2),
            card(train = "2", arrival = "19:10"), card(train = "3", arrival = "19:20"), card(train = "4", arrival = "19:30"))
        val ranked = SmartCards.rank(options, now)
        assertEquals(3, ranked.size)
        assertEquals("C", ranked.first().query.from)
        assertEquals(listOf("1", "2", "3"), ranked.map { it.journey.legs.first().train })
    }

    @Test fun nextTripIgnoresPastAndMissedPlans() {
        val past = plan("B", null)
        val missed = plan("B", RideOutcome.MISSED, "2026-11-12")
        val future = plan("B", null, "2026-11-13").copy(remindersStopped = true)
        assertEquals(future, SmartCards.nextTrip(listOf(past, missed, future), now))
    }

    @Test fun olderSettingsReceiveCompatibleDefaults() {
        val preferences = Json.decodeFromString<BetaPreferences>("{}")
        assertTrue(preferences.smartCards)
        assertTrue(preferences.homeMap)
        assertFalse(preferences.appWidgets)
    }
}
