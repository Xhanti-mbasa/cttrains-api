package za.co.cttrains.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimplePlannerUiTest {
    @get:Rule val app=createAndroidComposeRule<MainActivity>()
    @Test fun partialStationNameFindsTheStationAndProfileIsInSettings() {
        val train=ViewModelProvider(app.activity)[TrainViewModel::class.java]
        app.waitUntil(15000) { train.state.value.stations.any { it.name=="Bellville" && it.mapped } }
        UiScreenshots.closeSystemDialogs()
        app.onNodeWithTag("lab-tab-plan").performClick()
        app.onNodeWithTag("lab-plan-to").performClick()
        app.onNodeWithTag("lab-place-search").performTextInput("bellv")
        app.waitUntil(5000) { app.onAllNodes(hasText("Bellville") and hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        UiScreenshots.save("fuzzy-place-search")
    }
    @Test fun myProfileIsReachableFromSettings() {
        UiScreenshots.closeSystemDialogs()
        app.onNodeWithTag("lab-tab-mine").performClick()
        app.onNodeWithText("All places").performClick()
        app.onNodeWithText("My profile").assertIsDisplayed()
        app.onNodeWithTag("saved-addresses").performScrollToNode(hasTestTag("add-custom-place"))
        app.onNodeWithTag("add-custom-place").assertIsDisplayed()
    }
}
