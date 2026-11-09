package za.co.cttrains.core

import kotlin.math.cos
import kotlin.math.hypot

/** Combines short-lived motion and GPS evidence for the first mapped segment after boarding. */
class BoardingEvidence(private val station: TrackPoint, track: List<TrackPoint>) {
    private val longitudeScale = 111_195.0 * cos(Math.toRadians(station.latitude))
    private val points = track.map(::project)
    private var atStation: Long? = null
    private var previousMotionAt: Long? = null
    private var motionStarted: Long? = null
    private var sustainedAt: Long? = null
    private var firstMovingAt: Long? = null
    private var previousFixAt: Long? = null
    private var startProgress = 0.0
    private var progress = 0.0
    private var fixes = 0

    fun motion(at: Long, acceleration: Double) {
        if (acceleration !in 0.20..2.5 || previousMotionAt?.let { at - it !in 0..500 } != false) {
            motionStarted = null
        }
        previousMotionAt = at
        if (acceleration in 0.20..2.5) {
            val start = motionStarted ?: at.also { motionStarted = it }
            if (at - start >= 2_500) sustainedAt = at
        }
    }

    fun fix(at: Long, point: TrackPoint, accuracy: Double, speed: Double?, speedAccuracy: Double?): Boolean {
        if (previousFixAt?.let { at - it !in 1..10_000 } == true) resetMoving()
        previousFixAt = at
        val match = if (accuracy in 0.0..12.0) match(project(point)) else null
        if (match == null || match.distance > 13) {
            resetMoving()
            return false
        }
        if (match.progress <= 100 && RailRouter.distance(station, point) <= 110 &&
            speed != null && speed in 0.0..3.0) atStation = at
        val origin = atStation ?: return false
        if (at - origin !in 0..120_000 || speed == null || speedAccuracy == null || speedAccuracy !in 0.0..2.0 ||
            speed - speedAccuracy < 4.5 || match.progress + 12 < progress) {
            resetMoving()
            return false
        }
        val start = firstMovingAt ?: at.also {
            firstMovingAt = it
            startProgress = match.progress
        }
        fixes++
        progress = maxOf(progress, match.progress)
        return fixes >= 3 && at - start >= 8_000 && progress >= 140 && progress - startProgress >= 80 &&
            sustainedAt?.let { at - it in 0..60_000 && it >= origin - 10_000 } == true
    }

    private fun resetMoving() {
        firstMovingAt = null
        progress = 0.0
        fixes = 0
    }

    private data class Point(val x: Double, val y: Double)
    private data class Match(val distance: Double, val progress: Double)

    private fun project(point: TrackPoint) = Point(
        (point.longitude - station.longitude) * longitudeScale,
        (point.latitude - station.latitude) * 111_195.0,
    )

    private fun match(point: Point): Match? {
        var best: Match? = null
        var progress = 0.0
        for (index in 1 until points.size) {
            val a = points[index - 1]
            val b = points[index]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            if (length == 0.0) continue
            val fraction = (((point.x - a.x) * dx + (point.y - a.y) * dy) / (length * length)).coerceIn(0.0, 1.0)
            val distance = hypot(point.x - (a.x + fraction * dx), point.y - (a.y + fraction * dy))
            if (best == null || distance < best.distance) best = Match(distance, progress + fraction * length)
            progress += length
        }
        return best
    }
}
