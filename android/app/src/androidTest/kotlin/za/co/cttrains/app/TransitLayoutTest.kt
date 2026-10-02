package za.co.cttrains.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import za.co.cttrains.app.ui.BusBrowser
import za.co.cttrains.core.*
import za.co.cttrains.app.data.LocalExtras

class TransitLayoutTest {
    @get:Rule val app=createComposeRule()
    private val route=BusRouteDocument("test-route","Airport Industria","https://www.gabs.co.za/Pdf/Apdf/sample.pdf","2026-09-28","2026-10-04",true)
    private val source=BusSnapshot(TransportService.GOLDEN_ARROW,1,listOf(route),emptyList(),emptyList(),listOf(
        BusFareRow("fare-a","Airport Industria · cash",mapOf("Cash single" to 2500L)),
        BusFareRow("fare-b","Woodstock to Bellville",mapOf("Weekly" to 30000L))),"Survey points are not verified current stops.")
    private fun show() {
        app.setContent {
            val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,1.5f)) {
                MaterialTheme {
                    Box(Modifier.size(320.dp,420.dp)) {
                        BusBrowser(FeatureState(extras=LocalExtras(beta=BetaPreferences(goldenArrow=true)),snapshots=mapOf(source.service to source)),{}, {},{ _,_-> },{ _,_-> },{ _,_,_,_-> }, {},{ _,_-> })
                    }
                }
            }
        }
    }
    private fun list()=app.onNode(hasScrollToIndexAction())
    private fun screenshot(name: String) {
        UiScreenshots.save(name)
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"ui-screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { app.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun expandedSourceAndFiltersDoNotClipRouteCardsAtLargeTextSize() {
        show()
        list().performScrollToNode(hasText("Source details"));app.onNodeWithText("Source details").performClick()
        list().performScrollToNode(hasText("Filters"));app.onNodeWithText("Filters").performClick()
        val today=java.time.LocalDate.now(CapeTownZone).dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)
        if(today!="Friday") { list().performScrollToNode(hasText(today));app.onNodeWithText(today).performClick();app.onNodeWithText("Friday").performClick() }
        list().performScrollToNode(hasText("Airport Industria"));app.onNodeWithText("Airport Industria").assertIsDisplayed()
        list().performScrollToNode(hasText("Load Friday times"));app.onNodeWithText("Load Friday times").assertIsDisplayed()
        screenshot("golden-arrow-large-text-routes")
        list().performScrollToNode(hasText("Friday"));app.onNodeWithText("Friday").performClick();app.onNodeWithText("Tuesday").performClick()
        list().performScrollToNode(hasText("Load Tuesday times"));app.onNodeWithText("Load Tuesday times").assertIsDisplayed()
    }
    @Test fun changingDayReloadsTheSelectedRoute() {
        var requested: String?=null
        val calls=listOf(BusCall("a","Airport boarding stop","08:00"),BusCall("b","Destination","08:30"))
        val initial=BusTimetable(source.service,route,"Monday",1,listOf(BusTrip("trip",route.id,"Outbound","Monday",calls)))
        app.setContent {
            var state by remember { mutableStateOf(FeatureState(extras=LocalExtras(beta=BetaPreferences(goldenArrow=true)),snapshots=mapOf(source.service to source),timetable=initial)) }
            MaterialTheme { BusBrowser(state,{}, {},{ selected,day -> requested=day;state=state.copy(timetable=initial.copy(route=selected,day=day)) },{ _,_-> },{ _,_,_,_-> }, {},{ _,_-> }) }
        }
        val today=java.time.LocalDate.now(CapeTownZone).dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)
        list().performScrollToNode(hasText(today));app.onNodeWithText(today).performClick();app.onNodeWithText("Tuesday").performClick()
        app.runOnIdle { assertEquals("Tuesday",requested) }
        list().performScrollToNode(hasText("08:00 → 08:30"));app.onNodeWithText("08:00 → 08:30").assertIsDisplayed()
    }
    @Test fun fareProductsAndBudgetRemainReachableInOneScrollArea() {
        show()
        list().performScrollToNode(hasText("Fares"));app.onNodeWithText("Fares").performClick()
        list().performScrollToNode(hasText("Cash single · R25.00"));app.onNodeWithText("Cash single · R25.00").assertIsDisplayed()
        screenshot("golden-arrow-large-text-fares")
        list().performScrollToNode(hasText("Your budget & fare corrections"));app.onNodeWithText("Your budget & fare corrections").assertIsDisplayed()
        list().performScrollToNode(hasText("Save personal fare correction"));app.onNodeWithText("Save personal fare correction").assertIsDisplayed()
    }
}
