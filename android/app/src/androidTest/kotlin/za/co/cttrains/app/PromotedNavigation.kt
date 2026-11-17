package za.co.cttrains.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule

internal fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.openMine(route: String) {
    onNodeWithTag("lab-tab-mine").performClick()
    onNodeWithTag("lab-mine").performScrollToNode(hasTestTag("lab-mine-$route"))
    onNodeWithTag("lab-mine-$route").performClick()
}
internal fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.openMap() {
    onNodeWithTag("lab-tab-now").performClick()
    onNodeWithContentDescription("Open map").performClick()
}
