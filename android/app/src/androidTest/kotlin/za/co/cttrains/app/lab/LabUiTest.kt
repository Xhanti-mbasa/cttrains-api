package za.co.cttrains.app.lab

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import za.co.cttrains.app.BuildConfig
import za.co.cttrains.app.FeatureViewModel
import za.co.cttrains.app.MainActivity
import za.co.cttrains.app.TrainViewModel
import za.co.cttrains.app.UiScreenshots

/** CT Trains Lab edition only; skipped in the regular edition. Screenshots are pulled by check-device.sh. */
@RunWith(AndroidJUnit4::class)
class LabUiTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()

    @Before fun labOnly() {
        assumeTrue("Lab UI tests need CTTRAINS_EXPERIMENTAL=true", BuildConfig.EXPERIMENTAL_EDITION)
        UiScreenshots.closeSystemDialogs()
        val features = ViewModelProvider(app.activity)[FeatureViewModel::class.java]
        app.waitUntil(15_000) { features.state.value.ready }
        // Like the regular suite: the emulator's software GPU cannot host the online street map, so use the station atlas.
        val train = ViewModelProvider(app.activity)[TrainViewModel::class.java]
        app.runOnUiThread { train.setMapMode("stations") }
        app.waitUntil(15_000) { train.state.value.preferences.mapMode == "stations" }
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    /** Lazy lists only compose visible rows, so scroll the list to the row rather than the row into view. */
    private fun scrollTo(list: String, tag: String) = app.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))

    @Test fun threePlacesReplaceTheFiveTabs() {
        app.onNodeWithTag("lab-tab-now").assertIsDisplayed()
        app.onNodeWithTag("lab-tab-plan").assertIsDisplayed()
        app.onNodeWithTag("lab-tab-mine").assertIsDisplayed()
        app.onNodeWithText("Settings").assertDoesNotExist()
        app.onNodeWithTag("lab-now").assertIsDisplayed()
        app.onNodeWithContentDescription("Open map").assertIsDisplayed()
        app.onNodeWithTag("lab-alerts").assertIsDisplayed()
        scrollTo("lab-now", "lab-get-home")
        app.onNodeWithTag("lab-get-home").assertIsDisplayed()
        UiScreenshots.save("lab-now")
        app.onNodeWithTag("lab-tab-mine").performClick()
        app.onNodeWithTag("lab-mine").assertIsDisplayed()
        UiScreenshots.save("lab-mine")
        app.onNodeWithTag("lab-tab-plan").performClick()
        app.waitForIdle()
        UiScreenshots.save("lab-plan")
    }

    @Test fun everyRegularCapabilityIsReachableFromMine() {
        app.onNodeWithTag("lab-tab-mine").performClick()
        val routes = listOf("upcoming", "trips", "budget", "buses", "map", "alerts", "directions", "widgets", "community", "whatsapp", "settings", "app-updates")
        for (route in routes) {
            scrollTo("lab-mine", "lab-mine-$route")
            app.onNodeWithTag("lab-mine-$route").performClick()
            app.waitForIdle()
            if (route == "map") app.onNodeWithTag("lab-tab-now").performClick()
            else app.onNodeWithTag("lab-back").performClick()
            app.onNodeWithTag("lab-tab-mine").performClick()
        }
        scrollTo("lab-mine", "developer-build-label")
        app.onNodeWithTag("developer-build-label").assertIsDisplayed()
    }

    @Test fun privacyControlsAndBackupStayOnOneFlatPage() {
        app.onNodeWithTag("lab-tab-mine").performClick()
        scrollTo("lab-mine", "lab-mine-settings")
        app.onNodeWithTag("lab-mine-settings").performClick()
        app.onNodeWithTag("lab-settings").assertIsDisplayed()
        app.onNodeWithText("Forget sampled location").performScrollTo().assertIsDisplayed()
        app.onNodeWithText("Clear cached timetables").performScrollTo().assertIsDisplayed()
        app.onNodeWithText("Delete all local personal data").performScrollTo().performClick()
        app.onNodeWithText("Delete your local profile?").assertIsDisplayed()
        app.onNodeWithText("Cancel").performClick()
        app.onNodeWithText("Delete your local profile?").assertDoesNotExist()
        UiScreenshots.save("lab-settings")
    }

    @Test fun locationIsOnlyRequestedOnAsk() {
        val train = ViewModelProvider(app.activity)[TrainViewModel::class.java]
        app.runOnUiThread { train.forgetLocation() }
        app.waitForIdle()
        scrollTo("lab-now", "lab-locate")
        app.onNodeWithTag("lab-locate").assertIsDisplayed()
        org.junit.Assert.assertNull(train.state.value.currentPosition)
        UiScreenshots.save("lab-now-no-location")
    }

    @Test fun darkAppearanceAndLargeFontsKeepNowUsable() {
        val train = ViewModelProvider(app.activity)[TrainViewModel::class.java]
        app.runOnUiThread { train.setTheme("Dark") }
        app.waitForIdle()
        UiScreenshots.save("lab-now-dark")
        app.runOnUiThread { train.setTheme("System") }
        try {
            shell("settings put system font_scale 2.0")
            app.activityRule.scenario.recreate()
            app.waitForIdle()
            app.onNodeWithTag("lab-tab-now").assertIsDisplayed()
            scrollTo("lab-now", "lab-get-home")
            app.onNodeWithTag("lab-get-home").assertIsDisplayed()
            UiScreenshots.save("lab-now-font-200")
        } finally {
            shell("settings put system font_scale 1.0")
        }
    }
}
