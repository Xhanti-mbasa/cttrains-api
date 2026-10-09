package za.co.cttrains.core

import java.util.UUID

object JourneySharing {
    fun itinerary(plan: DirectionsPlan,service: TransportService,owner: String,now: Long): SafetyTrip {
        DirectionsEngine.validate(plan)
        val first=plan.steps.indexOfFirst { it.kind==DirectionKind.BOARD }
        require(first>=0)
        val boarding=plan.steps[first].scheduledAt ?: error("Choose a timed journey")
        var at=boarding-plan.steps.take(first).sumOf { ((it.durationSeconds ?: 0)*1000).toLong() }
        val steps=plan.steps.map { step->
            when(step.kind) {
                DirectionKind.WALK-> { at+=((step.durationSeconds ?: 0)*1000).toLong();TripStep("walk",null,step.title.take(180),at) }
                DirectionKind.BOARD-> { at=step.scheduledAt ?: at;TripStep("board",service,step.title.take(180),at) }
                DirectionKind.RIDE-> { at=step.scheduledAt ?: at;TripStep("alight",service,step.title.take(180),at) }
            }
        }
        return TripFiles.validate(SafetyTrip(id=UUID.randomUUID().toString(),owner=owner.trim().ifBlank { "Traveller" }.take(80),createdAt=now,steps=steps,sourceCheckedAt=plan.sourceCheckedAt,note="Shared itinerary · scheduled times, not live tracking. "+plan.notice.take(400)))
    }
}
