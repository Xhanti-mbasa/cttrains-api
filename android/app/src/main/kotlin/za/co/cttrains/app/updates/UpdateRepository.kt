package za.co.cttrains.app.updates

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import za.co.cttrains.core.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UpdateRepository(private val c: Context) {
    val store=UpdateStore(c)
    private val http=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(30,TimeUnit.SECONDS)
        .readTimeout(60,TimeUnit.SECONDS).callTimeout(5,TimeUnit.MINUTES).build()
    val directory=File(c.filesDir,"app-updates").apply { mkdirs() }
    val ready=File(directory,"next.apk")
    @Suppress("DEPRECATION") private fun installed()=c.packageManager.getPackageInfo(c.packageName,if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
    @Suppress("DEPRECATION") fun certificate(): String {
        val p=installed();val signatures=if(Build.VERSION.SDK_INT>=28) p.signingInfo!!.apkContentsSigners else p.signatures!!
        require(signatures.size==1) { "This updater supports a single installed signing certificate." }
        return hash(signatures.single().toByteArray())
    }
    @Suppress("DEPRECATION") fun versionCode()=installed().let { if(Build.VERSION.SDK_INT>=28) it.longVersionCode else it.versionCode.toLong() }
    private fun asset(repo: String,id: Long)="https://api.github.com/repos/${AppUpdates.repository(repo)}/releases/assets/$id"
    private suspend fun request(url: String,token: String,binary: Boolean): Response {
        var target=url.toHttpUrl()
        repeat(6) { hop ->
            currentCoroutineContext().ensureActive()
            require(target.isHttps && (target.host=="api.github.com" || target.host=="github.com" || target.host=="release-assets.githubusercontent.com" || target.host=="objects.githubusercontent.com")) { "Untrusted update download address." }
            val b=Request.Builder().url(target).header("User-Agent","CT-Trains-Android-updater").header("Accept",if(binary) "application/octet-stream" else "application/vnd.github+json")
            // Credentials go only to the initial GitHub API request, never to a redirect host.
            if(hop==0 && target.host=="api.github.com" && token.isNotEmpty()) b.header("Authorization","Bearer $token")
            val call=http.newCall(b.build())
            val cancellation=currentCoroutineContext()[Job]?.invokeOnCompletion { if(it!=null) call.cancel() }
            val response=try { call.execute() } finally { cancellation?.dispose() }
            if(response.code in listOf(301,302,303,307,308)) {
                val location=response.header("Location");response.close()
                target=location?.let { target.resolve(it) } ?: error("GitHub supplied an invalid download redirect.")
            } else {
                if(response.code !in 200..299) { val code=response.code;response.close();error(when(code) { 401,403->"GitHub access was denied. Check the read-only token, its expiry and repository access.";404->"No accessible releases. This private repository needs a read-only token, and a release must be published.";else->"GitHub could not be reached ($code). Retry later." }) }
                return response
            }
        }
        error("Too many download redirects.")
    }
    private suspend fun text(url: String,token: String,binary: Boolean,limit: Int): String = request(url,token,binary).use { r ->
        val body=r.body ?: error("Empty GitHub response.")
        if(body.contentLength()>limit) error("Update metadata is too large.")
        val bytes=body.byteStream().use { input->java.io.ByteArrayOutputStream().use { output->
            val buffer=ByteArray(8192);while(output.size()<=limit) { currentCoroutineContext().ensureActive();val n=input.read(buffer,0,minOf(buffer.size,limit+1-output.size()));if(n<0) break;output.write(buffer,0,n) };output.toByteArray()
        } }
        require(bytes.size<=limit) { "Update metadata is too large." };bytes.toString(Charsets.UTF_8)
    }
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val cfg=store.settings();val repo=AppUpdates.repository(cfg.repository)
        val rows=AppUpdates.releases(text("https://api.github.com/repos/$repo/releases?per_page=30",cfg.token,false,2*1024*1024),cfg.beta)
        var incompatible: String?=null
        var newest: AppUpdate?=null
        for(release in rows) {
            val descriptor=release.assets.singleOrNull { it.name=="update.json" } ?: continue
            val manifest=AppUpdates.manifest(text(asset(repo,descriptor.id),cfg.token,true,64*1024))
            try {
                AppUpdates.candidate(repo,release,manifest,versionCode(),c.packageName,certificate(),Build.VERSION.SDK_INT)?.let {
                    if(it.manifest.versionCode>(newest?.manifest?.versionCode ?: 0)) newest=it
                }
            } catch(e: IllegalArgumentException) { incompatible=e.message }
        }
        if(newest==null && incompatible!=null) error(incompatible!!)
        newest
    }
    suspend fun stage(update: AppUpdate,progress: suspend (Long,Long)->Unit) = withContext(Dispatchers.IO) { downloadLock.withLock {
        val m=update.manifest
        require(AppUpdates.repository(store.settings().repository)==update.repo) { "The release source changed. Check for updates again." }
        AppUpdates.candidate(update.repo,update.release,m,versionCode(),c.packageName,certificate(),Build.VERSION.SDK_INT) ?: error("This version is already installed.")
        require(StatFs(directory.absolutePath).availableBytes>m.sizeBytes+20*1024*1024) { "Free space is needed to stage this update." }
        val part=File(directory,"next.part")
        try {
            request(asset(update.repo,update.asset.id),store.settings().token,true).use { response ->
                val body=response.body ?: error("Empty APK download.")
                if(body.contentLength()>0) require(body.contentLength()==m.sizeBytes) { "Unexpected APK size." }
                val digest=MessageDigest.getInstance("SHA-256");var total=0L;var last=0L
                body.byteStream().use { input->part.outputStream().use { output->val bytes=ByteArray(65536)
                    while(true) { currentCoroutineContext().ensureActive();val n=input.read(bytes);if(n<0) break
                        total+=n;require(total<=m.sizeBytes) { "APK download exceeded the expected size." };output.write(bytes,0,n);digest.update(bytes,0,n)
                        if(total-last>=512*1024 || total==m.sizeBytes) { progress(total,m.sizeBytes);last=total }
                    };output.fd.sync()
                } }
                require(total==m.sizeBytes && hex(digest.digest())==m.sha256) { "The download is incomplete or its checksum is incorrect." }
            }
            verify(part,update)
            require(part.renameTo(ready)) { "Could not stage the verified APK." }
            store.stage(update)
        } finally { part.delete() }
    } }
    @Suppress("DEPRECATION") fun verify(file: File,update: AppUpdate) {
        val m=update.manifest
        require(file.exists() && file.length()==m.sizeBytes) { "Staged APK is missing or incomplete." }
        val sha=file.inputStream().use { i->val digest=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(65536);while(true) { val n=i.read(bytes);if(n<0) break;digest.update(bytes,0,n) };hex(digest.digest()) }
        require(sha==m.sha256) { "Staged APK checksum failed." }
        val p=c.packageManager.getPackageArchiveInfo(file.absolutePath,if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES) ?: error("Android could not verify this APK.")
        val code=if(Build.VERSION.SDK_INT>=28) p.longVersionCode else p.versionCode.toLong()
        require(p.packageName==c.packageName && code==m.versionCode && p.versionName==m.versionName && code>versionCode()) { "APK package or version failed verification." }
        require(p.applicationInfo?.minSdkVersion==m.minSdk && m.minSdk<=Build.VERSION.SDK_INT) { "APK Android requirements disagree with the release." }
        val signatures=if(Build.VERSION.SDK_INT>=28) p.signingInfo?.apkContentsSigners else p.signatures
        require(signatures?.size==1 && hash(signatures.single().toByteArray())==certificate() && certificate()==m.certificateSha256) { "APK signing certificate does not match this installation." }
        val verified=com.android.apksig.ApkVerifier.Builder(file).setMinCheckedPlatformVersion(Build.VERSION.SDK_INT).setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT).build().verify()
        require(verified.isVerified && verified.signerCertificates.size==1 && hash(verified.signerCertificates.single().encoded)==certificate()) { "APK cryptographic signature verification failed." }
    }
    suspend fun clear() = downloadLock.withLock { File(directory,"next.part").delete();ready.delete();store.stage(null) }
    companion object {
        private val downloadLock=Mutex()
        private fun hex(bytes: ByteArray)=bytes.joinToString("") { "%02x".format(it) }
        private fun hash(bytes: ByteArray)=hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
