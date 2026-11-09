package za.co.cttrains.core

import org.junit.Test
import org.junit.Assert.*
import kotlinx.serialization.encodeToString

class DirectionsTest {
    private val a=GeoPoint(-33.95,18.5)
    private val b=GeoPoint(-33.949,18.5)
    private val now=1791216000000L
    private fun plan(kind: DirectionKind=DirectionKind.WALK)=DirectionsPlan("trip","Home → Station",listOf(DirectionStep("walk",kind,"Walk to Station","Home → Station",b,listOf(a,b),listOf(WalkInstruction(a,"Continue on Albert Road",0.0)),120.0,120.0,now-10000)),now,now)
    @Test fun accurateLocationUsesWalkingGeometryAndInstruction() {
        val s=DirectionsSession(plan(),startedAt=now)
        val g=DirectionsEngine.guidance(s,DirectionFix(a,10.0,now),now)
        assertEquals("Continue on Albert Road",g.title);assertEquals(120,g.remainingMeters);assertEquals(2,g.minutes);assertFalse(g.nearTarget)
    }
    @Test fun coarseStaleOrFutureFixCannotClaimArrival() {
        val s=DirectionsSession(plan(),startedAt=now)
        for(f in listOf(DirectionFix(b,150.0,now),DirectionFix(b,10.0,now-46000),DirectionFix(b,10.0,now+1))) {
            val g=DirectionsEngine.guidance(s,f,now);assertFalse(g.nearTarget);assertFalse(g.fresh)
        }
    }
    @Test fun rideClockAndProximityNeverAdvanceTransitWithoutConfirmation() {
        val s=DirectionsSession(plan(DirectionKind.RIDE),startedAt=now)
        val g=DirectionsEngine.guidance(s,DirectionFix(b,10.0,now),now)
        assertTrue(g.nearTarget);assertTrue(g.detail.contains("has passed"));assertEquals(0,s.index)
        assertTrue(DirectionsEngine.advance(s).finished)
    }
    @Test fun pauseSuppressesProgressAndExpiryIsBounded() {
        val s=DirectionsSession(plan(),startedAt=now,paused=true)
        assertEquals("Directions paused",DirectionsEngine.guidance(s,DirectionFix(b,10.0,now),now).title)
        assertFalse(DirectionsEngine.alive(s,now+DirectionsEngine.MAX_SESSION_MS));assertFalse(DirectionsEngine.alive(s,now-1))
    }
    @Test fun offRouteDoesNotInventDirections() {
        val s=DirectionsSession(plan(),startedAt=now)
        val g=DirectionsEngine.guidance(s,DirectionFix(GeoPoint(-33.96,18.5),10.0,now),now)
        assertTrue(g.offRoute);assertNull(g.minutes);assertFalse(g.nearTarget)
    }
    @Test fun stepsAndSessionCanBeRestoredAndWalkParserRetainsManeuvers() {
        val s=DirectionsSession(plan(),startedAt=now,index=0,promote=false)
        assertEquals(s,TripFiles.json.decodeFromString<DirectionsSession>(TripFiles.json.encodeToString(s)))
        val raw="""{"code":"Ok","waypoints":[{"distance":0},{"distance":0}],"routes":[{"distance":120,"duration":120,"geometry":{"type":"LineString","coordinates":[[18.5,-33.95],[18.5,-33.949]]},"legs":[{"steps":[{"distance":120,"name":"Albert Road","maneuver":{"type":"depart","modifier":"straight","location":[18.5,-33.95]}},{"distance":0,"maneuver":{"type":"arrive","location":[18.5,-33.949]}}]}]}]}"""
        val leg=WalkingRoutes.parse(raw,WalkEndpoint("a","Home",a),WalkEndpoint("b","Station",b),now)
        assertEquals(2,leg.instructions.size);assertEquals(120.0,leg.instructions.last().distanceFromStart,0.0)
        assertTrue(WalkingRoutes.url(leg.from,leg.to).contains("steps=true"))
    }
    @Test fun oldWalkingCacheStillDecodesWithoutManeuvers() {
        val raw="""{"from":{"id":"a","label":"Home","point":{"latitude":-33.95,"longitude":18.5}},"to":{"id":"b","label":"Station","point":{"latitude":-33.949,"longitude":18.5}},"distanceMeters":120,"durationSeconds":120,"points":[],"fetchedAt":1}"""
        assertTrue(TripFiles.json.decodeFromString<WalkingLeg>(raw).instructions.isEmpty())
    }
}
