package za.co.cttrains.app.whatsapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import za.co.cttrains.core.*

data class WhatsAppState(val connection: WhatsAppConnection?=null,val inbox: WhatsAppInbox=WhatsAppInbox(),val channels: List<WhatsAppFeed> = emptyList(),val busy: Boolean=false,val issue: String?=null)
class WhatsAppModel(app: Application): AndroidViewModel(app) {
    private val reader=WhatsAppReader(app)
    private val mutable=MutableStateFlow(WhatsAppState())
    val state=mutable.asStateFlow()
    private var operation: Job?=null
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
            try { block() } catch(e: Exception) { if(e is CancellationException) throw e;mutable.update { it.copy(issue="Could not complete this OpenWA action. Check the HTTPS gateway, session and Reader API key. Core transit features continue working.") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun connect(server: String,session: String,key: String)=action { val c=WhatsAppUpdates.validate(WhatsAppConnection(server.trim().trimEnd('/'),session.trim(),key.trim()));reader.disconnect();reader.save(c) }
    fun channels()=action { val channels=reader.channels();mutable.update { it.copy(channels=channels) } }
    fun add(feed: WhatsAppFeed)=action { val c=reader.config() ?: error("No connection");reader.save(c.copy(feeds=(c.feeds.filterNot { it.id==feed.id }+WhatsAppUpdates.validateFeed(feed)).takeLast(5))) }
    fun remove(id: String)=action { val c=reader.config() ?: return@action;reader.save(c.copy(feeds=c.feeds.filterNot { it.id==id })) }
    fun background(enabled: Boolean)=action { val c=reader.config() ?: return@action;reader.save(c.copy(background=enabled)) }
    fun pause()=action(true) { val c=reader.config() ?: return@action;reader.save(c.copy(enabled=!c.enabled)) }
    fun disconnect()=action(true) { WhatsAppReader.configure(getApplication(),null);reader.disconnect();mutable.update { it.copy(channels=emptyList()) } }
    fun refresh()=action { reader.refresh() }
}
