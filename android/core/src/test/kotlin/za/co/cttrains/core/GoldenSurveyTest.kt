package za.co.cttrains.core
import org.junit.Assert.*
import org.junit.Test
class GoldenSurveyTest {
    private val page="""{"features":[{"attributes":{"BUSSTOPNO":"3120","sample_id":22952}}],"exceededTransferLimit":true}"""
    @Test fun aggregatedPagesAdvanceByStopCodeRatherThanIgnoredOffset() {
        val first=GoldenSurvey.ids(page,null);assertEquals(listOf(22952L),first.ids);assertEquals("3120",first.next)
        assertTrue(GoldenSurvey.where(first.next).endsWith("BUSSTOPNO > '3120'"))
        assertThrows(IllegalArgumentException::class.java) { GoldenSurvey.ids(page,first.next) }
        assertNull(GoldenSurvey.ids(page.replace("true","false"),first.next).next)
    }
    @Test fun verifiedBundledSurveyContainsUniqueMappedStopsAndNoAssumedRoutes() {
        val raw=javaClass.getResourceAsStream("/golden-arrow-survey-stops.json")!!.bufferedReader().readText()
        val snapshot=GoldenSeed.parse(raw)
        assertTrue(snapshot.stops.size>3500);assertTrue(snapshot.stops.size<4500)
        assertEquals(snapshot.stops.size,snapshot.stops.map { it.id }.distinct().size)
        assertTrue(snapshot.stops.all { it.point.valid && it.point.latitude<0 && it.point.longitude>18 && it.routes.isEmpty() })
        assertTrue(snapshot.routes.isEmpty());assertEquals(0L,snapshot.catalogueCheckedAt)
    }
    @Test fun sourceKeysAreSqlQuotedAndEmptyContinuationIsRejected() {
        assertTrue(GoldenSurvey.where("A'B").contains("'A''B'"))
        assertThrows(IllegalArgumentException::class.java) { GoldenSurvey.where("A\nB") }
        assertThrows(IllegalStateException::class.java) { GoldenSurvey.ids("{\"features\":[],\"exceededTransferLimit\":true}",null) }
    }
}
