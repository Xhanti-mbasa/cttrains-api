package za.co.cttrains.app.ui

import android.content.MutableContextWrapper
import androidx.credentials.*
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.libraries.identity.googleid.*
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import za.co.cttrains.app.BuildConfig
import za.co.cttrains.app.FeatureViewModel
import za.co.cttrains.app.data.SourceHttp
import za.co.cttrains.core.*

@Composable fun GoogleProfileSettings(model: FeatureViewModel) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val flow=remember(model) { model.store.store.observeLocal("google-profile").map { raw->raw?.takeIf(String::isNotBlank)?.let { model.store.json.decodeFromString<GoogleProfile>(it) } } }
    val profile by flow.collectAsState(null)
    val manager=remember(context) { CredentialManager.create(context) }
    HorizontalDivider()
    Text("Optional Google profile",style=MaterialTheme.typography.titleMedium)
    Text("Use Google to label your local profile. This does not sync files or sign you into Matrix. Maps, local profiles and offline features work without Google.",style=MaterialTheme.typography.bodySmall)
    if(BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) { Text("Google sign-in is awaiting configuration in this build. You can use a local profile or Matrix account now.",style=MaterialTheme.typography.bodySmall);return }
    profile?.let { p -> Text("${p.name.ifBlank { p.email }}\nLast verified ${checked(p.checkedAt)}",style=MaterialTheme.typography.bodySmall) }
    if(profile==null) OutlinedButton(onClick={ scope.launch {
        busy=true
        try {
            val nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
            val option=GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).setNonce(nonce).build()
            val result=manager.getCredential(MutableContextWrapper(context),GetCredentialRequest.Builder().addCredentialOption(option).build())
            val credential=result.credential as? CustomCredential ?: error("Unsupported credential")
            require(credential.type==GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            val token=GoogleIdTokenCredential.createFrom(credential.data).idToken
            val keys=SourceHttp(redirects=false).get("https://www.googleapis.com/oauth2/v3/certs",100_000,setOf("www.googleapis.com"))
            val p=withContext(Dispatchers.Default) { GoogleTokens.verify(token,keys,BuildConfig.GOOGLE_WEB_CLIENT_ID,nonce,System.currentTimeMillis()) }
            model.store.store.writeLocal("google-profile",model.store.json.encodeToString(p))
            if(model.store.extras().localName.isBlank() && p.name.isNotBlank()) model.localName(p.name.take(80))
            model.message("Google profile verified. Your files remain local; Matrix connects separately.")
        } catch(_: GetCredentialCancellationException) { }
        catch(_: NoCredentialException) { model.message("No Google credential is available. Add a Google account with a supported credential provider, or keep using your local profile.") }
        catch(e: Exception) { if(e is CancellationException) throw e;model.message("Google sign-in could not complete. Check internet, Play Services or provider support, and the OAuth web client, Android package and signing certificate configuration.") }
        finally { busy=false }
    } },enabled=!busy) { Text(if(busy) "Connecting…" else "Sign in with Google") }
    else TextButton(onClick={ scope.launch { model.store.store.writeLocal("google-profile","");runCatching { manager.clearCredentialState(ClearCredentialStateRequest()) };model.message("Google profile disconnected on this device.") } }) { Text("Disconnect Google profile") }
}
