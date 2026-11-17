package za.co.cttrains.app.lab

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import za.co.cttrains.app.updates.GitHubUpdateClient
import za.co.cttrains.app.updates.updateMessage

/** Intercept every request: no real tokens, network, GitHub quota or account state in tests. */
class UpdateAccessTest {
    private val api="https://api.github.com/repos/owner/repo/releases"
    private fun response(r: Request,code: Int,body: String="",vararg headers: Pair<String,String>)=
        Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody()).apply { headers.forEach { (k,v)->header(k,v) } }.build()
    private fun client(requests: MutableList<Request>,now: ()->Long={1_000_000L},handler: (Request)->Response)=
        GitHubUpdateClient(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain -> requests+=chain.request();handler(chain.request()) }.build(),mutableMapOf(),now)

    @Test fun staleSavedTokenDoesNotBreakPublicAccess() = runBlocking {
        val requests=mutableListOf<Request>()
        val client=client(requests) { r->response(r,if(r.header("Authorization")==null) 200 else 401,"[]") }
        client.request(api,"expired-fixture-token",false).use { assertEquals(200,it.code) }
        assertEquals(1,requests.size);assertNull(requests.single().header("Authorization"))
    }

    @Test fun privateAccessRetriesOnceWithTheOptionalToken() = runBlocking {
        val requests=mutableListOf<Request>()
        val client=client(requests) { r->response(r,if(r.header("Authorization")==null) 404 else 200,"[]") }
        client.request(api,"fixture-token",false).close()
        assertEquals(2,requests.size)
        assertNull(requests[0].header("Authorization"));assertEquals("Bearer fixture-token",requests[1].header("Authorization"))
    }

    @Test fun publicAssetUsesApiFallbackButNeverForwardsTokenToRedirects() = runBlocking {
        val requests=mutableListOf<Request>()
        val asset="https://api.github.com/repos/owner/repo/releases/assets/123"
        val cdn="https://release-assets.githubusercontent.com/fixture/app.apk"
        val client=client(requests) { r->when(r.url.host) {
            "github.com"->response(r,404)
            "api.github.com"->response(r,302,"","Location" to cdn)
            else->response(r,200,"fixture-bytes")
        } }
        client.request("https://github.com/owner/repo/releases/download/android-v1.0.0/app.apk","fixture-token",true,asset).close()
        assertEquals(3,requests.size)
        assertNull(requests[0].header("Authorization"));assertEquals(asset,requests[1].url.toString())
        assertEquals("Bearer fixture-token",requests[1].header("Authorization"));assertNull(requests[2].header("Authorization"))
    }

    @Test fun sameHostRedirectAlsoDropsTheCredential() = runBlocking {
        val requests=mutableListOf<Request>()
        val client=client(requests) { r->when {
            r.url.encodedPath.endsWith("/next")->response(r,200)
            r.header("Authorization")==null->response(r,404)
            else->response(r,302,"","Location" to "$api/next")
        } }
        client.request(api,"fixture-token",false).close()
        assertEquals(3,requests.size);assertNull(requests.last().header("Authorization"))
    }

    @Test fun rateLimitsUseBackoffInsteadOfTokenErrorsOrImmediateRetries() = runBlocking {
        for(kind in listOf("primary","retry-after","secondary")) {
            val requests=mutableListOf<Request>();var clock=1_000_000L;var limited=true
            val client=client(requests,{clock}) { r->if(!limited) response(r,200) else when(kind) {
                "primary"->response(r,403,"","X-RateLimit-Remaining" to "0","X-RateLimit-Reset" to "1180")
                "retry-after"->response(r,429,"","Retry-After" to "180")
                else->response(r,403,"You have exceeded a secondary rate limit")
            } }
            val first=runCatching { client.request(api,"fixture-token",false).close() }.exceptionOrNull()
            assertNotNull(first);assertTrue(updateMessage(first as Exception).contains("rate-limiting"))
            assertFalse(first.message.orEmpty().contains("token"))
            assertEquals(1,requests.size)
            assertTrue(runCatching { client.request(api,"fixture-token",false).close() }.isFailure)
            assertEquals("A second tap must respect the server backoff",1,requests.size)
            clock+=181_000;limited=false
            client.request(api,"fixture-token",false).close();assertEquals(2,requests.size)
        }
    }

    @Test fun authenticationFailureIsBoundedAndNeverExposesToken() = runBlocking {
        for(status in listOf(401,403)) {
            val requests=mutableListOf<Request>()
            val client=client(requests) { r->response(r,if(r.header("Authorization")==null) 404 else status) }
            val error=runCatching { client.request(api,"private-fixture-secret",false).close() }.exceptionOrNull()!!
            assertEquals(2,requests.size);assertFalse(error.message.orEmpty().contains("private-fixture-secret"))
            assertFalse(error.message.orEmpty().contains("rate-limiting"))
        }
    }

    @Test fun absentPrivateCredentialsDoNotCauseARetryLoop() = runBlocking {
        val requests=mutableListOf<Request>();val client=client(requests) { r->response(r,404) }
        assertTrue(runCatching { client.request(api,"",false).close() }.isFailure)
        assertEquals(1,requests.size)
    }

    @Test fun untrustedRedirectIsRejectedBeforeAnyRequestToThatHost() = runBlocking {
        for(target in listOf("https://evil.example/app.apk","http://github.com/app.apk","https://user:pass@github.com/app.apk")) {
            val requests=mutableListOf<Request>();val client=client(requests) { r->response(r,302,"","Location" to target) }
            assertTrue(runCatching { client.request(api,"fixture-token",true).close() }.isFailure)
            assertEquals(1,requests.size)
        }
    }
}
