package com.busnotice.commute.engine

import com.busnotice.commute.AccMotion
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 定位样本。t 为收到时刻，time 为定位自身时刻（毫秒）。 */
data class Fix(
    val t: Long, val time: Long, val lat: Double, val lng: Double,
    val accuracy: Float, val gps: Boolean, val speed: Float?,
)

data class Point(val lat: Double, val lng: Double)

object Dist {
    fun m(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2).pow(2)
        return 2 * 6_371_000.0 * asin(sqrt(h))
    }
    fun m(f: Fix, p: Point) = m(f.lat, f.lng, p.lat, p.lng)
}

/**
 * 传感器短期状态：与 TraceLog 内存中的窗口一致（加速度窗口、磁场波动、服务基站切换、最近定位）。
 * 不依赖 Android，在线运行与离线回放共用同一份逻辑。
 */
class Signals {
    private val wins = ArrayDeque<AccMotion.Win>()
    private val magRing = ArrayDeque<Pair<Long, Double>>()
    private val cellChanges = ArrayDeque<Long>()
    var cellSince = 0L; private set
    var best: Fix? = null; private set
    var lastGps: Fix? = null; private set
    var goodGpsAt = 0L; private set

    fun onAcc(w: AccMotion.Win) { wins.addLast(w); while (wins.size > RECENT_MAX) wins.removeFirst() }
    fun onMag(t: Long, sd: Double) { magRing.addLast(t to sd); while (magRing.size > 150) magRing.removeFirst() }
    fun onCellChange(t: Long) { cellChanges.addLast(t); while (cellChanges.size > 30) cellChanges.removeFirst(); cellSince = t }
    fun onFix(f: Fix) {
        best = f
        if (f.gps) { lastGps = f; if (f.accuracy <= 30f) goodGpsAt = f.time }
    }
    fun start(t: Long) { cellChanges.clear(); cellSince = t }

    fun windows(): List<AccMotion.Win> = wins.toList()
    fun cellChangeAfter(t: Long): Long? = cellChanges.firstOrNull { it > t }
    fun cellStillMs(now: Long): Long? = if (cellSince == 0L) null else now - cellSince

    /** 与 TraceLog.magTrainAt 相同：90 秒窗口内 ≥ 8 个样本、跨度 ≥ 45 秒、≥ 40% 样本 msd ≥ 8 μT。 */
    fun magTrainAt(since: Long): Long? {
        val xs = magRing.filter { it.first >= since }
        for (i in xs.indices) {
            val end = xs[i].first + 90_000L
            val w = xs.subList(i, xs.size).takeWhile { it.first < end }
            if (w.size >= 8 && w.last().first - w.first().first >= 45_000L && w.count { it.second >= 8.0 } * 10 >= w.size * 4) return xs[i].first
        }
        return null
    }

    /** 车速 ≥ 4 m/s 的新鲜定位（30 秒内）。 */
    fun fast(now: Long): Boolean = best?.let { now - it.time < 30_000 && it.speed != null && it.speed >= 4f } == true

    companion object { const val RECENT_MAX = 450 }
}

/** 一次决策输出，用于日志与离线评分。 */
data class EngineEvent(val name: String, val t: Long, val at: Long, val detail: Map<String, Any?> = emptyMap())

