package com.busnotice.commute

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 用户自己维护的线路：只记站名和顺序，不含坐标。站点位置和区间用时由行程记录学习得到。
 * 时刻表与发车间隔为可选项：填写后优先使用，未填写时按交通方式的默认值估算。
 */
data class RouteDef(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val direction: String = "",          // 「去程」「返程」，或为空
    val mode: Candidate.Type = Candidate.Type.BUS,
    val stopPattern: StopPattern = StopPattern.ALL,
    val stops: List<String> = emptyList(),
    val schedule: RouteSchedule = RouteSchedule(),
) {
    enum class StopPattern { ALL, ENDS }

    /** 完整名称，例如「线路 · 去程」；预览、文件和列表统一使用 */
    fun fullName(): String = if (direction.isEmpty()) name else "$name · $direction"

    /** 只停起终点时，途中站点不参与停靠判断 */
    fun boardingStops(): List<String> = if (stopPattern == StopPattern.ENDS && stops.size > 2) listOf(stops.first(), stops.last()) else stops

    /** 生成反向线路：站序倒置，发车间隔与首末班沿用，固定发车时刻清空（反向时刻通常不同） */
    fun reversed(): RouteDef = copy(
        id = UUID.randomUUID().toString(),
        direction = when (direction) { "去程" -> "返程"; "返程" -> "去程"; else -> "返程" },
        stops = stops.reversed(),
        schedule = schedule.copy(departures = emptyList()),
    )

    /**
     * 转为行程中的一个乘车选项（直接记下、位置待学习）。
     * 填写了时刻表或间隔就带上，未填写则沿用原有默认推算。
     */
    fun toCandidate(board: String, alight: String): Candidate {
        val s = schedule
        return Candidate(
            type = mode, name = name, stopName = board, lat = Double.NaN, lng = Double.NaN,
            alightName = alight, departures = s.departures,
            headwayMin = s.peakHeadwayMin, offPeakHeadwayMin = s.offPeakHeadwayMin,
            metroDir = if (mode == Candidate.Type.METRO) stops.lastOrNull().orEmpty() else "",
            origin = Candidate.Origin.MANUAL,
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("direction", direction).put("mode", mode.name)
        .put("stopPattern", stopPattern.name).put("stops", JSONArray(stops)).put("schedule", schedule.toJson())

    companion object {
        fun fromJson(o: JSONObject): RouteDef {
            val arr = o.optJSONArray("stops") ?: JSONArray()
            return RouteDef(
                id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                name = o.optString("name"), direction = o.optString("direction"),
                mode = runCatching { Candidate.Type.valueOf(o.optString("mode")) }.getOrDefault(Candidate.Type.BUS),
                stopPattern = runCatching { StopPattern.valueOf(o.optString("stopPattern")) }.getOrDefault(StopPattern.ALL),
                stops = (0 until arr.length()).map { arr.getString(it) },
                schedule = o.optJSONObject("schedule")?.let { RouteSchedule.fromJson(it) } ?: RouteSchedule(),
            )
        }
    }
}

/**
 * 可选的时刻信息，单位为一天中的分钟数。-1 或 0 表示未填写。
 * 固定发车时刻（班次制车辆）优先于发车间隔。
 */
data class RouteSchedule(
    val firstMin: Int = -1,
    val lastMin: Int = -1,
    val peakHeadwayMin: Int = 0,
    val offPeakHeadwayMin: Int = 0,
    val departures: List<Int> = emptyList(),
) {
    fun isEmpty() = firstMin < 0 && lastMin < 0 && peakHeadwayMin <= 0 && offPeakHeadwayMin <= 0 && departures.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("firstMin", firstMin).put("lastMin", lastMin)
        .put("peakHeadwayMin", peakHeadwayMin).put("offPeakHeadwayMin", offPeakHeadwayMin)
        .put("departures", JSONArray(departures))

    companion object {
        fun fromJson(o: JSONObject): RouteSchedule {
            val d = o.optJSONArray("departures")
            return RouteSchedule(
                firstMin = o.optInt("firstMin", -1), lastMin = o.optInt("lastMin", -1),
                peakHeadwayMin = o.optInt("peakHeadwayMin"), offPeakHeadwayMin = o.optInt("offPeakHeadwayMin"),
                departures = if (d == null) emptyList() else (0 until d.length()).map { d.getInt(it) }.sorted(),
            )
        }
    }
}

/** 时刻推算：用户填写的优先，未填写时使用交通方式默认值 */
object RouteTiming {
    /** 默认高峰间隔（分钟）。班车没有通用默认值，只能依靠固定时刻或学习结果 */
    fun defaultPeak(mode: Candidate.Type): Int = when (mode) {
        Candidate.Type.METRO -> 6
        Candidate.Type.BUS -> 10
        Candidate.Type.SHUTTLE -> 0
    }

    fun peak(r: RouteDef): Int = r.schedule.peakHeadwayMin.takeIf { it > 0 } ?: defaultPeak(r.mode)

    fun offPeak(r: RouteDef): Int = r.schedule.offPeakHeadwayMin.takeIf { it > 0 }
        ?: peak(r).let { if (it > 0) (it * 3 + 1) / 2 else 0 }

    /** 时刻信息来源：用户填写，或默认估算 */
    fun isUserProvided(r: RouteDef): Boolean = !r.schedule.isEmpty()

    /**
     * 自 [nowSec]（一天中的秒数）起的后续 [n] 个发车时刻，返回距现在的秒数。
     * 有固定发车时刻时直接使用；否则按首末班与间隔推算；都没有时返回空列表。
     */
    fun next(r: RouteDef, nowSec: Int, weekday: Boolean, n: Int = 3): List<Int> {
        val s = r.schedule
        if (s.departures.isNotEmpty()) return s.departures.map { it * 60 - nowSec }.filter { it >= 0 }.take(n)
        val pk = peak(r); val op = offPeak(r)
        if (pk <= 0) return emptyList()
        val first = if (s.firstMin >= 0) s.firstMin else 0
        val last = if (s.lastMin >= 0) s.lastMin else 24 * 60 - 1
        return Decision.metroSchedule(nowSec, first, last, weekday, pk, op, n)
    }
}

/** 粘贴文本的清洗：去空行、去行首序号、按箭头拆分 */
object RouteText {
    private val LEADING = Regex("^\\s*(?:[\\(（]?\\d{1,3}[\\)）.、:：．]|[①-⑳]|[一二三四五六七八九十]+[、.．])\\s*")
    private val ARROW = Regex("\\s*(?:→|->|⇒|＞|>|\\s[-—–]\\s)\\s*")

    data class Parsed(val stops: List<String>, val droppedBlank: Int, val strippedNumbers: Int)

    fun parse(raw: String): Parsed {
        var blank = 0; var numbered = 0
        val out = mutableListOf<String>()
        raw.replace("\r", "").split('\n').forEach { line ->
            if (line.isBlank()) { blank++; return@forEach }
            val stripped = LEADING.replace(line, "")
            if (stripped != line) numbered++
            stripped.split(ARROW).map { it.trim() }.filter { it.isNotEmpty() }.forEach { out += it }
        }
        // 末尾空行通常是粘贴带入的换行，不计入提示
        val trailing = raw.replace("\r", "").split('\n').takeLastWhile { it.isBlank() }.size
        return Parsed(out, (blank - trailing).coerceAtLeast(0), numbered)
    }

    fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    /** 「7:05」「07:05」→ 分钟数；非法返回 null */
    fun parseTime(s: String): Int? {
        val m = Regex("^(\\d{1,2})[:：](\\d{2})$").find(s.trim()) ?: return null
        val h = m.groupValues[1].toInt(); val mm = m.groupValues[2].toInt()
        return if (h in 0..23 && mm in 0..59) h * 60 + mm else null
    }

    /** 以空格、逗号分隔的多个时刻 */
    fun parseTimes(s: String): List<Int>? {
        val parts = s.split(Regex("[\\s,，、;；]+")).filter { it.isNotBlank() }
        val r = parts.map { parseTime(it) ?: return null }
        return r.distinct().sorted()
    }
}

/**
 * 线路文件：UTF-8 纯文本，便于手工编辑。示例：
 *
 *     # 通勤胶囊线路文件 v1
 *     [线路] 示例线 · 去程
 *     方式 = 公交
 *     停站 = 站站停
 *     首班 = 06:00
 *     末班 = 22:30
 *     高峰间隔 = 8
 *     平峰间隔 = 12
 *     发车 = 07:10 07:40
 *     站点:
 *     起点站
 *     终点站
 *
 * 不包含行程记录、坐标或学习结果。
 */
object RouteFile {
    const val HEADER = "# 通勤胶囊线路文件 v1"

    class FormatError(val line: Int, val reason: String) : Exception("第 $line 行：$reason")

    private val MODES = mapOf("公交" to Candidate.Type.BUS, "地铁" to Candidate.Type.METRO, "班车" to Candidate.Type.SHUTTLE)
    private val PATTERNS = mapOf("站站停" to RouteDef.StopPattern.ALL, "只停起终点" to RouteDef.StopPattern.ENDS)

    fun write(routes: List<RouteDef>): String = buildString {
        appendLine(HEADER)
        routes.forEach { r ->
            appendLine()
            appendLine("[线路] ${r.fullName()}")
            appendLine("方式 = ${MODES.entries.first { it.value == r.mode }.key}")
            appendLine("停站 = ${PATTERNS.entries.first { it.value == r.stopPattern }.key}")
            val s = r.schedule
            if (s.firstMin >= 0) appendLine("首班 = ${RouteText.hhmm(s.firstMin)}")
            if (s.lastMin >= 0) appendLine("末班 = ${RouteText.hhmm(s.lastMin)}")
            if (s.peakHeadwayMin > 0) appendLine("高峰间隔 = ${s.peakHeadwayMin}")
            if (s.offPeakHeadwayMin > 0) appendLine("平峰间隔 = ${s.offPeakHeadwayMin}")
            if (s.departures.isNotEmpty()) appendLine("发车 = ${s.departures.joinToString(" ") { RouteText.hhmm(it) }}")
            appendLine("站点:")
            r.stops.forEach { appendLine(it) }
        }
    }

    /** 解析失败抛出 [FormatError]，指明行号；调用方据此提示，不改动已有线路 */
    fun read(text: String): List<RouteDef> {
        val lines = text.removePrefix("\uFEFF").replace("\r", "").split('\n')
        val out = mutableListOf<RouteDef>()
        var cur: RouteDef? = null
        var curLine = 0
        var inStops = false
        var sawHeader = false

        fun finish() {
            val r = cur ?: return
            if (r.stops.size < 2) throw FormatError(curLine, "线路「${r.fullName()}」至少应包含 2 个站点")
            out += r; cur = null; inStops = false
        }

        lines.forEachIndexed { i, rawLine ->
            val n = i + 1
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith("#")) { if (line.startsWith("# 通勤胶囊线路文件")) sawHeader = true; return@forEachIndexed }
            if (line.startsWith("[线路]")) {
                finish()
                val full = line.removePrefix("[线路]").trim()
                if (full.isEmpty()) throw FormatError(n, "缺少线路名称")
                val parts = full.split(Regex("\\s*·\\s*"), limit = 2)
                cur = RouteDef(name = parts[0], direction = parts.getOrElse(1) { "" })
                curLine = n
                return@forEachIndexed
            }
            val r = cur ?: throw FormatError(n, "内容应位于「[线路] 名称」行之后")
            if (inStops) { cur = r.copy(stops = r.stops + line); return@forEachIndexed }
            if (line == "站点:" || line == "站点：") { inStops = true; return@forEachIndexed }
            val kv = line.split(Regex("\\s*[=＝]\\s*"), limit = 2)
            if (kv.size != 2) throw FormatError(n, "无法识别的内容「$line」")
            val (k, v) = kv
            val s = r.schedule
            cur = when (k) {
                "方式" -> r.copy(mode = MODES[v] ?: throw FormatError(n, "方式应为 公交、地铁 或 班车"))
                "停站" -> r.copy(stopPattern = PATTERNS[v] ?: throw FormatError(n, "停站应为 站站停 或 只停起终点"))
                "首班" -> r.copy(schedule = s.copy(firstMin = RouteText.parseTime(v) ?: throw FormatError(n, "时间格式应为 HH:MM")))
                "末班" -> r.copy(schedule = s.copy(lastMin = RouteText.parseTime(v) ?: throw FormatError(n, "时间格式应为 HH:MM")))
                "高峰间隔" -> r.copy(schedule = s.copy(peakHeadwayMin = minutes(v, n)))
                "平峰间隔" -> r.copy(schedule = s.copy(offPeakHeadwayMin = minutes(v, n)))
                "发车" -> r.copy(schedule = s.copy(departures = RouteText.parseTimes(v) ?: throw FormatError(n, "发车时刻格式应为 HH:MM，多个时刻以空格分隔")))
                else -> throw FormatError(n, "未知字段「$k」")
            }
        }
        finish()
        if (!sawHeader && out.isEmpty()) throw FormatError(1, "该文件不是线路文件")
        if (out.isEmpty()) throw FormatError(lines.size, "文件中未包含线路")
        return out
    }

    private fun minutes(v: String, n: Int): Int =
        v.removeSuffix("分钟").trim().toIntOrNull()?.takeIf { it in 1..180 } ?: throw FormatError(n, "间隔应为 1–180 之间的分钟数")
}

/** 导入合并 */
object RouteMerge {
    enum class Conflict { KEEP_BOTH, REPLACE }
    enum class Status { NEW, SAME_NAME }

    fun status(existing: List<RouteDef>, incoming: RouteDef): Status =
        if (existing.any { it.fullName() == incoming.fullName() }) Status.SAME_NAME else Status.NEW

    /**
     * 合并导入的线路。替换时保留原线路 id，只更新站点、方式与时刻，
     * 因而以 id 关联的已学位置与用时不受影响。保留两份时为新线路加序号后缀。
     */
    fun merge(existing: List<RouteDef>, incoming: List<RouteDef>, policy: Conflict): List<RouteDef> {
        val out = existing.toMutableList()
        incoming.forEach { inc ->
            val i = out.indexOfFirst { it.fullName() == inc.fullName() }
            if (i < 0) { out += inc.copy(id = UUID.randomUUID().toString()); return@forEach }
            when (policy) {
                Conflict.REPLACE -> out[i] = inc.copy(id = out[i].id)
                Conflict.KEEP_BOTH -> {
                    var k = 2
                    while (out.any { it.fullName() == inc.copy(name = "${inc.name}（$k）").fullName() }) k++
                    out += inc.copy(id = UUID.randomUUID().toString(), name = "${inc.name}（$k）")
                }
            }
        }
        return out
    }
}

object RouteStore {
    private const val PREFS = "routes"
    private const val KEY = "list"

    fun all(ctx: Context): List<RouteDef> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { runCatching { RouteDef.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    fun save(ctx: Context, routes: List<RouteDef>) {
        val arr = JSONArray(); routes.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun addAll(ctx: Context, routes: List<RouteDef>) = save(ctx, all(ctx) + routes)

    fun delete(ctx: Context, id: String) = save(ctx, all(ctx).filter { it.id != id })
}
