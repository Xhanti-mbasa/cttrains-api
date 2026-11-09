package za.co.cttrains.core

import kotlinx.serialization.json.*

data class SurveyPage(val ids: List<Long>,val next: String?)
object GoldenSurvey {
    fun where(after: String?): String {
        require(after==null || (after.length in 1..80 && after.none(Char::isISOControl)))
        return "BUSSTOPNO <> '-' AND BUSSTOPNO IS NOT NULL AND BUSSTOPNO <> ''"+
            (after?.let { " AND BUSSTOPNO > '${it.replace("'","''")}'" } ?: "")
    }
    // This service ignores resultOffset for aggregated statistics. Advance the WHERE key.
    fun ids(raw: String,after: String?): SurveyPage {
        val root=Json.parseToJsonElement(raw).jsonObject;require(root["error"]==null)
        val rows=root.getValue("features").jsonArray;require(rows.size<=2000)
        val ids=rows.map { it.jsonObject.getValue("attributes").jsonObject.getValue("sample_id").jsonPrimitive.long }
        require(ids.all { it>0 } && ids.distinct().size==ids.size)
        val more=root["exceededTransferLimit"]?.jsonPrimitive?.booleanOrNull==true
        val next=if(more) rows.lastOrNull()?.jsonObject?.get("attributes")?.jsonObject?.get("BUSSTOPNO")?.jsonPrimitive?.content ?: error("Empty continued survey page") else null
        require(next==null || next!=after) { "The survey did not advance to a new stop code." }
        return SurveyPage(ids,next)
    }
}
