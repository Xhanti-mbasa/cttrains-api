package za.co.cttrains.app.data

import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import za.co.cttrains.core.*

/** Photon supports search-as-you-type. Never send autocomplete to public Nominatim. */
class PlaceSearch {
    private val http=SourceHttp()
    private val cache=linkedMapOf<String,List<PlaceSuggestion>>()
    suspend fun suggestions(query: String): List<PlaceSuggestion> {
        val q=query.trim();require(q.length in 3..150)
        return gate.withLock {
            cache[q.lowercase()]?.let { return@withLock it }
            delay((next-SystemClock.elapsedRealtime()).coerceAtLeast(0))
            next=SystemClock.elapsedRealtime()+2000
            try {
                val raw=http.get("https://photon.komoot.io/api/?q=${Uri.encode(q)}&bbox=18.1,-34.5,19.2,-33.4&limit=8&lang=en",200_000,setOf("photon.komoot.io"))
                val results=PhotonPlaces.parse(raw);cache[q.lowercase()]=results
                while(cache.size>30) cache.remove(cache.keys.first())
                results
            } catch(e: HttpStatusException) { if(e.status==429) next=SystemClock.elapsedRealtime()+60_000;throw e }
        }
    }
    companion object { private val gate=Mutex();private var next=0L }
}
