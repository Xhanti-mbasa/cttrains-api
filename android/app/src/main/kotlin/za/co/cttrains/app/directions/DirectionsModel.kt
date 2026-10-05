package za.co.cttrains.app.directions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import za.co.cttrains.core.*

data class DirectionsState(val session: DirectionsSession?=null,val preview: DirectionsPlan?=null,val guidance: DirectionGuidance?=null,
    val fix: DirectionFix?=null,val running: Boolean=false,val error: String?=null)
object DirectionsRuntime { val state=MutableStateFlow(DirectionsState()) }
class DirectionsModel(app: Application): AndroidViewModel(app) {
    val state=DirectionsRuntime.state.asStateFlow()
    init { viewModelScope.launch { if(state.value.session==null) withContext(Dispatchers.IO) { DirectionsStore(app).load() }?.let { saved->
        DirectionsRuntime.state.update { if(it.session==null) it.copy(session=saved.copy(paused=true),guidance=DirectionsEngine.guidance(saved.copy(paused=true),null,System.currentTimeMillis())) else it }
    } } }
    fun preview(make: ()->DirectionsPlan,onReady: ()->Unit) { viewModelScope.launch {
        try { val plan=withContext(Dispatchers.Default) { make() };DirectionsRuntime.state.update { it.copy(preview=plan,error=null) };onReady() }
        catch(_: Exception) { DirectionsRuntime.state.update { it.copy(error="Choose a mapped, timed journey before starting directions.") } }
    } }
    fun start() { viewModelScope.launch {
        val old=state.value
        val session=old.preview?.let { DirectionsSession(it,startedAt=System.currentTimeMillis()) } ?: old.session?.copy(paused=false)
        if(session==null || !DirectionsEngine.alive(session,System.currentTimeMillis())) { DirectionsRuntime.state.update { it.copy(error="This journey has expired. Choose a new route in Plan.") };return@launch }
        try {
            DirectionsRuntime.state.update { it.copy(session=session,preview=null,error=null,fix=null) }
            withContext(Dispatchers.IO) { DirectionsStore(getApplication()).saveActive(session) }
            if(state.value.session?.plan?.id!=session.plan.id) return@launch
            DirectionsService.start(getApplication())
        } catch(_: Exception) { DirectionsRuntime.state.update { it.copy(error="Could not start. Allow location access and start directions while this app is open.",running=false) } }
    } }
    fun discardPreview()=DirectionsRuntime.state.update { it.copy(preview=null,error=null) }
    fun command(action: String) { DirectionsService.command(getApplication(),action) }
    fun stop()=DirectionsService.stop(getApplication())
}
