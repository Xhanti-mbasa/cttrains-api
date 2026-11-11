package za.co.cttrains.app.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.content.Intent
import android.widget.RemoteViews
import android.view.View
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import za.co.cttrains.app.MainActivity
import za.co.cttrains.app.R
import za.co.cttrains.app.SmartCardsModel
import za.co.cttrains.app.data.FeatureStore
import za.co.cttrains.app.data.LocalTransitStore
import za.co.cttrains.core.*

open class TravelWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        TravelWidgets.enqueue(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        TravelWidgets.enqueue(context)
    }
}

class SmartCardsWidgetProvider : TravelWidgetProvider()
class NextTripWidgetProvider : TravelWidgetProvider()

class TravelWidgetWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        TravelWidgets.update(applicationContext)
        Result.success()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Result.retry()
    }
}

object TravelWidgets {
    private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(CapeTownZone)
    private val dateTime = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(CapeTownZone)

    fun enqueue(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val installed = manager.getAppWidgetIds(ComponentName(context, SmartCardsWidgetProvider::class.java)).isNotEmpty() ||
            manager.getAppWidgetIds(ComponentName(context, NextTripWidgetProvider::class.java)).isNotEmpty()
        if (!installed) return
        WorkManager.getInstance(context).enqueueUniqueWork("travel-widgets", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TravelWidgetWorker>().build())
    }

    suspend fun snapshot(store: LocalTransitStore): SmartCardsSnapshot? =
        store.readLocal(SmartCardsModel.SNAPSHOT_KEY)?.takeIf { it.isNotBlank() }?.let {
            runCatching { TripFiles.json.decodeFromString<SmartCardsSnapshot>(it) }.getOrNull()
        }

    fun pin(context: Context, smart: Boolean): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        return manager.requestPinAppWidget(ComponentName(context,
            if (smart) SmartCardsWidgetProvider::class.java else NextTripWidgetProvider::class.java), null, null)
    }

    suspend fun update(context: Context) {
        val featureStore = FeatureStore(context)
        val store = featureStore.store
        store.initialize()
        val preferences = store.preferences.first()
        val beta = featureStore.extras().beta
        val now = System.currentTimeMillis()
        val snapshot = snapshot(store)?.takeIf {
            it.profileId == preferences.profileId && it.locationAt in (now - SmartCards.WIDGET_MAX_AGE)..(now + 30_000) &&
                it.updatedAt in (now - SmartCards.WIDGET_MAX_AGE)..(now + 30_000)
        }
        val cards = snapshot?.cards.orEmpty().filter { it.sourceId == preferences.sourceId }
            .let { SmartCards.rank(it, now) }
        val next = SmartCards.nextTrip(store.journeys.first().filter { it.profileId == preferences.profileId }, now)
        val manager = AppWidgetManager.getInstance(context)
        for (smart in listOf(true, false)) {
            val provider = if (smart) SmartCardsWidgetProvider::class.java else NextTripWidgetProvider::class.java
            for (id in manager.getAppWidgetIds(ComponentName(context, provider))) {
                val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, if (smart) 300 else 160)
                val views = RemoteViews(context.packageName, R.layout.travel_widget)
                views.setTextViewText(R.id.widget_title, if (smart) "Smart Cards · experimental" else "Next trip · experimental")
                views.removeAllViews(R.id.widget_rows)
                val needsSettings = !beta.appWidgets || (smart && !beta.smartCards)
                val open = intent(context, id, "open").putExtra("widget_settings", needsSettings)
                views.setOnClickPendingIntent(R.id.widget_root, pending(context, id, "open", open))
                val refresh = intent(context, id, "refresh").putExtra("smart_refresh", !needsSettings)
                    .putExtra("widget_settings", needsSettings)
                views.setOnClickPendingIntent(R.id.widget_refresh, pending(context, id, "refresh", refresh))
                views.setTextViewText(R.id.widget_refresh, if (needsSettings) "Enable in Settings" else "Open app / refresh")
                views.setViewVisibility(R.id.widget_refresh, if (height < 200) View.GONE else View.VISIBLE)
                views.setTextViewText(R.id.widget_updated, "Scheduled times · updated ${clock.format(Instant.ofEpochMilli(now))}")
                when {
                    !beta.appWidgets -> row(context, views, "App widgets are off", "Enable experimental app widgets in Settings.")
                    smart && !beta.smartCards -> row(context, views, "Smart Cards are off", "Turn on Smart Cards in Settings.")
                    smart && cards.isEmpty() -> row(context, views, "Find your next ride", "Open CT Trains to refresh your location and suggestions.")
                    smart -> {
                        val count = if (height < 200) 1 else if (height < 280) 2 else 3
                        cards.take(count).forEachIndexed { index, card ->
                            val action = intent(context, id, "card-$index").putExtra("smart_refresh", true)
                            row(context, views, "${card.query.from} → ${card.destinationLabel}",
                                "${card.journey.departure} → ${card.journey.arrival} · ${card.journey.durationMinutes} min on train",
                                pending(context, id, "card-$index", action))
                        }
                        views.setTextViewText(R.id.widget_updated,
                            "Last app location ${clock.format(Instant.ofEpochMilli(snapshot!!.locationAt))} · open for current options")
                    }
                    next == null -> row(context, views, "No upcoming journey", "Save a journey in CT Trains to show it here.")
                    else -> {
                        val action = intent(context, id, "journey").putExtra("journey_id", next.id)
                        row(context, views, "${next.query.from} → ${next.query.to}",
                            "${dateTime.format(Instant.ofEpochMilli(next.departureAt))} · arrival ${next.journey.arrival}",
                            pending(context, id, "journey", action))
                    }
                }
                manager.updateAppWidget(id, views)
            }
        }
    }

    private fun row(context: Context, views: RemoteViews, title: String, subtitle: String, action: PendingIntent? = null) {
        val child = RemoteViews(context.packageName, R.layout.travel_widget_row)
        child.setTextViewText(R.id.widget_row_title, title)
        child.setTextViewText(R.id.widget_row_subtitle, subtitle)
        if (action != null) child.setOnClickPendingIntent(R.id.widget_row_root, action)
        views.addView(R.id.widget_rows, child)
    }

    private fun intent(context: Context, id: Int, action: String) = Intent(context, MainActivity::class.java)
        .setData(Uri.Builder().scheme("cttrains").authority("widget").appendPath(id.toString()).appendPath(action).build())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun pending(context: Context, id: Int, action: String, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, "$id:$action".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
