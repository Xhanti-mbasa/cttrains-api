package za.co.cttrains.core

import org.junit.Assert.*
import org.junit.Test

class BoardingEvidenceTest {
    private val station = TrackPoint(0.0, 0.0)
    private fun point(east: Int, north: Int = 0) = TrackPoint(north / 111_195.0, east / 111_195.0)
    private fun session() = BoardingEvidence(station, listOf(station, point(500)))

    private fun checkDeparture(
        evidence: BoardingEvidence,
        north: Int = 0,
        speed: Double? = 13.0,
        accuracy: Double = 5.0,
        recordMotion: Boolean = true,
    ): Boolean {
        assertFalse(evidence.fix(0, station, 5.0, 0.0, 0.5))
        if (recordMotion) for (at in 500L..3_500L step 100L) evidence.motion(at, 0.7)
        assertFalse(evidence.fix(4_000, point(40, north), accuracy, speed, 0.5))
        assertFalse(evidence.fix(8_000, point(90, north), accuracy, speed, 0.5))
        return evidence.fix(12_000, point(160, north), accuracy, speed, 0.5)
    }

    @Test fun requiresBothSustainedAccelerationAndForwardRailProgress() {
        assertTrue(checkDeparture(session()))
        assertFalse(checkDeparture(session(), recordMotion = false))
        assertFalse(checkDeparture(session(), speed = null))
        assertFalse(checkDeparture(session(), speed = 1.5))
        assertFalse(checkDeparture(session(), north = 30))
        assertFalse(checkDeparture(session(), accuracy = 45.0))
    }

    @Test fun aSingleBumpAndAnOffRailFixDoNotQualify() {
        val bump = session()
        bump.motion(2_000L, 1.0)
        assertFalse(checkDeparture(bump, recordMotion = false))

        val interrupted = session()
        interrupted.fix(0, station, 5.0, 0.0, 0.5)
        for (at in 500L..3_500L step 100L) interrupted.motion(at, 0.7)
        assertFalse(interrupted.fix(4_000, point(40), 5.0, 13.0, 0.5))
        assertFalse(interrupted.fix(8_000, point(90, 30), 5.0, 13.0, 0.5))
        assertFalse(interrupted.fix(12_000, point(160), 5.0, 13.0, 0.5))
    }
}
