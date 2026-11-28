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

    // ---------- 上车位置样本（按线路 + 上车站） ----------
    private const val BOARD_KEEP = 20

    /** 上车站对应的线路键：段内所有候选关联同一线路且上车站相同时返回 */
    fun boardKeyFor(leg: Leg): String? {
        val cs = leg.candidates
        if (cs.isEmpty() || cs.any { it.routeId.isEmpty() }) return null
        return cs.map { "${it.routeId}|${ManualEntry.stationKey(it.stopName)}" }.distinct().singleOrNull()
    }

    fun parseSamples(raw: String): List<BoardPoint.Sample> = raw.split(';').mapNotNull { e ->
        val f = e.split(',').takeIf { it.size == 5 } ?: return@mapNotNull null
        BoardPoint.Sample(
            f[0].toLongOrNull() ?: return@mapNotNull null, f[1].toDoubleOrNull() ?: return@mapNotNull null,
            f[2].toDoubleOrNull() ?: return@mapNotNull null, f[3].toIntOrNull() ?: return@mapNotNull null,
            f[4].toIntOrNull() ?: return@mapNotNull null,
        )
    }

    fun formatSamples(list: List<BoardPoint.Sample>) = list.joinToString(";") { "${it.day},${it.lat},${it.lng},${it.accM},${it.weight}" }

    /** 记一个上车样本并返回当前聚类结果；同一天只保留权重最高的一次 */
    fun recordBoard(ctx: Context, key: String, s: BoardPoint.Sample): BoardPoint.Learned? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = parseSamples(sp.getString("bp:$key", "") ?: "")
        val same = old.firstOrNull { it.day == s.day }
        val list = if (same != null && same.weight >= s.weight) old else (old.filter { it.day != s.day } + s).takeLast(BOARD_KEEP)
        sp.edit().putString("bp:$key", formatSamples(list)).apply()
        return BoardPoint.cluster(list)
    }

    // ---------- 手动与自动上车判断的差异统计 ----------
    /** kind：false_auto（自动判断上车后用户点「未上车」）、manual_only（用户先于自动判断点「已上车」） */
    fun recordMismatch(ctx: Context, scope: String, kind: String, source: String): Int {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = "bs:$scope|$kind|$source"
        val n = sp.getInt(k, 0) + 1
        sp.edit().putInt(k, n).apply()
        return n
    }
}
