package za.co.cttrains.core

import java.math.BigDecimal
import kotlinx.serialization.Serializable

data class TicketType(val id: String, val label: String, val pattern: String)
@Serializable
data class FareCorrection(val from: String, val to: String, val ticketId: String, val cents: Long, val updatedAt: Long)

object TripBudget {
    val tickets = listOf(
        TicketType("single", "Single", "Single(?:\\s+(?:trip|ticket|fare))?"),
        TicketType("return", "Return", "Return(?:\\s+(?:trip|ticket|fare))?"),
        TicketType("weekly5", "Weekly Mon–Fri", "Weekly\\s*\\(?Mon(?:day)?\\s*[-–]\\s*Fri(?:day)?\\)?"),
        TicketType("weekly6", "Weekly Mon–Sat", "Weekly\\s*\\(?Mon(?:day)?\\s*[-–]\\s*Sat(?:urday)?\\)?"),
        TicketType("monthly", "Monthly", "Monthly"),
    )
    /** Accept explicit rand amounts only; never infer a missing fare. */
    fun sourcePrices(text: String?): Map<String, Long> = tickets.mapNotNull { type ->
        val amount = Regex(type.pattern + "\\s*:?\\s*R\\s*([0-9]+(?:[.,][0-9]{1,2})?)(?![0-9.,])", RegexOption.IGNORE_CASE).find(text.orEmpty())?.groupValues?.get(1)
        amount?.let(::parseCents)?.let { type.id to it }
    }.toMap()
    fun parseCents(text: String): Long? = runCatching {
        val clean = text.trim().removePrefix("R").trim().replace(',', '.')
        require(Regex("[0-9]{1,6}(?:\\.[0-9]{1,2})?").matches(clean))
        BigDecimal(clean).movePointRight(2).longValueExact().also { require(it in 0..10_000_000) }
    }.getOrNull()
    fun total(cents: Long, quantity: Int, travellers: Int): Long {
        require(cents in 0..10_000_000 && quantity in 1..100 && travellers in 1..20)
        return cents * quantity * travellers
    }
    fun correction(values: List<FareCorrection>, route: SavedRoute, ticketId: String): FareCorrection? =
        values.lastOrNull { it.from == route.from && it.to == route.to && it.ticketId == ticketId }
}
