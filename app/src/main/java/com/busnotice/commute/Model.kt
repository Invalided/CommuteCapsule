package com.busnotice.commute

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 一个可以上车的选项：实时公交、自定义通勤车（时刻表）、地铁（按间隔估算） */
data class Candidate(
    val type: Type,
    val name: String,            // 公交线路名 / 通勤车名称 / 地铁线路名
    val stopName: String,        // 上车点
    val lat: Double,             // WGS-84
    val lng: Double,
    // 公交
    val regionId: String = "",
    val lineId: String = "",
    val stationId: String = "",
    val lastStop: String = "",
    val stopIndex: Int = -1,
    val alightName: String = "", // 下车站（可选，用于生成回程）
    // 通勤车：一天中的发车分钟数
    val departures: List<Int> = emptyList(),
    // 地铁：高峰发车间隔（分钟）、平峰间隔（0 = 按高峰 ×1.5）、方向（终点站名，空 = 不分方向）
    val headwayMin: Int = 0,
    val offPeakHeadwayMin: Int = 0,
    val metroDir: String = "",
    // 下车站坐标（WGS-84，用于到站提醒；NaN 表示运行时再查）
    val alightLat: Double = Double.NaN,
    val alightLng: Double = Double.NaN,
    // 这条线路怎么来的：数据源选中 / 查不到时用户直接记下（lat/lng 为 NaN，位置待学习）
    val origin: Origin = Origin.SOURCE,
) {
    enum class Type { BUS, SHUTTLE, METRO }
    enum class Origin { SOURCE, MANUAL }

    fun isMetro() = type == Type.METRO

    /** 上车点有坐标。直接记下的线路没有坐标：不注册围栏、不按距离判断，按时间推算 */
    fun hasCoord() = !lat.isNaN() && !lng.isNaN()

    /** 用户直接记下的线路（不来自数据源） */
    fun isManual() = origin == Origin.MANUAL

    /** 到上车点的直线距离（米）；上车点没有坐标时返回 null */
    fun distanceFrom(fromLat: Double, fromLng: Double): Double? =
        if (hasCoord()) Geo.distanceM(fromLat, fromLng, lat, lng) else null
    fun peakHw() = headwayMin.takeIf { it > 0 } ?: 6
    fun offPeakHw() = offPeakHeadwayMin.takeIf { it > 0 } ?: ((peakHw() * 3 + 1) / 2)

    fun label() = when (type) {
        Type.BUS -> if (looksLikeMetro(name) || name.endsWith("线")) name else "${name}路"
        Type.SHUTTLE -> name
        Type.METRO -> name.ifEmpty { "地铁" }
    }

    // JSON 不允许 NaN：没有坐标时不写 lat/lng，读回来按 NaN 处理
    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name).put("name", name).put("stopName", stopName)
        .apply { if (hasCoord()) put("lat", lat).put("lng", lng) }
        .put("regionId", regionId).put("lineId", lineId)
        .put("stationId", stationId).put("lastStop", lastStop).put("stopIndex", stopIndex)
        .put("alightName", alightName).put("departures", JSONArray(departures)).put("headwayMin", headwayMin)
        .put("offPeakHeadwayMin", offPeakHeadwayMin).put("metroDir", metroDir)
        .apply { if (!alightLat.isNaN() && !alightLng.isNaN()) put("alightLat", alightLat).put("alightLng", alightLng) }
        .apply { if (origin != Origin.SOURCE) put("origin", origin.name) }

    companion object {
        private val METRO_RE = Regex("^(地铁)?\\s*\\d+\\s*号线|地铁|机场线|城际")

        /** 有的数据源把地铁也当线路返回，名字像「4号线」 */
        fun looksLikeMetro(name: String) = METRO_RE.containsMatchIn(name)

        fun fromJson(o: JSONObject): Candidate = normalize(parse(o))

        /** 数据源按公交返回的地铁线，按名称归类为地铁 */
        fun normalize(c: Candidate): Candidate =
            if (c.type == Type.BUS && looksLikeMetro(c.name)) c.copy(type = Type.METRO, headwayMin = c.headwayMin.takeIf { it > 0 } ?: 7)
            else c

        private fun parse(o: JSONObject): Candidate {
            val deps = o.optJSONArray("departures")
            return Candidate(
                type = runCatching { Type.valueOf(o.getString("type")) }.getOrDefault(Type.BUS),
                name = o.optString("name"), stopName = o.optString("stopName"),
                lat = o.optDouble("lat"), lng = o.optDouble("lng"),
                regionId = o.optString("regionId"), lineId = o.optString("lineId"),
                stationId = o.optString("stationId"), lastStop = o.optString("lastStop"),
                stopIndex = o.optInt("stopIndex", -1), alightName = o.optString("alightName"),
                departures = if (deps == null) emptyList() else (0 until deps.length()).map { deps.getInt(it) },
                headwayMin = o.optInt("headwayMin"),
                offPeakHeadwayMin = o.optInt("offPeakHeadwayMin"), metroDir = o.optString("metroDir"),
                alightLat = o.optDouble("alightLat"), alightLng = o.optDouble("alightLng"),
                origin = runCatching { Origin.valueOf(o.optString("origin")) }.getOrDefault(Origin.SOURCE),
            )
        }
    }
}

/** 行程中的一段：在同一处换乘点可选的若干交通方式，哪个先来坐哪个 */
data class Leg(
    val candidates: List<Candidate>,
    val rideMin: Int = 10,          // 这段大约坐多久
    val transferWalkMin: Int = 5,   // 从上一段下车走到这里要多久（第一段忽略）
) {
    fun title(): String = candidates.joinToString(" / ") { it.label() }.ifEmpty { "未设置" }
    fun stopName(): String = candidates.map { it.stopName }.distinct().joinToString(" / ")
    fun isMetroOnly() = candidates.isNotEmpty() && candidates.all { it.type == Candidate.Type.METRO }

    /** 这段有上车点没有坐标的线路（直接记下的），上车时要记候选上车点 */
    fun needsStopLearning() = candidates.any { !it.hasCoord() }

    fun toJson(): JSONObject = JSONObject()
        .put("candidates", JSONArray().apply { candidates.forEach { put(it.toJson()) } })
        .put("rideMin", rideMin).put("transferWalkMin", transferWalkMin)

    companion object {
        fun fromJson(o: JSONObject): Leg {
            val arr = o.optJSONArray("candidates") ?: JSONArray()
            return Leg(
                candidates = (0 until arr.length()).map { Candidate.fromJson(arr.getJSONObject(it)) },
                rideMin = o.optInt("rideMin", 10), transferWalkMin = o.optInt("transferWalkMin", 5),
            )
        }
    }
}

/** 一条通勤行程：哪些天、什么时间窗、依次坐哪几段 */
data class Trip(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "上班",
    val legs: List<Leg> = emptyList(),
    val dayMask: Int = 0b0011111,   // bit0=周一 ... bit6=周日
    val startMinute: Int = 8 * 60,  // 出发时间窗
    val endMinute: Int = 8 * 60 + 30,
    val warmupMin: Int = 10,
    val enabled: Boolean = true,
    // 「离开这里才弹」：时间窗内每几分钟粗定位一次，走出这个范围才启动胶囊；半径 0 = 不用
    val leaveLat: Double = Double.NaN,
    val leaveLng: Double = Double.NaN,
    val leaveRadiusM: Int = 0,
) {
    fun leaveMode() = leaveRadiusM > 0 && !leaveLat.isNaN() && !leaveLng.isNaN()

    fun timeText() = "%02d:%02d–%02d:%02d".format(startMinute / 60, startMinute % 60, endMinute / 60, endMinute % 60)
    fun daysText(): String {
        if (dayMask == 0b0011111) return "工作日"
        if (dayMask == 0b1111111) return "每天"
        if (dayMask == 0b1100000) return "周末"
        val n = arrayOf("一", "二", "三", "四", "五", "六", "日")
        return "周" + (0..6).filter { dayMask and (1 shl it) != 0 }.joinToString("") { n[it] }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("legs", JSONArray().apply { legs.forEach { put(it.toJson()) } })
        .put("dayMask", dayMask).put("startMinute", startMinute).put("endMinute", endMinute)
        .put("warmupMin", warmupMin).put("enabled", enabled)
        .put("leaveRadiusM", leaveRadiusM)
        .apply { if (!leaveLat.isNaN() && !leaveLng.isNaN()) put("leaveLat", leaveLat).put("leaveLng", leaveLng) }

    companion object {
        fun fromJson(o: JSONObject): Trip {
            val arr = o.optJSONArray("legs") ?: JSONArray()
            return Trip(
                id = o.getString("id"), name = o.optString("name", "通勤"),
                legs = (0 until arr.length()).map { Leg.fromJson(arr.getJSONObject(it)) },
                dayMask = o.optInt("dayMask", 0b0011111),
                startMinute = o.optInt("startMinute", 480), endMinute = o.optInt("endMinute", 510),
                warmupMin = o.optInt("warmupMin", 10), enabled = o.optBoolean("enabled", true),
                leaveLat = o.optDouble("leaveLat"), leaveLng = o.optDouble("leaveLng"),
                leaveRadiusM = o.optInt("leaveRadiusM", 0),
            )
        }
    }
}

object TripStore {
    private const val PREFS = "trips"
    private const val KEY = "list"

    fun all(ctx: Context): List<Trip> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { runCatching { Trip.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    fun get(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    fun save(ctx: Context, trips: List<Trip>) {
        val arr = JSONArray()
        trips.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun upsert(ctx: Context, t: Trip) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == t.id }
        if (i >= 0) list[i] = t else list += t
        save(ctx, list)
    }

    fun delete(ctx: Context, id: String) = save(ctx, all(ctx).filter { it.id != id })
}
