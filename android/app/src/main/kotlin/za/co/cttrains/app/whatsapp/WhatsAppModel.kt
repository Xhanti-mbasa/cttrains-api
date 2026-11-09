package za.co.cttrains.app.whatsapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import za.co.cttrains.core.*

data class WhatsAppState(val connection: WhatsAppConnection?=null,val inbox: WhatsAppInbox=WhatsAppInbox(),val channels: List<WhatsAppFeed> = emptyList(),val sessions: List<WhatsAppSession> = emptyList(),val busy: Boolean=false,val issue: String?=null)
class WhatsAppModel(app: Application): AndroidViewModel(app) {
    private val reader=WhatsAppReader(app)
    private val mutable=MutableStateFlow(WhatsAppState())
    val state=mutable.asStateFlow()
    private var operation: Job?=null
    private var lookupVersion=0
    init {
        viewModelScope.launch { reader.connection.catch { mutable.update { it.copy(issue="OpenWA settings could not be read. Transit features continue working.") };emit(null) }.collect { c -> mutable.update { it.copy(connection=c) };WhatsAppReader.configure(app,c) } }
        viewModelScope.launch { reader.inbox.catch { mutable.update { it.copy(issue="The WhatsApp inbox could not be read. Transit features continue working.") };emit(WhatsAppInbox()) }.collect { inbox -> mutable.update { it.copy(inbox=inbox) } } }
    }
    private fun action(interrupt: Boolean=false,block: suspend ()->Unit) {
        if(operation?.isActive==true && !interrupt) return
        val old=operation;if(interrupt) old?.cancel()
        operation=viewModelScope.launch {
            if(interrupt) old?.join()
            mutable.update { it.copy(busy=true,issue=null) }
            try { block() } catch(e: Exception) { if(e is CancellationException) throw e;mutable.update { it.copy(issue=when((e as? za.co.cttrains.app.data.HttpStatusException)?.status) {
                401,403->"The gateway refused this Reader key. Check expiry and permissions for your linked account. See How to set this up."
                404->"API or account not found. The usual base is https://your-host/api; use the account UUID, not its name."
                409->"This account is not ready. Start it and link WhatsApp in the OpenWA dashboard."
                429->"The gateway asked you to wait. Try again later."
                else->"Connection could not be verified. Check HTTPS, /api, the Reader key and that the linked account is ready. See How to set this up."
            }) } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun discover(server: String,key: String) { val version=++lookupVersion;action { mutable.update { it.copy(sessions=emptyList()) };val sessions=reader.sessions(server,key);if(version==lookupVersion) mutable.update { it.copy(sessions=sessions,issue=if(sessions.isEmpty()) "No accounts are visible to this key. Link an account and allow its UUID in your Reader key." else null) } } }
    fun clearSessions() { lookupVersion++;mutable.update { it.copy(sessions=emptyList()) } }
    fun connect(server: String,session: String,key: String)=action { val c=WhatsAppUpdates.validate(WhatsAppConnection(WhatsAppUpdates.apiBase(server),session.trim(),key.trim()));reader.check(c);reader.disconnect();reader.save(c) }
    fun channels()=action { val channels=reader.channels();mutable.update { it.copy(channels=channels) } }
    fun add(feed: WhatsAppFeed)=action { val c=reader.config() ?: error("No connection");reader.save(c.copy(feeds=(c.feeds.filterNot { it.id==feed.id }+WhatsAppUpdates.validateFeed(feed)).takeLast(5))) }
    fun remove(id: String)=action { val c=reader.config() ?: return@action;reader.save(c.copy(feeds=c.feeds.filterNot { it.id==id })) }
    fun background(enabled: Boolean)=action { val c=reader.config() ?: return@action;reader.save(c.copy(background=enabled)) }
    fun pause()=action(true) { val c=reader.config() ?: return@action;reader.save(c.copy(enabled=!c.enabled)) }
    fun disconnect()=action(true) { WhatsAppReader.configure(getApplication(),null);reader.disconnect();mutable.update { it.copy(channels=emptyList()) } }
    fun refresh()=action { reader.refresh() }
}
