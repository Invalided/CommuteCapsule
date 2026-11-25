package com.busnotice.commute

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 候选上车点：上车点没有坐标的段（用户直接记下的线路）每次判定上车时，
 * 取上车时刻附近的一个可信定位，写进本机行程记录（事件 `board_point`）。
 *
 * 只记录，不回填：攒够样本后由后续版本聚类、回填到空着的坐标；用户填过的坐标不会被改。
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
}
