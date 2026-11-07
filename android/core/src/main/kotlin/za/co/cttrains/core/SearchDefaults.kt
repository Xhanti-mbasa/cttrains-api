package za.co.cttrains.core

import java.time.LocalDateTime
import java.time.LocalTime

data class SearchDefaults(val date: String, val time: String) {
    companion object {
        /** Use one device-clock snapshot, including month/year rollover. 06:00 is a
         * search start, not a claim that a train runs at that time on every route. */
        fun at(now: LocalDateTime = LocalDateTime.now()): SearchDefaults =
            if (now.toLocalTime() >= LocalTime.of(19, 0))
                SearchDefaults(now.toLocalDate().plusDays(1).toString(), "06:00")
            else SearchDefaults(now.toLocalDate().toString(), TravelRules.defaultTime(now.toLocalTime()))
    }
}
