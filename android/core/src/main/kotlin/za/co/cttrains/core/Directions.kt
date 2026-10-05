package za.co.cttrains.core

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable enum class DirectionKind { WALK, BOARD, RIDE }
@Serializable data class WalkInstruction(val point: GeoPoint,val text: String,val distanceFromStart: Double)
@Serializable data class DirectionStep(val id: String,val kind: DirectionKind,val title: String,val detail: String,val target: GeoPoint?,
    val points: List<GeoPoint> = emptyList(),val instructions: List<WalkInstruction> = emptyList(),val distanceMeters: Double?=null,
    val durationSeconds: Double?=null,val scheduledAt: Long?=null)
@Serializable data class DirectionsPlan(val id: String,val title: String,val steps: List<DirectionStep>,val createdAt: Long,val sourceCheckedAt: Long,val notice: String="")
@Serializable data class DirectionsSession(val plan: DirectionsPlan,val index: Int=0,val startedAt: Long,val paused: Boolean=false,val promote: Boolean=true) {
    val finished get()=index>=plan.steps.size
    val step get()=plan.steps.getOrNull(index)
}
data class DirectionFix(val point: GeoPoint,val accuracy: Double,val at: Long)
data class DirectionGuidance(val title: String,val detail: String,val remainingMeters: Int?=null,val minutes: Int?=null,
    val progress: Int=0,val nearTarget: Boolean=false,val offRoute: Boolean=false,val fresh: Boolean=false)

/** Phone progress only. A clock never confirms boarding, an arrival, or a vehicle delay. */
object DirectionsEngine {
    const val MAX_SESSION_MS=4*60*60_000L
    fun validate(plan: DirectionsPlan): DirectionsPlan {
        require(plan.id.length in 1..180 && plan.title.length in 1..300 && plan.steps.size in 1..100)
        require(plan.steps.sumOf { it.points.size }<=30000)
        plan.steps.forEach { s ->
            require(s.id.isNotBlank() && s.title.length in 1..300 && s.detail.length<=1000)
            require(s.target==null || s.target.valid)
            require(s.points.all(GeoPoint::valid) && s.instructions.size<=1000)
            require(s.instructions.all { it.point.valid && it.text.length in 1..300 && it.distanceFromStart.isFinite() && it.distanceFromStart>=0 })
            require(s.distanceMeters==null || s.distanceMeters.isFinite() && s.distanceMeters in 0.0..30000.0)
            require(s.durationSeconds==null || s.durationSeconds.isFinite() && s.durationSeconds in 0.0..28800.0)
        }
        return plan
    }
    fun alive(s: DirectionsSession,now: Long)=s.startedAt<=now && now-s.startedAt<MAX_SESSION_MS && !s.finished && s.index>=0
    fun advance(s: DirectionsSession)=s.copy(index=(s.index+1).coerceAtMost(s.plan.steps.size))
    fun back(s: DirectionsSession)=s.copy(index=(s.index-1).coerceAtLeast(0))
    fun fresh(fix: DirectionFix?,now: Long)=fix!=null && fix.point.valid && fix.accuracy.isFinite() && fix.accuracy in 0.0..50.0 && now-fix.at in 0..45_000
    fun guidance(s: DirectionsSession,fix: DirectionFix?,now: Long): DirectionGuidance {
        val step=s.step ?: return DirectionGuidance("Journey finished","Directions stopped")
        if(s.paused) return DirectionGuidance("Directions paused",step.title)
        val usable=fresh(fix,now)
        val targetDistance=if(usable && step.target!=null) NearbySuggestions.distance(fix!!.point,step.target) else null
        val near=targetDistance!=null && targetDistance<=60
        val baseProgress=(s.index*100/s.plan.steps.size).coerceIn(0,100)
        if(step.kind==DirectionKind.WALK && usable && step.points.size>=2) {
            // Nearest mapped path vertex, deliberately an estimate rather than an exact turn trigger.
            val nearest=step.points.indices.minByOrNull { NearbySuggestions.distance(fix!!.point,step.points[it]) }!!
            val away=NearbySuggestions.distance(fix!!.point,step.points[nearest])>100
            if(away) return DirectionGuidance("Away from the saved walking path","Check the map or recalculate this walk in Plan.",offRoute=true,fresh=true,progress=baseProgress)
            val lengths=step.points.zipWithNext { a,b->NearbySuggestions.distance(a,b) }
            val geometry=lengths.sum();val fraction=if(geometry>0) lengths.take(nearest).sum()/geometry else 0.0
            val distance=step.distanceMeters ?: geometry
            val covered=distance*fraction
            val remaining=ceil(distance-covered).toInt().coerceAtLeast(0)
            val instruction=step.instructions.lastOrNull { it.distanceFromStart<=covered+10 }
            val next=step.instructions.firstOrNull { it.distanceFromStart>covered+10 }
            val title=if(near) "Arriving · ${step.title.substringAfter("Walk to ")}" else instruction?.text ?: step.title
            val detail=next?.let { "In ${ceil(it.distanceFromStart-covered).toInt()} m · ${it.text}" } ?: step.detail
            return DirectionGuidance(title,detail,remaining,step.durationSeconds?.let { ceil(it*(1-fraction)/60).toInt() },
                ((s.index+fraction)*100/s.plan.steps.size).toInt().coerceIn(0,99),near,fresh=true)
        }
        val clock=step.scheduledAt?.let { java.time.Instant.ofEpochMilli(it).atZone(CapeTownZone).toLocalTime().toString().take(5) }
        val detail=buildList {
            add(step.detail)
            if(clock!=null) add(if(now>step.scheduledAt!!) "Scheduled $clock has passed; check service status." else "Scheduled $clock")
            if(!usable) add("Waiting for an accurate location; use the step controls.")
        }.joinToString(" · ")
        val title=if(near && step.kind==DirectionKind.RIDE) "Near your stop · prepare to get off" else step.title
        return DirectionGuidance(title,detail,targetDistance?.let { ceil(it).toInt() },progress=baseProgress,nearTarget=near,fresh=usable)
    }
}
