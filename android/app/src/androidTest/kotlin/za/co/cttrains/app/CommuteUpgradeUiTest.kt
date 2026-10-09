package za.co.cttrains.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import za.co.cttrains.core.*
import za.co.cttrains.app.data.*

@RunWith(AndroidJUnit4::class)
class CommuteUpgradeUiTest {
    @get:Rule val app=createAndroidComposeRule<MainActivity>()
    private lateinit var features: FeatureViewModel
    private lateinit var trains: TrainViewModel
    @Before fun setup() {
        features=ViewModelProvider(app.activity)[FeatureViewModel::class.java];trains=ViewModelProvider(app.activity)[TrainViewModel::class.java]
        app.waitUntil(15000) { features.state.value.ready && trains.state.value.stations.isNotEmpty() }
        app.runOnUiThread { trains.setMapMode("stations") }
    }
    @After fun cleanup() { runBlocking { features.store.update { it.copy(routines=it.routines.filterNot { r->r.id=="test-repeat" },spots=it.spots.filterNot { p->p.id=="test-home" }) } } }
    @Test fun savedAddressesCanBeChangedWithoutLosingStationAndUsedInPlan() {
        val home=MarkedSpot("test-home","Home",null,GeoPoint(-33.94,18.63),"Home","Old address",createdAt=1,preferredStation="Unibell")
        runBlocking { features.store.update { it.copy(spots=listOf(home)) } }
        app.waitUntil(15000) { features.state.value.extras.spots.any { it.id==home.id } }
        app.onNodeWithText("Saved").performClick()
        app.onNodeWithText("Home, Work & School · manage addresses").performScrollTo().performClick()
        app.onNodeWithTag("edit-address:Home").performScrollTo().performClick()
        app.onNodeWithText("Search places, stations or stops").performTextInput("Salt River")
        app.onNode(hasText("Salt River") and hasClickAction()).performClick()
        app.onNodeWithText("Confirm Home address").assertIsDisplayed()
        app.onNodeWithText("Save address").performClick()
        app.waitUntil(15000) { features.state.value.extras.spots.single().note=="Salt River" }
        assertEquals("test-home",features.state.value.extras.spots.single().id)
        assertEquals("Unibell",features.state.value.extras.spots.single().preferredStation)
        app.onNodeWithText("To here").performScrollTo().performClick()
        app.waitUntil(15000) { features.state.value.destination?.id=="spot:test-home" }
        assertEquals("Unibell",features.state.value.arrivalStation)
        UiScreenshots.save("saved-home-plan")
    }
    @Test fun recurringDaysCanBeEditedAndRemainInEncryptedBackup() {
        val r=CommuteRoutine("test-repeat","Morning commute",SearchQuery("Salt River","Unibell","2026-10-12","08:00"),setOf(1,2,3,4,5),"cttrains-web",20,5,createdAt=1)
        runBlocking { features.store.update { it.copy(routines=listOf(r)) } }
        app.waitUntil(15000) { features.state.value.extras.routines.size==1 }
        app.onNodeWithText("Journeys").performClick()
        app.onNodeWithText("Edit recurrence").performScrollTo().performClick()
        app.onNodeWithTag("repeat-day:6").performClick()
        app.onNodeWithTag("save-routine").performScrollTo().performClick()
        app.waitUntil(15000) { 6 in features.state.value.extras.routines.single().days }
        runBlocking { val raw=features.files.export("a-long-local-backup-password");val restored=features.files.inspect(raw,"a-long-local-backup-password");assertTrue(6 in features.store.json.decodeFromString<LocalExtras>(restored.values.getValue("extras")).routines.single().days) }
        UiScreenshots.save("recurring-journeys")
    }
}
