package za.co.cttrains.app

import android.os.Bundle
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
        setContent {
            val model: TrainViewModel = viewModel()
            val state = model.state.collectAsStateWithLifecycle().value
            TrainTheme(state.preferences) { TrainApp(state, model) }
        }
    }
}
