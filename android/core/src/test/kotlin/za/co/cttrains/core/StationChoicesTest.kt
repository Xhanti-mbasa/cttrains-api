package za.co.cttrains.core

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class StationChoicesTest {
    private val work=WalkEndpoint("spot:work","Work",GeoPoint(-33.927,18.451))
    private val stations=listOf(StationPosition("Woodstock",-33.927,18.452),StationPosition("Esplanade",-33.927,18.454),StationPosition("Salt River",-33.927,18.465),StationPosition("Unibell",-33.94,18.63),StationPosition("Unmapped"))
    private val available=stations.map { it.name }
    @Test fun recommendationsAreNearestFirstAndExcludeUnmappedOrUnpublishedStations() {
        assertEquals(listOf("Woodstock","Esplanade","Salt River","Unibell"),StationChoices.ranked(work.point,stations.reversed(),available).map { it.name })
        assertEquals(listOf("Salt River"),StationChoices.ranked(work.point,stations,listOf("Salt River")).map { it.name })
    }
    @Test fun preferredStationOutsideTheTwoNearestIsHonoured() {
        assertEquals(listOf("Woodstock","Esplanade"),StationChoices.candidates(work,stations,available,5000).map { it.name })
        assertEquals(listOf("Salt River"),StationChoices.candidates(work,stations,available,5000,"SALT RIVER").map { it.name })
    }
    @Test fun missingOrTooDistantPreferenceDoesNotSilentlyFallBackToWoodstock() {
        assertTrue(StationChoices.candidates(work,stations,available,500,"Salt River").isEmpty())
        assertTrue(StationChoices.candidates(work,stations,available,5000,"Removed station").isEmpty())
        assertTrue(StationChoices.candidates(work,stations,listOf("Woodstock"),5000,"Salt River").isEmpty())
    }
    @Test fun explicitStationEndpointWinsOverSavedPlacePreference() {
        val unibell=WalkingRoutes.endpoint(stations[3])
        assertEquals(listOf("Unibell"),StationChoices.candidates(unibell,stations,available,5000,"Salt River").map { it.name })
    }
    @Test fun arrivalStationOffersNearbySavedHomeInProximityOrder() {
        val home=MarkedSpot("home","Home",null,GeoPoint(-33.9402,18.6301),"Home",createdAt=1,preferredStation="Unibell")
        val friend=home.copy(id="friend",name="Friend",point=GeoPoint(-33.945,18.636))
        val work=home.copy(id="work",name="Work",point=work.point)
        assertEquals(listOf(home,friend),StationChoices.nearbyPlaces(stations[3],listOf(friend,work,home)))
    }
    @Test fun oldSpotsRemainReadableAndPreferenceSurvivesSerialization() {
        val old="""{"id":"work","name":"Work","service":null,"point":{"latitude":-33.927,"longitude":18.451},"tag":"Work","createdAt":1}"""
        val spot=TripFiles.json.decodeFromString<MarkedSpot>(old)
        assertNull(spot.preferredStation)
        val preferred=spot.copy(preferredStation="Salt River")
        assertEquals(preferred,TripFiles.json.decodeFromString<MarkedSpot>(TripFiles.json.encodeToString(preferred)))
    }
}
