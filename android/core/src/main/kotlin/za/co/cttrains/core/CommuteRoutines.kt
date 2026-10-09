package za.co.cttrains.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Serializable data class CommuteRoutine(val id: String, val name: String, val query: SearchQuery,
    val days: Set<Int>, val sourceId: String, val accessMinutes: Int, val bufferMinutes: Int,
    val reminderMinutes: Int? = null, val origin: WalkEndpoint? = null, val destination: WalkEndpoint? = null,
    val enabled: Boolean = true, val createdAt: Long, val service: TransportService = TransportService.PRASA,
    val busRoute: BusRouteDocument? = null, val boardCallId: String? = null, val alightCallId: String? = null)

object CommuteRoutines {
    fun validate(r: CommuteRoutine): CommuteRoutine {
        require(r.id.length in 1..80 && r.name.length in 1..100)
        require(r.days.isNotEmpty() && r.days.all { it in 1..7 })
        require(r.query.from.length in 1..180 && r.query.to.length in 1..180 && r.query.from != r.query.to)
        LocalDate.parse(r.query.date); LocalTime.parse(r.query.time)
        if(r.service==TransportService.PRASA) require(r.sourceId in setOf("cttrains-web", "cttrains-api"))
        else { require(r.busRoute!=null && r.busRoute.id.length in 1..500 && r.busRoute.url.startsWith("https://"));require(r.boardCallId?.length in 1..180 && r.alightCallId?.length in 1..180) }
        require(r.accessMinutes in 0..360 && r.bufferMinutes in 0..120 && (r.reminderMinutes == null || r.reminderMinutes in 0..1440))
        listOfNotNull(r.origin, r.destination).forEach { require(it.point.valid && it.label.length in 1..100 && it.id.length<=500) }
        return r
    }
    fun departure(r: CommuteRoutine, date: LocalDate): Long = date.atTime(LocalTime.parse(r.query.time)).atZone(CapeTownZone).toInstant().toEpochMilli()
    fun dates(r: CommuteRoutine, now: Long, horizon: Int = 8): List<LocalDate> {
        validate(r); require(horizon in 1..31)
        if(!r.enabled) return emptyList()
        val today = Instant.ofEpochMilli(now).atZone(CapeTownZone).toLocalDate()
        return (0 until horizon).map { today.plusDays(it.toLong()) }.filter { it.dayOfWeek.value in r.days && departure(r,it)>now }
    }
    fun checkAt(r: CommuteRoutine,date: LocalDate): Long = departure(r,date)-(r.accessMinutes+r.bufferMinutes+(r.reminderMinutes ?: 0)+60)*60_000L
}

/** Home/Work/School update the same local place; other pins keep their own identity. */
object SavedAddresses {
    val labels=listOf("Home","Work","School")
    fun role(s: MarkedSpot): String? = labels.firstOrNull { it.equals(s.tag,true) || it.equals(s.name,true) || (it=="School" && s.tag.equals("Campus",true)) }
    fun upsert(spots: List<MarkedSpot>,value: MarkedSpot): List<MarkedSpot> {
        val kind=role(value)
        val previous=spots.firstOrNull { it.id==value.id } ?: kind?.let { label->spots.firstOrNull { role(it)==label } }
        val saved=value.copy(id=previous?.id ?: value.id,createdAt=previous?.createdAt ?: value.createdAt,
            photo=value.photo ?: previous?.photo,preferredStation=value.preferredStation ?: previous?.preferredStation)
        return (spots.filterNot { it.id==saved.id || (kind!=null && role(it)==kind) }+saved).takeLast(100)
    }
}
