package za.co.cttrains.app

import androidx.compose.material3.MaterialTheme
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.runner.RunWith
import za.co.cttrains.app.ui.JourneyScheduleSheet
import za.co.cttrains.core.*

@RunWith(AndroidJUnit4::class)
class LeaveEstimateUiTest {
    @get:Rule val app=createAndroidComposeRule<MainActivity>()
    @Test fun leaveEstimateUsesTheChosenMappedWalkAndBoardingBuffer() {
        val model=ViewModelProvider(app.activity)[TrainViewModel::class.java]
        app.waitUntil(15000) { model.state.value.stations.any { it.mapped } }
        val station=WalkingRoutes.endpoint(model.state.value.stations.first { it.mapped })
        val home=WalkEndpoint("test:home","Test home",GeoPoint(station.point.latitude+.001,station.point.longitude))
        val walk=WalkingLeg(home,station,300.0,240.0,listOf(home.point,station.point),System.currentTimeMillis(),"Test walking fixture")
        val query=SearchQuery(station.label,"Destination",java.time.LocalDate.now(CapeTownZone).plusDays(1).toString(),"08:00")
        val journey=Journey(listOf(Leg("Test","123",listOf(Stop(station.label,"08:00"),Stop("Destination","08:30")))))
        app.runOnUiThread { app.activity.setContent { MaterialTheme { JourneyScheduleSheet(query,journey,model,initialAccess=20,accessEndpoint=home,initialWalk=walk) {} } } }
        app.onNodeWithText("Auto estimate leave time").performScrollTo().performClick()
        app.onNodeWithText("Leave by 07:51").performScrollTo().assertIsDisplayed()
        app.onNodeWithText("Save journey").performScrollTo().assertIsDisplayed()
        UiScreenshots.save("automatic-leave-time")
    }
}
