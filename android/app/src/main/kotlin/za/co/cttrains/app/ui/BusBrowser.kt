@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package za.co.cttrains.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import za.co.cttrains.app.*
import za.co.cttrains.app.data.*
import za.co.cttrains.core.*

@Composable fun BusScreen(s: FeatureState,m: FeatureViewModel,onTrip: ()->Unit,onMap: (TransportService,String?)->Unit) {
    val enabled=listOf(TransportService.GOLDEN_ARROW,TransportService.MYCITI).filter(s.extras.beta::enabled)
    LaunchedEffect(enabled) { if(s.bus !in enabled && enabled.isNotEmpty()) m.chooseBus(enabled.first()) }
    BusBrowser(s,m::chooseBus,{ m.refresh(s.bus,true) },m::timetable,m::favoriteBus,m::correctFare,onTrip,onMap)
}

/** One scrolling surface: filters, details and results never compete for a fixed remainder. */
@Composable internal fun BusBrowser(
    s: FeatureState,onService: (TransportService)->Unit,onRefresh: ()->Unit,
    onTimetable: (BusRouteDocument,String)->Unit,onFavorite: (BusRouteDocument,String)->Unit,
    onFare: (TransportService,String,String,Long?)->Unit,onTrip: ()->Unit,onMap: (TransportService,String?)->Unit,
) {
    var query by rememberSaveable(s.bus) { mutableStateOf("") }
    var day by rememberSaveable { mutableStateOf(LocalDate.now(CapeTownZone).dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)) }
    var tab by rememberSaveable { mutableStateOf("Routes") }
    var filters by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    val source=s.snapshots[s.bus]
    val enabled=listOf(TransportService.GOLDEN_ARROW,TransportService.MYCITI).filter(s.extras.beta::enabled)
    val timetable=s.timetable?.takeIf { it.service==s.bus && it.day==day }
    val routes=remember(source,query) { source?.routes.orEmpty().filter { it.title.contains(query,true) || it.id.contains(query,true) } }
    val uri=LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Text("Bus schedules · beta",style=MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { enabled.forEach { service -> FilterChip(s.bus==service,{ onService(service) },label={ Text(service.title) }) } }
            if(enabled.isEmpty()) Text("Enable Golden Arrow or MyCiTi in Settings → Beta features.")
        }
        if(enabled.isNotEmpty()) {
            item {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("Routes","Fares").forEach { value -> FilterChip(tab==value,{ tab=value },label={ Text(value) }) }
                    TextButton(onClick=onRefresh,enabled=s.bus !in s.loading) { Text("Refresh") }
                }
                FeatureStatus(s)
            }
            item {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(tab=="Routes") DayPicker(day) { chosen ->
                        day=chosen
                        s.timetable?.takeIf { it.service==s.bus }?.let { onTimetable(it.route,chosen) }
                    }
                    TextButton(onClick={ filters=!filters }) { Text(if(filters) "Hide filters" else "Filters") }
                    TextButton(onClick={ details=!details }) { Text(if(details) "Hide source details" else "Source details") }
                }
                if(filters) OutlinedTextField(query,{ query=it.take(100) },label={ Text(if(tab=="Routes") "Search route or stop name" else "Find fare route / distance band") },singleLine=true,modifier=Modifier.fillMaxWidth())
                if(details) {
                    Text("Catalog: ${checked(source?.catalogueCheckedAt ?: 0)}\nFares: ${checked(source?.faresCheckedAt ?: 0)}",style=MaterialTheme.typography.bodySmall)
                    source?.let { Text((listOf(it.mapNote)+it.notes).joinToString("\n"),style=MaterialTheme.typography.bodySmall) }
                    TextButton(onClick={ uri.openUri(if(s.bus==TransportService.GOLDEN_ARROW) BusSources.GABS else BusSources.MYCITI_DOWNLOADS) }) { Text("Operator timetable website") }
                }
            }
            if(tab=="Routes") {
                if(timetable!=null) {
                    item {
                        Text(timetable.route.title,style=MaterialTheme.typography.titleMedium)
                        Text("${timetable.day} · ${if(s.timetableCached) "Saved" else "Checked"} ${checked(timetable.fetchedAt)}",style=MaterialTheme.typography.labelSmall)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick={ onService(s.bus) }) { Text("Choose another route") }
                            TextButton(onClick=onTrip) { Text("Save / share trip") }
                            TextButton(onClick={ onMap(s.bus,timetable.route.id) }) { Text("View map") }
                            TextButton(onClick={ uri.openUri(timetable.route.url) }) { Text("Published document") }
                        }
                    }
                    if(timetable.trips.isEmpty()) item { Text("No supported timed trips were found for $day. Check the published document.") }
                    items(timetable.trips.take(250),key={ it.id }) { BusDepartureCard(it) }
                    item { Text("Published day pattern. Confirm holiday exceptions and current boarding stops with the operator.",style=MaterialTheme.typography.bodySmall) }
                } else {
                    item { Text("$day · ${routes.size} routes",style=MaterialTheme.typography.labelLarge) }
                    if(routes.isEmpty()) item { Text("No matching published routes. Refresh or adjust the filter.") }
                    items(routes,key={ it.id }) { route ->
                        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(route.title,style=MaterialTheme.typography.titleMedium)
                            Text("Effective ${route.validFrom ?: "date not supplied"}${route.validUntil?.let { " – $it" }.orEmpty()}",style=MaterialTheme.typography.labelSmall)
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick={ onTimetable(route,day) },enabled=!s.timetableLoading) { Text("Load $day times") }
                                TextButton(onClick={ onFavorite(route,day) }) { Text(if(BusFavorite(s.bus,route.id,day) in s.extras.busFavorites) "Unsave" else "Save for refresh") }
                                TextButton(onClick={ onMap(s.bus,route.id) }) { Text(if(s.bus==TransportService.MYCITI) "Map route" else "Survey map") }
                                TextButton(onClick={ uri.openUri(route.url) }) { Text("Source") }
                            }
                        } }
                    }
                }
            } else fareItems(s,source,query,onFare)
        }
    }
}

@Composable private fun DayPicker(day: String,onDay: (String)->Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={ open=true }) { Text(day) }
        DropdownMenu(open,{ open=false }) { DayOfWeek.values().forEach { value ->
            val title=value.name.lowercase().replaceFirstChar(Char::uppercase)
            DropdownMenuItem(text={ Text(title) },onClick={ onDay(title);open=false })
        } }
    }
}

@Composable private fun BusDepartureCard(trip: BusTrip) {
    var expanded by rememberSaveable(trip.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Text("${trip.calls.first().time} → ${trip.calls.last().time}",style=MaterialTheme.typography.titleMedium)
        Text(trip.direction,style=MaterialTheme.typography.bodyMedium)
        val calls=if(expanded) trip.calls else listOf(trip.calls.first(),trip.calls.last())
        calls.forEach { call -> Text("${call.time} · ${call.name}${call.platform?.let { " · Platform $it" }.orEmpty()}",style=MaterialTheme.typography.bodySmall) }
        TextButton(onClick={ expanded=!expanded }) { Text(if(expanded) "Hide intermediate stops" else "Show ${trip.calls.size} timed stops") }
        if(expanded && trip.notes.isNotBlank()) Text(trip.notes,style=MaterialTheme.typography.bodySmall)
    } }
}

private fun LazyListScope.fareItems(s: FeatureState,source: BusSnapshot?,filter: String,onFare: (TransportService,String,String,Long?)->Unit) {
    item { Text("Published products · ${source?.fareDate ?: "effective date not supplied"}",style=MaterialTheme.typography.bodySmall) }
    items(source?.fares.orEmpty().filter { it.title.contains(filter,true) }.take(100)) { row ->
        PublishedFareCard(s,row,onFare)

    }
    item {
        if(s.bus==TransportService.GOLDEN_ARROW) {
            Text("Daily / cash single fares",style=MaterialTheme.typography.titleMedium)
            Text("Where no cash single is published, confirm the trip price with Golden Arrow. Weekly and monthly products cannot determine it.",style=MaterialTheme.typography.bodySmall)
        }
        BusPersonalBudget(s,onFare)
    }
}

@Composable private fun BusPersonalBudget(s: FeatureState,onFare: (TransportService,String,String,Long?)->Unit) {
    var route by rememberSaveable(s.bus) { mutableStateOf("") }
    var product by rememberSaveable(s.bus) { mutableStateOf("Cash single (personal quote)") }
    var price by rememberSaveable(s.bus) { mutableStateOf("") }
    var quantity by rememberSaveable(s.bus) { mutableStateOf("1") }
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Your budget & fare corrections",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(route,{ route=it.take(100) },label={ Text("Route or fare band") },modifier=Modifier.fillMaxWidth())
        OutlinedTextField(product,{ product=it.take(100) },label={ Text("Product · single, weekly…") },modifier=Modifier.fillMaxWidth())
        OutlinedTextField(price,{ price=it.take(12) },label={ Text("Price in rand") },modifier=Modifier.fillMaxWidth())
        OutlinedTextField(quantity,{ quantity=it.filter(Char::isDigit).take(4) },label={ Text("Number of these products") },modifier=Modifier.fillMaxWidth())
        val cents=TripBudget.parseCents(price);val count=quantity.toIntOrNull()?.takeIf { it in 1..1000 }
        if(cents!=null && count!=null) Text("Budget: ${money(cents*count)}",style=MaterialTheme.typography.titleMedium)
        Button(onClick={ onFare(s.bus,"personal:$route",product,cents) },enabled=cents!=null && route.isNotBlank() && product.isNotBlank()) { Text("Save personal fare correction") }
        Text("Corrections stay on this device. Choose a fare product explicitly; distance alone does not determine its price.",style=MaterialTheme.typography.bodySmall)
        s.extras.priceCorrections.filter { it.service==s.bus }.forEach { c ->
            Text("${c.product} · ${money(c.cents)} · ${c.fareId.substringAfter("personal:")}",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={ onFare(c.service,c.fareId,c.product,null) }) { Text("Remove correction") }
        }
    }
}

@Composable private fun PublishedFareCard(s: FeatureState,row: BusFareRow,onFare: (TransportService,String,String,Long?)->Unit) {
    var product by remember { mutableStateOf<String?>(null) }
    var value by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(row.title,style=MaterialTheme.typography.titleMedium)
        row.products.forEach { (name,cents) ->
            val corrected=s.extras.priceCorrections.lastOrNull { it.service==s.bus && it.fareId==row.id && it.product==name }
            TextButton(onClick={ product=name;value=String.format(java.util.Locale.ROOT,"%.2f",(corrected?.cents ?: cents)/100.0) }) {
                Text("$name · ${money(cents)}${corrected?.let { " · Your price ${money(it.cents)}" }.orEmpty()}")
            }
        }
        Text("Tap a product to correct your price",style=MaterialTheme.typography.labelSmall)
        if(row.rules.isNotBlank()) Text(row.rules,style=MaterialTheme.typography.bodySmall)
    } }
    product?.let { name -> AlertDialog(onDismissRequest={ product=null },title={ Text("Correct $name") },text={
        OutlinedTextField(value,{ value=it.take(12) },label={ Text("Your price in rand") },singleLine=true)
    },confirmButton={ TextButton(onClick={ onFare(s.bus,row.id,name,TripBudget.parseCents(value));product=null },enabled=TripBudget.parseCents(value)!=null) { Text("Save local correction") } },dismissButton={ TextButton(onClick={ product=null }) { Text("Cancel") } }) }
}
