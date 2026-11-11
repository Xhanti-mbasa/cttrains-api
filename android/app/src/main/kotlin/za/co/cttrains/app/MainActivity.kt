package za.co.cttrains.app

import android.os.Bundle
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import za.co.cttrains.app.scheduling.RideFollowUps
import za.co.cttrains.core.RideOutcome
import za.co.cttrains.app.ui.TrainApp
import za.co.cttrains.app.ui.TrainTheme
import za.co.cttrains.app.ui.lab.LabApp
import za.co.cttrains.app.ui.lab.LabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[TrainViewModel::class.java]
        val features = ViewModelProvider(this)[FeatureViewModel::class.java]
        handleWidgetIntent(model, intent)
        features.importIntent(intent)
        handleRideIntent(model, intent)
        model.openScheduled(intent.getStringExtra("journey_id"))
        if(intent.getBooleanExtra("journeys",false)) model.edit { it.copy(requestedScreen="upcoming") }
        if(intent.getBooleanExtra("community",false)) model.openCommunity()
        if(intent.getBooleanExtra("directions",false)) model.openDirections()
        if(intent.getBooleanExtra("app_updates",false)) model.openAppUpdates()
        setContent {
            val state = model.state.collectAsStateWithLifecycle().value
            // The Lab edition (separate package, data and widgets) gets its own UI root; the regular edition is unchanged.
            if (BuildConfig.EXPERIMENTAL_EDITION) LabTheme(state.preferences) { LabApp(state, model) }
            else TrainTheme(state.preferences) { TrainApp(state, model) }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val model = ViewModelProvider(this)[TrainViewModel::class.java]
        handleWidgetIntent(model, intent)
        handleRideIntent(model, intent)
        model.openScheduled(intent.getStringExtra("journey_id"))
        ViewModelProvider(this)[FeatureViewModel::class.java].importIntent(intent)
        if(intent.getBooleanExtra("journeys",false)) ViewModelProvider(this)[TrainViewModel::class.java].edit { it.copy(requestedScreen="upcoming") }
        if(intent.getBooleanExtra("community",false)) ViewModelProvider(this)[TrainViewModel::class.java].openCommunity()
        if(intent.getBooleanExtra("directions",false)) ViewModelProvider(this)[TrainViewModel::class.java].openDirections()
        if(intent.getBooleanExtra("app_updates",false)) ViewModelProvider(this)[TrainViewModel::class.java].openAppUpdates()
    }
    private fun handleRideIntent(model: TrainViewModel, intent: Intent) {
        when (intent.action) {
            OPEN_RIDES -> model.openRideHistory(intent.getStringExtra(RideFollowUps.ID))
            RideFollowUps.REPLAN -> {
                val id = intent.getStringExtra(RideFollowUps.ID) ?: return
                val token = intent.getStringExtra(RideFollowUps.TOKEN) ?: return
                model.answerRide(id, token, RideOutcome.MISSED)
            }
        }
    }

    private fun handleWidgetIntent(model: TrainViewModel, intent: Intent) {
        if (intent.getBooleanExtra("widget_settings", false)) model.edit { it.copy(requestedScreen = "settings") }
        if (intent.getBooleanExtra("smart_refresh", false)) {
            model.openMap()
            model.locateOnce()
        }
    }

    companion object {
        const val OPEN_RIDES = "za.co.cttrains.OPEN_RIDES"
    }

    override fun onStop() {
        ViewModelProvider(this)[SmartCardsModel::class.java].pause()
        ViewModelProvider(this)[TrainViewModel::class.java].apply { forgetLocation(); offlineMaps.foreground(false) }
        super.onStop()
    }
    override fun onStart() { super.onStart(); ViewModelProvider(this)[TrainViewModel::class.java].offlineMaps.foreground(true) }
}
