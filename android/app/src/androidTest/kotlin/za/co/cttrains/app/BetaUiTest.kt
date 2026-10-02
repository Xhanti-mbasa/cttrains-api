package za.co.cttrains.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BetaUiTest {
    @get:Rule val app=createAndroidComposeRule<MainActivity>()
    @Test fun settingsOperatorSwitchesRemainIndependent() {
        app.onNodeWithText("Settings").performClick()
        app.onNodeWithContentDescription("Golden Arrow").performScrollTo().assertIsOff().performClick()
        app.onNodeWithContentDescription("Golden Arrow").assertIsOn()
        app.onNodeWithContentDescription("MyCiTi").assertIsOff()
        app.onNodeWithContentDescription("Golden Arrow").performClick().assertIsOff()
        app.onNodeWithContentDescription("MyCiTi").performScrollTo().performClick().assertIsOn()
        app.onNodeWithContentDescription("Golden Arrow").assertIsOff()
        app.onNodeWithContentDescription("MyCiTi").performClick().assertIsOff()
    }
}
