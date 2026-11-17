package za.co.cttrains.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import za.co.cttrains.app.data.*
import za.co.cttrains.app.updates.*
import za.co.cttrains.core.*
import java.time.LocalDate

/** Run twice by check-upgrade.sh across adb install -r, in separate app processes. */
@RunWith(AndroidJUnit4::class)
class UpgradeFixtureTest {
    @Test fun encryptedProductionStateSurvivesUpgrade() = runBlocking {
        val phase=InstrumentationRegistry.getArguments().getString("upgradePhase")
        require(phase in listOf("seed","verify")) { "Run with check-upgrade.sh; this fixture is excluded from the ordinary device suite." }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("za.co.cttrains.app",context.packageName)
        val store=LocalTransitStore.get(context); store.initialize()
        val feature=FeatureStore(context)
        if(phase=="seed") {
            val date=LocalDate.now(CapeTownZone).plusDays(1).toString()
            val query=SearchQuery("Salt River","Unibell",date,"03:00")
            val journey=Journey(listOf(Leg("Test","upgrade",listOf(Stop("Salt River","08:00"),Stop("Unibell","08:40")))))
            store.updatePreferences { it.copy(routes=listOf(SavedRoute("Salt River","Unibell")),favoriteStations=listOf("Unibell"),theme="Dark",mapMode="stations") }
            val id=store.preferences.first().profileId
            store.writeLocal("upgrade-profile",id)
            feature.update { it.copy(localName="Upgrade fixture",spots=listOf(MarkedSpot("upgrade-home","Home",null,GeoPoint(-33.94,18.63),"Home",createdAt=1,preferredStation="Unibell"))) }
            store.cache(CachedSearch(query,listOf(journey),System.currentTimeMillis(),null,"cttrains-web"))
            store.saveJourney(ScheduledJourney("upgrade-trip",id,query,journey,"cttrains-web",System.currentTimeMillis(),System.currentTimeMillis()))
            UpdateStore(context).save(UpdateSettings(beta=true,token="synthetic_upgrade_token"))
        } else {
            val prefs=store.preferences.first()
            assertEquals(store.readLocal("upgrade-profile"),prefs.profileId)
            assertEquals(listOf(SavedRoute("Salt River","Unibell")),prefs.routes)
            assertEquals(listOf("Unibell"),prefs.favoriteStations)
            assertEquals("Dark",prefs.theme); assertEquals("stations",prefs.mapMode)
            assertEquals("Unibell",feature.extras().spots.single().preferredStation)
            assertEquals("Upgrade fixture",feature.extras().localName)
            assertEquals("upgrade-trip",store.journeys.first().single().id)
            assertEquals("upgrade",store.searches().single().journeys.single().legs.single().train)
            assertEquals("synthetic_upgrade_token",UpdateStore(context).settings().token)
        }
    }
}
