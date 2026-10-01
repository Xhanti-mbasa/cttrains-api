package za.co.cttrains.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class ScheduleFormatException(message: String) : IllegalStateException(message)

/** Parse only timetable content: advertisements and third-party scripts are never executed. */
object CtTrainsParser {
    private val validTime = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
    private val trainNumber = Regex("Train number:\\s*([A-Za-z0-9-]+)", RegexOption.IGNORE_CASE)
    private val holidayList = Regex("const\\s+holidays\\s*=\\s*\\[([^]]*)]")

    fun catalog(html: String): StationCatalog {
        val document = Jsoup.parse(html)
        val stations = document.select("select[name=fromStation] option[value]")
            .map { it.attr("value").trim() }.filter { it.isNotBlank() }.distinct().sorted()
        if (stations.size < 2) throw ScheduleFormatException("The station list has changed. Open CT Trains in your browser or try again later.")
        val holidays = holidayList.find(html)?.groupValues?.get(1)?.let { block ->
            Regex("\\d{4}-\\d{2}-\\d{2}").findAll(block).map { it.value }.toList()
        }.orEmpty()
        return StationCatalog(stations, holidays)
    }

    fun fares(html: String): String? = Jsoup.parse(html).select("p").map { it.text().trim() }
        .firstOrNull { it.startsWith("Fares:", ignoreCase = true) && it.length <= 800 }

    fun journeys(html: String, notBefore: String? = null): List<Journey> {
        val document = Jsoup.parse(html)
        val cards = document.select(".bg-white.rounded-lg.shadow-lg").filter { it.selectFirst("h2")?.text()?.startsWith("Option ") == true }
        if (cards.isEmpty()) {
            val text = document.body().text()
            if (Regex("no (?:train (?:times|schedules)|trains|(?:matching|available) (?:trains|schedules)|routes|journeys|schedules).*?(?:found|available|match|between)", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
                text.contains("no trains", ignoreCase = true) ||
                Regex("no\\s+(?:valid|suitable|matching|available)\\s+(?:itinerar|route|journey|train|schedule)", RegexOption.IGNORE_CASE).containsMatchIn(text)) return emptyList()
            throw ScheduleFormatException("CT Trains returned an unfamiliar page. Open the website or try again later.")
        }
        return cards.map { card ->
            val legs = card.select("div.relative.pl-6").map { block ->
                val line = block.selectFirst("span.font-semibold")?.text()?.trim().orEmpty()
                val train = trainNumber.find(block.text())?.groupValues?.get(1).orEmpty()
                val stops = block.select(".station-time").map { timeElement ->
                    val nameElement = timeElement.parent()?.selectFirst(".station-name")
                    val row = timeElement.parent()
                    val platformText = row?.attr("data-platform")?.trim()?.takeIf { it.isNotBlank() }
                        ?: row?.selectFirst(".platform, .station-platform")?.text()?.let { Regex("(?:Platform\\s*)?([0-9]{1,2}[A-Za-z]?)", RegexOption.IGNORE_CASE).matchEntire(it.trim())?.groupValues?.get(1) }
                    val platform = platformText?.takeIf { Regex("[0-9]{1,2}[A-Za-z]?").matches(it) }?.uppercase()
                    Stop(stationName(nameElement), timeElement.text().trim(), platform)
                }
                if (line.isBlank() || train.isBlank() || stops.size < 2 || stops.any { it.name.isBlank() || !validTime.matches(it.time) })
                    throw ScheduleFormatException("A timetable could not be read safely. Check this journey on the website.")
                Leg(line, train, stops)
            }
            if (legs.isEmpty() || legs.zipWithNext().any { (a, b) -> a.arrival.name != b.departure.name })
                throw ScheduleFormatException("A train connection could not be read safely. Check this journey on the website.")
            Journey(legs)
        }.distinct().filter { notBefore == null || it.departure >= notBefore }.sortedBy { it.departure }
    }

    private fun stationName(element: Element?): String {
        if (element == null) return ""
        return element.selectFirst("span.font-medium")?.text()?.trim() ?: element.clone().apply { select(".platform, .station-platform").remove() }.text().trim()
    }
}
