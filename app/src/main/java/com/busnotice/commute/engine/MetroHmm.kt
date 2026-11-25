package com.busnotice.commute.engine

import kotlin.math.abs
import kotlin.math.exp

/**
 * 地铁沿线模型：按行驶方向排列的地下基站（eNB）及其到终点站的典型剩余时间。
 * 由历史乘车记录学习得到，order[0] 为起点站，order.last() 为终点站。
 */
class MetroLine(val order: List<Long>, val remMs: LongArray) {
    val size get() = order.size
    private val index = order.withIndex().associate { it.value to it.index }
    fun indexOf(enb: Long): Int? = index[enb]

    /** 第 i 个位置到下一个位置的典型行驶时间（毫秒），下限 20 秒。 */
    fun segMs(i: Int): Long = if (i >= size - 1) 0L else (remMs[i] - remMs[i + 1]).coerceAtLeast(20_000L)

    companion object {
        const val MIN_RIDES = 3

        /**
         * rides：每趟按时间排列的 (基站号, 首次出现毫秒)。
         * 终点取出现最多的末位基站（需 ≥ 60%），起点取出现最多的首位基站。
         * 每个基站的剩余时间取各趟中位数（至少 2 趟出现）；起点的剩余时间按「下一位置 + 相邻位置间隔中位数」估算，不含候车时间。
         */
        fun learn(rides: List<List<Pair<Long, Long>>>): MetroLine? {
            val ok = rides.filter { it.size >= 3 }
            if (ok.size < MIN_RIDES) return null
            val term = ok.groupingBy { it.last().first }.eachCount().maxByOrNull { it.value } ?: return null
            if (term.value * 10 < ok.size * 6) return null
            val origin = ok.groupingBy { it.first().first }.eachCount().maxByOrNull { it.value }!!.key
            val rem = HashMap<Long, MutableList<Long>>()
            for (r in ok) {
                val tEnd = r.firstOrNull { it.first == term.key }?.second ?: continue
                for ((e, t) in r) if (t <= tEnd && e != origin) rem.getOrPut(e) { ArrayList() } += tEnd - t
            }
            val med = rem.filterValues { it.size >= 2 }.mapValues { (_, v) -> v.sorted()[v.size / 2] }
                .toList().sortedByDescending { it.second }
            if (med.size < 2) return null
            val gaps = med.zipWithNext { a, b -> a.second - b.second }.filter { it > 0 }.sorted()
            val gap = if (gaps.isEmpty()) 60_000L else gaps[gaps.size / 2]
            val order = listOf(origin) + med.map { it.first }
            val remMs = LongArray(order.size)
            remMs[0] = med[0].second + gap
            med.forEachIndexed { i, p -> remMs[i + 1] = p.second }
            return MetroLine(order, remMs)
        }
    }
}

/**
 * 隐马尔可夫跟踪：隐藏状态为「列车当前所在的沿线位置」，观测为服务基站。
 *
 * - 状态转移：每个位置按典型行驶时间以几何分布前进到下一位置；起点位置额外包含候车时间。
 * - 观测模型：观测到第 j 个基站时，真实位置为 j 的似然最高，相邻位置次之（切换滞后或提前），其余位置给小概率（容错）。
 * - 未知基站或无观测（进程被冻结）时只做预测，不做更新。
 *
 * 剩余时间：以最可能位置的典型剩余时间减去在该位置已停留的时间，下限为下一位置的剩余时间。
 */
class MetroHmm(private val line: MetroLine, private val platformMs: Long = 240_000L) {
    private val n = line.size
    private var b = DoubleArray(n) { if (it == 0) 0.9 else 0.1 / (n - 1).coerceAtLeast(1) }
    private var lastT = 0L
    var map = 0; private set
    var mapSince = 0L; private set
    /** 首次可信地观测到起点以外位置的时刻与位置，用于反推开车时刻。 */
    var firstMoveAt = 0L; private set
    var firstMoveIdx = -1; private set
    var observed = 0; private set

    fun belief(): DoubleArray = b.copyOf()
    fun pTerminal(): Double = b[n - 1]
    val atTerminal get() = map == n - 1

    fun predict(t: Long) {
        if (lastT == 0L) { lastT = t; mapSince = t; return }
        var left = (t - lastT).coerceAtLeast(0L)
        lastT = t
        if (left == 0L) return
        // 按不超过 5 秒的小步推进，长时间无观测（进程冻结）时位置可连续前进多个
        while (left > 0) {
            val dt = minOf(left, STEP_MS); left -= dt
            val nb = DoubleArray(n)
            for (i in 0 until n) {
                if (i == n - 1) { nb[i] += b[i]; continue }
                val mean = line.segMs(i) + if (i == 0) platformMs else 0L
                val p = 1 - exp(-dt.toDouble() / mean)
                nb[i] += b[i] * (1 - p); nb[i + 1] += b[i] * p
            }
            b = nb
        }
        refreshMap(t)
    }

    /** 一次基站观测。返回观测是否属于沿线模型。 */
    fun observe(t: Long, enb: Long?): Boolean {
        predict(t)
        val j = enb?.let { line.indexOf(it) } ?: return false
        observed++
        var s = 0.0
        for (i in 0 until n) {
            val d = abs(i - j)
            val l = when (d) { 0 -> 0.80; 1 -> 0.08; else -> 0.005 }
            b[i] *= l; s += b[i]
        }
        if (s <= 0) { b = DoubleArray(n) { if (it == j) 1.0 else 0.0 } } else for (i in 0 until n) b[i] /= s
        refreshMap(t)
        if (firstMoveAt == 0L && map >= 1 && b[map] >= 0.6) { firstMoveAt = t; firstMoveIdx = map }
        return true
    }

    private fun refreshMap(t: Long) {
        var m = 0
        for (i in 1 until n) if (b[i] > b[m]) m = i
        if (m != map) { map = m; mapSince = t }
    }

    /** 估计的剩余时间（毫秒）。仍在起点（未开车）时返回 null。 */
    fun remainingMs(t: Long): Long? {
        if (map == 0) return null
        val floor = if (map < n - 1) line.remMs[map + 1] else 0L
        return (line.remMs[map] - (t - mapSince)).coerceAtLeast(floor)
    }

    /** 由首次离开起点的观测反推的开车时刻（离线诊断用）。 */
    fun departEstimate(): Long? =
        if (firstMoveAt == 0L) null else firstMoveAt - (line.remMs[0] - line.remMs[firstMoveIdx])

    companion object { const val STEP_MS = 5_000L }
}
