package za.co.cttrains.app.scheduling

import android.content.Intent
import android.provider.CalendarContract
import androidx.core.net.toUri
import za.co.cttrains.core.*

fun calendarIntent(item: ScheduledJourney): Intent = Intent(Intent.ACTION_INSERT).apply {
    data = CalendarContract.Events.CONTENT_URI
    putExtra(CalendarContract.Events.TITLE, "Train: ${item.query.from} → ${item.query.to}")
    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, item.departureAt)
    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, item.arrivalAt)
    putExtra(CalendarContract.Events.EVENT_TIMEZONE, CapeTownZone.id)
    putExtra(CalendarContract.Events.EVENT_LOCATION, "${item.query.from} railway station, Cape Town, South Africa")
    putExtra(CalendarContract.Events.DESCRIPTION, journeyShareText(item.query, item.journey) + "\nSaved source: ${item.sourceId}. Calendar reminders are controlled by your calendar app.")
}

fun googleMapsJourneyUrl(query: SearchQuery): String = "https://www.google.com/maps/dir/".toUri().buildUpon()
    .appendQueryParameter("api", "1").appendQueryParameter("origin", "${query.from} railway station, Western Cape, South Africa")
    .appendQueryParameter("destination", "${query.to} railway station, Western Cape, South Africa")
    .appendQueryParameter("travelmode", "transit").build().toString()
