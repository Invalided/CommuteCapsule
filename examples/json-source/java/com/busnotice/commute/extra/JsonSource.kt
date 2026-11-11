package com.busnotice.commute.extra

import com.busnotice.commute.Geo
import com.busnotice.commute.LineDetail
import com.busnotice.commute.LineDir
import com.busnotice.commute.NearLine
import com.busnotice.commute.NearStop
import com.busnotice.commute.SearchLine
import com.busnotice.commute.SearchResult
import com.busnotice.commute.SearchStation
import com.busnotice.commute.StaticSource
import com.busnotice.commute.StopPoint
import com.busnotice.commute.Transit
import org.json.JSONObject

/**
 * 最小数据源模板：从 assets/transit.json 读线路和站点。
 *
 * 只实现静态数据（搜索、线路详情、附近站点、某站经过的线路），没有实时来车。
 * 没有实时数据时，App 会用学到的时刻表和「站距 + 间隔」估算，照样能用。
 * 想接实时数据：再实现 RealtimeSource，并加进 Extras.sources。
 */
object JsonSource : StaticSource {
    override val id = "json"
    override val title = "本地 JSON"

    private class Line(val id: String, val name: String, val subway: Boolean, val reverseId: String, val stops: List<StopPoint>)

    private val lines: List<Line> by lazy {
        val txt = Transit.appContext.assets.open("transit.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(txt).getJSONArray("lines")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val st = o.getJSONArray("stops")
            Line(
                id = o.getString("id"), name = o.getString("name"),
                subway = o.optBoolean("subway", false), reverseId = o.optString("reverseId", ""),
                stops = (0 until st.length()).map { j ->
                    val s = st.getJSONObject(j)
                    StopPoint("${o.getString("id")}-${j + 1}", s.getString("name"), j + 1, s.getDouble("lat"), s.getDouble("lng"))
                },
            )
        }
    }

    override fun search(key: String, region: String): SearchResult {
        val k = key.trim()
        if (k.isEmpty()) return SearchResult(emptyList(), emptyList())
        val ls = lines.filter { it.name.contains(k) }.groupBy { it.name }.map { (name, g) ->
            SearchLine(name, g.first().subway, g.map { LineDir(it.id, it.stops.first().name, it.stops.last().name) })
        }
        val st = lines.flatMap { l -> l.stops.map { l to it } }
            .filter { (_, s) -> s.name.contains(k) }
            .distinctBy { (l, s) -> s.name to l.subway }
            .map { (l, s) -> SearchStation(s.stopId, s.name, s.lat, s.lng, l.subway, s.name, s.name) }
        return SearchResult(ls, st)
    }

    override fun lineDetail(lineId: String, region: String): LineDetail {
        val l = lines.first { it.id == lineId }
        return LineDetail(l.id, l.name, l.stops.first().name, l.stops.last().name, l.stops, l.reverseId)
    }

    /** 站点按名字归并：同名站 = 同一个站 */
    private fun stopAt(name: String, distanceM: Int): NearStop? {
        val hits = lines.filter { l -> l.stops.any { it.name == name } }
        if (hits.isEmpty()) return null
        return NearStop(
            stopId = name, name = name, distanceM = distanceM, isSubway = hits.all { it.subway },
            siteId = name, sameNameId = name,
            lines = hits.filter { !it.subway }.map { l ->
                NearLine(l.id, l.name, l.stops.last().name, l.stops.first { it.name == name }.order)
            },
        )
    }

    override fun nearbyStops(lat: Double, lng: Double, region: String): List<NearStop> =
        lines.flatMap { it.stops }
            .map { it.name to Geo.distanceM(lat, lng, it.lat, it.lng) }
            .filter { it.second < 1000 }
            .sortedBy { it.second }
            .distinctBy { it.first }
            .mapNotNull { (n, d) -> stopAt(n, d.toInt()) }

    override fun stationLines(siteId: String, sameNameId: String, region: String): List<NearStop> =
        listOfNotNull(stopAt(siteId, 0))
}
