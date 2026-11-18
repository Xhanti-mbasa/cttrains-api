package za.co.cttrains.app.lab

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import za.co.cttrains.app.*
import za.co.cttrains.core.*

/** Offline: every answer the board needs is saved first, including a train past the hour so paging stops. */
@RunWith(AndroidJUnit4::class)
class StationBoardUiTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()
    private lateinit var train: TrainViewModel

    @Before fun setup() {
        train = ViewModelProvider(app.activity)[TrainViewModel::class.java]
        app.waitUntil(20_000) { "Unibell" in train.state.value.catalog.stations && "Chris Hani" in train.state.value.catalog.stations }
    }

    private fun leg(trainNo: String, vararg stops: Pair<String, LocalTime>) =
        Journey(listOf(Leg("Central line", trainNo, stops.map { Stop(it.first, it.second.toString().take(5)) })))

    @Test fun counterListsNextHourAndFindsTrainByNumber() {
        val clock = LocalDateTime.now(CapeTownZone)
        val now = clock.toLocalTime().withSecond(0).withNano(0)
        Assume.assumeTrue("Trains are listed Monday to Saturday", clock.dayOfWeek.value != 7 && clock.toLocalDate().toString() !in train.state.value.catalog.holidays)
        Assume.assumeTrue("Fixture times stay inside one service day", now.isAfter(LocalTime.of(3, 30)) && now.isBefore(LocalTime.of(22, 0)))
        val date = clock.toLocalDate().toString()
        val slot = RailDay.slotAtOrBefore(now.toString())
        val source = train.state.value.preferences.sourceId
        val pages = mapOf(
            "Cape Town" to listOf(leg("3504", "Unibell" to now.plusMinutes(10), "Belhar" to now.plusMinutes(13), "Cape Town" to now.plusMinutes(55)),
                leg("3506", "Unibell" to now.plusMinutes(80), "Cape Town" to now.plusMinutes(125))),
            "Chris Hani" to listOf(leg("3600", "Unibell" to now.plusMinutes(20), "Nyanga" to now.plusMinutes(30), "Chris Hani" to now.plusMinutes(60)),
                leg("3602", "Unibell" to now.plusMinutes(90), "Chris Hani" to now.plusMinutes(130))),
            "Kapteinsklip" to listOf(leg("3700", "Unibell" to now.plusMinutes(85), "Kapteinsklip" to now.plusMinutes(120))),
            "Bellville" to listOf(leg("3800", "Unibell" to now.plusMinutes(95), "Bellville" to now.plusMinutes(115))),
        )
        runBlocking {
            pages.forEach { (end, journeys) ->
                train.repository.store.cache(CachedSearch(SearchQuery("Unibell", end, date, slot), journeys, System.currentTimeMillis(), null, source))
            }
        }
        app.onNodeWithTag("lab-train-row").performScrollTo().performClick()
        app.onNodeWithTag("lab-station-search").performTextInput("Unibell")
        app.onNodeWithTag("lab-station-choice:unibell").performClick()
        app.waitUntil(15_000) { app.onAllNodesWithTag("lab-station-count").fetchSemanticsNodes().any { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString() == "2" } }
        app.onNodeWithTag("lab-station").performScrollToNode(hasTestTag("lab-departure:3504"))
        app.onNodeWithTag("lab-departure:3504").assertIsDisplayed()
        app.onNodeWithTag("lab-station").performScrollToNode(hasTestTag("lab-departure:3600"))
        app.onNodeWithTag("lab-departure:3600").assertIsDisplayed()
        app.onAllNodesWithTag("lab-departure:3506").assertCountEquals(0)

        app.onNodeWithTag("lab-station").performScrollToNode(hasTestTag("lab-train-number"))
        app.onNodeWithTag("lab-train-number").performTextInput("T3600")
        app.onNodeWithTag("lab-train-find").performClick()
        app.onNodeWithTag("lab-station").performScrollToNode(hasText("Train 3600 · towards Chris Hani"))
        app.onAllNodesWithText("Train 3600 · towards Chris Hani")[0].assertIsDisplayed()
        app.onAllNodesWithText("Nyanga")[0].assertExists()
    }
}
