package za.co.cttrains.app

import android.os.Bundle
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import za.co.cttrains.app.ui.TrainApp
import za.co.cttrains.app.ui.TrainTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[TrainViewModel::class.java]
        model.openScheduled(intent.getStringExtra("journey_id"))
        setContent {
            val state = model.state.collectAsStateWithLifecycle().value
            TrainTheme(state.preferences) { TrainApp(state, model) }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ViewModelProvider(this)[TrainViewModel::class.java].openScheduled(intent.getStringExtra("journey_id"))
    }
    override fun onStop() {
        ViewModelProvider(this)[TrainViewModel::class.java].apply { forgetLocation(); offlineMaps.foreground(false) }
        super.onStop()
    }
    override fun onStart() { super.onStart(); ViewModelProvider(this)[TrainViewModel::class.java].offlineMaps.foreground(true) }
}
