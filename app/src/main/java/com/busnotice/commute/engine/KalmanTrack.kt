package com.busnotice.commute.engine

import kotlin.math.max

/**
 * 地面段一维卡尔曼滤波：状态为「到目标点的直线距离 d（米）」与「接近速度 v（米/秒，靠近为正）」。
 *
 * - 预测：匀速模型，d ← d − v·dt；加速度噪声 σa 表示车辆加减速、红绿灯等不确定性。
 * - 更新：每条定位换算为到目标点的距离，观测噪声取定位精度（GPS 下限 15 米，网络定位下限 50 米）。
 * - 观测选择：以 GPS 为主；最近 60 秒内有 GPS 时忽略网络定位（网络定位常重复旧位置或滞后），与上一条采纳的定位相隔不足 3 秒的也忽略。
 * - 误差门限：新观测与预测的偏差超过 3σ 视为异常丢弃；连续 3 次异常则以观测重新初始化。
 * - 就绪条件：至少 3 条采纳的观测，且跨度不少于 60 秒。外推时速度限制在 0–20 米/秒。
 *
 * 长时间无定位时，可用 [predictD] 外推当前距离。
 */
class KalmanTrack(private val target: Point, private val sigmaA: Double = 0.6) {
    var d = 0.0; private set
    var v = 0.0; private set
    private var p00 = 0.0; private var p01 = 0.0; private var p11 = 0.0
    var t = 0L; private set
    var accepted = 0; private set
    var lastAcceptAt = 0L; private set
    private var initAt = 0L
    private var lastGpsAt = 0L
    private var rejectRun = 0
    val ready get() = accepted >= 3 && lastAcceptAt - initAt >= 60_000L

    /** 输入一条定位；返回 null 表示按规则忽略，true 为采纳，false 为超出误差门限被丢弃。 */
    fun onFix(f: Fix): Boolean? {
        if (f.gps) lastGpsAt = f.time
        else if (lastGpsAt > 0 && f.time - lastGpsAt < 60_000L) return null
        if (accepted > 0 && f.time - lastAcceptAt < 3_000L) return null
        val z = Dist.m(f, target)
        val r = max(f.accuracy.toDouble(), if (f.gps) 15.0 else 100.0).let { it * it }
        if (accepted == 0) { if (!f.gps && f.accuracy > 50f) return null; init(f.time, z, r); return true }
        if (f.time <= t) return null
        predictTo(f.time)
        val s = p00 + r
        val y = z - d
        if (y * y > 9 * s) {
            if (++rejectRun >= 3) { init(f.time, z, r); return true }
            return false
        }
        rejectRun = 0
        val k0 = p00 / s; val k1 = p01 / s
        d += k0 * y
        // F 中 d 与 v 负相关（p01 < 0）：观测比预测小（y < 0）时 v 增大
        v += k1 * y
        val n00 = (1 - k0) * p00; val n01 = (1 - k0) * p01; val n11 = p11 - k1 * p01
        p00 = n00; p01 = n01; p11 = n11
        accepted++; lastAcceptAt = f.time
        return true
    }

    private fun init(at: Long, z: Double, r: Double) {
        d = z; v = 8.0; p00 = r; p01 = 0.0; p11 = 36.0
        t = at; accepted = 1; lastAcceptAt = at; initAt = at; rejectRun = 0
    }

    private fun predictTo(at: Long) {
        val dt = (at - t) / 1000.0
        if (dt <= 0) return
        // 状态转移 F = [[1, −dt], [0, 1]]，过程噪声来自加速度 σa
        d = (d - v * dt).coerceAtLeast(0.0)
        val q = sigmaA * sigmaA
        val a00 = p00 - 2 * dt * p01 + dt * dt * p11 + q * dt * dt * dt * dt / 4
        val a01 = p01 - dt * p11 - q * dt * dt * dt / 2
        val a11 = p11 + q * dt * dt
        p00 = a00; p01 = a01; p11 = a11
        t = at
    }

    /** 外推到 now 时刻的距离（不改变滤波状态）。 */
    fun predictD(now: Long): Double = (d - v.coerceIn(0.0, 20.0) * ((now - t) / 1000.0).coerceAtLeast(0.0)).coerceAtLeast(0.0)
    fun sigmaD(): Double = kotlin.math.sqrt(p00.coerceAtLeast(0.0))
}

/**
 * 「距离 → 剩余时间」经验曲线：由历史每天的（到目标点距离，离到达还有多少毫秒）学习，每天按时间先后排列。
 * 查询距离 q 时，在每天找到跨过 q 的相邻两条定位（前一条在 q 外、后一条在 q 内，间隔不超过 90 秒），
 * 按距离线性插值出剩余时间，再取各天中位数；至少 3 天有数据才返回。定位间隔过大的一天不参与，避免把剩余时间估短。
 */
class DistProfile(private val days: List<List<Pair<Double, Long>>>) {
    fun remainingMs(q: Double): Long? {
        val xs = days.mapNotNull { day ->
            day.zipWithNext().firstOrNull { (a, b) -> a.first > q && b.first <= q }?.takeIf { (a, b) -> a.second - b.second <= 90_000L }
                ?.let { (a, b) -> b.second + ((a.second - b.second) * (q - b.first) / (a.first - b.first)).toLong() }
        }.sorted()
        return if (xs.size < 3) null else xs[xs.size / 2]
    }
}
