package za.co.cttrains.app

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class QualityOfLifeUiTest {
    @get:Rule val app=createAndroidComposeRule<MainActivity>()
    @Test fun mapOptionsCollapseAndExpandedMapKeepsControlsHidden() {
        val train=ViewModelProvider(app.activity)[TrainViewModel::class.java]
        app.runOnUiThread { train.setMapMode("stations") }
        app.waitForIdle()
        app.onNodeWithText("Online").assertDoesNotExist()
        app.onNodeWithText("Map options").performClick()
        app.onNodeWithText("Online").assertIsDisplayed()
        app.onNodeWithText("Collapse").performClick()
        app.onNodeWithText("Online").assertDoesNotExist()
        app.onNodeWithContentDescription("Expand map to full screen").performClick()
        app.onNodeWithContentDescription("Close full-screen map").assertIsDisplayed()
        app.onNodeWithText("Online").assertDoesNotExist()
        app.onNodeWithText("Open in Google").assertIsDisplayed()
        app.onNodeWithContentDescription("Close full-screen map").performClick()
        app.onNodeWithContentDescription("Refresh map and schedules").assertIsDisplayed()
        app.onNodeWithText("Map options").performClick()
        app.onNodeWithContentDescription("Manage offline maps").performClick()
        app.onNodeWithText("Collapse").assertDoesNotExist()
        app.onNodeWithText("Offline street maps").assertIsDisplayed()
    }
    @Test fun rapidFeatureChangesDoNotOverwriteEachOtherAndTrainViewClearsBusFilter() {
        val features=ViewModelProvider(app.activity)[FeatureViewModel::class.java]
        app.waitUntil(15000) { features.state.value.ready }
        app.runOnUiThread {
            features.beta { it.copy(goldenArrow=true) }
            features.beta { it.copy(myCiti=true) }
            features.beta { it.copy(hostedSharing=true) }
        }
        app.waitUntil(15000) { features.state.value.extras.beta.run { goldenArrow && myCiti && hostedSharing } }
        app.runOnUiThread { features.showBusMap(za.co.cttrains.core.TransportService.MYCITI,"101");features.beta { it.copy(showTrains=false) } }
        app.waitUntil(15000) { !features.state.value.extras.beta.showTrains }
        app.runOnUiThread { features.showTrainMap() }
        app.waitUntil(15000) { features.state.value.extras.beta.showTrains }
        assertNull(features.state.value.mapRoute);assertNull(features.state.value.mapService)
        app.runOnUiThread { features.beta { it.copy(goldenArrow=false,myCiti=false,hostedSharing=false) } }
        app.waitUntil(15000) { features.state.value.extras.beta.run { !goldenArrow && !myCiti && !hostedSharing } }
    }
    @Test fun savedPlacesAndChatAccountSetupAreDiscoverable() {
        app.onNodeWithText("Plan").performClick()
        val train=ViewModelProvider(app.activity)[TrainViewModel::class.java]
        app.runOnUiThread { train.edit { it.copy(from="",to="") } }
        app.onNodeWithText("Choose your start").performScrollTo().performClick()
        app.onNodeWithText("Where do you leave from?").assertIsDisplayed()
        app.onNodeWithText("Search places, stations or stops").performTextInput("Unibell")
        app.onNode(hasText("Unibell") and hasClickAction() and !hasSetTextAction()).performClick()
        app.onNodeWithText("Tools").performClick()
        app.onNodeWithText("Community reports, chat & friends").performClick()
        app.onNodeWithText("Chat").performClick()
        val features=ViewModelProvider(app.activity)[FeatureViewModel::class.java]
        app.waitUntil(15000) { features.state.value.ready }
        app.runOnUiThread { features.beta { it.copy(hostedSharing=true) } }
        app.waitUntil(15000) { features.state.value.extras.beta.hostedSharing }
        app.onNodeWithText("Hosted community account").assertIsDisplayed()
        app.onNodeWithText("Connect Matrix").performScrollTo().assertIsDisplayed()
        app.onNodeWithText("Enable hosted community").assertDoesNotExist()
        app.runOnUiThread { features.beta { it.copy(hostedSharing=false) } }
    }
    @Test fun focusedMapPreferencePersistsAndEnabledOperatorsAppearInPlan() {
        val features=ViewModelProvider(app.activity)[FeatureViewModel::class.java]
        val train=ViewModelProvider(app.activity)[TrainViewModel::class.java]
        app.waitUntil(15000) { features.state.value.ready }
        app.runOnUiThread { train.setMapMode("stations");features.beta { it.copy(journeyFocus=true,myCiti=true,goldenArrow=true) } }
        app.waitUntil(15000) { features.state.value.extras.beta.run { journeyFocus && myCiti && goldenArrow } }
        app.onNodeWithText("Map options").performClick()
        app.onNodeWithText("Focus on selected journey").performScrollTo().assertIsDisplayed()
        app.onNodeWithText("Collapse").performClick()
        app.onNodeWithText("Plan").performClick()
        app.runOnUiThread { train.edit { it.copy(from="Unibell",to="Salt River") } }
        app.onNodeWithText("Mode of transport · Trains").performScrollTo().performClick()
        app.onNodeWithText("MyCiTi").performClick()
        app.onNodeWithText("Find MyCiTi routes").performScrollTo().assertIsDisplayed()
        app.onNodeWithText("Unibell").performScrollTo().assertIsDisplayed()
        app.onNodeWithText("Salt River").assertIsDisplayed()
        app.onNodeWithText("Mode of transport · MyCiTi").performScrollTo().performClick()
        app.onNodeWithText("Golden Arrow").performClick()
        app.onNodeWithText("Find Golden Arrow routes").performScrollTo().assertIsDisplayed()
        app.onNodeWithText("Mode of transport · Golden Arrow").performScrollTo().performClick()
        app.onNodeWithText("Compare").performClick()
        app.onNodeWithText("Compare routes").performScrollTo().assertIsDisplayed()
        app.onNodeWithText("Mode of transport · Compare").performScrollTo()
        UiScreenshots.save("planner-compare")
        val image=app.onRoot().captureToImage()
        val file=java.io.File(app.activity.getExternalFilesDir(null),"ui-screenshots/planner-compare.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        app.onNodeWithText("Unibell").performScrollTo().assertIsDisplayed()
        app.onNodeWithText("Salt River").assertIsDisplayed()
        app.runOnUiThread { features.beta { it.copy(journeyFocus=false,myCiti=false,goldenArrow=false) } }
    }
    @Test fun whatsAppAlphaIsDiscoverableAndHasNoDirectPhoneAccountAccess() {
        app.onNodeWithText("Tools").performClick()
        app.onNodeWithText("WhatsApp updates · alpha").performScrollTo().performClick()
        app.onNodeWithText("WhatsApp updates · alpha").assertIsDisplayed()
        app.onNodeWithText("Gateway website · HTTPS").performScrollTo().assertIsDisplayed()
        app.onNodeWithText("Reader API key").performScrollTo().assertIsDisplayed()
        app.onNodeWithText("Find linked accounts").performScrollTo().assertIsDisplayed()
        app.onNodeWithText("How to set this up").performScrollTo().performClick()
        app.onNodeWithText("Set up WhatsApp updates").assertIsDisplayed()
        UiScreenshots.save("whatsapp-setup-guide")
        app.onNodeWithTag("whatsapp-setup-guide")
            .performScrollToNode(hasText("5 · Connect in CT Trains"))
        app.onNodeWithText("5 · Connect in CT Trains").assertIsDisplayed()
    }
    @Test fun calendarTimeSelectorAndSwapPreserveCustomEndpoints() {
        val train=ViewModelProvider(app.activity)[TrainViewModel::class.java]
        val features=ViewModelProvider(app.activity)[FeatureViewModel::class.java]
        app.waitUntil(15000) { features.state.value.ready }
        val home=za.co.cttrains.core.WalkEndpoint("spot:home","Home",za.co.cttrains.core.GeoPoint(-33.94,18.63))
        val campus=za.co.cttrains.core.WalkEndpoint("spot:campus","Campus",za.co.cttrains.core.GeoPoint(-33.93,18.46))
        app.runOnUiThread { train.edit { it.copy(from="Unibell",to="Salt River") };features.place(home,true);features.place(campus,false) }
        app.onNodeWithText("Plan").performClick()
        app.onNodeWithText("Swap From and To").performScrollTo().performClick()
        app.runOnIdle { assertEquals(campus,features.state.value.access);assertEquals(home,features.state.value.destination);assertEquals("Salt River",train.state.value.from);assertEquals("Unibell",train.state.value.to) }
        app.onNodeWithTag("journey-planner").performScrollToNode(hasText("Travel date"))
        app.onNodeWithText("Travel date").performClick()
        app.onNodeWithText("Choose date").assertIsDisplayed()
        UiScreenshots.save("journey-calendar")
        app.onNodeWithText("Cancel").performClick()
        app.onNodeWithText("Leave after").performScrollTo().performClick()
        app.onNodeWithText("24-hour time · 15-minute intervals").assertIsDisplayed()
        UiScreenshots.save("journey-time-picker")
        app.onNodeWithText("Choose time").performClick()
        app.onNodeWithText("Options").performScrollTo().performClick()
        app.onNodeWithText("Auto estimate").performScrollTo().assertIsDisplayed()
        app.runOnUiThread { features.place(null,true);features.place(null,false) }
    }
    @Test fun healthAccessIsReadOnlyAndBluetoothIsNotRequestedForMatrixPresence() {
        val info=app.activity.packageManager.getPackageInfo(app.activity.packageName,android.content.pm.PackageManager.GET_PERMISSIONS)
        val permissions=info.requestedPermissions.orEmpty().toSet()
        assertTrue("android.permission.health.READ_STEPS" in permissions)
        assertFalse(permissions.any { it.startsWith("android.permission.health.WRITE_") })
        assertFalse("android.permission.BLUETOOTH_SCAN" in permissions)
        assertFalse("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" in permissions)
    }

}
