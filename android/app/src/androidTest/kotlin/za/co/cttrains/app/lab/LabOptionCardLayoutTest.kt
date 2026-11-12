package za.co.cttrains.app.lab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import za.co.cttrains.app.PlannerOption
import za.co.cttrains.app.UiScreenshots
import za.co.cttrains.app.ui.lab.*
import za.co.cttrains.core.*

/** Offline fixtures: the layout check must not depend on today's timetable or network. */
class LabOptionCardLayoutTest {
    @get:Rule val app = createComposeRule()

    private data class Display(val width: Int = 320, val fontScale: Float = 1f, val dark: Boolean = false)

    private fun option(transfer: Boolean): PlannerOption {
        val journey = Journey(if (transfer) listOf(
            Leg("Northern line", "test-a", listOf(Stop("Woodstock", "14:03"), Stop("Bellville", "14:30"))),
            Leg("Central line", "test-b", listOf(Stop("Bellville", "14:35"), Stop("Unibell", "14:58"))),
        ) else listOf(Leg("Northern line", "test-a", listOf(Stop("Woodstock", "14:03"), Stop("Unibell", "14:58")))))
        val departure = JourneyComparison.requestedAt("2026-11-12", "14:03")
        return PlannerOption("layout-fixture", TransportService.PRASA, null, journey, null, emptyList(),
            TripEstimate(departure, null, null, null, null, null), emptyList(), departure, false)
    }

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        app.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
            assertTrue("Text layout is available for $text", get(results))
        }
        return results.single()
    }

    private fun verify(transfer: Boolean) {
        val display = mutableStateOf(Display())
        val option = option(transfer)
        var opened = false
        app.setContent {
            val density = LocalDensity.current
            val d = display.value
            CompositionLocalProvider(LocalDensity provides Density(density.density, d.fontScale),
                LocalLab provides if (d.dark) LabDark else LabLight) {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Box(Modifier.width(d.width.dp)) { OptionCard(option, null, false) { opened = true } }
                    }
                }
            }
        }
        for (width in listOf(320, 360)) for (scale in listOf(1f, 2f)) for (dark in listOf(false, true)) {
            app.runOnIdle { display.value = Display(width, scale, dark) }
            app.waitForIdle()
            val duration = "55 min on board · ${if (transfer) "1 change" else "direct"}"
            for (time in listOf("14:03", "Arrive ~14:58")) {
                val layout = textLayout(time)
                assertEquals("$time at $width dp / $scale font / dark=$dark", 1, layout.lineCount)
                assertFalse("$time must not clip or overflow", layout.hasVisualOverflow)
            }
            assertFalse("Duration must remain fully laid out", textLayout(duration).hasVisualOverflow)
            val departure = app.onNodeWithText("14:03", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val arrival = app.onNodeWithText("Arrive ~14:58", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val summary = app.onNodeWithText(duration, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Arrival must not overlap departure", departure.bottom <= arrival.top)
            assertTrue("Duration must not overlap arrival", arrival.bottom <= summary.top)
            if (width == 320 && dark) {
                UiScreenshots.save("lab-plan-card-${if (transfer) "transfer" else "direct"}-font-${(scale * 100).toInt()}")
            }
            app.onNodeWithText("Woodstock → Unibell", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            app.onNodeWithText("Fare: confirm", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            // Return to the header before the next display configuration.
            app.onNodeWithText("14:03", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        app.onNodeWithTag("lab-option").performClick()
        app.runOnIdle { assertTrue("The card still opens its trip", opened) }
    }

    @Test fun transferTimesStayReadableAtNarrowWidthsAndLargeFonts() = verify(transfer = true)
    @Test fun directTimesStayReadableAtNarrowWidthsAndLargeFonts() = verify(transfer = false)
}
