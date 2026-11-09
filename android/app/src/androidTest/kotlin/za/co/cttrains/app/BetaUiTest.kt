package za.co.cttrains.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BetaUiTest {
    @get:Rule val app=createAndroidComposeRule<MainActivity>()
    @Test fun settingsOperatorSwitchesRemainIndependent() {
        val features=ViewModelProvider(app.activity)[FeatureViewModel::class.java]
        app.waitUntil(15000) { features.state.value.ready }
        app.runOnUiThread { features.beta { it.copy(goldenArrow=false,myCiti=false) } }
        app.waitUntil(15000) { features.state.value.extras.beta.run { !goldenArrow && !myCiti } }
        app.onNodeWithText("Settings").performClick()
        app.onNodeWithContentDescription("Golden Arrow").performScrollTo().assertIsOff().performClick()
        app.waitUntil(15000) { features.state.value.extras.beta.goldenArrow }
        app.onNodeWithContentDescription("Golden Arrow").assertIsOn()
        app.onNodeWithContentDescription("MyCiTi").assertIsOff()
        app.onNodeWithContentDescription("Golden Arrow").performClick()
        app.waitUntil(15000) { !features.state.value.extras.beta.goldenArrow }
        app.onNodeWithContentDescription("Golden Arrow").assertIsOff()
        app.onNodeWithContentDescription("MyCiTi").performScrollTo().performClick()
        app.waitUntil(15000) { features.state.value.extras.beta.myCiti }
        app.onNodeWithContentDescription("MyCiTi").assertIsOn()
        app.onNodeWithContentDescription("Golden Arrow").assertIsOff()
        app.onNodeWithContentDescription("MyCiTi").performClick()
        app.waitUntil(15000) { !features.state.value.extras.beta.myCiti }
        app.onNodeWithContentDescription("MyCiTi").assertIsOff()
    }
}
