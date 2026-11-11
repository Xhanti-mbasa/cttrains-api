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
import org.junit.Assert.assertTrue
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
    @Test fun widgetHeaderAndActionFitCompactAndExpandedSizes() {
        app.runOnUiThread {
            val context=app.activity
            for((width,height) in listOf(180 to 180,250 to 220,350 to 320)) {
                val views=RemoteViews(context.packageName,R.layout.travel_widget)
                views.setTextViewText(R.id.widget_title,"Smart Cards")
                views.setTextViewText(R.id.widget_refresh,"Refresh nearby rides  →")
                views.setViewVisibility(R.id.widget_badge,if(height<240)android.view.View.GONE else android.view.View.VISIBLE)
                views.setViewVisibility(R.id.widget_updated,if(height<200)android.view.View.GONE else android.view.View.VISIBLE)
                val row=RemoteViews(context.packageName,R.layout.travel_widget_row)
                row.setTextViewText(R.id.widget_row_title,"Your next ride starts here")
                row.setTextViewText(R.id.widget_row_subtitle,"Set Home in My profile, then refresh nearby rides.")
                views.addView(R.id.widget_rows,row)
                val root=views.apply(context,FrameLayout(context))
                val density=context.resources.displayMetrics.density
                val w=(width*density).toInt();val h=(height*density).toInt()
                root.measure(android.view.View.MeasureSpec.makeMeasureSpec(w,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(h,android.view.View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)
                val title=root.findViewById<TextView>(R.id.widget_title)
                val parent=title.parent as android.view.ViewGroup
                assertTrue(title.right<=parent.getChildAt(1).left)
                val action=root.findViewById<TextView>(R.id.widget_refresh)
                assertTrue("Action clipped at $width x $height",action.bottom<=h)
                val bitmap=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                val file=java.io.File(context.filesDir,"widget-$width-$height.png")
                file.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                UiScreenshots.publishAppFile(context.packageName,"widget-$width-$height")
            }
        }
    }

    @Test fun sevenBuildTapsOpenLockedDeveloperOptionsWithoutGrantingAccess() {
        UiScreenshots.closeSystemDialogs()
        app.onNodeWithText("Settings").performClick()
        app.onNodeWithTag("developer-build-label").performScrollTo()
        repeat(7) { app.onNodeWithTag("developer-build-label").performClick() }
        app.onNodeWithText("Developer options").assertExists()
        app.onNodeWithText("Verify access").assertExists()
        app.onAllNodesWithText("Owner controls").assertCountEquals(0)
        UiScreenshots.save("developer-locked")
    }

}
