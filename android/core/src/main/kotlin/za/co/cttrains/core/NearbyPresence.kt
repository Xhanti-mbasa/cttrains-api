package za.co.cttrains.core

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable data class NearbyPresence(val format: String="cttrains.nearby",val version: Int=1,val sentAt: Long,val expiresAt: Long,val point: GeoPoint?=null,val active: Boolean=true)
data class NearbyPerson(val sender: String,val presence: NearbyPresence,val distanceMeters: Double)
object NearbyPeople {
    const val TTL=5*60_000L
    fun coarse(point: GeoPoint): GeoPoint { require(point.valid);return GeoPoint(round(point.latitude*100)/100,round(point.longitude*100)/100) }
    fun create(point: GeoPoint,now: Long)=NearbyPresence(sentAt=now,expiresAt=now+TTL,point=coarse(point))
    fun validate(p: NearbyPresence): NearbyPresence {
        require(p.format=="cttrains.nearby" && p.version==1 && p.sentAt>0 && p.expiresAt in p.sentAt..p.sentAt+TTL)
        require(if(p.active) p.point?.valid==true && abs(p.point.latitude*100-round(p.point.latitude*100))<.00001 && abs(p.point.longitude*100-round(p.point.longitude*100))<.00001 else p.point==null)
        return p
    }
    fun visible(rows: List<Pair<String,NearbyPresence>>,self: String,point: GeoPoint,now: Long): List<NearbyPerson> = rows.filter { it.first!=self }.groupBy { it.first }.mapNotNull { (sender,entries)->
        val p=entries.maxBy { it.second.sentAt }.second
        if(runCatching { validate(p) }.isFailure || !p.active || p.sentAt>now+30_000 || now>=p.expiresAt) null
        else p.point?.let { at->val distance=NearbySuggestions.distance(coarse(point),at);if(distance<=5000) NearbyPerson(sender,p,distance) else null }
    }.sortedBy { it.distanceMeters }.take(50)
}
