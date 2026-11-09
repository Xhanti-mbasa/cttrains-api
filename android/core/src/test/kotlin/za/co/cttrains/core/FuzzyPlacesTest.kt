package za.co.cttrains.core

import org.junit.Assert.*
import org.junit.Test

class FuzzyPlacesTest {
    @Test fun partialAndCasualQueriesFindPlaces() {
        val names=listOf("Bellville","Cape Town","Unibell","Bella Vista","Erica Square","Salt River")
        assertEquals("Bellville",FuzzyPlaces.rank("bell",names) { it }.first())
        assertEquals("Bella Vista",FuzzyPlaces.rank("bella v",names) { it }.first())
        assertEquals("Cape Town",FuzzyPlaces.rank("cape  town!",names) { it }.first())
        assertEquals("Erica Square",FuzzyPlaces.rank("ercsq",names) { it }.first())
        assertEquals(1000,FuzzyPlaces.score("SALT RIVER","Salt River"))
        assertNull(FuzzyPlaces.score("zzz","Bellville"))
    }
    @Test fun accentsAndWordOrderAreIgnored() {
        assertNotNull(FuzzyPlaces.score("square erica","Erica Square"))
        assertNotNull(FuzzyPlaces.score("cafe","Café Street"))
    }
    @Test fun nearestStopsAreSortedAndLimited() {
        val here=GeoPoint(-33.88,18.70)
        val stops=listOf(BusStopPoint("far","Far",GeoPoint(-33.90,18.70),emptyList(),"s"),BusStopPoint("near","Near",GeoPoint(-33.881,18.70),emptyList(),"s"))
        val result=NearbyStops.nearest(here,stops,1,5000.0)
        assertEquals("near",result.single().first.id)
        assertTrue(result.single().second in 100..130)
    }
}
