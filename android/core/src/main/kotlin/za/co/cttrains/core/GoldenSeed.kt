package za.co.cttrains.core
import kotlinx.serialization.json.*
object GoldenSeed {
    fun parse(raw: String): BusSnapshot {
        val root=Json.parseToJsonElement(raw).jsonObject
        val at=root.getValue("checkedAt").jsonPrimitive.long
        val stops=root.getValue("stops").jsonArray.map { e->val a=e.jsonArray
            val p=GeoPoint(a[2].jsonPrimitive.double,a[3].jsonPrimitive.double)
            BusStopPoint("${a[0].jsonPrimitive.content}:${p.latitude}:${p.longitude}",a[1].jsonPrimitive.content,p,emptyList(),"${BusSources.GABS_SURVEY}/6")
        }
        require(at>0)
        return BusSources.validate(BusSnapshot(TransportService.GOLDEN_ARROW,at,emptyList(),stops,emptyList(),emptyList(),
            "Bundled government survey checked 2 October 2026. One representative record per numbered stop; separate directions/platforms are not verified. Survey capture date and current operation are unknown. Refresh to check the source.",mapFetchedAt=at,catalogueCheckedAt=0,faresCheckedAt=0))
    }
}
