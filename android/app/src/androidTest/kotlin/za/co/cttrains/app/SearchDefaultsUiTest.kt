package za.co.cttrains.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDateTime
import org.junit.*
import org.junit.runner.RunWith
import za.co.cttrains.app.location.CurrentPosition

@RunWith(AndroidJUnit4::class)
class SearchDefaultsUiTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()

    @Test fun freshDefaultsRollOverAndManualChoicesSurviveRefresh() {
        val model = ViewModelProvider(app.activity)[TrainViewModel::class.java]
        app.runOnIdle {
            model.refreshSearchDefaults(LocalDateTime.parse("2026-12-31T18:45"))
            Assert.assertEquals("2026-12-31", model.state.value.date)
            Assert.assertEquals("18:45", model.state.value.time)
            model.refreshSearchDefaults(LocalDateTime.parse("2026-12-31T19:00"))
            Assert.assertEquals("2027-01-01", model.state.value.date)
            Assert.assertEquals("06:00", model.state.value.time)
            model.chooseDate("2027-01-02")
            model.chooseTime("08:30")
            model.refreshSearchDefaults(LocalDateTime.parse("2027-01-01T12:00"))
            Assert.assertEquals("2027-01-02", model.state.value.date)
            Assert.assertEquals("08:30", model.state.value.time)
        }
    }

    @Test fun nearbyStationReplacesRestoredOriginButNeverManualChoice() {
        val model = ViewModelProvider(app.activity)[TrainViewModel::class.java]
        app.waitUntil(15000) { model.state.value.stations.any { it.name == "Bellville" && it.mapped } && model.state.value.catalog.stations.contains("Bellville") }
        app.runOnIdle {
            val station = model.state.value.stations.first { it.name == "Bellville" }
            val fresh = CurrentPosition(station.latitude!!, station.longitude!!, 20f, System.currentTimeMillis())
            model.directionsPosition(fresh)
            model.defaultNearbyOrigin()
            Assert.assertEquals("Bellville", model.state.value.from)
            model.keepOrigin()
            model.edit { it.copy(from = "Cape Town") }
            model.directionsPosition(fresh.copy(capturedAt = System.currentTimeMillis()))
            model.defaultNearbyOrigin()
            Assert.assertEquals("Cape Town", model.state.value.from)
        }
    }

    @Test fun staleLocationDoesNotChooseADeparture() {
        val model = ViewModelProvider(app.activity)[TrainViewModel::class.java]
        app.waitUntil(15000) { model.state.value.stations.any { it.name == "Bellville" && it.mapped } }
        app.runOnIdle {
            val before = model.state.value.from
            val station = model.state.value.stations.first { it.name == "Bellville" }
            model.directionsPosition(CurrentPosition(station.latitude!!, station.longitude!!, 20f, System.currentTimeMillis() - 600_000))
            model.defaultNearbyOrigin()
            Assert.assertEquals(before, model.state.value.from)
        }
    }
}
