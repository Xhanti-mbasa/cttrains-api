package za.co.cttrains.core

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class SearchDefaultsTest {
    @Test fun daytimeUsesTodayAndTheNextSupportedTimetableInterval() {
        assertEquals(SearchDefaults("2026-11-06", "12:15"), SearchDefaults.at(LocalDateTime.parse("2026-11-06T12:07")))
    }
    @Test fun cutoffStartsTomorrowAtSix() {
        assertEquals(SearchDefaults("2026-11-06", "19:00"), SearchDefaults.at(LocalDateTime.parse("2026-11-06T18:59")))
        assertEquals(SearchDefaults("2026-11-07", "06:00"), SearchDefaults.at(LocalDateTime.parse("2026-11-06T19:00")))
        assertEquals(SearchDefaults("2026-11-07", "06:00"), SearchDefaults.at(LocalDateTime.parse("2026-11-06T23:59")))
    }
    @Test fun rolloverHandlesYearAndLeapDay() {
        assertEquals(SearchDefaults("2027-01-01", "06:00"), SearchDefaults.at(LocalDateTime.parse("2026-12-31T20:00")))
        assertEquals(SearchDefaults("2028-02-29", "06:00"), SearchDefaults.at(LocalDateTime.parse("2028-02-28T20:00")))
    }
    @Test fun tomorrowIsNotSilentlyChangedWhenThereAreNoSundayTrains() {
        assertEquals(SearchDefaults("2026-11-08", "06:00"), SearchDefaults.at(LocalDateTime.parse("2026-11-07T20:00")))
    }
    @Test fun earlyMorningRespectsTheSourceMinimumTime() {
        assertEquals(SearchDefaults("2026-11-06", "03:00"), SearchDefaults.at(LocalDateTime.parse("2026-11-06T00:01")))
    }
}
