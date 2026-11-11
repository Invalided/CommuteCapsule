package com.busnotice.commute

import android.content.Context

/*
 * 公交 / 地铁数据接口层。
 *
 * 引擎和界面只认这里的数据类和 [Transit] 门面，不关心数据从哪来。
 * 数据源分两类能力：
 *  - 静态数据（[StaticSource]）：搜线路 / 站点、线路站序和坐标、地铁首末班、地铁途经站；
 *  - 实时数据（[RealtimeSource]）：某站的来车。可以没有——没有时引擎退回学到的时刻表和估算。
 * 所有坐标统一 WGS-84（和手机 GPS 一致），其他坐标系由各数据源自行转换。
 * region 是数据源自己的「城市 / 区域」编号，由用户配置，代码里不写任何默认城市。
 */

// ---------- 统一数据格式 ----------

data class NearLine(val lineId: String, val name: String, val lastStop: String, val stopIndex: Int)

data class NearStop(
    val stopId: String, val name: String, val distanceM: Int, val isSubway: Boolean,
    val siteId: String, val sameNameId: String,
    val lines: List<NearLine>,
)

data class StopPoint(val stopId: String, val name: String, val order: Int, val lat: Double, val lng: Double)

data class LineDir(val lineId: String, val firstStop: String, val lastStop: String)

data class SearchLine(val name: String, val isSubway: Boolean, val dirs: List<LineDir>)

data class SearchStation(
    val stopId: String, val name: String, val lat: Double, val lng: Double,
    val isSubway: Boolean, val siteId: String, val sameNameId: String,
)

data class SearchResult(val lines: List<SearchLine>, val stations: List<SearchStation>)

data class LineDetail(
    val lineId: String, val name: String, val firstStop: String, val lastStop: String,
    val stations: List<StopPoint>, val reverseLineId: String,
)

data class BusEta(
    val busId: String,
    val travelSec: Int,      // 预计还有多少秒到达等车站，<0 表示未知
    val stopsAway: Int,      // 还有几站
    val distanceM: Int,      // 距等车站的路程（米）
)

data class LineRealtime(val buses: List<BusEta>, val tip: String) {
    companion object { val EMPTY = LineRealtime(emptyList(), "") }
}

/** 地铁站某线某方向的首末班 */
data class SubwayDir(
    val line: String, val shortName: String, val dest: String, val firstMin: Int, val lastMin: Int,
    val dirId: String = "", val order: Int = -1,   // 该方向的编号、本站是这个方向的第几站
)

data class MetroStation(val name: String, val lat: Double, val lng: Double)
data class MetroRoute(val stations: List<MetroStation>, val durSec: Int) {
    val hops get() = stations.size - 1
}

/** 查地铁途经站 */
data class MetroQuery(
    val region: String, val lineName: String,
    val fromName: String, val fromLat: Double, val fromLng: Double,
    val toName: String, val toLat: Double, val toLng: Double,
)

/** 查某线路驶向等车站的来车 */
data class RealtimeQuery(
    val region: String, val lineId: String, val stationId: String, val stopIndex: Int,
    val lat: Double, val lng: Double,
)

// ---------- 数据源接口 ----------

interface StaticSource {
    val id: String
    val title: String
    fun search(key: String, region: String): SearchResult
    fun lineDetail(lineId: String, region: String): LineDetail
    fun nearbyStops(lat: Double, lng: Double, region: String): List<NearStop> = emptyList()
    fun stationLines(siteId: String, sameNameId: String, region: String): List<NearStop> = emptyList()
    fun subwayDirs(lat: Double, lng: Double, stationName: String, region: String): List<SubwayDir> = emptyList()
    fun metroStops(board: List<SubwayDir>, toLat: Double, toLng: Double, region: String): Int? = null
    fun metroRoute(q: MetroQuery): MetroRoute? = null
}

interface RealtimeSource {
    val id: String
    val title: String
    fun lineRealtime(q: RealtimeQuery): LineRealtime
}

/** 没有配置任何数据源时：什么都查不到，引擎按「无数据」降级 */
object NoSource : StaticSource {
    override val id = "none"
    override val title = "未配置"
    override fun search(key: String, region: String) = SearchResult(emptyList(), emptyList())
    override fun lineDetail(lineId: String, region: String): LineDetail = error("未配置数据源")
}

// ---------- 门面 ----------

object Transit {
    private const val PREFS = "transit"
    private const val KEY_REGION = "region"

    /** 可选扩展入口（src/extra 里的 object Extras { val sources: List<Any> }），没有就跳过 */
    private const val EXTRAS = "com.busnotice.commute.extra.Extras"

    @Volatile var static: StaticSource = NoSource; private set
    @Volatile var realtime: RealtimeSource? = null; private set
    @Volatile var region: String = ""; private set

    @Volatile private var inited = false

    /** 给数据源用的 Application Context（读 assets、存缓存等），init 之后可用 */
    lateinit var appContext: Context; private set

    fun init(ctx: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            appContext = ctx.applicationContext
            val extras = runCatching {
                val k = Class.forName(EXTRAS)
                k.getMethod("getSources").invoke(k.getField("INSTANCE").get(null)) as List<*>
            }.getOrNull().orEmpty()
            for (o in extras) {
                if (o is StaticSource && static === NoSource) static = o
                if (o is RealtimeSource && realtime == null) realtime = o
            }
            // 什么都没接：用内置的虚构「演示城市」，装上就能把流程走一遍
            if (static === NoSource) { static = MockSource; if (realtime == null) realtime = MockSource }
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var r = sp.getString(KEY_REGION, "").orEmpty()
            if (r.isEmpty()) {
                // 升级迁移：从已保存行程里取区域编号，用户不用重新设置
                r = runCatching {
                    TripStore.all(ctx).asSequence().flatMap { it.legs.asSequence() }
                        .flatMap { it.candidates.asSequence() }.map { it.regionId }.firstOrNull { it.isNotEmpty() }
                }.getOrNull().orEmpty()
                if (r.isNotEmpty()) sp.edit().putString(KEY_REGION, r).apply()
            }
            region = r
            inited = true
        }
    }

    fun setRegion(ctx: Context, r: String) {
        region = r.trim()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_REGION, region).apply()
    }

    val hasRealtime get() = realtime != null

    private fun reg(r: String) = r.ifEmpty { region }

    fun search(key: String, region: String = ""): SearchResult = static.search(key, reg(region))
    fun lineDetail(lineId: String, region: String = ""): LineDetail = static.lineDetail(lineId, reg(region))
    fun nearbyStops(lat: Double, lng: Double, region: String = ""): List<NearStop> = static.nearbyStops(lat, lng, reg(region))
    fun stationLines(siteId: String, sameNameId: String, region: String = ""): List<NearStop> =
        static.stationLines(siteId, sameNameId, reg(region))
    fun subwayDirs(lat: Double, lng: Double, stationName: String, region: String = ""): List<SubwayDir> =
        static.subwayDirs(lat, lng, stationName, reg(region))
    fun metroStops(board: List<SubwayDir>, toLat: Double, toLng: Double, region: String = ""): Int? =
        static.metroStops(board, toLat, toLng, reg(region))

    fun metroRoute(c: Candidate, toName: String, toLat: Double, toLng: Double): MetroRoute? =
        static.metroRoute(MetroQuery(reg(c.regionId), c.name, c.stopName, c.lat, c.lng, toName, toLat, toLng))

    /** 没有实时数据源时返回空（= 「无车」），引擎退回时刻表 / 估算 */
    fun lineRealtime(c: Candidate, lat: Double, lng: Double): LineRealtime =
        realtime?.lineRealtime(RealtimeQuery(reg(c.regionId), c.lineId, c.stationId, c.stopIndex, lat, lng))
            ?: LineRealtime.EMPTY

    /** 线路名是否对得上：「4号线」「地铁4号线」都算 */
    fun sameLine(name: String, d: SubwayDir): Boolean {
        val n = name.replace("地铁", "").trim()
        return n.isNotEmpty() && (n == d.shortName || n == d.line || "地铁$n" == d.line)
    }
}
