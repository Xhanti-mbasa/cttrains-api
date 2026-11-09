package za.co.cttrains.app.updates

import android.app.Application
import android.content.*
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import za.co.cttrains.core.*

data class UpdatesState(val settings: UpdateSettings=UpdateSettings(),val candidate: AppUpdate?=null,val staged: AppUpdate?=null,val checking: Boolean=false,
    val downloading: Boolean=false,val bytes: Long=0,val total: Long=0,val message: String?=null,val error: String?=null)
class UpdatesModel(app: Application): AndroidViewModel(app) {
    private val repository=UpdateRepository(app)
    private val work=WorkManager.getInstance(app)
    private val mutable=MutableStateFlow(UpdatesState())
    val state=mutable.asStateFlow()
    init {
        refresh()
        viewModelScope.launch { work.getWorkInfosForUniqueWorkFlow(UpdateWork.DOWNLOAD).collect { infos ->
            val current=infos.firstOrNull { it.id.toString()==repository.store.downloadId() }
            val active=current?.takeIf { !it.state.isFinished }
            val failure=current?.takeIf { it.state==WorkInfo.State.FAILED }?.outputData?.getString("error")
            val staged=withContext(Dispatchers.IO) { repository.store.staged()?.takeIf { repository.ready.exists() && it.manifest.versionCode>repository.versionCode() } }
            mutable.update { it.copy(downloading=active!=null,bytes=active?.progress?.getLong("done",0) ?: 0,total=active?.progress?.getLong("total",0) ?: 0,staged=staged,error=failure ?: it.error) }
        } }
    }
    fun refresh() { viewModelScope.launch { val cfg=withContext(Dispatchers.IO) { repository.store.settings() };val staged=withContext(Dispatchers.IO) {
        repository.store.staged()?.let { if(it.manifest.versionCode<=repository.versionCode()) { repository.clear();null } else it.takeIf { repository.ready.exists() } }
    };mutable.update { it.copy(settings=cfg,staged=staged) } } }
    fun save(repo: String,token: String?,beta: Boolean,automatic: Boolean) { viewModelScope.launch {
        try {
            val cfg=UpdateSettings(AppUpdates.repository(repo),token ?: state.value.settings.token,beta,automatic)
            work.cancelUniqueWork(UpdateWork.DOWNLOAD)
            withContext(Dispatchers.IO) { repository.store.save(cfg) }
            UpdateWork.configure(getApplication(),automatic)
            mutable.update { it.copy(settings=cfg,candidate=null,error=null,message="Update settings saved.") }
        } catch(e: Exception) { mutable.update { it.copy(error=updateMessage(e)) } }
    } }
    fun check() { if(state.value.checking) return;viewModelScope.launch {
        mutable.update { it.copy(checking=true,error=null,message=null) }
        try { val candidate=repository.check();mutable.update { it.copy(candidate=candidate,message=if(candidate==null) "No newer compatible release was found. Only published releases with update.json are checked." else "${candidate.manifest.versionName} is available.") } }
        catch(e: Exception) { if(e is CancellationException) throw e;mutable.update { it.copy(error=updateMessage(e)) } }
        finally { mutable.update { it.copy(checking=false) } }
    } }
    fun download() { val candidate=state.value.candidate ?: return
        // WorkManager input has a 10 KiB limit; release notes are shown in the UI, not sent to the worker.
        if(state.value.downloading) return
        val compact=TripFiles.json.encodeToString(candidate.copy(release=candidate.release.copy(notes="",assets=listOf(candidate.asset))))
        val request=OneTimeWorkRequestBuilder<UpdateDownloadWorker>().setInputData(workDataOf("update" to compact)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        repository.store.downloadId(request.id.toString())
        work.enqueueUniqueWork(UpdateWork.DOWNLOAD,ExistingWorkPolicy.KEEP,request)
        mutable.update { it.copy(error=null,message="Download queued. Your installed version stays available.") }
    }
    fun cancel() { work.cancelUniqueWork(UpdateWork.DOWNLOAD);mutable.update { it.copy(message="Download cancelled. Your current app remains installed.",error=null) } }
    fun discard() { if(state.value.downloading) return;viewModelScope.launch { withContext(Dispatchers.IO) { repository.clear() };mutable.update { it.copy(staged=null,message="Staged download removed.") } } }
    fun install(c: Context) { val staged=state.value.staged ?: return;viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { repository.verify(repository.ready,staged) }
            if(!c.packageManager.canRequestPackageInstalls()) {
                c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${c.packageName}")))
                mutable.update { it.copy(message="Allow installation from CT Trains, then return and tap Install again.") };return@launch
            }
            val uri=FileProvider.getUriForFile(c,"${c.packageName}.files",repository.ready)
            c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            mutable.update { it.copy(message="Review Android's installation prompt. Cancelling keeps your current version.") }
        } catch(e: Exception) { if(e is CancellationException) throw e;mutable.update { it.copy(error=updateMessage(e)) } }
    } }
}
