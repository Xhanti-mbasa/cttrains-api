package za.co.cttrains.app

import androidx.compose.ui.test.*
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
        app.onNodeWithText("Start: At departure station").performScrollTo().performClick()
        app.onNodeWithText("Where do you leave from?").assertIsDisplayed()
        app.onNodeWithText("Just the station").performClick()
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
}
