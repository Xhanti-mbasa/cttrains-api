@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package za.co.cttrains.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import za.co.cttrains.app.TrainState
import za.co.cttrains.app.TrainViewModel
import za.co.cttrains.core.*

private fun rand(cents: Long) = "R" + java.math.BigDecimal.valueOf(cents, 2).toPlainString()

@Composable
fun BudgetScreen(state: TrainState, model: TrainViewModel, onPlan: () -> Unit) {
    val uri = LocalUriHandler.current
    val route = SavedRoute(state.from, state.to)
    val valid = route.from.isNotBlank() && route.to.isNotBlank() && route.from != route.to
    val source = state.result?.entry?.takeIf { it.query.from == route.from && it.query.to == route.to }
    val prices = remember(source?.fares) { TripBudget.sourcePrices(source?.fares) }
    var ticket by rememberSaveable { mutableStateOf("single") }
    var quantity by rememberSaveable { mutableStateOf("1") }
    var travellers by rememberSaveable { mutableStateOf("1") }
    var budget by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf("") }
    var priceError by remember { mutableStateOf<String?>(null) }
    val correction = TripBudget.correction(state.preferences.fareCorrections, route, ticket)
    val price = correction?.cents ?: prices[ticket]
    val total = price?.let { runCatching { TripBudget.total(it, quantity.toInt(), travellers.toInt()) }.getOrNull() }
    val allowance = if (budget.isBlank()) null else TripBudget.parseCents(budget)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Trip budget", style = MaterialTheme.typography.headlineLarge)
        Text(if (valid) "${route.from} → ${route.to}" else "Choose a route in Plan first.", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onPlan) { Text(if (valid) "Change route, date or time" else "Choose route") }
        if (valid) {
            Button(onClick = model::search, enabled = !state.loading) { Text(if (state.loading) "Checking fares…" else "Check fares from CT Trains") }
            Text("Uses your Plan date and time: ${state.date} · ${state.time}. A failed refresh may show a labelled saved timetable.", style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Text("Ticket or pass", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TripBudget.tickets.forEach { kind ->
                val p = TripBudget.correction(state.preferences.fareCorrections, route, kind.id)?.cents ?: prices[kind.id]
                FilterChip(selected = ticket == kind.id, onClick = { ticket = kind.id }, label = { Text(kind.label + (p?.let { " · ${rand(it)}" } ?: " · price needed")) })
            }
        }
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(price?.let { rand(it) } ?: "No price supplied", style = MaterialTheme.typography.headlineMedium)
                Text(if (correction != null) "Your local price correction" else if (price != null) "CT Trains source fare" else "Search for trains to load source fares, or enter a price you have confirmed.")
                if (source != null) Text("${sourceLabel(source.sourceId)} · ${if (state.result?.restoredPlan == true) "saved journey snapshot" else if (state.result?.offline == true) "saved timetable (refresh unavailable)" else "fetched timetable"}", style = MaterialTheme.typography.labelSmall)
                val timestamp = correction?.updatedAt ?: source?.fetchedAt
                timestamp?.let { Text("${if (correction != null) "Corrected" else "Fetched"} ${DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH).withZone(CapeTownZone).format(Instant.ofEpochMilli(it))}", style = MaterialTheme.typography.labelSmall) }
                Button(onClick = { entered = price?.let { java.math.BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty(); priceError = null; editing = true }, enabled = valid) { Text(if (price == null) "Enter confirmed price" else "Correct price") }
                if (correction != null) TextButton(onClick = { model.setFareCorrection(route, ticket, null) }) { Text("Remove correction / use source price") }
            }
        }
        BudgetNumber("Tickets / passes per person (1–100)", quantity, { quantity = it }, decimal = false)
        BudgetNumber("Travellers (1–20)", travellers, { travellers = it }, decimal = false)
        BudgetNumber("Available budget in rand (optional)", budget, { budget = it })
        Text(total?.let { "Total: ${rand(it)}" } ?: "Enter a fare and valid ticket/traveller counts.", style = MaterialTheme.typography.headlineSmall)
        if (total != null && allowance != null) Text(if (allowance >= total) "Remaining: ${rand(allowance - total)}" else "Over budget by ${rand(total - allowance)}", color = if (allowance >= total) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        if (budget.isNotBlank() && allowance == null) Text("Enter a valid rand budget with up to two decimal places.", color = MaterialTheme.colorScheme.error)
        Text("Quantity counts the selected tickets or passes. Weekly/monthly fares cover return travel according to the source; check validity dates and ticket-office prices. This calculator does not infer how many passes your calendar needs. Walking, taxi and other costs are excluded unless included in your entered price.", style = MaterialTheme.typography.bodySmall)
        source?.fares?.let { Text("Source fare details", style = MaterialTheme.typography.titleMedium); Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("Corrections apply only to this route on this device. They do not change CT Trains' published fares. Removing a correction restores the available source value.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { uri.openUri("https://cttrains.co.za/fare-calculator.php") }) { Text("Open CT Trains fare calculator") }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text("Your corrected ticket price") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${TripBudget.tickets.first { it.id == ticket }.label} · ${route.from} → ${route.to}")
            BudgetNumber("Price in rand", entered, { entered = it })
            priceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { val cents = TripBudget.parseCents(entered); if (cents == null) priceError = "Use 0–100000 rand with up to two decimal places." else { model.setFareCorrection(route, ticket, cents); editing = false } }) { Text("Save locally") } }, dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } })
}

@Composable
private fun BudgetNumber(label: String, value: String, update: (String) -> Unit, decimal: Boolean = true) {
    OutlinedTextField(value, { if (it.length <= 9 && it.all { c -> c.isDigit() || (decimal && c in ".,") }) update(it) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number))
}
