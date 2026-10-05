package za.co.cttrains.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class UpdateAsset(val id: Long,val name: String,val size: Long,val digest: String?=null)
@Serializable data class AppRelease(val tag: String,val title: String,val notes: String,val prerelease: Boolean,val assets: List<UpdateAsset>)
@Serializable data class UpdateManifest(val schema: Int=1,val packageName: String,val versionName: String,val versionCode: Long,val minSdk: Int,
    val apk: String,val sha256: String,val certificateSha256: String,val sizeBytes: Long)
@Serializable data class AppUpdate(val repo: String,val release: AppRelease,val manifest: UpdateManifest,val asset: UpdateAsset)

object AppUpdates {
    const val MAX_APK_BYTES=200L*1024*1024
    fun repository(raw: String): String {
        val value=raw.trim().removePrefix("https://github.com/").trimEnd('/').removeSuffix(".git")
        require(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}").matches(value)) { "Use a GitHub owner/repository, without a branch or URL query." }
        return value
    }
    fun releases(raw: String,beta: Boolean): List<AppRelease> {
        require(raw.toByteArray().size<=2*1024*1024)
        return Json.parseToJsonElement(raw).jsonArray.take(100).mapNotNull { e ->
            val o=e.jsonObject
            if(o["draft"]?.jsonPrimitive?.booleanOrNull!=false) return@mapNotNull null
            val pre=o["prerelease"]?.jsonPrimitive?.booleanOrNull ?: return@mapNotNull null
            val tag=o["tag_name"]?.jsonPrimitive?.content.orEmpty()
            if((pre && !beta) || !Regex("android-v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?").matches(tag)) return@mapNotNull null
            val assets=o["assets"]?.jsonArray.orEmpty().mapNotNull { a ->
                val v=a.jsonObject;val id=v["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val name=v["name"]?.jsonPrimitive?.content.orEmpty();val size=v["size"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                if(id<=0 || size<=0 || name.length !in 1..180 || name.contains('/') || name.contains('\\')) null
                else UpdateAsset(id,name,size,v["digest"]?.jsonPrimitive?.contentOrNull)
            }
            AppRelease(tag,o["name"]?.jsonPrimitive?.contentOrNull.orEmpty().take(300),o["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(12000),pre,assets)
        }
    }
    fun manifest(raw: String): UpdateManifest {
        require(raw.toByteArray().size<=64*1024)
        val v=Json { ignoreUnknownKeys=true }.decodeFromString<UpdateManifest>(raw)
        require(v.schema==1 && v.versionCode>0 && v.minSdk>=26 && v.versionName.length in 1..100)
        require(v.sizeBytes in 1..MAX_APK_BYTES && Regex("[a-f0-9]{64}").matches(v.sha256) && Regex("[a-f0-9]{64}").matches(v.certificateSha256))
        require(v.apk.endsWith(".apk") && v.apk.length in 5..180 && !v.apk.contains('/') && !v.apk.contains('\\'))
        return v
    }
    fun candidate(repo: String,release: AppRelease,m: UpdateManifest,currentCode: Long,packageName: String,certificate: String,sdk: Int): AppUpdate? {
        require(m.packageName==packageName) { "The release belongs to a different app." }
        require(m.versionName==release.tag.removePrefix("android-v")) { "Release tag and APK version disagree." }
        if(m.versionCode<=currentCode) return null
        require(m.certificateSha256==certificate) { "This release uses a different signing key and cannot update this installation." }
        require(m.minSdk<=sdk) { "This update requires a newer Android version." }
        val a=release.assets.singleOrNull { it.name==m.apk } ?: error("The release is missing its APK.")
        require(a.size==m.sizeBytes && (a.digest==null || a.digest=="sha256:${m.sha256}")) { "Release metadata and APK checksum disagree." }
        return AppUpdate(repository(repo),release,m,a)
    }
}
