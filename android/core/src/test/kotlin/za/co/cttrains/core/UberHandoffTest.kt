package za.co.cttrains.core

import org.junit.Assert.*
import org.junit.Test

class UberHandoffTest {
    private val home = UberPlace("Home & Garden", GeoPoint(-33.9425, 18.6280))
    private val station = UberPlace("Unibell station", GeoPoint(-33.9381, 18.6262))

    @Test fun buildsUniversalLinkWithEncodedPlaces() {
        val link = UberHandoff.link(home, station, "abcDEF123_-xyz")
        assertTrue(link.startsWith("https://m.uber.com/ul/?action=setPickup&client_id=abcDEF123_-xyz&"))
        assertTrue(link.contains("pickup%5Blatitude%5D=-33.942500"))
        assertTrue(link.contains("pickup%5Blongitude%5D=18.628000"))
        assertTrue(link.contains("pickup%5Bnickname%5D=Home%20%26%20Garden"))
        assertTrue(link.contains("dropoff%5Blatitude%5D=-33.938100"))
        assertTrue(link.contains("dropoff%5Bnickname%5D=Unibell%20station"))
        assertFalse(link.contains("my_location"))
    }

    @Test fun unknownPickupUsesCurrentLocationInUber() {
        val link = UberHandoff.link(UberPlace("Current location", null), station)
        assertTrue(link.contains("pickup=my_location"))
        assertFalse(link.contains("pickup%5Blatitude%5D"))
        assertFalse(link.contains("client_id"))
    }

    @Test fun rejectsMissingOrInvalidPointsAndOddClientIds() {
        assertThrows(IllegalArgumentException::class.java) { UberHandoff.link(home, UberPlace("Nowhere", null)) }
        assertThrows(IllegalArgumentException::class.java) { UberHandoff.link(home, UberPlace("Bad", GeoPoint(120.0, 18.0))) }
        assertFalse(UberHandoff.link(home, station, "bad id&x=1").contains("client_id"))
        assertTrue(UberHandoff.link(UberPlace("  \n", home.point), station).contains("pickup%5Bnickname%5D=Pickup"))
    }

    @Test fun stationLookupUsesBundledPositions() {
        val stations = listOf(StationPosition("UNIBELL", -33.9381, 18.6262), StationPosition("Nowhere"))
        assertEquals(GeoPoint(-33.9381, 18.6262), UberHandoff.station("Unibell", stations)?.point)
        assertNull(UberHandoff.station("Nowhere", stations))
        assertNull(UberHandoff.station("Bellville", stations))
    }
}
