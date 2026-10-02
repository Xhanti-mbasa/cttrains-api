package za.co.cttrains.core

import java.time.DayOfWeek
import java.time.LocalDate

/** The publisher's column is a trip. 'via', '--' and unknown annotations never become invented times. */
object GoldenTimetables {
    private val clock = Regex("^(\\d{2}:\\d{2})([a-z]*)$")
    private val weekdays = setOf(1,2,3,4,5)
    private val dayNames = DayOfWeek.values().associate { it.name.lowercase() to it.value }
    fun parse(text: String, document: BusRouteDocument, day: String, now: Long): BusTimetable {
        require(text.length <= 500000)
        val requested = dayNames[day.lowercase()] ?: error("Choose a timetable day.")
        val result = mutableListOf<BusTrip>()
        text.split('\u000c').filter { it.isNotBlank() }.forEachIndexed { pageIndex, page ->
            val effective = Regex("EFFECTIVE DATE:\\s*(\\d{4}/\\d{2}/\\d{2})").find(page)?.groupValues?.get(1)?.replace('/','-')
                ?: throw ScheduleFormatException("The Golden Arrow effective date could not be read.")
            LocalDate.parse(effective)
            val number = Regex("TIMETABLE NUMBER:\\s*([0-9 ]+)").find(page)?.groupValues?.get(1)?.filter(Char::isDigit).orEmpty()
            if (number.isBlank()) throw ScheduleFormatException("The timetable number could not be read.")
            val notes = Regex("(?m)^([a-z])\\s+-\\s+([^\\n]+)").findAll(page).associate { m ->
                val parsed=m.groupValues[2].split(',').map { name -> dayNames[name.trim().lowercase().removeSuffix("s")] }
                m.groupValues[1] to if(parsed.any { it==null }) emptySet() else parsed.filterNotNull().toSet()
            }
            val groups = mutableListOf<Pair<Set<Int>, MutableList<List<String>>>>()
            var currentDays = emptySet<Int>()
            page.lines().forEach { line ->
                val trimmed = line.trim()
                val days = when {
                    trimmed.startsWith("MONDAYS TO FRIDAYS") -> weekdays
                    trimmed == "SATURDAYS" -> setOf(6)
                    trimmed == "SUNDAYS" -> setOf(7)
                    trimmed.contains("NO SERVICE") -> emptySet()
                    else -> null
                }
                if (days != null) { currentDays = days; groups += days to mutableListOf() }
                else if (trimmed.startsWith('|') && currentDays.isNotEmpty()) {
                    val cells = trimmed.split('|').drop(1).dropLast(1).map(String::trim)
                    if (cells.size >= 3) groups.lastOrNull()?.second?.add(cells)
                }
            }
            val heading = page.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            groups.forEachIndexed { groupIndex, (allowedDays, rows) ->
                if (requested !in allowedDays || rows.isEmpty()) return@forEachIndexed
                val count = rows.maxOf { it.size } - 1
                (1..count).forEach { column ->
                    var allowed = true
                    val calls = rows.mapNotNull { cells ->
                        val token = cells.getOrNull(column).orEmpty(); val match = clock.matchEntire(token)
                        if(match==null) { if(token.isNotBlank() && token !in listOf("via","--")) allowed=false;return@mapNotNull null }
                        val annotation = match.groupValues[2]
                        if (annotation.isNotEmpty() && annotation.any { requested !in notes[it.toString()].orEmpty() }) allowed = false
                        BusCall(stationKey(cells[0]),cells[0],match.groupValues[1])
                    }.sortedBy { it.time }
                    // Cross-midnight columns cannot safely be ordered without explicit service-day offsets.
                    if (allowed && calls.size >= 2 && minutesBetween(calls.first().time,calls.last().time) <= 720)
                        result += BusTrip("$number:$pageIndex:$groupIndex:$column",number,heading,day,calls,"Effective $effective. Only published timed points are shown. 'Via' locations have no departure time; confirm holiday exceptions with Golden Arrow.",effective)
                }
            }
        }
        if (!text.contains("TIMETABLE NUMBER:") || !text.contains("EFFECTIVE DATE:")) throw ScheduleFormatException("This timetable format is unfamiliar. Use the downloaded source PDF.")
        return BusSources.validate(BusTimetable(TransportService.GOLDEN_ARROW,document,day,now,result.distinctBy { it.id }))
    }
}
