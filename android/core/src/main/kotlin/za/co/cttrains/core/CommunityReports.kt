package za.co.cttrains.core

import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

@Serializable enum class ReportCategory(val title: String) { DELAYED("Delayed"), NOT_ARRIVED("Not arrived"), CANCELLED("Cancelled"), ROAD_OBSTRUCTION("Road obstruction"), OTHER("Other") }
@Serializable data class CommunityReport(val format: String = "cttrains.report", val version: Int = 1, val id: String, val service: TransportService, val stop: String, val route: String, val direction: String, val observedAt: Long, val category: ReportCategory, val message: String = "")
@Serializable data class ReceivedReport(val report: CommunityReport, val sender: String, val eventId: String, val serverTime: Long, val hosted: Boolean)
@Serializable data class ReportRoom(val service: TransportService, val room: String)
@Serializable data class QueuedReport(val report: CommunityReport, val room: String, val owner: String = "")

object CommunityReports {
    const val MAX_AGE = 60 * 60_000L
    fun validate(report: CommunityReport): CommunityReport {
        require(report.format == "cttrains.report" && report.version == 1 && report.id.length in 1..80)
        require(report.stop.length in 1..180 && report.route.length <= 100 && report.direction.length <= 180 && report.message.length <= 600 && report.observedAt > 0)
        require(listOf(report.stop,report.route,report.direction).none { text -> text.any { it.code < 32 } })
        return report
    }
    fun fresh(report: CommunityReport, now: Long) = report.observedAt <= now + 30_000 && now - report.observedAt <= MAX_AGE
    fun key(r: CommunityReport) = "${r.service}|${stationKey(r.stop)}|${r.route.trim().uppercase()}|${stationKey(r.direction)}"
    fun accounts(reports: List<ReceivedReport>, report: CommunityReport, now: Long): Int = reports
        .filter { it.hosted && fresh(it.report,now) && key(it.report)==key(report) }
        .groupBy { it.sender }.map { (_, list) -> list.maxBy { it.report.observedAt } }
        .count { it.report.category==report.category }
    fun text(report: CommunityReport): String {
        validate(report)
        val time=DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(CapeTownZone).format(Instant.ofEpochMilli(report.observedAt))
        return "CT Trains · Community report\nService: ${report.service.title}\nCategory: ${report.category.title}\nStop/station: ${report.stop}\nRoute/train: ${report.route.ifBlank { "Not specified" }}\nDirection: ${report.direction.ifBlank { "Not specified" }}\nObserved: $time SAST\nMessage: ${report.message.ifBlank { "No additional details" }}\nUser observation; not an official service update."
    }
}
