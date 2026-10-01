package za.co.cttrains.core

import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

@Serializable
data class ServiceAlert(val id: String, val group: String, val message: String, val reportedAt: Long,
    val time: String, val kind: String, val trains: List<String>)
@Serializable
data class AlertSnapshot(val sourceDate: String, val fetchedAt: Long, val alerts: List<ServiceAlert>)
data class PlatformNotice(val train: String, val station: String, val platform: String, val reportedAt: Long)

object ServiceAlerts {
    const val URL = "https://cttrains.co.za/status2.php"
    const val CACHE_AGE = 48L * 60 * 60 * 1000
    private val clock = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
    private val date = Regex("(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday),?\\s+[A-Za-z]+\\s+\\d{1,2},?\\s+\\d{4}")
    fun parse(html: String, fetchedAt: Long): AlertSnapshot {
        val document = Jsoup.parse(html)
        val dateText = document.select("span").map { it.text() }.firstOrNull { date.matches(it) }
            ?: throw ScheduleFormatException("The alerts page has changed. Check the source website.")
        val day = runCatching { LocalDate.parse(dateText.replace(",", ""), DateTimeFormatter.ofPattern("EEEE MMMM d yyyy", Locale.ENGLISH)) }
            .getOrElse { throw ScheduleFormatException("The alert date could not be read.") }
        val rows = document.select(".msg-row")
        if (rows.size > 200) throw ScheduleFormatException("The alerts response is too large.")
        val alerts = rows.map { row ->
            val message = row.selectFirst(".msg-text")?.text()?.trim().orEmpty()
            val time = row.selectFirst("span.font-mono")?.text()?.trim().orEmpty()
            val group = generateSequence(row.parent()) { it.parent() }.firstOrNull { it.selectFirst("h2") != null }
                ?.selectFirst("h2")?.text()?.trim().orEmpty()
            if (message.isBlank() || message.length > 4000 || group.isBlank() || !clock.matches(time))
                throw ScheduleFormatException("An alert could not be read safely. Check the source website.")
            val id = MessageDigest.getInstance("SHA-256").digest("$day|$group|$time|$message".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
            val reported = day.atTime(LocalTime.parse(time)).atZone(CapeTownZone).toInstant().toEpochMilli()
            val kind = when {
                Regex("\\b(?:no|without)\\s+(?:delays?|cancellations?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(message) -> "Information"
                Regex("cancel", RegexOption.IGNORE_CASE).containsMatchIn(message) -> "Cancellation"
                Regex("delay|additional travel time|failure", RegexOption.IGNORE_CASE).containsMatchIn(message) -> "Disruption"
                group.equals("Advisories", true) -> "Advisory"
                else -> "Information"
            }
            val trains = Regex("\\bT(\\d{3,6})(?!\\d)", RegexOption.IGNORE_CASE).findAll(message).map { it.groupValues[1] }.distinct().toList()
            ServiceAlert(id, group, message, reported, time, kind, trains)
        }.distinctBy { it.id }.sortedByDescending { it.reportedAt }
        if (alerts.isEmpty() && !Regex("no (?:current |new |service |status )*(?:updates|messages|alerts)|link.{0,40}(?:down|unavailable)", RegexOption.IGNORE_CASE).containsMatchIn(document.body().text()))
            throw ScheduleFormatException("No recognizable alert messages were returned. Check the source website.")
        return AlertSnapshot(day.toString(), fetchedAt, alerts)
    }
    fun usable(snapshot: AlertSnapshot, now: Long) = snapshot.fetchedAt <= now && now - snapshot.fetchedAt <= CACHE_AGE
    fun trainKey(train: String) = train.trim().uppercase().removePrefix("T")
    /** Only explicit boarding statements; never assign one train's platform to a connecting train. */
    fun platformNotices(snapshot: AlertSnapshot): List<PlatformNotice> = snapshot.alerts.flatMap { alert ->
        Regex("(?:embark|board)\\s+(T?\\d{3,6})\\s*from\\s+([A-Za-z][A-Za-z '\\-]{1,70}?)\\s+(?:station\\s+)?at\\s+platform\\s*(\\d{1,2}[A-Za-z]?)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
            .findAll(alert.message).map { m -> PlatformNotice(trainKey(m.groupValues[1]), m.groupValues[2].trim(), m.groupValues[3].uppercase(), alert.reportedAt) }.toList()
    }
    fun boardingNotice(snapshot: AlertSnapshot?, query: SearchQuery, stop: Stop, train: String, now: Long): PlatformNotice? {
        if (snapshot == null || snapshot.sourceDate != query.date || snapshot.fetchedAt > now || now - snapshot.fetchedAt > 10 * 60_000) return null
        val boarding = LocalDate.parse(query.date).atTime(LocalTime.parse(stop.time)).atZone(CapeTownZone).toInstant().toEpochMilli()
        return platformNotices(snapshot).filter { it.train == trainKey(train) && stationKey(it.station) == stationKey(stop.name) &&
            it.reportedAt <= now && it.reportedAt <= boarding && boarding - it.reportedAt <= 2 * 60 * 60_000 }.maxByOrNull { it.reportedAt }
    }
    fun relevant(alert: ServiceAlert, journey: Journey): Boolean = journey.legs.any { leg ->
        trainKey(leg.train) in alert.trains || alert.group.contains(leg.line.removeSuffix(" Line"), true) ||
            leg.stops.any { stop -> stop.name.length >= 4 && alert.message.contains(stop.name, true) }
    }
}
