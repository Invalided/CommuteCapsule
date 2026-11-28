package com.busnotice.commute

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * 候选上车点：上车点没有坐标的段（用户直接记下的线路）每次判定上车时，
 * 取上车时刻附近的一个可信定位，写进本机行程记录（事件 `board_point`）。
 *
 * 样本按「线路 + 上车站」累积并聚类（[cluster]），当前为旁路观察：结果只写入行程记录，不参与判断；
 * 用户填过的坐标不会被改。
 */
object BoardPoint {
    /** 定位时刻与上车时刻最多相差多久（毫秒） */
    const val MAX_FIX_GAP_MS = 90_000L
    /** 定位精度上限（米） */
    const val MAX_ACC_M = 200f

    data class Fix(val lat: Double, val lng: Double, val accM: Int, val fixAt: Long)

    /** 上车时刻 [at] 的候选定位：精度和时间差都在范围内才用，否则 null（只记时刻） */
    fun pick(at: Long, lat: Double, lng: Double, acc: Float, fixAt: Long): Fix? {
        if (lat.isNaN() || lng.isNaN() || acc.isNaN() || acc > MAX_ACC_M) return null
        if (abs(fixAt - at) > MAX_FIX_GAP_MS) return null
        return Fix(round6(lat), round6(lng), acc.toInt(), fixAt)
    }

    private fun round6(v: Double) = (v * 1e6).roundToLong() / 1e6

    /** 聚类半径（米）与判定学会的条件 */
    const val CLUSTER_M = 150.0
    const val MIN_DAYS = 3

    /** 一次上车样本；手点与刷卡的权重高于自动判断 */
    data class Sample(val day: Long, val lat: Double, val lng: Double, val accM: Int, val weight: Int)

    data class Learned(val lat: Double, val lng: Double, val days: Int, val n: Int, val share: Double)

    fun weightOf(manual: Boolean, tap: Boolean) = if (manual || tap) 2 else 1

    /** 近距离平面近似，足够用于百米级比较 */
    fun distM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dy = (aLat - bLat) * 111_320.0
        val dx = (aLng - bLng) * 111_320.0 * cos(Math.toRadians((aLat + bLat) / 2))
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * 取邻域权重最大的样本为中心，半径 [CLUSTER_M] 内的样本按权重求中心点。
     * 主簇覆盖至少 [MIN_DAYS] 个不同日期且权重过半时返回，否则 null（继续按时间估算）。
     */
    fun cluster(samples: List<Sample>): Learned? {
        if (samples.isEmpty()) return null
        fun near(c: Sample) = samples.filter { distM(c.lat, c.lng, it.lat, it.lng) <= CLUSTER_M }
        val seed = samples.maxWith(compareBy<Sample> { s -> near(s).sumOf { it.weight } }.thenBy { -it.accM })
        val members = near(seed)
        val w = members.sumOf { it.weight }.toDouble()
        val share = w / samples.sumOf { it.weight }
        val days = members.map { it.day }.distinct().size
        if (days < MIN_DAYS || share <= 0.5) return null
        return Learned(
            round6(members.sumOf { it.lat * it.weight } / w), round6(members.sumOf { it.lng * it.weight } / w),
            days, members.size, (share * 100).roundToLong() / 100.0,
        )
    }
}
