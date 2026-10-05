package za.co.cttrains.app.updates

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import za.co.cttrains.app.data.SecureCodec
import za.co.cttrains.core.*

@Serializable data class UpdateSettings(val repository: String="Xhanti-mbasa/cttrains-api",val token: String="",val beta: Boolean=true,val automatic: Boolean=false)
/** Credentials and staged update metadata are device-only and never included in portable backups. */
class UpdateStore(c: Context) {
    private val prefs=c.getSharedPreferences("app-updates-private",Context.MODE_PRIVATE)
    private val codec=SecureCodec()
    private inline fun <reified T> read(key: String): T?=prefs.getString(key,null)?.let { runCatching { TripFiles.json.decodeFromString<T>(codec.decrypt(it)) }.getOrNull() }
    fun settings()=read<UpdateSettings>("settings") ?: UpdateSettings()
    fun save(v: UpdateSettings) { require(v.token.length<=300 && !v.token.any(Char::isWhitespace));prefs.edit().putString("settings",codec.encrypt(TripFiles.json.encodeToString(v))).commit() }
    fun staged()=read<AppUpdate>("staged")
    fun downloadId()=prefs.getString("download-id",null)
    fun downloadId(value: String)=prefs.edit().putString("download-id",value).commit()
    fun notifiedVersion()=prefs.getLong("notified-version",0)
    fun notifiedVersion(value: Long)=prefs.edit().putLong("notified-version",value).commit()
    fun stage(v: AppUpdate?) { prefs.edit().apply { if(v==null) remove("staged") else putString("staged",codec.encrypt(TripFiles.json.encodeToString(v))) }.commit() }
    fun clear() { prefs.edit().clear().commit() }
}
