package za.co.cttrains.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.util.UUID
import java.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class TripStep(val kind: String, val service: TransportService?, val label: String, val at: Long, val platform: String? = null, val route: String? = null)
@Serializable data class SafetyTrip(val format: String = "cttrains.trip", val version: Int = 1, val id: String, val owner: String, val createdAt: Long, val steps: List<TripStep>, val sourceCheckedAt: Long? = null, val note: String = "Planned times; confirm services and boarding locations.")
@Serializable data class MarkedSpot(val id: String, val name: String, val service: TransportService?, val point: GeoPoint, val tag: String, val note: String = "", val photo: String? = null, val createdAt: Long, val preferredStation: String? = null)
@Serializable data class PortableEnvelope(val format: String = "cttrains.profile.encrypted", val version: Int = 1, val iterations: Int = 210000, val salt: String, val nonce: String, val ciphertext: String)
@Serializable data class CircleLink(val format: String = "cttrains.circle", val version: Int = 1, val id: String, val room: String, val ownerId: String, val secret: String, val expiresAt: Long, val trip: SafetyTrip)
@Serializable data class FriendUpdate(val circleId: String, val sequence: Long, val sentAt: Long, val expiresAt: Long, val phase: String, val point: GeoPoint? = null, val accuracy: Float? = null, val near: String? = null)
@Serializable data class SealedUpdate(val circleId: String, val nonce: String, val ciphertext: String)

object TripFiles {
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    const val MAX_TRIP_BYTES = 256 * 1024
    fun document(raw: String,maxBytes: Int=MAX_TRIP_BYTES) {
        require(raw.toByteArray().size<=maxBytes)
        var depth=0;var quoted=false;var escape=false
        raw.forEach { c->if(quoted) { if(escape) escape=false else if(c=='\\') escape=true else if(c=='"') quoted=false } else when(c) { '"'->quoted=true;'{','['->{ depth++;require(depth<=32) };'}',']'->{ depth--;require(depth>=0) } } }
        require(depth==0 && !quoted)
    }
    fun validate(trip: SafetyTrip): SafetyTrip {
        require(trip.format == "cttrains.trip" && trip.version == 1 && trip.id.length in 1..80 && trip.owner.length in 1..80)
        require(trip.createdAt > 0 && trip.steps.size in 2..100 && trip.note.length <= 500)
        require(trip.steps.all { it.kind in listOf("leave","board","alight","transfer","arrive","walk") && it.label.length in 1..180 && it.at > 0 && it.route.orEmpty().length <= 100 && (it.platform == null || Regex("[0-9]{1,2}[A-Za-z]?").matches(it.platform)) })
        require(trip.steps.zipWithNext().all { (a,b) -> b.at >= a.at } && trip.steps.last().at - trip.steps.first().at <= 24 * 60 * 60_000L)
        return trip
    }
    fun parse(raw: String): SafetyTrip { document(raw); return validate(json.decodeFromString<SafetyTrip>(raw)) }
    fun fromJourney(query: SearchQuery, journey: Journey, owner: String, access: Int, buffer: Int, origin: String, destination: String, finalWalk: Int, now: Long, checkedAt: Long?): SafetyTrip {
        require(access in 0..360 && buffer in 0..120 && finalWalk in 0..360)
        val base = LocalDate.parse(query.date).atStartOfDay(CapeTownZone).toInstant().toEpochMilli()
        var dayOffset = 0L; var previous = -1L
        fun stamp(time: String): Long { val ms = LocalTime.parse(time).toSecondOfDay()*1000L; if (previous >= 0 && ms < previous) dayOffset += 24*60*60_000L; previous=ms; return base+dayOffset+ms }
        val steps = mutableListOf<TripStep>()
        journey.legs.forEachIndexed { i, leg ->
            val departure = stamp(leg.departure.time)
            if (i == 0) { steps += TripStep("leave",null,origin.ifBlank { "Leave for station" },departure-(access+buffer)*60_000L); steps += TripStep("walk",null,"Reach ${leg.departure.name} · ${access} min access estimate",departure-buffer*60_000L) }
            else steps += TripStep("transfer",TransportService.PRASA,"Change trains at ${leg.departure.name}",departure,leg.departure.platform,leg.train)
            steps += TripStep("board",TransportService.PRASA,leg.departure.name,departure,leg.departure.platform,leg.train)
            steps += TripStep("alight",TransportService.PRASA,leg.arrival.name,stamp(leg.arrival.time),leg.arrival.platform,leg.train)
        }
        steps += TripStep("arrive",null,destination.ifBlank { "Destination" },steps.last().at+finalWalk*60_000L)
        return validate(SafetyTrip(id=UUID.randomUUID().toString(),owner=owner.trim().ifBlank { "Traveller" },createdAt=now,steps=steps,sourceCheckedAt=checkedAt))
    }
    fun text(trip: SafetyTrip) = buildString {
        append("CT Trains · Safety trip\n${trip.owner}\n")
        val format = java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(CapeTownZone)
        trip.steps.forEach { s -> append("${format.format(Instant.ofEpochMilli(s.at))} · ${s.kind.replaceFirstChar(Char::uppercase)} · ${s.label}${s.route?.let { " · ${s.service?.title ?: "Service"} $it" }.orEmpty()}${if (s.kind in listOf("board","transfer")) " · Platform ${s.platform ?: "unknown"}" else ""}\n") }
        append("${trip.note}\nSouth African time. Shared itinerary; not a live location feed.")
    }
}

/** Portable passwords are independent of Android's installation-specific Keystore. */
object PortableCrypto {
    private val random = SecureRandom()
    fun encrypt(plain: String, password: CharArray): String {
        require(password.size in 10..256 && plain.toByteArray().size <= 24*1024*1024)
        val salt = ByteArray(16).also(random::nextBytes); val nonce = ByteArray(12).also(random::nextBytes)
        val key = derive(password,salt,210000)
        val encrypted = try { crypt(Cipher.ENCRYPT_MODE,key,nonce,plain.toByteArray(),"cttrains.profile.v1".toByteArray()) } finally { key.fill(0) }
        return TripFiles.json.encodeToString(PortableEnvelope(salt=encode(salt),nonce=encode(nonce),ciphertext=encode(encrypted)))
    }
    fun decrypt(raw: String, password: CharArray): String {
        TripFiles.document(raw,34*1024*1024);require(password.size in 10..256)
        val e = TripFiles.json.decodeFromString<PortableEnvelope>(raw)
        require(e.format == "cttrains.profile.encrypted" && e.version == 1 && e.iterations == 210000)
        val salt = decode(e.salt); val nonce=decode(e.nonce); require(salt.size==16 && nonce.size==12)
        val key=derive(password,salt,e.iterations)
        return try { crypt(Cipher.DECRYPT_MODE,key,nonce,decode(e.ciphertext),"cttrains.profile.v1".toByteArray()).toString(Charsets.UTF_8) } finally { key.fill(0) }
    }
    private fun derive(password: CharArray,salt: ByteArray,iterations: Int): ByteArray {
        val spec=PBEKeySpec(password,salt,iterations,256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    fun encode(bytes: ByteArray)=Base64.getEncoder().encodeToString(bytes)
    fun decode(value: String)=Base64.getDecoder().decode(value)
    fun secret()=encode(ByteArray(32).also(random::nextBytes))
    fun crypt(mode: Int,key: ByteArray,nonce: ByteArray,input: ByteArray,aad: ByteArray): ByteArray {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));cipher.updateAAD(aad);return cipher.doFinal(input)
    }
}

object CircleUpdates {
    val phases=listOf("Ready","Leaving","At boarding stop","On board","At transfer","Arrived","Stopped")
    fun validate(link: CircleLink, now: Long): CircleLink {
        TripFiles.validate(link.trip)
        require(link.format=="cttrains.circle" && link.version==1 && link.id.length in 1..80 && Regex("![^\\s]{1,250}:[^\\s]{1,250}").matches(link.room) && Regex("@[^\\s]{1,250}:[^\\s]{1,250}").matches(link.ownerId))
        require(PortableCrypto.decode(link.secret).size==32 && link.expiresAt>now && link.expiresAt<=now+6*60*60_000L)
        return link
    }
    fun seal(link: CircleLink, update: FriendUpdate): SealedUpdate {
        require(update.circleId==link.id && update.expiresAt==link.expiresAt)
        val nonce=ByteArray(12).also(SecureRandom()::nextBytes)
        val bytes=PortableCrypto.crypt(Cipher.ENCRYPT_MODE,PortableCrypto.decode(link.secret),nonce,TripFiles.json.encodeToString(update).toByteArray(),"${link.id}|${link.room}|${link.ownerId}|v1".toByteArray())
        return SealedUpdate(link.id,PortableCrypto.encode(nonce),PortableCrypto.encode(bytes))
    }
    fun open(link: CircleLink,sealed: SealedUpdate,sender: String,now: Long,lastSequence: Long): FriendUpdate {
        require(sender==link.ownerId && sealed.circleId==link.id && sealed.ciphertext.length<=12000)
        val nonce=PortableCrypto.decode(sealed.nonce);require(nonce.size==12)
        val plain=PortableCrypto.crypt(Cipher.DECRYPT_MODE,PortableCrypto.decode(link.secret),nonce,PortableCrypto.decode(sealed.ciphertext),"${link.id}|${link.room}|${link.ownerId}|v1".toByteArray())
        val u=TripFiles.json.decodeFromString<FriendUpdate>(plain.toString(Charsets.UTF_8))
        require(u.circleId==link.id && u.sequence>=0 && u.sequence>lastSequence && u.sentAt>0 && u.sentAt<=now+30000 && now-u.sentAt<=5*60_000 && u.expiresAt==link.expiresAt && u.expiresAt>now && u.phase in phases && u.near.orEmpty().length<=180)
        require(u.point==null || u.point.valid && u.accuracy!=null && u.accuracy.isFinite() && u.accuracy in 0f..5000f)
        return u
    }
}
