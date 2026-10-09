package za.co.cttrains.app.ui

import androidx.compose.material3.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.encodeToString
import za.co.cttrains.app.FeatureViewModel
import za.co.cttrains.core.*

@Composable internal fun ShareJourneyButton(m: FeatureViewModel,make: ()->SafetyTrip) {
    val c=LocalContext.current
    var review by remember { mutableStateOf<SafetyTrip?>(null) }
    TextButton(onClick={ runCatching(make).onSuccess { review=it }.onFailure { m.message("Choose a complete timed journey before sharing.") } }) { Text("Share journey") }
    review?.let { trip->AlertDialog(onDismissRequest={ review=null },title={ Text("Share this journey?") },text={ androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) { Text(TripFiles.text(trip));Text("Only the itinerary is shared. This does not start live location sharing.",style=MaterialTheme.typography.bodySmall) } },confirmButton={ TextButton(onClick={ shareText(c,TripFiles.text(trip));review=null }) { Text("Share text") } },dismissButton={ androidx.compose.foundation.layout.Row { TextButton(onClick={ m.share(TripFiles.json.encodeToString(trip),"cttrip","Share journey");review=null }) { Text("Share trip file") };TextButton(onClick={ review=null }) { Text("Cancel") } } }) }
}
