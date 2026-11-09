package za.co.cttrains.core

import java.text.Normalizer

/** fzf-style place matching: ignores case, accents and punctuation; accepts partial words, any word order and gaps. */
object FuzzyPlaces {
    private val marks=Regex("\\p{Mn}+")
    private val separators=Regex("[^a-z0-9]+")
    fun normalize(text: String): String=Normalizer.normalize(text,Normalizer.Form.NFD).replace(marks,"").lowercase().replace(separators," ").trim()

    /** Higher is better. 1000 is an exact match; null means no match. */
    fun score(query: String,text: String): Int? {
        val q=normalize(query);val t=normalize(text)
        if(q.isEmpty()) return 0
        if(t.isEmpty()) return null
        if(t==q) return 1000
        if(t.startsWith(q)) return 900-(t.length-q.length).coerceAtMost(150)
        val words=t.split(' ');val terms=q.split(' ').filter(String::isNotEmpty)
        if(terms.all { term -> words.any { it.startsWith(term) } }) return 750-(t.length-q.length).coerceIn(0,150)
        if(t.contains(q)) return 550-t.indexOf(q).coerceAtMost(150)
        // Letters in order with gaps; consecutive letters and word starts score higher.
        val letters=q.replace(" ","")
        if(letters.length<3) return null
        var i=0;var total=0;var run=0;var previous=' '
        for(c in t) {
            if(i<letters.length && c==letters[i]) { run++;total+=3+run*2+(if(previous==' ') 6 else 0);i++ } else run=0
            previous=c
        }
        return if(i==letters.length) (total-t.length/2).coerceIn(1,400) else null
    }

    fun <T> rank(query: String,items: List<T>,limit: Int=40,label: (T)->String): List<T> =
        items.mapNotNull { item -> score(query,label(item))?.let { item to it } }.sortedByDescending { it.second }.take(limit).map { it.first }
}

/** Stops of one operator near a point, nearest first, with straight-line metres. */
object NearbyStops {
    fun nearest(point: GeoPoint,stops: List<BusStopPoint>,limit: Int=5,maxMeters: Double=2000.0): List<Pair<BusStopPoint,Int>> =
        stops.asSequence().map { it to NearbySuggestions.distance(point,it.point) }.filter { it.second<=maxMeters }
            .sortedBy { it.second }.take(limit).map { it.first to it.second.toInt() }.toList()
}
