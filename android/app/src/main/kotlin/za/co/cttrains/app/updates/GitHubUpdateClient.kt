package za.co.cttrains.app.updates

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/** Public first; private-repository credentials are a bounded fallback, never redirect headers. */
internal class GitHubUpdateClient(
    private val http: OkHttpClient,
    private val retryAt: MutableMap<String, Long> = sharedRetryAt,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun request(url: String, token: String, binary: Boolean, privateApiUrl: String = url): Response {
        var target = url.toHttpUrl()
        val authenticatedTarget = privateApiUrl.toHttpUrl()
        var authenticated = false
        var sendCredential = false
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            require(target.isHttps && target.host in hosts && target.username.isEmpty() && target.password.isEmpty()) { "Untrusted update download address." }
            retryAt[target.host]?.takeIf { it > now() }?.let { error(rateMessage(it)) }
            val builder = Request.Builder().url(target).header("User-Agent", "CT-Trains-Android-updater")
                .header("Accept", if (binary) "application/octet-stream" else "application/vnd.github+json")
            if (sendCredential && target.host == "api.github.com") builder.header("Authorization", "Bearer $token")
            val call = http.newCall(builder.build())
            val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
            val response = try { call.execute() } finally { cancellation?.dispose() }
            if (response.code in 200..299) return response
            if (response.code in listOf(301, 302, 303, 307, 308)) {
                val location = response.header("Location")
                response.close()
                require(++redirects <= 5) { "Too many download redirects." }
                target = location?.let(target::resolve) ?: error("GitHub supplied an invalid download redirect.")
                sendCredential = false
                continue
            }
            val code = response.code
            val limited = code == 429 || (code == 403 && (response.header("X-RateLimit-Remaining") == "0" ||
                response.header("Retry-After") != null || response.peekBody(4096).string().contains("rate limit", ignoreCase = true)))
            if (limited) {
                val seconds = response.header("Retry-After")?.toLongOrNull()?.takeIf { it in 1..604800 }
                val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
                val until = maxOf(now() + (seconds ?: 60) * 1000, reset ?: 0)
                response.close()
                retryAt[target.host] = until
                error(rateMessage(until))
            }
            // Restart once at the API endpoint only if public access was unavailable.
            // Never replay a credential to a CDN or an arbitrary redirect destination.
            val tryPrivate = !authenticated && token.isNotEmpty() && code in listOf(401, 403, 404) &&
                target.host in setOf("api.github.com", "github.com") && authenticatedTarget.isHttps &&
                authenticatedTarget.host == "api.github.com" && authenticatedTarget.username.isEmpty() && authenticatedTarget.password.isEmpty()
            response.close()
            if (tryPrivate) {
                authenticated = true
                sendCredential = true
                target = authenticatedTarget
                continue
            }
            error(when (code) {
                401 -> "GitHub rejected the saved token. Replace it for a private repository; public releases need no token."
                403 -> "GitHub refused this update request. For a private repository, check the token's read-only repository access."
                404 -> "No accessible releases or update asset found. Check the repository and published release; private repositories need read-only access."
                else -> "GitHub could not be reached ($code). Retry later."
            })
        }
    }

    private fun rateMessage(until: Long): String {
        val minutes = ((until - now()).coerceAtLeast(1) / 60000 + 1)
        return "GitHub is temporarily rate-limiting update requests. Try again in $minutes min. Your installed app is unchanged."
    }

    companion object {
        private val hosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        private val sharedRetryAt = ConcurrentHashMap<String, Long>()
    }
}
