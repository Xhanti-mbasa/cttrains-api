package za.co.cttrains.app.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*

class HttpStatusException(val status: Int): IOException(if(status==429) "The service asked us to slow down. Try again later." else "Service returned HTTP $status.")

class SourceHttp(cookies: Boolean = false, private val redirects: Boolean = true) {
    private val jar=object: CookieJar {
        private val values=mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl,cookies: List<Cookie>) { cookies.forEach { next -> values.removeAll { it.name==next.name && it.domain==next.domain }; values+=next } }
        @Synchronized override fun loadForRequest(url: HttpUrl)=values.filter { it.matches(url) && it.expiresAt>System.currentTimeMillis() }
    }
    private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS)
        .followRedirects(redirects).followSslRedirects(redirects).cookieJar(if(cookies) jar else CookieJar.NO_COOKIES).build()
    suspend fun bytes(request: Request,limit: Int=8*1024*1024,allowedHosts: Set<String>?=null): ByteArray = suspendCancellableCoroutine { continuation ->
        require(request.url.isHttps && request.url.username.isBlank() && request.url.password.isBlank())
        val call=client.newCall(request.newBuilder().header("User-Agent","CTTrains-Android/1.6 (community app; https://github.com/Xhanti-mbasa)").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call,response: Response) {
                try { response.use {
                    if (!it.isSuccessful) throw HttpStatusException(it.code)
                    if (allowedHosts!=null && it.request.url.host !in allowedHosts) throw IOException("The source redirected to another service.")
                    val body=it.body ?: throw IOException("Empty response.")
                    require(body.contentLength()<=limit)
                    val output=java.io.ByteArrayOutputStream();body.byteStream().use { input -> val buffer=ByteArray(16384); var read=input.read(buffer)
                        while(read>=0) { if(output.size()+read>limit) throw IOException("The response is too large.");output.write(buffer,0,read);read=input.read(buffer) } }
                    if(continuation.isActive) continuation.resume(output.toByteArray())
                } } catch(e: Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    suspend fun get(url: String,limit: Int=8*1024*1024,hosts: Set<String>?=null)=bytes(Request.Builder().url(url).build(),limit,hosts).toString(Charsets.UTF_8)
}
