package za.co.cttrains.app.lab

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import za.co.cttrains.app.PlannerOption
import za.co.cttrains.app.ui.lab.UberHandoffCard
import za.co.cttrains.core.*

/** Offline: Uber is never launched; a fake handler records the link only after explicit confirmation. */
class UberHandoffUiTest {
    @get:Rule val app = createComposeRule()

    private val stations = listOf(StationPosition("Woodstock", -33.9269, 18.4489), StationPosition("Unibell", -33.9381, 18.6262))
    private val departure = JourneyComparison.requestedAt("2026-11-12", "14:03")
    private val option = PlannerOption("uber-fixture", TransportService.PRASA, null,
        Journey(listOf(Leg("Northern line", "test-a", listOf(Stop("Woodstock", "14:03"), Stop("Unibell", "14:58"))))), null, emptyList(),
        TripEstimate(departure, null, null, null, null, null), emptyList(), departure, false)
    private val home = WalkEndpoint("spot:home", "Home", GeoPoint(-33.9300, 18.4600))
    private val work = WalkEndpoint("spot:work", "Work", GeoPoint(-33.9420, 18.6400))

    @Test fun confirmsBeforeHandingFirstAndLastMileToUber() {
        val opened = mutableListOf<String>()
        app.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened += uri } }) {
                UberHandoffCard(option, home, work, stations, departure - 60 * 60_000L)
            }
        }
        app.onNodeWithTag("uber-first-mile").assertTextContains("Uber to Woodstock station").performClick()
        app.onNodeWithText("Open Uber?").assertIsDisplayed()
        app.onNodeWithText("Cancel").performClick()
        assertTrue(opened.isEmpty())
        app.onNodeWithTag("uber-last-mile").assertTextContains("Uber from Unibell station to Work").performClick()
        app.onNodeWithTag("uber-open").performClick()
        val link = opened.single()
        assertTrue(link.startsWith("https://m.uber.com/ul/?action=setPickup"))
        assertTrue(link.contains("pickup%5Bnickname%5D=Unibell%20station"))
        assertTrue(link.contains("dropoff%5Bnickname%5D=Work"))
    }

    @Test fun hidesFirstMileWhenTripStartsAtStationOrHasDeparted() {
        app.setContent { UberHandoffCard(option, WalkEndpoint("station:woodstock", "Woodstock", GeoPoint(-33.9269, 18.4489)), work, stations, departure + 60_000L) }
        app.onNodeWithTag("uber-first-mile").assertDoesNotExist()
        app.onNodeWithTag("uber-last-mile").assertExists()
    }
}
