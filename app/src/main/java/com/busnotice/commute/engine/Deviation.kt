package com.busnotice.commute.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToLong

/**
 * 偏离识别参数。默认值与离线回放评估选定的参数一致，集中在此处便于调整。
 *
 * 判定规则：三路信号中至少 [minSignals] 路同时成立并持续 [sustainMs] 才判为偏离；单路成立只记录。
 * 本方向（同一条行程）的历史行程少于 [minHistory] 趟时不做判定。
 */
data class DeviationConfig(
    /** 每个方向至少需要的历史行程数（已判偏离的行程不计入） */
    val minHistory: Int = MIN_HISTORY,
    /** 信号 1：连续多少条服务小区采样在本方向历史中从未出现 */
    val cellRun: Int = CELL_RUN,
    /** 信号 2：预测对数似然的平均窗口（条） */
    val hmmWindow: Int = HMM_WINDOW,
    /** 信号 2：窗口内平均 log10 预测似然低于此值时成立 */
    val hmmThreshold: Double = HMM_THRESHOLD,
    /** 信号 2：不在学到序列中的基站的似然。须满足 log10(该值) < [hmmThreshold] */
    val hmmUnseenLikelihood: Double = HMM_UNSEEN_LIKELIHOOD,
    /** 信号 2：起点位置额外包含的候车时间 */
    val hmmPlatformMs: Long = HMM_PLATFORM_MS,
    /** 信号 3：只采用精度不超过此值的 GPS 定位（米） */
    val gpsMaxAccuracyM: Float = GPS_MAX_ACCURACY_M,
    /** 信号 3：距计划站点与学到轨迹的最近距离超过此值时成立（米） */
    val gpsFarM: Double = GPS_FAR_M,
    /** 判定所需的同时成立持续时间 */
    val sustainMs: Long = SUSTAIN_MS,
    /** 每路信号在没有新的同类采样时保持原状态的最长时间，超过后视为未知 */
    val holdMs: Long = HOLD_MS,
    /** 判定所需的同时成立信号数 */
    val minSignals: Int = MIN_SIGNALS,
    /** 学到轨迹去重所用的网格（度） */
    val pointGridDeg: Double = POINT_GRID_DEG,
    /** 学习基站序列时，一个基站至少出现在多少比例的乘车段中 */
    val seqMinFraction: Double = SEQ_MIN_FRACTION,
    /** 学习基站序列所需的最少乘车段数 */
    val seqMinRides: Int = SEQ_MIN_RIDES,
    /** 学到的基站序列的最少长度 */
    val seqMinSize: Int = SEQ_MIN_SIZE,
    /** 地下采样间隔超过此值时视为两段 */
    val runGapMs: Long = RUN_GAP_MS,
    /** 地下段至少覆盖的时长，较短的段不用于学习序列 */
    val runMinMs: Long = RUN_MIN_MS,
) {
    companion object {
        const val MIN_HISTORY = 5
        const val CELL_RUN = 3
        const val HMM_WINDOW = 2
        const val HMM_THRESHOLD = -2.0
        const val HMM_UNSEEN_LIKELIHOOD = 0.005
        const val HMM_PLATFORM_MS = 240_000L
        const val GPS_MAX_ACCURACY_M = 50f
        const val GPS_FAR_M = 800.0
        const val SUSTAIN_MS = 120_000L
        const val HOLD_MS = 600_000L
        const val MIN_SIGNALS = 2
        const val POINT_GRID_DEG = 0.0005
        const val SEQ_MIN_FRACTION = 0.3
        const val SEQ_MIN_RIDES = 2
        const val SEQ_MIN_SIZE = 3
        const val RUN_GAP_MS = 12 * 60_000L
        const val RUN_MIN_MS = 5 * 60_000L
        /** 学习时回看的天数 */
        const val HISTORY_DAYS = 30L
    }
}

/** 服务小区键：有 LTE 时为 eNB 号，否则为 NR 区号 */
data class CellKey(val nr: Boolean, val value: Long)

/**
 * 一条服务小区采样。[lteCi] 为第一个 LTE 服务小区的小区号，[nrTac] 为第一个 NR 服务小区的区号。
 */
data class DevCell(val t: Long, val nrTac: Int?, val lteCi: Long?) {
    val enb: Long? get() = lteCi?.takeIf { it in 1L..0x0FFFFFFFL }?.let { it ushr 8 }
    val key: CellKey? get() = enb?.let { CellKey(false, it) } ?: nrTac?.takeIf { it != 0 }?.let { CellKey(true, it.toLong()) }
}

/** 一条高精度定位 */
data class DevPoint(val t: Long, val lat: Double, val lng: Double)

/**
 * 一趟历史行程中偏离识别所需的内容。
 * @param cells 全部服务小区采样（按时间排序）
 * @param startAt 行程开始时刻（第一次非误启动的开始）
 * @param endAt 行程结束时刻（最后一次手动结束，否则为最后一条记录）
 * @param gps 全部精度达标的 GPS 定位
 * @param flagged 该趟已被判为偏离：作为冷数据保留，不参与学习
 */
class DevTrip(val cells: List<DevCell>, val startAt: Long, val endAt: Long, val gps: List<DevPoint>, val flagged: Boolean)

/** 沿线基站序列及到终点的典型剩余时间 */
class DevLine(val order: List<Long>, val remMs: LongArray) {
    val size get() = order.size
}

/**
 * 偏离识别所用的模型：按方向从历史行程学习得到。
 * @param history 参与学习的历史行程数
 * @param excluded 因已判偏离而未参与学习的行程数
 */
class DeviationModel(
    val seenCells: Set<CellKey>,
    val line: DevLine?,
    val points: List<Pair<Double, Double>>,
    val history: Int,
    val excluded: Int,
    val config: DeviationConfig,
) {
    val ready get() = history >= config.minHistory

    companion object {
        /**
         * @param trips 本方向更早的历史行程
         * @param plan 计划线路的站点坐标（上车站、下车站）
         * @param isMetroTac 判断区号是否为地下区号
         */
        fun learn(trips: List<DevTrip>, plan: List<Pair<Double, Double>>, isMetroTac: (Int?) -> Boolean,
                  config: DeviationConfig = DeviationConfig()): DeviationModel {
            val train = trips.filter { !it.flagged }
            val seen = HashSet<CellKey>()
            train.forEach { tr -> tr.cells.forEach { c -> c.key?.let(seen::add) } }
            val g = config.pointGridDeg
            fun snap(v: Double) = (v / g).roundToLong() * g
            val pts = LinkedHashSet<Pair<Double, Double>>()
            plan.forEach { (a, b) -> pts += snap(a) to snap(b) }
            train.forEach { tr -> tr.gps.forEach { p -> pts += snap(p.lat) to snap(p.lng) } }
            return DeviationModel(seen, learnLine(train, isMetroTac, config), pts.toList(), train.size, trips.size - train.size, config)
        }

        /** 本趟行程时段内最长的一段连续地下采样，返回 (时刻, eNB)；不足 [DeviationConfig.runMinMs] 的段不计 */
        fun metroRun(tr: DevTrip, isMetroTac: (Int?) -> Boolean, config: DeviationConfig): List<Pair<Long, Long?>>? {
            val runs = ArrayList<List<Pair<Long, Long?>>>()
            var cur = ArrayList<Pair<Long, Long?>>()
            for (c in tr.cells) {
                if (c.t < tr.startAt - 1 || c.t > tr.endAt || c.key == null) continue
                if (isMetroTac(c.nrTac)) {
                    if (cur.isNotEmpty() && c.t - cur.last().first > config.runGapMs) { runs += cur; cur = ArrayList() }
                    cur += c.t to c.enb
                } else if (cur.isNotEmpty()) { runs += cur; cur = ArrayList() }
            }
            if (cur.isNotEmpty()) runs += cur
            return runs.filter { it.last().first - it.first().first >= config.runMinMs }
                .maxByOrNull { it.last().first - it.first().first }
        }

        /** 从历次地下段学习 eNB 顺序（按段内相对位置的中位数排序）与到终点的剩余时间（中位数） */
        fun learnLine(train: List<DevTrip>, isMetroTac: (Int?) -> Boolean, config: DeviationConfig): DevLine? {
            val runs = train.mapNotNull { metroRun(it, isMetroTac, config) }
            if (runs.size < config.seqMinRides) return null
            val pos = LinkedHashMap<Long, MutableList<Double>>()
            val firsts = ArrayList<Map<Long, Long>>()
            for (r in runs) {
                val t0 = r.first().first; val t1 = r.last().first
                val seen = LinkedHashMap<Long, Long>()
                for ((t, e) in r) if (e != null && e !in seen) seen[e] = t
                firsts += seen
                for ((e, t) in seen) pos.getOrPut(e) { ArrayList() } += (t - t0).toDouble() / maxOf(t1 - t0, 1L)
            }
            val keep = pos.filter { it.value.size >= config.seqMinFraction * runs.size }.keys.toList()
            if (keep.size < config.seqMinSize) return null
            val order = keep.sortedBy { median(pos.getValue(it)) }
            val rem = HashMap<Long, MutableList<Double>>()
            for (seen in firsts) {
                val present = order.filter { it in seen }
                if (present.isEmpty()) continue
                val anchor = seen.getValue(present.last())
                for (e in present) {
                    val t = seen.getValue(e)
                    if (t <= anchor) rem.getOrPut(e) { ArrayList() } += (anchor - t).toDouble()
                }
            }
            val rm = DoubleArray(order.size) { i -> rem[order[i]]?.takeIf { it.isNotEmpty() }?.let(::median) ?: 0.0 }
            rm[rm.size - 1] = 0.0
            for (i in rm.size - 2 downTo 0) rm[i] = maxOf(rm[i], rm[i + 1] + 20_000.0)
            return DevLine(order, LongArray(rm.size) { rm[it].toLong() })
        }

        private fun median(v: List<Double>): Double {
            val s = v.sorted(); val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
        }

        /** 两点间距离（米），等距圆柱投影近似，适用于数公里以内 */
        fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val x = Math.toRadians(lng2 - lng1) * cos(Math.toRadians((lat1 + lat2) / 2))
            val y = Math.toRadians(lat2 - lat1)
            return 6_371_000.0 * hypot(x, y)
        }
    }

    /** 到计划站点与学到轨迹的最近距离（米）；没有任何参考点时返回 null */
    fun nearestM(lat: Double, lng: Double): Double? =
        if (points.isEmpty()) null else points.minOf { (a, b) -> distanceM(lat, lng, a, b) }
}

/**
 * 沿学到的基站序列做隐马尔可夫跟踪，并给出每条观测的预测似然 p(观测 | 此前的观测)。
 * 状态转移与观测模型与 [MetroHmm] 相同；不在序列中的基站按 [DeviationConfig.hmmUnseenLikelihood] 计，不更新信念。
 */
class DeviationHmm(private val line: DevLine, private val config: DeviationConfig) {
    private val n = line.size
    private val index = line.order.withIndex().associate { it.value to it.index }
    private var b = DoubleArray(n) { if (it == 0) 0.9 else 0.1 / (n - 1).coerceAtLeast(1) }
    private var lastT = 0L

    private fun segMs(i: Int): Long = if (i >= n - 1) 0L else maxOf(line.remMs[i] - line.remMs[i + 1], 20_000L)

    fun predict(t: Long) {
        if (lastT == 0L) { lastT = t; return }
        var left = (t - lastT).coerceAtLeast(0L)
        lastT = t
        while (left > 0) {
            val d = minOf(left, MetroHmm.STEP_MS); left -= d
            val nb = DoubleArray(n)
            for (i in 0 until n) {
                if (i == n - 1) { nb[i] += b[i]; continue }
                val mean = segMs(i) + if (i == 0) config.hmmPlatformMs else 0L
                val p = 1 - exp(-d.toDouble() / mean)
                nb[i] += b[i] * (1 - p); nb[i + 1] += b[i] * p
            }
            b = nb
        }
    }

    /** 一次地下观测，返回预测似然 */
    fun observe(t: Long, enb: Long?): Double {
        predict(t)
        val j = enb?.let { index[it] } ?: return config.hmmUnseenLikelihood
        val lik = DoubleArray(n) { i -> b[i] * when (abs(i - j)) { 0 -> 0.80; 1 -> 0.08; else -> 0.005 } }
        val s = lik.sum()
        b = if (s > 0) DoubleArray(n) { lik[it] / s } else DoubleArray(n) { if (it == j) 1.0 else 0.0 }
        return s
    }
}

/** 偏离识别输出的一条诊断事件：[name] 为事件名，[fields] 为附带的数值（不含位置与基站值） */
data class DevEvent(val name: String, val fields: List<Pair<String, Any?>>)

/**
 * 偏离识别（观察模式）：逐条接收本趟行程的服务小区采样与定位，计算三路信号并按规则判定，只输出诊断事件。
 *
 * - 基站：服务小区键连续 [DeviationConfig.cellRun] 条在本方向历史中从未出现。
 * - HMM：地下采样沿学到序列的预测似然，最近 [DeviationConfig.hmmWindow] 条的平均 log10 值低于阈值。
 * - GPS：精度达标的 GPS 距计划站点与学到轨迹超过 [DeviationConfig.gpsFarM]。
 *
 * 每路信号在下一条同类采样到来前保持原状态，超过 [DeviationConfig.holdMs] 视为未知。
 * 至少 [DeviationConfig.minSignals] 路同时成立并持续 [DeviationConfig.sustainMs] 时判为偏离，每趟只判一次。
 * 模型未就绪（历史不足）时不计算信号。
 */
class DeviationDetector(private val model: DeviationModel, private val startAt: Long) {
    private val cfg = model.config
    private var hmm: DeviationHmm? = null
    private var run = 0
    private val liks = ArrayDeque<Double>()
    private val state = arrayOfNulls<Boolean>(3)
    private val stateAt = LongArray(3)
    private val shown = arrayOfNulls<Boolean>(3)
    private val value = arrayOfNulls<Any>(3)
    private var since = 0L
    private val sinceSignals = sortedSetOf<Int>()
    private var lastT = 0L
    private val onMs = LongArray(3)
    private var coMs = 0L
    private var coMaxMs = 0L
    private var nCell = 0; private var nHmm = 0; private var nGps = 0

    var flagAt = 0L; private set
    var flagOnset = 0L; private set
    var flagSignals: List<String> = emptyList(); private set
    val flagged get() = flagAt > 0
    /** 最近一次处理的采样时刻 */
    val lastSampleAt get() = lastT

    /** 一条服务小区采样；[underground] 为该采样的区号是否为地下区号 */
    fun onCell(c: DevCell, underground: Boolean): List<DevEvent> {
        if (!model.ready || c.key == null || c.t < lastT) return emptyList()
        nCell++
        run = if (c.key !in model.seenCells) run + 1 else 0
        state[CELL] = run >= cfg.cellRun; stateAt[CELL] = c.t; value[CELL] = run
        val line = model.line
        if (line != null && underground) {
            val h = hmm ?: DeviationHmm(line, cfg).also { hmm = it; it.predict(c.t) }
            liks.addLast(log10(maxOf(h.observe(c.t, c.enb), 1e-9)))
            while (liks.size > cfg.hmmWindow) liks.removeFirst()
            nHmm++
            if (liks.size >= minOf(cfg.hmmWindow, 2) || cfg.hmmWindow == 1) {
                val avg = liks.average()
                state[HMM] = avg < cfg.hmmThreshold; stateAt[HMM] = c.t; value[HMM] = "%.2f".format(java.util.Locale.ROOT, avg)
            }
        }
        return step(c.t)
    }

    /** 一条定位；只采用精度达标的 GPS 定位 */
    fun onFix(t: Long, lat: Double, lng: Double, accuracy: Float, gps: Boolean): List<DevEvent> {
        if (!model.ready || !gps || accuracy > cfg.gpsMaxAccuracyM || t < lastT) return emptyList()
        val d = model.nearestM(lat, lng) ?: return emptyList()
        nGps++
        state[GPS] = d > cfg.gpsFarM; stateAt[GPS] = t; value[GPS] = d.toLong()
        return step(t)
    }

    private fun current(i: Int, t: Long): Boolean? = if (t - stateAt[i] <= cfg.holdMs) state[i] else null

    private fun step(t: Long): List<DevEvent> {
        val out = ArrayList<DevEvent>()
        val prevT = lastT
        // 累计时长：上一采样时刻成立的信号按保持期计入
        if (prevT > 0) {
            var co = 0
            for (i in 0 until 3) if (shown[i] == true) {
                onMs[i] += minOf(t, stateAt[i] + cfg.holdMs).coerceAtLeast(prevT) - prevT; co++
            }
            if (co >= cfg.minSignals) coMs += t - prevT
        }
        lastT = t
        val cur = Array(3) { current(it, t) }
        for (i in 0 until 3) if (cur[i] != shown[i]) {
            shown[i] = cur[i]
            out += DevEvent("dev_signal", listOf("sig" to NAMES[i], "state" to stateName(cur[i]), "value" to value[i], "sinceStartS" to (t - startAt) / 1000))
        }
        val on = (0 until 3).filter { cur[it] == true }
        if (on.size >= cfg.minSignals) {
            if (since == 0L) { since = t; sinceSignals.clear() }
            sinceSignals += on
            coMaxMs = maxOf(coMaxMs, t - since)
            if (!flagged && t - since >= cfg.sustainMs) {
                flagAt = t; flagOnset = since; flagSignals = sinceSignals.map { NAMES[it] }
                out += DevEvent("dev_flag", listOf("at" to t, "onset" to since, "signals" to flagSignals.joinToString("+"),
                    "sinceStartS" to (t - startAt) / 1000, "sustainS" to (t - since) / 1000))
            }
        } else {
            since = 0L
        }
        return out
    }

    /** 本趟结束时的汇总 */
    fun summary(): DevEvent = DevEvent("dev_summary", listOf(
        "ready" to model.ready, "flagged" to flagged, "flagAt" to (if (flagged) flagAt else null),
        "signals" to (if (flagged) flagSignals.joinToString("+") else null),
        "cellS" to onMs[CELL] / 1000, "hmmS" to onMs[HMM] / 1000, "gpsS" to onMs[GPS] / 1000,
        "coS" to coMs / 1000, "coMaxS" to coMaxMs / 1000,
        "nCell" to nCell, "nHmm" to nHmm, "nGps" to nGps,
    ))

    companion object {
        const val CELL = 0
        const val HMM = 1
        const val GPS = 2
        val NAMES = listOf("cell", "hmm", "gps")
        private fun stateName(v: Boolean?) = when (v) { true -> "on"; false -> "off"; null -> "unknown" }

        /** 模型说明事件，行程开始时写入一次 */
        fun modelEvent(m: DeviationModel, reason: String? = null) = DevEvent("dev_model", listOf(
            "ready" to m.ready, "history" to m.history, "excluded" to m.excluded, "minHistory" to m.config.minHistory,
            "cells" to m.seenCells.size, "seq" to (m.line?.size ?: 0), "points" to m.points.size, "reason" to reason,
        ))
    }
}
