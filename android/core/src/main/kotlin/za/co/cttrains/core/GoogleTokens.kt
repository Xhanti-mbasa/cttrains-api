package za.co.cttrains.core

import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class GoogleProfile(val subject: String,val email: String,val name: String,val checkedAt: Long,val expiresAt: Long)

/** Local identity only. A future backend must independently verify the token before granting server access. */
object GoogleTokens {
    fun verify(token: String,jwks: String,clientId: String,nonce: String,now: Long): GoogleProfile {
        require(token.length<=16000 && jwks.length<=100000 && clientId.endsWith(".apps.googleusercontent.com") && nonce.length>=32)
        val parts=token.split('.');require(parts.size==3)
        val decoder=Base64.getUrlDecoder()
        val head=Json.parseToJsonElement(decoder.decode(parts[0]).toString(Charsets.UTF_8)).jsonObject
        require(head["alg"]?.jsonPrimitive?.content=="RS256")
        val key=Json.parseToJsonElement(jwks).jsonObject.getValue("keys").jsonArray.single { it.jsonObject["kid"]==head["kid"] }.jsonObject
        require(key["kty"]?.jsonPrimitive?.content=="RSA" && key["alg"]?.jsonPrimitive?.content=="RS256")
        val n=BigInteger(1,decoder.decode(key.getValue("n").jsonPrimitive.content));val e=BigInteger(1,decoder.decode(key.getValue("e").jsonPrimitive.content))
        require(n.bitLength()>=2048)
        val public=KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n,e))
        val sig=Signature.getInstance("SHA256withRSA");sig.initVerify(public);sig.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII));require(sig.verify(decoder.decode(parts[2])))
        val c=Json.parseToJsonElement(decoder.decode(parts[1]).toString(Charsets.UTF_8)).jsonObject
        require(c["iss"]?.jsonPrimitive?.content in listOf("accounts.google.com","https://accounts.google.com"))
        val aud=c["aud"];require(if(aud is JsonArray) aud.any { it.jsonPrimitive.content==clientId } && c["azp"]?.jsonPrimitive?.content==clientId else aud?.jsonPrimitive?.content==clientId)
        require(c["nonce"]?.jsonPrimitive?.content==nonce)
        val exp=c.getValue("exp").jsonPrimitive.long*1000;val iat=c.getValue("iat").jsonPrimitive.long*1000
        require(exp>now && iat<=now+60_000 && iat>=now-24*60*60_000L && exp>iat && exp-iat<=24*60*60_000L)
        val sub=c.getValue("sub").jsonPrimitive.content;val email=c.getValue("email").jsonPrimitive.content
        require(sub.length in 1..255 && email.length in 3..320 && c["email_verified"]?.jsonPrimitive?.booleanOrNull==true)
        return GoogleProfile(sub,email,c["name"]?.jsonPrimitive?.content.orEmpty().take(100),now,exp)
    }
}
