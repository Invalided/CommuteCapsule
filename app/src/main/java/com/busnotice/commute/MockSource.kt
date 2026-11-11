package com.busnotice.commute

import java.time.LocalTime
import kotlin.math.cos

/**
 * 内置的虚构数据源「演示城市」：没有接入任何真实数据源时使用，方便装上就能把流程走一遍。
 *
 * 城市中心放在海上的一个点，所有线路、站名、时刻都是编的。
 * 两条公交（东西向 1 路、南北向 2 路，在「中心广场」相交）和一条地铁（1 号线）。
 * 实时来车按时钟模拟：06:00–22:00 每 8 分钟从起点发一班，站间 90 秒。
 */
object MockSource : StaticSource, RealtimeSource {
    override val id = "mock"
    override val title = "演示城市（虚构数据）"

    private const val LAT0 = 30.5
    private const val LNG0 = 124.5
    private const val STEP_M = 600.0          // 站距
    private const val HEADWAY_S = 8 * 60
    private const val HOP_S = 90
    private const val FIRST_S = 6 * 3600
    private const val LAST_S = 22 * 3600

    private fun pt(eastM: Double, northM: Double): Pair<Double, Double> {
        val lat = LAT0 + northM / 111_320.0
        val lng = LNG0 + eastM / (111_320.0 * cos(Math.toRadians(LAT0)))
        return lat to lng
    }

    private class MockLine(val id: String, val name: String, val names: List<String>, val coords: List<Pair<Double, Double>>, val subway: Boolean)

    private val busEW = listOf("西站", "梧桐路", "图书馆", "中心广场", "科技园", "东湖", "东站")
    private val busNS = listOf("北门", "体育馆", "中心广场", "医院", "南湖公园", "南站")
    private val metro = listOf("西站", "大学城", "中心广场", "会展中心", "软件园", "机场")

    private val lines: List<MockLine> = run {
        val ew = busEW.indices.map { pt((it - 3) * STEP_M, 0.0) }
        val ns = busNS.indices.map { pt(0.0, (2 - it) * STEP_M) }
        val mt = metro.indices.map { pt((it - 2) * STEP_M * 2, 150.0) }
        listOf(
            MockLine("m1", "1", busEW, ew, false),
            MockLine("m1r", "1", busEW.reversed(), ew.reversed(), false),
            MockLine("m2", "2", busNS, ns, false),
            MockLine("m2r", "2", busNS.reversed(), ns.reversed(), false),
            MockLine("mt1", "地铁1号线", metro, mt, true),
            MockLine("mt1r", "地铁1号线", metro.reversed(), mt.reversed(), true),
        )
    }

    private fun line(id: String) = lines.first { it.id == id }
    private fun reverseOf(id: String) = if (id.endsWith("r")) id.dropLast(1) else id + "r"
    private fun idOf(l: MockLine, i: Int) = "${l.id}-${i + 1}"
    private fun phyId(name: String) = "p-$name"

    private fun lineStops(l: MockLine) = l.names.indices.map {
        StopPoint(idOf(l, it), l.names[it], it + 1, l.coords[it].first, l.coords[it].second)
    }

    override fun search(key: String, region: String): SearchResult {
        val k = key.trim().removeSuffix("路")
        if (k.isEmpty()) return SearchResult(emptyList(), emptyList())
        val ls = lines.filter { it.name.contains(k) }.groupBy { it.name }.map { (name, g) ->
            SearchLine(name, g.first().subway, g.map { LineDir(it.id, it.names.first(), it.names.last()) })
        }
        val st = lines.flatMap { l -> l.names.indices.map { l to it } }
            .filter { (l, i) -> l.names[i].contains(k) }
            .distinctBy { (l, i) -> l.names[i] to l.subway }
            .map { (l, i) ->
                SearchStation(idOf(l, i), l.names[i], l.coords[i].first, l.coords[i].second, l.subway, phyId(l.names[i]), phyId(l.names[i]))
            }
        return SearchResult(ls, st)
    }

    override fun lineDetail(lineId: String, region: String): LineDetail {
        val l = line(lineId)
        return LineDetail(l.id, l.name, l.names.first(), l.names.last(), lineStops(l), reverseOf(l.id))
    }

    private fun stopsAt(name: String, subway: Boolean?, distanceM: Int): NearStop? {
        val hits = lines.filter { (subway == null || it.subway == subway) && name in it.names }
        if (hits.isEmpty()) return null
        return NearStop(
            stopId = phyId(name), name = name, distanceM = distanceM, isSubway = hits.all { it.subway },
            siteId = phyId(name), sameNameId = phyId(name),
            lines = hits.filter { !it.subway }.map { l ->
                NearLine(l.id, l.name, l.names.last(), l.names.indexOf(name) + 1)
            },
        )
    }

    override fun nearbyStops(lat: Double, lng: Double, region: String): List<NearStop> =
        lines.flatMap { l -> l.names.indices.map { Triple(l.names[it], l.subway, Geo.distanceM(lat, lng, l.coords[it].first, l.coords[it].second)) } }
            .filter { it.third < 1500 }
            .distinctBy { it.first to it.second }
            .sortedBy { it.third }
            .mapNotNull { (n, sub, d) -> stopsAt(n, sub, d.toInt()) }

    override fun stationLines(siteId: String, sameNameId: String, region: String): List<NearStop> =
        listOfNotNull(stopsAt(siteId.removePrefix("p-"), false, 0))

    override fun subwayDirs(lat: Double, lng: Double, stationName: String, region: String): List<SubwayDir> {
        val name = if (stationName in metro) stationName
        else metro.indices.minByOrNull { Geo.distanceM(lat, lng, line("mt1").coords[it].first, line("mt1").coords[it].second) }?.let { metro[it] }
            ?: return emptyList()
        return lines.filter { it.subway }.map { l ->
            SubwayDir("地铁1号线", "1号线", l.names.last(), 6 * 60, 23 * 60, l.id, l.names.indexOf(name) + 1)
        }
    }

    override fun metroStops(board: List<SubwayDir>, toLat: Double, toLng: Double, region: String): Int? {
        for (b in board) {
            val l = lines.firstOrNull { it.id == b.dirId } ?: continue
            val j = l.coords.indices.minByOrNull { Geo.distanceM(toLat, toLng, l.coords[it].first, l.coords[it].second) } ?: continue
            val n = (j + 1) - b.order
            if (n > 0) return n
        }
        return null
    }

    override fun metroRoute(q: MetroQuery): MetroRoute? {
        val l = line("mt1")
        fun near(la: Double, ln: Double) = l.coords.indices.minByOrNull { Geo.distanceM(la, ln, l.coords[it].first, l.coords[it].second) }!!
        val a = l.names.indexOf(q.fromName).takeIf { it >= 0 } ?: near(q.fromLat, q.fromLng)
        val b = l.names.indexOf(q.toName).takeIf { it >= 0 } ?: near(q.toLat, q.toLng)
        if (a == b) return null
        val idx = if (a < b) (a..b).toList() else (a downTo b).toList()
        return MetroRoute(idx.map { MetroStation(l.names[it], l.coords[it].first, l.coords[it].second) }, (idx.size - 1) * 150)
    }

    override fun lineRealtime(q: RealtimeQuery): LineRealtime {
        val l = lines.firstOrNull { it.id == q.lineId } ?: return LineRealtime.EMPTY
        val k = q.stopIndex - 1
        if (k < 0 || k >= l.names.size) return LineRealtime.EMPTY
        val now = LocalTime.now().toSecondOfDay()
        val buses = mutableListOf<BusEta>()
        var t0 = FIRST_S
        while (t0 <= LAST_S && buses.size < 3) {
            val arrive = t0 + k * HOP_S
            if (t0 <= now && arrive >= now) {
                val passed = (now - t0) / HOP_S
                buses += BusEta("mock-$t0", arrive - now, k - passed, ((k - passed) * STEP_M).toInt())
            } else if (t0 > now && buses.isEmpty()) {
                break
            }
            t0 += HEADWAY_S
        }
        buses.sortBy { it.travelSec }
        val tip = if (buses.isEmpty()) {
            val next = generateSequence(FIRST_S) { it + HEADWAY_S }.takeWhile { it <= LAST_S }.firstOrNull { it > now }
            if (next == null) "末班已过" else "下一班 %02d:%02d 从起点发车".format(next / 3600, next % 3600 / 60)
        } else ""
        return LineRealtime(buses.take(2), tip)
    }
}
