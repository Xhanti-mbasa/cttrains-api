package za.co.cttrains.core

import java.util.PriorityQueue
import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
data class RailNetwork(val snapshotAt: String, val nodes: List<List<Double>>, val edges: List<List<Int>>, val anchors: Map<String, Int>, val alternatives: Map<String, List<Int>> = emptyMap())
data class TrackPoint(val latitude: Double, val longitude: Double)
data class RouteSection(val points: List<TrackPoint>, val followsRail: Boolean)
data class JourneyTrace(val legs: List<List<RouteSection>>, val railPairs: Int, val totalPairs: Int)

/** Approximate visualization along mapped railways, not a signalling or train-routing authority. */
class RailRouter(private val network: RailNetwork) {
    private data class Edge(val to: Int, val meters: Double)
    private data class Candidate(val node: Int, val cost: Double)
    private val graph: Array<MutableList<Edge>>
    private val cache = object : LinkedHashMap<Pair<String, String>, List<Int>?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, List<Int>?>?) = size > 256
    }
    init {
        require(network.nodes.size in 2..40000 && network.edges.size <= 80000)
        require(network.nodes.all { it.size == 2 && it[0].isFinite() && it[1].isFinite() && it[0] in -180.0..180.0 && it[1] in -85.0..85.0 })
        require(network.anchors.values.all { it in network.nodes.indices })
        require(network.alternatives.values.all { it.size in 1..24 && it.all { i -> i in network.nodes.indices } })
        graph = Array(network.nodes.size) { mutableListOf() }
        network.edges.forEach { edge ->
            require(edge.size == 2 && edge.all { it in network.nodes.indices })
            if (edge[0] != edge[1]) {
                val cost = distance(point(edge[0]), point(edge[1]))
                graph[edge[0]].add(Edge(edge[1], cost)); graph[edge[1]].add(Edge(edge[0], cost))
            }
        }
    }
    private fun point(index: Int) = TrackPoint(network.nodes[index][1], network.nodes[index][0])
    private fun path(from: String, to: String, a: TrackPoint, b: TrackPoint): List<Int>? {
        val key = from to to
        if (cache.containsKey(key)) return cache[key]
        val costs = DoubleArray(graph.size) { Double.POSITIVE_INFINITY }
        val previous = IntArray(graph.size) { -1 }
        val starts = network.alternatives[from] ?: listOfNotNull(network.anchors[from])
        val ends = (network.alternatives[to] ?: listOfNotNull(network.anchors[to])).associateWith { distance(b, point(it)) }
        val queue = PriorityQueue<Candidate>(compareBy { it.cost })
        starts.forEach { node -> costs[node] = distance(a, point(node)); queue.add(Candidate(node, costs[node])) }
        val limit = distance(a, b) * 2.2 + 500
        var best = Double.POSITIVE_INFINITY; var target = -1
        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (current.cost > costs[current.node]) continue
            if (current.cost > minOf(limit, best)) break
            ends[current.node]?.let { offset -> if (current.cost + offset < best && current.cost + offset <= limit) { best = current.cost + offset; target = current.node } }
            graph[current.node].forEach { edge ->
                val cost = current.cost + edge.meters
                if (cost < costs[edge.to]) { costs[edge.to] = cost; previous[edge.to] = current.node; queue.add(Candidate(edge.to, cost)) }
            }
        }
        if (target >= 0) {
            val result = mutableListOf(target); var node = target
            while (previous[node] != -1) { node = previous[node]; result.add(node) }
            return result.reversed().also { cache[key] = it; cache[to to from] = it.reversed() }
        }
        cache[key] = null
        return null
    }
    @Synchronized
    fun trace(journey: Journey, stations: List<StationPosition>): JourneyTrace {
        val lookup = stations.associateBy { stationKey(it.name) }
        var total = 0; var rail = 0
        val legs = journey.legs.map { leg ->
            leg.stops.zipWithNext().mapNotNull { (a, b) ->
                total++
                val start = stationKey(a.name); val end = stationKey(b.name)
                val first = lookup[start]?.takeIf { it.mapped }; val last = lookup[end]?.takeIf { it.mapped }
                val points = if (first != null && last != null && start != end) path(start, end, TrackPoint(first.latitude!!, first.longitude!!), TrackPoint(last.latitude!!, last.longitude!!))?.map(::point) else null
                if (points != null && points.size > 1) { rail++; RouteSection(points, true) }
                else {
                    if (first == null || last == null) null else RouteSection(listOf(TrackPoint(first.latitude!!, first.longitude!!), TrackPoint(last.latitude!!, last.longitude!!)), false)
                }
            }
        }
        return JourneyTrace(legs, rail, total)
    }
    companion object {
        fun distance(a: TrackPoint, b: TrackPoint): Double {
            val lat = Math.toRadians(b.latitude - a.latitude); val lon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
            return 6371000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
