package za.co.cttrains.app.directions

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.encodeToString
import za.co.cttrains.app.data.SecureCodec
import za.co.cttrains.core.*

/** Ephemeral, encrypted itinerary. No location history and no automatic restart after reboot. */
class DirectionsStore(context: Context) {
    private val file=AtomicFile(java.io.File(context.noBackupFilesDir,"active-directions"))
    fun load(): DirectionsSession? = synchronized(lock) {
        runCatching {
            val raw=file.openRead().use { it.readBytes().also { b->require(b.size<=4*1024*1024) }.toString(Charsets.UTF_8) }
            val s=TripFiles.json.decodeFromString<DirectionsSession>(SecureCodec().decrypt(raw))
            DirectionsEngine.validate(s.plan)
            s.takeIf { DirectionsEngine.alive(it,System.currentTimeMillis()) }
        }.getOrNull()
    }
    fun save(s: DirectionsSession?) = synchronized(lock) {
        if(s==null) { file.delete();return@synchronized }
        val out=file.startWrite()
        try { out.write(SecureCodec().encrypt(TripFiles.json.encodeToString(s)).toByteArray());file.finishWrite(out) }
        catch(e: Exception) { file.failWrite(out);throw e }
    }
    fun saveActive(s: DirectionsSession) = synchronized(lock) {
        if(DirectionsRuntime.state.value.session?.plan?.id==s.plan.id) save(s)
    }
    fun clearStopped() = synchronized(lock) {
        if(DirectionsRuntime.state.value.session==null) file.delete()
    }
    companion object { private val lock=Any() }
}
