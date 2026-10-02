package za.co.cttrains.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Each column in each table block is one trip. Continuation pages inherit publisher metadata. */
object GoldenTimetables {
    private val clock=Regex("^(\\d{2}:\\d{2})([a-z]*)$")
    private val weekdays=setOf(1,2,3,4,5)
    private val dayNames=DayOfWeek.values().associate { it.name.lowercase() to it.value }
    private data class Page(val index: Int,val text: String,val number: String,val effective: String)
    fun parse(text: String,document: BusRouteDocument,day: String,now: Long): BusTimetable {
        require(text.length<=500000)
        val requested=dayNames[day.lowercase()] ?: error("Choose a timetable day.")
        var number="";var effective=""
        val pages=text.split('\u000c').filter(String::isNotBlank).mapIndexed { index,page ->
            Regex("EFFECTIVE DATE:\\s*(\\d{4}/\\d{2}/\\d{2})").find(page)?.groupValues?.get(1)?.let { effective=it.replace('/','-');LocalDate.parse(effective) }
            Regex("TIMETABLE NUMBER:\\s*([0-9 ]+)").find(page)?.groupValues?.get(1)?.let { number=it.filter(Char::isDigit) }
            if(number.isBlank() || effective.isBlank()) throw ScheduleFormatException("The Golden Arrow effective date or timetable number could not be read.")
            Page(index,page,number,effective)
        }
        val notes=pages.groupBy { it.number to it.effective }.mapValues { (_,parts) ->
            Regex("(?m)^([a-z])\\s+-\\s+([^\\n]+)").findAll(parts.joinToString("\n") { it.text }).associate { m ->
                val parsed=m.groupValues[2].split(',').map { name->dayNames[name.trim().lowercase().removeSuffix("s")] }
                m.groupValues[1] to if(parsed.any { it==null }) emptySet() else parsed.filterNotNull().toSet()
            }
        }
        val result=mutableListOf<BusTrip>();var days=emptySet<Int>();var previousNumber=""
        pages.forEach { page ->
            if(page.number!=previousNumber) days=emptySet()
            previousNumber=page.number
            val rows=mutableListOf<List<String>>();var block=0
            val heading=page.text.lineSequence().firstOrNull(String::isNotBlank)?.trim().orEmpty()
            fun flush() {
                if(requested in days && rows.isNotEmpty()) {
                    val count=rows.maxOf { it.size }-1
                    for(column in 1..count) {
                        var allowed=true
                        val calls=rows.mapNotNull { cells ->
                            val token=cells.getOrNull(column).orEmpty();val match=clock.matchEntire(token)
                            if(match==null) { if(token.isNotBlank() && token !in listOf("via","--")) allowed=false;return@mapNotNull null }
                            if(runCatching { LocalTime.parse(match.groupValues[1]) }.isFailure) { allowed=false;return@mapNotNull null }
                            if(match.groupValues[2].any { requested !in notes[page.number to page.effective]?.get(it.toString()).orEmpty() }) allowed=false
                            BusCall(stationKey(cells[0]),cells[0],match.groupValues[1])
                        }.sortedBy { it.time }
                        // Publisher rows can be out of travel order; clocks order calls inside this one column only.
                        if(allowed && calls.size>=2 && minutesBetween(calls.first().time,calls.last().time)<=720)
                            result+=BusTrip("${page.number}:${page.index}:$block:$column",page.number,heading,day,calls,"Effective ${page.effective}. Published timed points only; via locations have no departure time. Confirm holiday exceptions with Golden Arrow.",page.effective)
                    }
                }
                rows.clear();block++
            }
            page.text.lines().forEach { line ->
                val value=line.trim()
                val nextDays=when {
                    value.startsWith("MONDAYS TO FRIDAYS")->weekdays
                    value=="SATURDAYS"->setOf(6)
                    value=="SUNDAYS"->setOf(7)
                    value.contains("NO SERVICE")->emptySet()
                    else->null
                }
                when {
                    nextDays!=null -> { flush();days=nextDays }
                    value.startsWith("|-") -> flush()
                    value.startsWith('|') -> {
                        val cells=value.split('|').drop(1).dropLast(1).map(String::trim)
                        if(cells.size>=3 && cells[0].isNotBlank()) {
                            if(rows.any { it[0]==cells[0] }) flush()
                            rows+=cells
                        }
                    }
                }
            }
            flush()
        }
        if(pages.isEmpty()) throw ScheduleFormatException("This timetable format is unfamiliar. Use the source PDF.")
        return BusSources.validate(BusTimetable(TransportService.GOLDEN_ARROW,document,day,now,result.distinctBy { it.id }))
    }
}
