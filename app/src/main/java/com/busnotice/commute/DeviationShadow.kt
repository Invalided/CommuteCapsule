package com.busnotice.commute

import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.busnotice.commute.engine.DevCell
import com.busnotice.commute.engine.DevEvent
import com.busnotice.commute.engine.DevPoint
import com.busnotice.commute.engine.DevTrip
import com.busnotice.commute.engine.DeviationConfig
import com.busnotice.commute.engine.DeviationDetector
import com.busnotice.commute.engine.DeviationModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** 从行程记录（明文行）中提取偏离识别所需的内容。不依赖 Android，便于单测。 */
object DeviationTrace {
    /** 一条记录：类型、时刻（定位按定位时刻）与原始内容 */
    private class Row(val k: String, val t: Long, val o: JSONObject)

    private fun rows(lines: List<String>): List<Row> = lines.mapNotNull { l ->
        val o = runCatching { JSONObject(l) }.getOrNull() ?: return@mapNotNull null
        val k = o.optString("k")
        val t = if (k == "loc" && o.has("ft")) o.optLong("ft") else o.optLong("t")
        Row(k, t, o)
    }.sortedBy { it.t }

    private fun ev(r: Row): String? = if (r.k == "ev") r.o.optString("e") else null

    /** 服务小区采样：第一个小区号有效的 LTE 小区与第一个 NR 小区的区号 */
    fun cell(o: JSONObject, t: Long): DevCell? {
        val cells = o.optJSONArray("cells") ?: return null
        var lte: Long? = null; var nrTac: Int? = null; var nrSeen = false
        for (i in 0 until cells.length()) {
            val c = cells.optJSONObject(i) ?: continue
            when (c.optString("type")) {
                "lte" -> if (lte == null) c.optLong("id", 0L).takeIf { it in 1L..0x0FFFFFFFL }?.let { lte = it }
                "nr" -> if (!nrSeen) { nrSeen = true; nrTac = if (c.has("tac")) c.optInt("tac") else null }
            }
        }
        return DevCell(t, nrTac, lte)
    }

    private fun gps(r: Row, maxAcc: Float): DevPoint? {
        if (r.k != "loc" || r.o.optString("p") != LocationManager.GPS_PROVIDER || r.o.optDouble("acc", 999.0) > maxAcc) return null
        return DevPoint(r.t, r.o.optDouble("lat"), r.o.optDouble("lng"))
    }

    private fun startTimes(rs: List<Row>): Long? {
        val falseStarts = rs.filter { ev(it) == "false_start" }.map { it.t }
        val starts = rs.filter { ev(it) == "start" }.map { it.t }
        return starts.firstOrNull { s -> falseStarts.none { f -> f - s in 0 until 10_000L } } ?: starts.firstOrNull()
    }

    /** 一天中一条行程的记录 → 历史行程；记录中有 `dev_flag` 时标记为已判偏离 */
    fun parse(lines: List<String>, config: DeviationConfig = DeviationConfig()): DevTrip? {
        val rs = rows(lines)
        if (rs.isEmpty()) return null
        val t0 = startTimes(rs) ?: rs.first().t
        val t1 = rs.filter { ev(it) == "user_end" && it.t > t0 }.maxOfOrNull { it.t } ?: rs.last().t
        val cells = rs.filter { it.k == "cell" }.mapNotNull { cell(it.o, it.t) }
        val gps = rs.mapNotNull { gps(it, config.gpsMaxAccuracyM) }
        return DevTrip(cells, t0, t1, gps, rs.any { ev(it) == "dev_flag" })
    }

    /** 当天正在进行的这次运行：最近一次结束之后的采样，用于进程重启后恢复判定状态 */
    class Current(val startAt: Long?, val samples: List<Any>, val flagLogged: Boolean)

    fun current(lines: List<String>): Current {
        val rs = rows(lines)
        val lastEnd = rs.indexOfLast { ev(it) in END_EVENTS }
        val tail = rs.drop(lastEnd + 1)
        val start = startTimes(tail) ?: return Current(null, emptyList(), false)
        val samples = tail.filter { it.t >= start }.mapNotNull { r ->
            when (r.k) {
                "cell" -> cell(r.o, r.t)
                "loc" -> Fix(r.t, r.o.optDouble("lat"), r.o.optDouble("lng"), r.o.optDouble("acc", 999.0).toFloat(),
                    r.o.optString("p") == LocationManager.GPS_PROVIDER)
                else -> null
            }
        }
        return Current(start, samples, tail.any { ev(it) == "dev_flag" && it.t >= start })
    }

    /** 一条定位采样 */
    data class Fix(val t: Long, val lat: Double, val lng: Double, val acc: Float, val gps: Boolean)

    private val END_EVENTS = setOf("finish", "stop", "user_end")
}

/**
 * 偏离识别观察模式在行程服务中的接入。只向行程记录写诊断事件（`dev_model`、`dev_signal`、`dev_flag`、`dev_summary`），
 * 不发提醒、不改变行程进度、到站判定、用户设定的时间与任何学习结果。
 *
 * 行程开始时在后台读取本方向此前 [DeviationConfig.HISTORY_DAYS] 天的行程记录学习模型（已判偏离的行程不参与），
 * 并重放当天本次运行已有的采样，使进程被系统重启后判定状态得以延续。所有方法在主线程调用。
 */
class DeviationShadow(private val scope: CoroutineScope) {
    private var detector: DeviationDetector? = null
    private var isMetroTac: (Int?) -> Boolean = { false }
    private var loading = false
    private var generation = 0
    private val pending = ArrayList<Any>()

    fun begin(ctx: Context, trip: Trip) {
        val c = ctx.applicationContext
        val g = ++generation
        detector = null; pending.clear(); loading = true
        scope.launch(Dispatchers.IO) {
            val r = runCatching { load(c, trip) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (g != generation) return@withContext
                loading = false
                if (r == null) { pending.clear(); return@withContext }
                isMetroTac = r.isMetroTac
                detector = r.detector
                log(DeviationDetector.modelEvent(r.model).let { e -> e.copy(fields = e.fields + listOf("replayed" to r.replayed)) })
                val d = r.detector
                if (d.flagged && !r.flagLogged) log(DevEvent("dev_flag", listOf("at" to d.flagAt, "onset" to d.flagOnset,
                    "signals" to d.flagSignals.joinToString("+"), "replayed" to true)))
                val queued = pending.toList(); pending.clear()
                queued.forEach { feed(it, d.lastSampleAt) }
            }
        }
    }

    fun cell(s: CellSnap) {
        if (!s.recorded) return
        val c = DevCell(s.t, s.nrTac, s.lteId?.toLong())
        if (loading) { if (pending.size < MAX_PENDING) pending += c; return }
        feed(c, 0L)
    }

    fun fix(l: Location) {
        val f = DeviationTrace.Fix(l.time, l.latitude, l.longitude, l.accuracy, l.provider == LocationManager.GPS_PROVIDER)
        if (loading) { if (pending.size < MAX_PENDING) pending += f; return }
        feed(f, 0L)
    }

    /** 行程结束：写入汇总 */
    fun end() {
        generation++
        detector?.let { log(it.summary()) }
        detector = null; pending.clear(); loading = false
    }

    private fun feed(x: Any, after: Long) {
        val d = detector ?: return
        val events = when (x) {
            is DevCell -> if (x.t <= after) emptyList() else d.onCell(x, isMetroTac(x.nrTac))
            is DeviationTrace.Fix -> if (x.t <= after) emptyList() else d.onFix(x.t, x.lat, x.lng, x.acc, x.gps)
            else -> emptyList()
        }
        events.forEach(::log)
    }

    private fun log(e: DevEvent) = TraceLog.event(e.name, *e.fields.toTypedArray())

    private class Loaded(val model: DeviationModel, val detector: DeviationDetector, val isMetroTac: (Int?) -> Boolean,
                         val replayed: Int, val flagLogged: Boolean)

    private fun load(ctx: Context, trip: Trip): Loaded {
        val learner = MetroCells.load(ctx)
        val metro: (Int?) -> Boolean = { learner.isMetroTac(it) }
        val trip8 = trip.id.take(8)
        val today = LocalDate.now()
        val from = today.minusDays(DeviationConfig.HISTORY_DAYS).toString()
        val dir = TraceStore.dir(ctx)
        val history = TraceStore.files(ctx).filter { it.name.endsWith("_$trip8.jsonl") }.mapNotNull { f ->
            val day = f.name.substringBefore('_')
            if (day >= today.toString() || day < from) return@mapNotNull null
            runCatching { DeviationTrace.parse(TraceStore.readLines(ctx, f)) }.getOrNull()
        }
        val model = DeviationModel.learn(history, planPoints(trip), metro)
        val current = runCatching { DeviationTrace.current(TraceStore.readLines(ctx, File(dir, "${today}_$trip8.jsonl"))) }.getOrNull()
        val start = current?.startAt ?: System.currentTimeMillis()
        val d = DeviationDetector(model, start)
        var n = 0
        current?.samples?.forEach { x ->
            when (x) {
                is DevCell -> d.onCell(x, metro(x.nrTac))
                is DeviationTrace.Fix -> d.onFix(x.t, x.lat, x.lng, x.acc, x.gps)
            }
            n++
        }
        return Loaded(model, d, metro, n, current?.flagLogged == true)
    }

    companion object {
        private const val MAX_PENDING = 2_000

        /** 计划线路的站点坐标：各段候选的上车点与下车点 */
        fun planPoints(trip: Trip): List<Pair<Double, Double>> = trip.legs.flatMap { leg ->
            leg.candidates.flatMap { c ->
                listOfNotNull(
                    if (c.hasCoord()) c.lat to c.lng else null,
                    if (!c.alightLat.isNaN() && !c.alightLng.isNaN()) c.alightLat to c.alightLng else null,
                )
            }
        }
    }
}
