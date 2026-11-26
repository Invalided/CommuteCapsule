package com.busnotice.commute

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * 本机学「家」和「公司」两个常去地点：存在手机本地（SharedPreferences），不上传，不进导出记录。
 * 学习结果（投票聚成的点、手动设置、采样结果快照）存在 places；全天随机采样的原始定位单独存在 place_samples，
 * 在本机加密存储（见 [LocalCrypto]），不参与系统备份（见 res/xml/data_extraction_rules.xml）。
 * 投票来源：
 *  1. 行程起点 / 终点：上午（12 点前）出发的行程，起点投「家」、终点投「公司」；下午/晚上的反过来。
 *     终点不按下车站投（下车后还要走一段），只投起点；
 *  2. 半夜 0–5 点拿到的定位投「家」（每天最多一票）。
 *  3.（优先）全天随机采样（PlaceSampler，通勤以外每 1–2 小时一次）：不先贴标签，所有样本 150 米内聚成点；
 *     夜里（21 点–次日 7 点前）样本最多、且跨 ≥ 3 天的点 = 家；工作日白天（10–17 点前）样本最多、跨 ≥ 3 天的另一个点 = 公司。
 *     采样点只存本机，不进导出轨迹。采样学出来的就用采样的，并保存为快照（只有坐标、半径、样本数和更新时间）；
 *     采样不足以独立算出时（例如恢复到新手机后）用快照兜底；都没有才用 1、2 的投票。
 * 同类的票 150 米内并成一个点（按精度加权的滑动中心），≥ 3 票且来自 ≥ 2 天才算确认。
 * 用户也可以手动设置家 / 公司（用当时的定位）：手动设置优先于自动学到的结果，自动学习只更新自己的数据，
 * 永远不会覆盖或清除手动设置；只有用户自己能修改或清除。
 */
object Places {
    enum class Kind { HOME, WORK }
    enum class Label { HOME, WORK, ON_THE_WAY, UNKNOWN }

    private const val PREFS = "places"
    /** 原始定位采样：与学习结果分开存放，不参与系统备份 */
    const val SAMPLE_PREFS = "place_samples"
    private const val KEY_SAMPLES = "samples"
    private const val KEY_SNAPSHOT = "sample_snapshot"
    private const val MERGE_M = 150.0
    private const val NEED_VOTES = 3
    private const val NEED_DAYS = 2
    private const val MAX_CLUSTERS = 6
    private const val MAX_ACC = 200f

    private class Cluster(val kind: Kind, var lat: Double, var lng: Double, var w: Double, var votes: Int, val days: MutableSet<Long>) {
        val confirmed get() = votes >= NEED_VOTES && days.size >= NEED_DAYS
    }

    private var cache: MutableList<Cluster>? = null

    @Synchronized
    private fun load(ctx: Context): MutableList<Cluster> {
        cache?.let { return it }
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("clusters", null)
        val out = mutableListOf<Cluster>()
        runCatching {
            val a = JSONArray(raw ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val d = o.optJSONArray("days") ?: JSONArray()
                out += Cluster(Kind.valueOf(o.getString("k")), o.getDouble("lat"), o.getDouble("lng"), o.getDouble("w"),
                    o.getInt("v"), (0 until d.length()).map { d.getLong(it) }.toMutableSet())
            }
        }
        cache = out
        return out
    }

    @Synchronized
    private fun save(ctx: Context, l: List<Cluster>) {
        val a = JSONArray()
        l.forEach { c ->
            a.put(JSONObject().put("k", c.kind.name).put("lat", c.lat).put("lng", c.lng).put("w", c.w).put("v", c.votes)
                .put("days", JSONArray(c.days.sorted().takeLast(14))))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("clusters", a.toString()).apply()
    }

    private fun day(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
    private fun hour(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).hour

    /** 投一票：精度太差（≥ 200 米）不收 */
    @Synchronized
    fun vote(ctx: Context, kind: Kind, lat: Double, lng: Double, acc: Float, at: Long = System.currentTimeMillis()) {
        if (acc >= MAX_ACC || lat.isNaN() || lng.isNaN()) return
        val l = load(ctx)
        val wt = 1.0 / acc.coerceAtLeast(15f)
        val c = l.filter { it.kind == kind }.minByOrNull { Geo.distanceM(lat, lng, it.lat, it.lng) }
            ?.takeIf { Geo.distanceM(lat, lng, it.lat, it.lng) <= MERGE_M }
        if (c != null) {
            val tw = c.w + wt
            c.lat = (c.lat * c.w + lat * wt) / tw
            c.lng = (c.lng * c.w + lng * wt) / tw
            c.w = tw; c.votes++; c.days += day(at)
        } else {
            l += Cluster(kind, lat, lng, wt, 1, mutableSetOf(day(at)))
            // 同类候选太多：去掉票最少的
            val same = l.filter { it.kind == kind }
            if (same.size > MAX_CLUSTERS) l.remove(same.minBy { it.votes })
        }
        save(ctx, l)
    }

    /** 行程起点/终点投票：start = 起点（否则终点），startedAt = 行程开始时刻（判断上午/下午） */
    fun voteTrip(ctx: Context, start: Boolean, startedAt: Long, lat: Double, lng: Double, acc: Float, at: Long) {
        val morning = hour(startedAt) < 12
        val kind = if (start == morning) Kind.HOME else Kind.WORK
        vote(ctx, kind, lat, lng, acc, at)
    }

    /** 随手拿到的定位：0–5 点之间的投「家」，每天最多一票 */
    fun passive(ctx: Context, lat: Double, lng: Double, acc: Float, at: Long) {
        if (CommuteService.runningTripId() == null) sample(ctx, lat, lng, acc, at)
        if (hour(at) !in 0..4 || acc >= MAX_ACC) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = day(at)
        if (p.getLong("nightDay", -1L) == d) return
        p.edit().putLong("nightDay", d).apply()
        vote(ctx, Kind.HOME, lat, lng, acc, at)
    }

    // ---------- 全天随机采样 ----------
    internal class Sample(val lat: Double, val lng: Double, val acc: Float, val t: Long)
    internal class Spot(var lat: Double, var lng: Double, var w: Double) {
        val nightDays = mutableSetOf<Long>(); val workDays = mutableSetOf<Long>()
        var night = 0; var work = 0
    }
    private const val KEEP_DAYS = 21
    private const val MAX_SAMPLES = 800
    private const val NEED_SAMPLE_DAYS = 3
    private var samplesCache: MutableList<Sample>? = null
    private var derivedCache: Map<Kind, Spot>? = null
    private var derivedBest: Map<Kind, Int>? = null
    private var snapshotCache: Map<Kind, Snap>? = null

    /**
     * 旧版本把采样和学习结果存在同一个文件（places 的 samples 键）：搬到 place_samples。
     * 先同步写入新文件，成功后再从旧文件删除，中途失败时旧数据仍在，下次再搬。
     */
    @Synchronized
    fun migrateSamples(ctx: Context) {
        moveKey(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            ctx.getSharedPreferences(SAMPLE_PREFS, Context.MODE_PRIVATE), KEY_SAMPLES)
    }

    /**
     * 把字符串键 [key] 从 [src] 搬到 [dst]：[dst] 已有该键时以 [dst] 为准（搬过之后只写 [dst]）；
     * 写入 [dst] 成功后才从 [src] 删除。返回 [src] 中是否已不再有该键。
     */
    internal fun moveKey(src: SharedPreferences, dst: SharedPreferences, key: String): Boolean {
        val raw = src.getString(key, null) ?: return true
        val moved = dst.contains(key) || dst.edit().putString(key, raw).commit()
        return moved && src.edit().remove(key).commit()
    }

    @Synchronized
    private fun samples(ctx: Context): MutableList<Sample> {
        samplesCache?.let { return it }
        migrateSamples(ctx)
        val sp = ctx.getSharedPreferences(SAMPLE_PREFS, Context.MODE_PRIVATE)
        val stored = sp.getString(KEY_SAMPLES, null)
        // 无法解密（密钥已更换）时按没有采样处理，之后的采样用新密钥覆盖写入；
        // 密钥暂不可用时不缓存，避免之后用空列表覆盖已保存的采样
        val plain = stored?.let { LocalCrypto.decode(ctx, it) }
        if (stored != null && plain == null && !LocalCrypto.ready(ctx)) return mutableListOf()
        val out = mutableListOf<Sample>()
        runCatching {
            val a = JSONArray(plain ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONArray(i)
                out += Sample(o.getDouble(0), o.getDouble(1), o.getDouble(2).toFloat(), o.getLong(3))
            }
        }
        encryptPlain(ctx, sp, stored)
        samplesCache = out
        return out
    }

    /** 旧版本的明文采样：转为密文后用 commit 整体替换，失败时明文保留，下次再转 */
    private fun encryptPlain(ctx: Context, sp: SharedPreferences, stored: String?) {
        if (stored == null || RecordFormat.isEncrypted(stored)) return
        LocalCrypto.encrypt(ctx, stored)?.let { sp.edit().putString(KEY_SAMPLES, it).commit() }
    }

    /** 首次启动把旧版明文采样转为密文（后台线程调用） */
    @Synchronized
    fun encryptStoredSamples(ctx: Context) {
        migrateSamples(ctx)
        val sp = ctx.getSharedPreferences(SAMPLE_PREFS, Context.MODE_PRIVATE)
        encryptPlain(ctx, sp, sp.getString(KEY_SAMPLES, null))
    }

    /** 收一个采样点：精度 ≥ 200 米、超过 3 分钟的旧定位、离上一个采样不到 20 分钟的都不收 */
    @Synchronized
    fun sample(ctx: Context, lat: Double, lng: Double, acc: Float, at: Long) {
        val now = System.currentTimeMillis()
        if (acc >= MAX_ACC || lat.isNaN() || lng.isNaN() || now - at > 3 * 60_000L) return
        val l = samples(ctx)
        if (l.isNotEmpty() && at - l.last().t < 20 * 60_000L) return
        l += Sample(lat, lng, acc, at)
        l.removeAll { now - it.t > KEEP_DAYS * 86_400_000L }
        while (l.size > MAX_SAMPLES) l.removeAt(0)
        val a = JSONArray()
        l.forEach { a.put(JSONArray().put(it.lat).put(it.lng).put(it.acc.toDouble()).put(it.t)) }
        // 密钥不可用时只留在内存，不以明文落盘
        LocalCrypto.encrypt(ctx, a.toString())?.let {
            ctx.getSharedPreferences(SAMPLE_PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SAMPLES, it).apply()
        }
        derivedCache = null; derivedBest = null
    }

    /** 0 = 其他时间，1 = 夜里（算家），2 = 工作日白天（算公司） */
    private fun slot(t: Long, zone: ZoneId): Int {
        val z = Instant.ofEpochMilli(t).atZone(zone)
        val h = z.hour
        if (h >= 21 || h < 7) return 1
        if (z.dayOfWeek.value <= 5 && h in 10..16) return 2
        return 0
    }

    private fun day(t: Long, zone: ZoneId) = Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toEpochDay()

    /** 夜里 21 点以后算「当天晚上」，跨天的同一晚只算一天 */
    private fun nightDay(t: Long, zone: ZoneId): Long = day(t - 7 * 3_600_000L, zone)

    /** 由采样算出的结果：[spots] 为足以确认的家 / 公司，[best] 为各自已覆盖的天数（进度用） */
    internal class Derived(val spots: Map<Kind, Spot>, val best: Map<Kind, Int>)

    /** 只由采样独立推算家 / 公司（纯计算） */
    internal fun deriveFrom(samples: List<Sample>, zone: ZoneId = ZoneId.systemDefault()): Derived {
        val spots = mutableListOf<Spot>()
        samples.sortedBy { it.acc }.forEach { s ->
            val wt = 1.0 / s.acc.coerceAtLeast(15f)
            val sp = spots.minByOrNull { Geo.distanceM(s.lat, s.lng, it.lat, it.lng) }
                ?.takeIf { Geo.distanceM(s.lat, s.lng, it.lat, it.lng) <= MERGE_M }
                ?: Spot(s.lat, s.lng, 0.0).also { spots += it }
            val tw = sp.w + wt
            sp.lat = (sp.lat * sp.w + s.lat * wt) / tw; sp.lng = (sp.lng * sp.w + s.lng * wt) / tw; sp.w = tw
            when (slot(s.t, zone)) {
                1 -> { sp.night++; sp.nightDays += nightDay(s.t, zone) }
                2 -> { sp.work++; sp.workDays += day(s.t, zone) }
            }
        }
        val home = spots.filter { it.night > 0 }.maxByOrNull { it.night }
        val work = spots.filter { it !== home && it.work > 0 }.maxByOrNull { it.work }
        val best = mapOf(Kind.HOME to (home?.nightDays?.size ?: 0), Kind.WORK to (work?.workDays?.size ?: 0))
        val out = mutableMapOf<Kind, Spot>()
        if (home != null && home.nightDays.size >= NEED_SAMPLE_DAYS && home.night >= 4) out[Kind.HOME] = home
        if (work != null && work.workDays.size >= NEED_SAMPLE_DAYS && work.work >= 4 &&
            (home == null || Geo.distanceM(work.lat, work.lng, home.lat, home.lng) > 300)) out[Kind.WORK] = work
        return Derived(out, best)
    }

    @Synchronized
    private fun derive(ctx: Context): Map<Kind, Spot> {
        derivedCache?.let { return it }
        val d = deriveFrom(samples(ctx))
        derivedBest = d.best
        // 采样暂时读不到（密钥未就绪）时不缓存，下次重新计算
        if (samplesCache == null) return d.spots
        derivedCache = d.spots
        // 采样足以独立算出的地点刷新快照（随学习结果一起备份）
        refreshSnapshot(snapshot(ctx), d.spots, System.currentTimeMillis())?.let { snap ->
            snapshotCache = snap
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SNAPSHOT, encodeSnapshot(snap)).apply()
        }
        return d.spots
    }

    // ---------- 采样结果快照 ----------
    /** 采样推算结果的快照：只有中心坐标、聚类半径、样本数和更新时间，不含任何轨迹或单个采样 */
    internal data class Snap(val lat: Double, val lng: Double, val radiusM: Double, val samples: Int, val updatedAt: Long)

    internal fun encodeSnapshot(m: Map<Kind, Snap>): String {
        val o = JSONObject()
        m.forEach { (k, v) ->
            o.put(k.name, JSONObject().put("lat", v.lat).put("lng", v.lng).put("r", v.radiusM).put("n", v.samples).put("t", v.updatedAt))
        }
        return o.toString()
    }

    internal fun decodeSnapshot(raw: String?): Map<Kind, Snap> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            Kind.values().mapNotNull { k ->
                val v = o.optJSONObject(k.name) ?: return@mapNotNull null
                val lat = v.getDouble("lat"); val lng = v.getDouble("lng")
                if (lat.isNaN() || lng.isNaN()) null
                else k to Snap(lat, lng, v.optDouble("r", MERGE_M), v.optInt("n"), v.optLong("t"))
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * 采样独立算出的地点（[fresh]）覆盖快照中的同类项，其余类保留原快照；
     * 坐标和样本数都没变时返回 null（不必重写）。
     */
    internal fun refreshSnapshot(old: Map<Kind, Snap>, fresh: Map<Kind, Spot>, now: Long): Map<Kind, Snap>? {
        if (fresh.isEmpty()) return null
        val out = old.toMutableMap()
        var changed = false
        fresh.forEach { (k, sp) ->
            val n = if (k == Kind.HOME) sp.night else sp.work
            val prev = old[k]
            if (prev == null || prev.samples != n || Geo.distanceM(prev.lat, prev.lng, sp.lat, sp.lng) > 1.0) {
                out[k] = Snap(sp.lat, sp.lng, MERGE_M, n, now); changed = true
            }
        }
        return if (changed) out else null
    }

    /** 取值顺序：手动设置 > 采样独立算出 > 采样快照 > 行程起终点投票 */
    internal fun choose(manual: Pair<Double, Double>?, sampled: Pair<Double, Double>?, snapshot: Pair<Double, Double>?,
                        voted: Pair<Double, Double>?): Pair<Double, Double>? = manual ?: sampled ?: snapshot ?: voted

    @Synchronized
    private fun snapshot(ctx: Context): Map<Kind, Snap> = snapshotCache
        ?: decodeSnapshot(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SNAPSHOT, null)).also { snapshotCache = it }

    /** 采样算出的地点；采样不足时用快照 */
    private fun sampledOrSnapshot(ctx: Context, kind: Kind): Pair<Double, Double>? =
        choose(null, derive(ctx)[kind]?.let { it.lat to it.lng }, snapshot(ctx)[kind]?.let { it.lat to it.lng }, null)

    private fun voted(ctx: Context, kind: Kind): Pair<Double, Double>? =
        load(ctx).filter { it.kind == kind && it.confirmed }.maxByOrNull { it.votes }?.let { it.lat to it.lng }

    private fun sampleProgress(ctx: Context, k: Kind): Int {
        derive(ctx)
        return (derivedBest?.get(k) ?: 0).coerceAtMost(NEED_SAMPLE_DAYS)
    }

    // ---------- 手动设置 ----------
    private fun manualKey(k: Kind) = "manual_${k.name}"

    /** 用户手动设置的位置；没有设置返回 null */
    fun manual(ctx: Context, kind: Kind): Pair<Double, Double>? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(manualKey(kind), null) ?: return null
        return parseManual(raw)
    }

    /** 手动设置家 / 公司（只由用户操作调用）；之后自动学习不会改动它 */
    fun setManual(ctx: Context, kind: Kind, lat: Double, lng: Double) {
        if (lat.isNaN() || lng.isNaN()) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(manualKey(kind), formatManual(lat, lng)).apply()
    }

    /** 清除手动设置，改回自动学习的结果 */
    fun clearManual(ctx: Context, kind: Kind) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(manualKey(kind)).apply()
    }

    fun formatManual(lat: Double, lng: Double) = "$lat,$lng"

    fun parseManual(raw: String): Pair<Double, Double>? {
        val p = raw.split(',')
        if (p.size != 2) return null
        val la = p[0].trim().toDoubleOrNull() ?: return null
        val ln = p[1].trim().toDoubleOrNull() ?: return null
        return if (la.isNaN() || ln.isNaN()) null else la to ln
    }

    /** 自动学到的地点（不含手动设置）：先用采样学出来的，其次是采样快照，最后是投票的 */
    @Synchronized
    fun learned(ctx: Context, kind: Kind): Pair<Double, Double>? =
        choose(null, sampledOrSnapshot(ctx, kind), null, voted(ctx, kind))

    /** 确认的地点：手动设置优先，其次是采样学出来的（不足时用快照），最后是投票的 */
    @Synchronized
    private fun confirmed(ctx: Context, kind: Kind): Pair<Double, Double>? =
        choose(manual(ctx, kind), sampledOrSnapshot(ctx, kind), null, voted(ctx, kind))

    /** 在确认地点 200 米 + min(精度, 150) 米内 → 家/公司；两个都确认了但都不在 → 路上；其余 → 未知 */
    @Synchronized
    fun classify(ctx: Context, lat: Double, lng: Double, acc: Float): Label {
        if (acc >= 500f) return Label.UNKNOWN
        val r = 200.0 + acc.coerceAtMost(150f)
        val hits = Kind.values().mapNotNull { k -> confirmed(ctx, k)?.let { k to Geo.distanceM(lat, lng, it.first, it.second) } }
        hits.filter { it.second <= r }.minByOrNull { it.second }?.let { return if (it.first == Kind.HOME) Label.HOME else Label.WORK }
        return if (hits.size == 2) Label.ON_THE_WAY else Label.UNKNOWN
    }

    /** 到最近一个已确认地点（家/公司）的距离，米；都未确认时返回 null */
    @Synchronized
    fun distanceToConfirmedM(ctx: Context, lat: Double, lng: Double): Double? =
        Kind.values().mapNotNull { k -> confirmed(ctx, k)?.let { Geo.distanceM(lat, lng, it.first, it.second) } }.minOrNull()

    /** 学习进度 0..3：两个地点里较慢的那个（不满 2 天最多算 2） */
    @Synchronized
    fun progress(ctx: Context): Int = Kind.values().minOf { progressOf(ctx, it) }

    /** 单个地点的进度 0..3（首页分别显示「家」「公司」的学习进度） */
    @Synchronized
    fun progressOf(ctx: Context, k: Kind): Int {
        if (manual(ctx, k) != null || sampledOrSnapshot(ctx, k) != null) return NEED_VOTES
        val v = load(ctx).filter { it.kind == k }.maxOfOrNull { c ->
            if (c.confirmed) NEED_VOTES else c.votes.coerceAtMost(if (c.days.size >= NEED_DAYS) NEED_VOTES else NEED_VOTES - 1)
        } ?: 0
        return maxOf(v, sampleProgress(ctx, k).coerceAtMost(NEED_VOTES - 1))
    }

    fun bothConfirmed(ctx: Context) = Kind.values().all { confirmed(ctx, it) != null }

    /** 清空自动学习的数据（投票、采样和采样快照）；手动设置的家 / 公司保留 */
    @Synchronized
    fun reset(ctx: Context) {
        cache = mutableListOf(); samplesCache = mutableListOf(); derivedCache = null; derivedBest = null; snapshotCache = emptyMap()
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keep = Kind.values().mapNotNull { k -> sp.getString(manualKey(k), null)?.let { manualKey(k) to it } }
        sp.edit().clear().apply { keep.forEach { (k, v) -> putString(k, v) } }.apply()
        ctx.getSharedPreferences(SAMPLE_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun text(l: Label): String? = when (l) {
        Label.HOME -> "在家"
        Label.WORK -> "在公司"
        Label.ON_THE_WAY -> "路上"
        Label.UNKNOWN -> null
    }
}
