package za.co.cttrains.app.lab

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import za.co.cttrains.app.*
import za.co.cttrains.core.*
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class PenelopePlanTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()
    private lateinit var train: TrainViewModel
    private lateinit var features: FeatureViewModel
    @Before fun setup() {
        train = ViewModelProvider(app.activity)[TrainViewModel::class.java]
        features = ViewModelProvider(app.activity)[FeatureViewModel::class.java]
        app.waitUntil(20_000) { features.state.value.ready && train.state.value.stations.any { it.name == "Salt River" && it.mapped } && train.state.value.stations.any { it.name == "Unibell" && it.mapped } }
        app.runOnUiThread {
            train.setMapMode("stations")
            features.place(null, true); features.place(null, false)
            train.edit { it.copy(from = "Salt River", to = "Unibell") }
        }
        app.onNodeWithTag("lab-tab-plan").performClick()
    }
    @Test fun switchingTimeModesKeepsRouteDateAndOperators() {
        var date = LocalDate.now(CapeTownZone).plusDays(1)
        while (date.dayOfWeek.value == 7) date = date.plusDays(1)
        app.runOnUiThread { train.chooseDate(date.toString()); train.chooseTime("14:00") }
        app.onNodeWithTag("lab-time-all").performScrollTo().performClick().assertIsSelected()
        app.onNodeWithTag("lab-plan-from").assertTextContains("Salt River")
        app.onNodeWithTag("lab-plan-to").assertTextContains("Unibell")
        app.onNodeWithTag("lab-mode-PRASA").performScrollTo().assertIsSelected()
        assertEquals(date.toString(), train.state.value.date)
        app.onNodeWithTag("lab-time-now").performScrollTo().performClick().assertIsSelected()
        assertEquals(date.toString(), train.state.value.date)
        app.onNodeWithTag("lab-time-depart").performScrollTo().performClick()
        app.onNodeWithText("Choose time").performClick()
        app.onNodeWithTag("lab-time-depart").assertIsSelected()
        app.onNodeWithTag("lab-plan-from").assertTextContains("Salt River")
    }
    @Test fun cachedAllDayIncludesEarlierAndLaterTransfersAndPagesWithoutAnotherSearch() {
        val date = LocalDate.now(CapeTownZone).let { today -> if(today.dayOfWeek.value == 7) today.plusDays(1) else today }
        val query = SearchQuery("Salt River", "Unibell", date.toString(), "03:00")
        val journeys = (0 until 40).map { i ->
            val time = LocalTime.of(3,0).plusMinutes(i*25L)
            Journey(listOf(
                Leg("Test", "a-$i", listOf(Stop("Salt River", time.toString()), Stop("Mutual", time.plusMinutes(10).toString()))),
                Leg("Test", "b-$i", listOf(Stop("Mutual", time.plusMinutes(15).toString()), Stop("Unibell", time.plusMinutes(30).toString())))))
        }.reversed()
        runBlocking { train.repository.store.cache(CachedSearch(query, journeys, System.currentTimeMillis(), null, train.state.value.preferences.sourceId)) }
        app.runOnUiThread { train.chooseDate(date.toString()); train.chooseTime("17:00") }
        app.onNodeWithTag("lab-time-all").performScrollTo().performClick()
        app.onNodeWithTag("lab-plan-search").performScrollTo().performClick()
        val planner = ViewModelProvider(app.activity)[JourneyPlannerModel::class.java]
        app.waitUntil(15_000) { !planner.state.value.loading && planner.state.value.sections.isNotEmpty() }
        val options = planner.state.value.sections.getValue(TransportService.PRASA).options
        assertEquals(40, options.size)
        assertEquals("03:00", options.first().rail!!.departure)
        assertEquals("19:15", options.last().rail!!.departure)
        assertTrue(options.all { it.cached && it.rail!!.transfers == 1 })
        app.onNodeWithTag("lab-plan").performScrollToNode(hasTestTag("lab-more-departures"))
        app.onNodeWithTag("lab-more-departures").performClick()
        app.onNodeWithTag("lab-plan").performScrollToNode(hasText("19:15"))
        app.onNodeWithText("19:15").assertIsDisplayed()
        assertEquals(40, planner.state.value.sections.getValue(TransportService.PRASA).options.size)
        if (date == LocalDate.now(CapeTownZone) && System.currentTimeMillis() > options.first().estimate.departureAt) {
            app.onNodeWithTag("lab-plan").performScrollToIndex(0)
            app.onNodeWithTag("lab-plan").performScrollToNode(hasText("Departed · scheduled time has passed"))
            app.onAllNodesWithText("Departed · scheduled time has passed")[0].assertIsDisplayed()
        }
    }
}
