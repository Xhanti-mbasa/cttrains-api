package za.co.cttrains.app

import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextClock
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmartFeaturesUiTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()

    @Test fun hidingHomeMapKeepsSmartCardsAndPersistsTheChoice() {
        val features = ViewModelProvider(app.activity)[FeatureViewModel::class.java]
        app.waitUntil(15_000) { features.state.value.ready }
        val original = features.state.value.extras.beta
        try {
            runBlocking { features.store.update { it.copy(beta = it.beta.copy(homeMap = true, smartCards = true)) } }
            UiScreenshots.closeSystemDialogs()
            app.onNodeWithText("Settings").performClick()
            app.onNodeWithTag("home-map-toggle").performScrollTo().performClick()
            app.waitUntil(5000) { !features.state.value.extras.beta.homeMap }
            assertEquals(false, runBlocking { features.store.extras() }.beta.homeMap)
            app.onNodeWithText("Home").performClick()
            app.onNodeWithTag("smart-cards").assertExists()
            app.onAllNodesWithContentDescription("Expand map to full screen").assertCountEquals(0)
            UiScreenshots.save("smart-cards-map-hidden")
        } finally {
            runBlocking { features.store.update { it.copy(beta = original) } }
        }
    }

    @Test fun widgetRemoteViewsAndDynamicJourneyRowsInflateOnAndroid() {
        app.runOnUiThread {
            val context = app.activity
            val views = RemoteViews(context.packageName, R.layout.travel_widget)
            val row = RemoteViews(context.packageName, R.layout.travel_widget_row)
            row.setTextViewText(R.id.widget_row_title, "Bellville → Home")
            row.setTextViewText(R.id.widget_row_subtitle, "18:20 → 19:00")
            views.addView(R.id.widget_rows, row)
            val root = views.apply(context, FrameLayout(context))
            assertEquals("Bellville → Home", root.findViewById<TextView>(R.id.widget_row_title).text.toString())
            val clocks = (root as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup
            assertEquals("Africa/Johannesburg", (clocks.getChildAt(1) as TextClock).timeZone)
        }
    }
}
