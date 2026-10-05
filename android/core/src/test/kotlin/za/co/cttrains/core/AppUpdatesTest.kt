package za.co.cttrains.core

import org.junit.Test
import org.junit.Assert.*
import kotlinx.serialization.encodeToString

class AppUpdatesTest {
    private val cert="1".repeat(64);private val hash="2".repeat(64)
    private fun manifest()=UpdateManifest(packageName="za.co.cttrains.app",versionName="1.9.0",versionCode=900,minSdk=26,apk="CT-Trains-1.9.0.apk",sha256=hash,certificateSha256=cert,sizeBytes=1000)
    private fun release()=AppRelease("android-v1.9.0","CT Trains","Notes",false,listOf(UpdateAsset(123,"CT-Trains-1.9.0.apk",1000,"sha256:$hash")))
    private fun candidate(m: UpdateManifest=manifest(),r: AppRelease=release(),installed: Long=801)=AppUpdates.candidate("owner/repo",r,m,installed,"za.co.cttrains.app",cert,36)
    @Test fun validUpgradeRequiresMatchingReleaseAndSigningKey() { assertNotNull(candidate());assertNull(candidate(installed=900));assertNull(candidate(installed=1000)) }
    @Test fun wrongPackageCertificateSdkOrTagAreRejected() {
        for(m in listOf(manifest().copy(packageName="attacker.app"),manifest().copy(certificateSha256="3".repeat(64)),manifest().copy(minSdk=37),manifest().copy(versionName="1.9.1"))) assertThrows(IllegalArgumentException::class.java) { candidate(m) }
    }
    @Test fun apkSizeAndGithubDigestMustAgree() {
        assertThrows(IllegalArgumentException::class.java) { candidate(r=release().copy(assets=listOf(release().assets.single().copy(size=999)))) }
        assertThrows(IllegalArgumentException::class.java) { candidate(r=release().copy(assets=listOf(release().assets.single().copy(digest="sha256:${"3".repeat(64)}")))) }
    }
    @Test fun repositoryCannotContainCredentialsQueryOrTraversal() {
        assertEquals("owner/repo",AppUpdates.repository("https://github.com/owner/repo.git"))
        for(v in listOf("https://user:pass@github.com/owner/repo","owner/repo?token=secret","owner/repo/tree/main","../repo","http://github.com/owner/repo")) assertThrows(IllegalArgumentException::class.java) { AppUpdates.repository(v) }
    }
    @Test fun draftAndBetaReleasesAreFilteredAndAssetUrlsAreNotTrusted() {
        fun row(pre: Boolean,draft: Boolean,tag: String)="""{"draft":$draft,"prerelease":$pre,"tag_name":"$tag","name":"v","assets":[{"id":123,"name":"app.apk","size":1000,"browser_download_url":"https://evil.example/app.apk"}]}"""
        val raw="[${row(false,false,"android-v1.9.0")},${row(true,false,"android-v1.9.1-beta")},${row(false,true,"android-v1.9.2")},${row(false,false,"other-release")}]"
        assertEquals(1,AppUpdates.releases(raw,false).size);assertEquals(2,AppUpdates.releases(raw,true).size)
        assertEquals(123L,AppUpdates.releases(raw,false).single().assets.single().id)
    }
    @Test fun manifestBoundsAndFilenamesAreValidated() {
        val json=TripFiles.json
        assertEquals(manifest(),AppUpdates.manifest(json.encodeToString(manifest())))
        for(m in listOf(manifest().copy(apk="../app.apk"),manifest().copy(sha256="bad"),manifest().copy(sizeBytes=AppUpdates.MAX_APK_BYTES+1),manifest().copy(schema=2))) assertThrows(IllegalArgumentException::class.java) { AppUpdates.manifest(json.encodeToString(m)) }
    }
}
