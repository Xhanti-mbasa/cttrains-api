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
        app.onNodeWithText("Plan").performClick()
        app.onNodeWithTag("journey-planner").performScrollToNode(hasText("To"))
        app.onNode(hasText("To") and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        app.onNodeWithTag("use-current-location").assertIsDisplayed()
        app.onNodeWithText("Search places, stations or stops").performTextInput("bellv")
        app.waitUntil(5000) { app.onAllNodes(hasText("Bellville") and hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        UiScreenshots.save("fuzzy-place-search")
    }
    @Test fun myProfileIsReachableFromSettings() {
        UiScreenshots.closeSystemDialogs()
        app.onNodeWithText("Settings").performClick()
        app.onNodeWithTag("my-profile").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        app.onNodeWithText("My profile").assertIsDisplayed()
        app.onNodeWithTag("add-custom-place").performScrollTo().assertIsDisplayed()
    }
}
