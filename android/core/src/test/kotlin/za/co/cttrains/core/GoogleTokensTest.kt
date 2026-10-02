package za.co.cttrains.core

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class GoogleTokensTest {
    private val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val client="test.apps.googleusercontent.com"
    private val nonce="nonce-test-".repeat(4)
    private val now=1_791_000_000_000L
    private fun encode(b: ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun jwt(aud: String=client,issuer: String="https://accounts.google.com",tokenNonce: String=nonce,expire: Long=now/1000+3600): String {
        val head=encode("{\"alg\":\"RS256\",\"kid\":\"test\"}".toByteArray())
        val body=encode("{\"iss\":\"$issuer\",\"aud\":\"$aud\",\"nonce\":\"$tokenNonce\",\"iat\":${now/1000},\"exp\":$expire,\"sub\":\"1234\",\"email\":\"test@example.test\",\"email_verified\":true,\"name\":\"Test\"}".toByteArray())
        val signature=Signature.getInstance("SHA256withRSA").apply { initSign(pair.private);update("$head.$body".toByteArray()) }.sign()
        return "$head.$body.${encode(signature)}"
    }
    private fun jwks(): String { val p=pair.public as RSAPublicKey;fun positive(b: ByteArray)=if(b.first()==0.toByte()) b.drop(1).toByteArray() else b
        return "{\"keys\":[{\"kid\":\"test\",\"alg\":\"RS256\",\"kty\":\"RSA\",\"n\":\"${encode(positive(p.modulus.toByteArray()))}\",\"e\":\"${encode(p.publicExponent.toByteArray())}\"}]}"
    }
    @Test fun `Google identity requires a valid signature audience nonce issuer and expiry`() {
        assertEquals("test@example.test",GoogleTokens.verify(jwt(),jwks(),client,nonce,now).email)
        listOf(jwt(aud="other.apps.googleusercontent.com"),jwt(issuer="https://evil.test"),jwt(tokenNonce="different"),jwt(expire=now/1000-1)).forEach { token->assertThrows(Exception::class.java) { GoogleTokens.verify(token,jwks(),client,nonce,now) } }
    }
    @Test fun `changing signed identity or signature is rejected`() {
        val parts=jwt().split('.');val bytes=Base64.getUrlDecoder().decode(parts[2]).apply { this[0]=(this[0].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { GoogleTokens.verify("${parts[0]}.${parts[1]}.${encode(bytes)}",jwks(),client,nonce,now) }
    }
}
