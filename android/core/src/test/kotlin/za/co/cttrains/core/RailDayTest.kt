package za.co.cttrains.core

import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class RailDayTest {
    private fun direct(train: String, vararg stops: Pair<String, String>) = Journey(listOf(Leg("Central line", train, stops.map { Stop(it.first, it.second) })))

    @Test fun pagingWalksTheWholeDayAndAlwaysMovesForward() {
        // Four trains per page, every 20 minutes from 04:10 to 22:30: paging must reach the last train.
        val all = (0..55).map { i -> LocalTime.of(4, 10).plusMinutes(20L * i) }.filter { it <= LocalTime.of(22, 30) }
            .map { direct("${2000 + it.hour * 100 + it.minute}", "Unibell" to it.toString(), "Cape Town" to it.plusMinutes(40).toString()) }
        var slot: String? = RailDay.FIRST_SLOT
        val pages = mutableListOf<List<Journey>>()
        var guard = 0
        while (slot != null && guard++ < 100) {
            val from = LocalTime.parse(slot)
            val page = all.filter { !LocalTime.parse(it.departure).isBefore(from) }.take(4)
            pages += page
            slot = RailDay.nextSlot(slot, page)
        }
        val merged = RailDay.merge(pages)
        assertEquals(all.map { it.departure }, merged.map { it.departure })
        assertTrue("paging stays polite", pages.size < 30)
        assertNull(RailDay.nextSlot("23:45", listOf(direct("1", "A" to "23:50", "B" to "23:59"))))
        assertNull(RailDay.nextSlot("10:00", emptyList()))
        // All four trains inside the current slot: still advance.
        val crowded = (0..3).map { direct("$it", "A" to "10:0$it", "B" to "10:30") }
        assertEquals("10:15", RailDay.nextSlot("10:00", crowded))
        assertEquals("03:00", RailDay.slotAtOrBefore("01:10"))
        assertEquals("08:30", RailDay.slotAtOrBefore("08:44"))
    }

    @Test fun mergeRemovesOverlappingPagesAndSorts() {
        val a = direct("2501", "Cape Town" to "05:20", "Bellville" to "06:01")
        val b = direct("2503", "Cape Town" to "04:45", "Bellville" to "05:26")
        assertEquals(listOf("2503", "2501"), RailDay.merge(listOf(listOf(a, b), listOf(a))).map { it.legs.single().train })
    }

    @Test fun destinationsFollowTheLinesThroughAStation() {
        val catalog = listOf("Cape Town", "Unibell", "Bellville", "Chris Hani", "Kapteinsklip", "Simonstown", "Retreat", "Strand", "Wellington", "Muldersvlei", "Fish Hoek")
        assertEquals(setOf("Cape Town", "Chris Hani", "Kapteinsklip", "Bellville"), StationBoard.destinations("Unibell", catalog).toSet())
        assertEquals(setOf("Cape Town", "Simonstown", "Retreat"), StationBoard.destinations("Fish Hoek", catalog).toSet())
        assertFalse("Cape Town" in StationBoard.destinations("Cape Town", catalog))
        assertTrue(StationBoard.destinations("Nowhere", catalog).isEmpty())
    }

    @Test fun boardKeepsOneEntryPerTrainWithTheLongestView() {
        val toCity = direct("3504", "Unibell" to "07:10", "Belhar" to "07:13", "Cape Town" to "07:55")
        val partOfTransfer = Journey(listOf(
            Leg("Central line", "3504", listOf(Stop("Unibell", "07:10"), Stop("Belhar", "07:13"))),
            Leg("Northern line", "2601", listOf(Stop("Belhar", "07:20"), Stop("Bellville", "07:40"))),
        ))
        val fromEarlier = direct("3600", "Nyanga" to "06:50", "Unibell" to "07:30", "Chris Hani" to "08:10")
        val board = StationBoard.departures("Unibell", listOf(partOfTransfer, toCity, fromEarlier))
        assertEquals(listOf("3504", "3600"), board.map { it.train })
        assertTrue(board[0].throughToEnd)
        assertEquals("Cape Town", board[0].towards)
        assertEquals(listOf("Unibell", "Chris Hani"), board[1].stops.map { it.name })
        assertEquals(listOf("3504"), StationBoard.within(board, LocalTime.of(7, 0), 20).map { it.train })
        assertEquals(2, StationBoard.within(board, LocalTime.of(7, 0)).size)
    }

    @Test fun trainNumbersAreMatchedLoosely() {
        val board = listOf(StationDeparture("2501", "Northern line", "04:45", null, listOf(Stop("Cape Town", "04:45"), Stop("Bellville", "05:26")), true))
        assertEquals("2501", StationBoard.find("T2501", board)?.train)
        assertEquals("2501", StationBoard.find(" 2501 ", board)?.train)
        assertNull(StationBoard.find("2502", board))
        assertNull(StationBoard.find("bus", board))
        assertNull(StationBoard.trainKey("Cape Town"))
    }
}
