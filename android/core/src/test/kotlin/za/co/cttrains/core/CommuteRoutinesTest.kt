package za.co.cttrains.core

import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate
import kotlinx.serialization.encodeToString

class CommuteRoutinesTest {
    private fun routine()=CommuteRoutine("r","School commute",SearchQuery("Salt River","Unibell","2026-10-09","08:00"),setOf(1,2,3,4,5),"cttrains-web",20,5,10,createdAt=1)
    @Test fun weekdaysSkipTheWeekendAndPastDepartures() {
        val now=JourneyComparison.requestedAt("2026-10-09","09:00")
        assertEquals(listOf("2026-10-12","2026-10-13","2026-10-14","2026-10-15","2026-10-16"),CommuteRoutines.dates(routine(),now).map { it.toString() })
        assertTrue(CommuteRoutines.dates(routine().copy(enabled=false),now).isEmpty())
    }
    @Test fun checkRunsBeforeWalkingBufferAndReminder() {
        val r=routine();val date=LocalDate.parse("2026-10-12")
        assertEquals(95*60_000L,CommuteRoutines.departure(r,date)-CommuteRoutines.checkAt(r,date))
    }
    @Test fun invalidDaysAndCoordinatesAreRejected() {
        listOf(routine().copy(days=emptySet()),routine().copy(days=setOf(8)),routine().copy(origin=WalkEndpoint("p","Home",GeoPoint(200.0,0.0)))).forEach { r->assertTrue(runCatching { CommuteRoutines.validate(r) }.isFailure) }
    }
    @Test fun homeUpdatesPreserveIdentityStationAndPhotoAndCollapseDuplicates() {
        val old=MarkedSpot("a","Home",null,GeoPoint(-33.9,18.5),"Home",photo="photo",createdAt=1,preferredStation="Unibell")
        val changed=old.copy(id="new",point=GeoPoint(-33.91,18.51),photo=null,preferredStation=null,createdAt=2)
        val rows=SavedAddresses.upsert(listOf(old,old.copy(id="duplicate")),changed)
        assertEquals(1,rows.size);assertEquals("a",rows[0].id);assertEquals("Unibell",rows[0].preferredStation);assertEquals("photo",rows[0].photo);assertEquals(changed.point,rows[0].point)
    }
    @Test fun campusIsOfferedAsSchoolAndRegularPinsRemainIndependent() {
        val campus=MarkedSpot("school","Campus",null,GeoPoint(-33.9,18.5),"Campus",createdAt=1)
        assertEquals("School",SavedAddresses.role(campus))
        val a=campus.copy(id="custom",name="Cafe",tag="Custom")
        assertEquals(2,SavedAddresses.upsert(listOf(campus),a).size)
    }
    @Test fun recurrenceAndOldSpotFieldsRoundTrip() {
        assertEquals(routine(),TripFiles.json.decodeFromString<CommuteRoutine>(TripFiles.json.encodeToString(routine())))
    }
    @Test fun sharingKeepsChronologicalStepsAndDoesNotShareGps() {
        val board=JourneyComparison.requestedAt("2026-10-12","08:00")
        val plan=DirectionsPlan("p","Work to Home",listOf(DirectionStep("walk",DirectionKind.WALK,"Walk to Salt River","",GeoPoint(-33.9,18.5),durationSeconds=600.0),DirectionStep("board",DirectionKind.BOARD,"Board train","",null,scheduledAt=board),DirectionStep("ride",DirectionKind.RIDE,"Get off at Unibell","",null,scheduledAt=board+1800000),DirectionStep("final",DirectionKind.WALK,"Walk to Home","",null,durationSeconds=300.0)),1,1)
        val shared=JourneySharing.itinerary(plan,TransportService.PRASA,"Me",2)
        assertEquals(board,shared.steps[0].at);assertEquals(board+2100000,shared.steps.last().at)
        assertEquals(shared,TripFiles.parse(TripFiles.json.encodeToString(shared)))
        assertFalse(TripFiles.json.encodeToString(shared).contains("latitude"))
    }
}
