package com.busnotice.commute

import android.content.Context
import com.busnotice.commute.engine.Dist
import com.busnotice.commute.engine.DistProfile
import com.busnotice.commute.engine.Point
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * 从本机行程记录（traces/日期_行程.jsonl）学习地面段「到换乘点距离 → 剩余时间」曲线，供卡尔曼换乘提醒使用。
 * 学习口径与 MetroEngine 离线回放一致：只用 GPS（精度 ≤ 50 米），到达以首次进入目标点 300 米为准，当天记录不参与。
 */
object SurfaceProfiles {
    private const val KEEP_DAYS = 14

    /**
     * [trip8] 行程标识前 8 位；[busLeg] 地面段序号（其上车事件的 leg 字段）；[target] 下一段上车点。
     * 返回 null 表示没有任何一天的可用记录（此时不启用卡尔曼）。
     */
    fun load(ctx: Context, trip8: String, busLeg: Int, target: Point): DistProfile? = runCatching {
        val today = LocalDate.now().toString()
        // 记录不再过期，读取范围限定为最近 LEARN_DAYS 天，与此前保留期内的输入一致
        val from = TraceStore.learnFrom()
        val files = File(ctx.filesDir, "traces")
            .listFiles { f -> f.name.endsWith("_$trip8.jsonl") && !f.name.startsWith(today) && f.name.take(10) >= from }
            ?.sortedBy { it.name } ?: return null
        val days = files.mapNotNull { f -> runCatching { series(TraceStore.readLines(ctx, f), busLeg, target) }.getOrNull() }.takeLast(KEEP_DAYS)
        if (days.isEmpty()) null else DistProfile(days)
    }.getOrNull()

    /** 一天记录中该地面段的（距离米，离到达还有多少毫秒）序列；按定位时刻排列。 */
    fun series(lines: List<String>, busLeg: Int, target: Point): List<Pair<Double, Long>>? {
        var boardAt = 0L
        val fixes = ArrayList<JSONObject>()
        for (l in lines) {
            val isLoc = l.contains("\"loc\"")
            if (!isLoc && !(boardAt == 0L && l.contains("\"boarded\""))) continue
            val o = runCatching { JSONObject(l) }.getOrNull() ?: continue
            when (o.optString("k")) {
                "ev" -> if (boardAt == 0L && o.optString("e") == "boarded" && o.optInt("leg", -1) == busLeg)
                    boardAt = o.optLong("at", o.optLong("t"))
                "loc" -> fixes += o
            }
        }
        if (boardAt == 0L) return null
        val fx = fixes.filter { it.optLong("ft") > boardAt && it.optDouble("acc", 999.0) <= 100 }.sortedBy { it.optLong("ft") }
        fun d(o: JSONObject) = Dist.m(o.optDouble("lat"), o.optDouble("lng"), target.lat, target.lng)
        val arr = fx.firstOrNull { d(it) <= 300 }?.optLong("ft") ?: return null
        if (arr - boardAt > 60 * 60_000L) return null
        return fx.filter { it.optLong("ft") <= arr && it.optDouble("acc", 999.0) <= 50 && it.optString("p") == "gps" }
            .map { d(it) to arr - it.optLong("ft") }.takeIf { it.size >= 3 }
    }
}
