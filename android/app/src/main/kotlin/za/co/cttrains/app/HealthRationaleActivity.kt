package za.co.cttrains.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class HealthRationaleActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState);setContent {
        MaterialTheme { Surface { Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("CT Trains · local walking observations",style=MaterialTheme.typography.headlineSmall)
            Text("Optional read-only steps access annotates walking segments you explicitly start and complete. Only those segment time windows are read while CT Trains is visible. We do not read heart rate, sleep or your full health history, write health records, or upload health data.")
            Text("Aggregate step counts stay encrypted on this device. They may be included if you explicitly export your encrypted profile backup. No steps or health records are included in trip sharing, chat, nearby presence or WhatsApp. Resetting local data removes observations. You can turn the feature off in Settings or revoke Steps access in Health Connect at any time.")
            Text("Samsung Health must sync watch activity to Health Connect first. Counts may arrive later or be unavailable. Walking estimates use routed distance and measured duration; a step count alone does not determine travel time.")
            Button(onClick={ finish() }) { Text("Close") }
        } } }
    } }
}
