package com.busnotice.commute

import android.content.Context

/**
 * 按「线路 + 上车站 + 下车站」保存的学习结果，供使用同一线路的多个行程共享。
 *
 * 当前处于旁路观察阶段：与按行程保存的结果同时记录，每次记录后写入行程记录（事件 `route_shadow`）以便对比，
 * 提醒与预测仍使用按行程保存的结果。
 */
object RouteLearn {
    private const val PREFS = "learn_route"
    private const val RIDE_KEEP = 10
    const val MIN_RIDES = 3

    /**
     * 乘车段对应的线路键。段内所有候选都关联了线路、填写了下车站，且指向同一线路与同一对站点时才返回；
     * 段内有多条可选线路时无法确定实际乘坐的线路，返回 null。
     */
    fun keyFor(leg: Leg): String? {
        val cs = leg.candidates
        if (cs.isEmpty() || cs.any { it.routeId.isEmpty() || it.alightName.isBlank() }) return null
        val keys = cs.map { "${it.routeId}|${ManualEntry.stationKey(it.stopName)}|${ManualEntry.stationKey(it.alightName)}" }.distinct()
        return keys.singleOrNull()
    }

    /** 中位数，样本不足返回 null；与按行程的乘车时长规则一致 */
    fun median(samples: List<Int>): Int? = if (samples.size < MIN_RIDES) null else samples.sorted()[samples.size / 2]

    /** 指数平均，新样本占 30%；与按行程的车速规则一致 */
    fun blend(old: Double?, sample: Double): Double = if (old == null || old <= 0) sample else old * 0.7 + sample * 0.3

    private fun rides(ctx: Context, key: String): List<Int> =
        (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ride:$key", "") ?: "")
            .split(',').mapNotNull { it.trim().toIntOrNull() }

    fun recordRide(ctx: Context, key: String, minutes: Int) {
        if (minutes !in 1..180) return
        val out = (rides(ctx, key) + minutes).takeLast(RIDE_KEEP)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("ride:$key", out.joinToString(",")).apply()
    }

    fun rideMin(ctx: Context, key: String): Int? = median(rides(ctx, key))

    fun busSpeed(ctx: Context, key: String): Double? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("speed:$key", 0f).takeIf { it > 0f }?.toDouble()

    fun recordBusSpeed(ctx: Context, key: String, mPerMin: Double) {
        if (mPerMin.isNaN() || mPerMin !in 80.0..700.0) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("speed:$key", blend(busSpeed(ctx, key), mPerMin).toFloat()).apply()
    }

    /** 记录对比：同一段在按行程与按线路两种口径下的乘车时长和车速 */
    fun shadow(ctx: Context, trip: Trip, legIdx: Int, key: String) {
        TraceLog.event(
            "route_shadow", "leg" to legIdx,
            "tripRide" to Learn.rideMin(ctx, trip.id, legIdx), "routeRide" to rideMin(ctx, key),
            "routeRides" to rides(ctx, key).size,
            "tripSpeed" to Learn.busSpeed(ctx, trip.id, legIdx)?.toInt(), "routeSpeed" to busSpeed(ctx, key)?.toInt(),
        )
    }
}
