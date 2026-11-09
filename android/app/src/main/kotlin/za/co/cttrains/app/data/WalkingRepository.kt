package za.co.cttrains.app.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import za.co.cttrains.core.*

/** Explicit, consented requests only. A public routing service sees both selected coordinates. */
class WalkingRepository(context: Context) {
    private val store=FeatureStore(context)
    private val http=SourceHttp()
    suspend fun route(a: WalkEndpoint,b: WalkEndpoint): Pair<WalkingLeg,Boolean> {
        val key=WalkingRoutes.key(a,b);val cacheKey="walk-"+SecureCodec.digest(key)
        val saved=store.store.readLocal(cacheKey)?.let { runCatching { store.json.decodeFromString<WalkingLeg>(it) }.getOrNull() }
        val now=System.currentTimeMillis()
        if(saved!=null && saved.key==key && now-saved.fetchedAt in 0..24*60*60_000L) return saved to true
        try {
            val raw=gate.withLock {
                val wait=1100-(android.os.SystemClock.elapsedRealtime()-lastRequest)
                if(wait>0) delay(wait)
                lastRequest=android.os.SystemClock.elapsedRealtime()
                http.get(WalkingRoutes.url(a,b),1024*1024,setOf("routing.openstreetmap.de"))
            }
            val leg=withContext(Dispatchers.Default) { WalkingRoutes.parse(raw,a,b,now) }
            store.store.writeLocal(cacheKey,store.json.encodeToString(leg))
            store.store.trimWalkCaches()
            return leg to false
        } catch(e: Exception) {
            if(e is CancellationException) throw e
            if(saved!=null && saved.key==key) return saved to true
            throw e
        }
    }
    companion object { private val gate=Mutex();private var lastRequest=0L }
}
