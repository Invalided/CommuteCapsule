package com.busnotice.commute.engine

import com.busnotice.commute.AccMotion
import com.busnotice.commute.CellSnap
import com.busnotice.commute.MetroGate
import kotlin.math.abs

/** 行程中的一段。metro 段的 board / alight 为进站与出站坐标；地面段的 board 为上车点，alight 为下车点。 */
data class LegSpec(val metro: Boolean, val board: Point?, val alight: Point?, val rideMs: Long)

/**
 * 地铁相关判断（纯逻辑）：进站、上车、到站时刻（E1 基站预测）、下车、换乘提醒。
 * 与 CommuteService 中现行规则保持一致，用于离线回放评估；地面段的上车由外部输入（onBoarded）。
 *
 * 与 CommuteService 在线判定的已知差异：
 * - 列车班次按「进站后 4 分钟」估算，不读时刻表；
 * - 不处理刷卡、半路上车（join）与手动操作；
 * - 下车判断不含计步分支（历史数据无步数）。
 */
class MetroEngine(
    private val legs: List<LegSpec>,
    private val isMetroTac: (Int?) -> Boolean,
    private val enbEta: Map<Long, Long>,
    private val sig: Signals,
    private val startedAt: Long,
    /** 不为空时启用沿线 HMM：到站预测、换乘提醒提前量与「基站不变即下车」的否决改用 HMM 的位置估计。 */
    private val hmmLine: MetroLine? = null,
    /** 不为空时启用地面段卡尔曼：换乘提醒按滤波后的距离与历史「距离 → 剩余时间」曲线提前发出。 */
    private val busProfile: DistProfile? = null,
) {
    val out = ArrayList<EngineEvent>()
    var legIdx = 0; private set
    var riding = false; private set
    private var boardedAt = 0L
    private var stationAt = 0L
    private var alightAt = 0L
    private var enterVetoAt = 0L
    private var transferAlerted = false
    private var xferHoldSince = 0L
    private var fpEtaAt = 0L
    private val fpSeen = HashSet<Long>()
    private val gate = MetroGate(isMetroTac)
    private val fenceFixes = ArrayDeque<Fix>()
    private var stillSince = 0L
    private var lastFastAt = 0L
    private var firstFix: Fix? = null
    private var hmm: MetroHmm? = null
    private var hmmDepartEmitted = false
    private var kf: KalmanTrack? = null
    private var kfLegIdx = -1

    private fun emit(name: String, now: Long, at: Long, vararg kv: Pair<String, Any?>) { out += EngineEvent(name, now, at, mapOf(*kv)) }
    private val cur get() = legs[legIdx]
    private val prev get() = if (legIdx > 0) legs[legIdx - 1] else null

    /** 地铁行程中（含站台候车之后已判上车、尚未下车）。 */
    private fun inMetroRide(): Boolean =
        if (riding) cur.metro else prev?.metro == true && boardedAt > 0 && alightAt == 0L

    private fun onBus(): Boolean =
        if (riding) !cur.metro else prev?.metro == false && boardedAt > 0 && alightAt == 0L

    private fun busMoving(now: Long) = onBus() && sig.fast(now)

    fun onFix(f: Fix) {
        if (firstFix == null && f.time >= startedAt - 30_000) firstFix = f
        sig.onFix(f)
        kfFeed(f)
    }

    /** 地面段乘车中（已上车、下一段有上车点）把定位喂给卡尔曼。 */
    private fun kfFeed(f: Fix) {
        if (busProfile == null || !onBus() || riding || boardedAt == 0L || f.time <= boardedAt) return
        val target = cur.board ?: return
        if (kf == null || kfLegIdx != legIdx) { kf = KalmanTrack(target); kfLegIdx = legIdx }
        if (f.accuracy > 500) return
        val k = kf!!
        val r = k.onFix(f) ?: return
        if (!r) emit("kf_reject", f.t, f.time, "acc" to f.accuracy.toInt(), "gps" to f.gps, "z" to Dist.m(f, target).toInt(), "pred" to k.predictD(f.time).toInt())
        else emit("kf", f.t, f.time, "d" to k.d.toInt(), "v" to "%.1f".format(k.v), "sd" to k.sigmaD().toInt(), "z" to Dist.m(f, target).toInt(), "gps" to f.gps)
    }

    /** 卡尔曼给出的到换乘点剩余时间（毫秒）；滤波未就绪或最后一次采纳的定位超过 3 分钟则返回 null。 */
    private fun kfRemainingMs(now: Long): Long? {
        val k = kf ?: return null
        if (kfLegIdx != legIdx || !k.ready || now - k.lastAcceptAt > KF_MAX_EXTRAP_MS) return null
        val d = k.predictD(now)
        return busProfile?.remainingMs(d) ?: (d / k.v.coerceIn(2.0, 20.0) * 1000).toLong()
    }

    /** 外部判定的上车（地面段）。与 CommuteService.onBoarded 的段推进规则一致。 */
    fun onBoarded(at: Long, by: String) {
        emit("boarded", at, at, "leg" to legIdx, "by" to by)
        stationAt = 0L
        if (legIdx >= legs.size - 1) {
            if (riding) return
            riding = true; boardedAt = at; return
        }
        boardedAt = at; legIdx++
        transferAlerted = false; xferHoldSince = 0L; alightAt = 0L
        fenceFixes.clear(); stillSince = 0L; lastFastAt = 0L
        fpEtaAt = 0L; fpSeen.clear()
    }

    fun onCell(s: CellSnap) {
        val underground = isMetroTac(s.nrTac)
        hmmFeed(s, underground)
        if (hmmLine == null && inMetroRide()) {
            val e = s.enb?.toLong()
            val maxRem = enbEta.values.maxOrNull() ?: 0L
            if (underground && e != null && fpSeen.add(e)) enbEta[e]?.takeIf { maxRem - it >= 120_000L }?.let { rem ->
                fpEtaAt = s.t + rem
                emit("fp_eta", s.t, fpEtaAt, "enb" to e)
            }
        }
        val edge = gate.feed(s)
        if (edge == null && enterVetoAt > 0 && underground && gate.sawMetro) {
            if (!cur.metro || riding || stationAt > 0) enterVetoAt = 0L
            else if (s.t - enterVetoAt >= ENTER_RETRY_MS && sig.goodGpsAt < enterVetoAt && !busMoving(s.t))
                acceptEnter(enterVetoAt, s.t, "retry")
        }
        if (edge == null) return
        when (edge.first) {
            MetroGate.Edge.ENTER -> {
                if (!cur.metro || riding || stationAt > 0) return
                val fix = sig.best?.takeIf { s.t - it.time < 60_000 && it.accuracy <= ENTER_VETO_ACC }
                val st = cur.board
                val far = fix != null && st != null && run {
                    val d = Dist.m(fix, st)
                    if (fix.gps) d > 500 + fix.accuracy.coerceAtMost(150f) else d > ENTER_VETO_NET_M
                }
                if (busMoving(s.t) || far) {
                    enterVetoAt = if (far && fix?.gps == false) 0L else edge.second
                    emit("cell_enter_skip", s.t, edge.second, "by" to if (far) (if (fix!!.gps) "gps" else "network") else "bus")
                    return
                }
                acceptEnter(edge.second, s.t, "edge")
            }
            MetroGate.Edge.EXIT -> {
                enterVetoAt = 0L
                if (!inMetroRide()) return
                emit("cell_exit", s.t, edge.second)
                if (!riding && prev?.metro == true && alightAt == 0L) markAlight(edge.second, s.t, "cell")
            }
        }
    }

    private fun hmmStart(at: Long) {
        if (hmmLine == null || hmm != null) return
        hmm = MetroHmm(hmmLine).also { it.predict(at) }
        hmmDepartEmitted = false
    }

    /** 地铁段（站台候车或车上）每个基站采样喂给 HMM，并刷新到站预测。 */
    private fun hmmFeed(s: CellSnap, underground: Boolean) {
        if (hmmLine == null) return
        val active = inMetroRide() || (cur.metro && !riding && stationAt > 0)
        if (!active) { if (hmm != null && alightAt > 0) hmm = null; return }
        if (hmm == null) hmmStart(if (stationAt > 0) stationAt else s.t)
        val h = hmm!!
        if (underground) h.observe(s.t, s.enb?.toLong()) else h.predict(s.t)
        if (!hmmDepartEmitted) h.departEstimate()?.let { hmmDepartEmitted = true; emit("hmm_depart", s.t, it, "idx" to h.firstMoveIdx) }
        if (inMetroRide()) h.remainingMs(s.t)?.let { rem ->
            // 已到终点位置：到站时刻固定为进入终点位置的时刻，不随时间后移
            val eta = if (h.atTerminal) h.mapSince else s.t + rem
            if (fpEtaAt == 0L || abs(eta - fpEtaAt) >= 30_000L) emit("fp_eta", s.t, eta, "pos" to h.map, "pT" to "%.2f".format(h.pTerminal()))
            fpEtaAt = eta
            // 终点位置概率足够高且观测充分：以进入终点位置的时刻作为下车时刻
            if (h.atTerminal && h.pTerminal() >= HMM_TERM_P && h.observed >= 3 && !riding && prev?.metro == true && alightAt == 0L)
                markAlight(h.mapSince, s.t, "hmm_term")
        }
    }

    private fun acceptEnter(at: Long, now: Long, how: String) {
        enterVetoAt = 0L; stationAt = at
        emit("cell_enter", now, at, "how" to how)
    }

    private fun markAlight(at: Long, now: Long, by: String) {
        if (alightAt > 0) return
        alightAt = at
        emit("alight_detect", now, at, "by" to by)
    }

    /** 周期判断，返回下一次判断的间隔（毫秒），节奏与 CommuteService 的在线判定相近。 */
    fun tick(now: Long): Long {
        val leg = cur
        val p = prev
        val loc = sig.best?.takeIf { now - it.time < 180_000 }
        val target = if (p != null && boardedAt > 0) leg.board else null
        val nearest = if (loc != null && target != null) Dist.m(loc, target) else null

        // 前一段为地铁、尚未确认下车：仅当精度 ≤ 100 米且已进入预计到站前 5 分钟，贴近换乘点 300 米内才记为下车
        val metroUnsure = p?.metro == true && boardedAt > 0 && alightAt == 0L
        if (metroUnsure && loc != null && loc.accuracy <= 100 && nearest != null && nearest <= 300 &&
            now >= boardedAt + p!!.rideMs - 5 * 60_000L) markAlight(now, now, "near")

        // 前一段为地铁：E1 到站时刻优先，否则「上车时刻 + 学到的乘车时长」
        val fpArrive = if (p?.metro == true && fpEtaAt > boardedAt && now < fpEtaAt + 10 * 60_000L) fpEtaAt else 0L
        val expectArrive = if (boardedAt > 0 && p != null) (fpArrive.takeIf { it > 0 } ?: (boardedAt + p.rideMs)) else 0L
        val fromMetro = p?.metro == true
        if (fromMetro && expectArrive > 0 && alightAt == 0L) alightTick(now, expectArrive)
        // HMM 模式下，下车已判定但换乘提醒尚未发出（进程冻结后恢复）时，仍在 2 分钟内补发
        val lateOk = hmmLine != null && alightAt > 0 && now - alightAt < 120_000L
        val connecting = expectArrive > 0 && (if (fromMetro) (alightAt == 0L || lateOk) else (nearest == null || nearest > 300) && now < expectArrive + 5 * 60_000L)

        // GPS 推断进站：最后一次 GPS 贴近站口且之后 90 秒无信号，且不在出发地附近
        if (leg.metro && stationAt == 0L && !riding) {
            val g = sig.lastGps; val st = leg.board
            if (g != null && st != null && g.time >= startedAt - 30_000 && now - g.time > 90_000 && Dist.m(g, st) <= 200 &&
                firstFix.let { o -> o == null || Dist.m(g.lat, g.lng, o.lat, o.lng) > 150 }) {
                stationAt = g.time; emit("station_gps", now, g.time)
            }
        }
        if (leg.metro && stationAt > 0 && !riding) {
            metroBoardAt(leg, stationAt + 4 * 60_000L, now)?.let { onBoarded(it, "metro"); return 1_000L }
        }

        // 换乘提醒
        val tFresh = loc != null && now - loc.time < 120_000 && loc.accuracy < 200
        val lead = if (fpArrive > 0) (if (hmmLine != null) HMM_LEAD_MS else 90_000L) else 180_000L
        // HMM 给出到站时刻时提醒更早，列车此时离换乘点本就较远：距离拦截阈值由 800 米放宽到 2500 米
        val gateM = if (hmmLine != null && fpArrive > 0) HMM_GATE_M else 800.0
        var timeOk = connecting && now >= expectArrive - lead && !(tFresh && nearest != null && nearest > gateM)
        if (timeOk && !tFresh && fpArrive == 0L && p?.metro == false) {
            if (xferHoldSince == 0L) { xferHoldSince = now; emit("transfer_hold", now, now) }
            if (now - xferHoldSince < XFER_HOLD_MS) timeOk = false
        } else if (tFresh) xferHoldSince = 0L
        val kfRem = if (p?.metro == false) kfRemainingMs(now) else null
        val kfOk = kfRem != null && kfRem <= KF_LEAD_MS
        if (legIdx > 0 && boardedAt > 0 && !transferAlerted && ((nearest != null && nearest <= 500) || timeOk || kfOk)) {
            transferAlerted = true
            val kv = mutableListOf<Pair<String, Any?>>("nearest" to nearest?.toInt(), "byTime" to timeOk, "fp" to (fpArrive > 0))
            if (busProfile != null) { kv += "kf" to kfOk; kv += "kfRem" to kfRem?.let { it / 1000 } }
            emit("transfer_alert", now, now, *kv.toTypedArray())
        }
        val onMetro = connecting && fromMetro && now < expectArrive - 5 * 60_000L
        return when {
            onMetro -> 60_000L
            leg.metro && !riding -> 30_000L
            nearest != null && nearest < 300 -> 20_000L
            else -> 40_000L
        }
    }

    private fun metroBoardAt(leg: LegSpec, train: Long, now: Long): Long? {
        val ws = sig.windows()
        val walkEnd = AccMotion.lastWalkEnd(ws, stationAt, now) ?: stationAt
        val dep = AccMotion.departureSince(ws, walkEnd, now)
        val fallbackAt = dep ?: maxOf(train, walkEnd)
        val st = leg.board
        val b = sig.best
        if (st != null && b != null && b.time > stationAt && b.accuracy <= 1_000 && Dist.m(b, st) - b.accuracy > 500) {
            val at = minOf(fallbackAt, b.time)
            emit("metro_board", now, at, "by" to "moved"); return at
        }
        if (dep != null) {
            val cc = sig.cellChangeAfter(maxOf(dep + 30_000L, walkEnd + 20_000L))
            if (cc != null && cc <= now) { emit("metro_board", now, dep, "by" to "cell"); return dep }
        }
        sig.magTrainAt(maxOf(walkEnd, stationAt))?.takeIf { now - it >= 60_000L }?.let { at ->
            emit("metro_board", now, at, "by" to "mag"); return at
        }
        if ((now >= train + 6 * 60_000L && now - walkEnd > 60_000L) || now >= train + 12 * 60_000L) {
            emit("metro_board", now, fallbackAt, "by" to "time"); return fallbackAt
        }
        return null
    }

    private fun alightTick(now: Long, expectArrive: Long) {
        val window = now >= expectArrive - 3 * 60_000L
        val st = prev?.alight
        val fix = sig.best?.takeIf { now - it.time < 60_000 && it.time > boardedAt && it.accuracy < 500 }
        if (fix != null && (fenceFixes.isEmpty() || fenceFixes.last().time != fix.time)) {
            fenceFixes.addLast(fix); while (fenceFixes.size > 6) fenceFixes.removeFirst()
        }
        val pair = fenceFixes.toList().takeLast(2)
        val speed = if (pair.size == 2 && pair[1].time > pair[0].time)
            Dist.m(pair[0].lat, pair[0].lng, pair[1].lat, pair[1].lng) / ((pair[1].time - pair[0].time) / 1000.0) else null
        if (speed != null && speed >= 6.0) { stillSince = 0L; lastFastAt = now }
        val dist = if (fix != null && st != null) Dist.m(fix, st) else null
        val inFence = dist != null && dist <= 350 + fix!!.accuracy.coerceAtMost(200f)
        if (inFence && (speed == null || speed < 2.0)) { if (stillSince == 0L) stillSince = now } else if (dist != null && !inFence) stillSince = 0L
        val recentlyFast = now - lastFastAt < 60_000
        val noLocFor = fenceFixes.lastOrNull()?.let { now - it.time } ?: Long.MAX_VALUE
        val cellStill = sig.cellStillMs(now)
        val wins = if (window) sig.windows() else emptyList()
        val walkStart = AccMotion.walkingSustainedSince(wins, now)?.takeIf { it > boardedAt }
        val motionOk = window && walkStart != null && AccMotion.hadStillBefore(wins, walkStart) && !recentlyFast &&
            (inFence || dist == null || noLocFor > 2 * 60_000L)
        val g = sig.lastGps
        val by = when {
            g != null && now - g.time < 60_000 && g.time > boardedAt && now >= expectArrive - 2 * 60_000L && (dist == null || dist < 1_500) -> "gps"
            motionOk -> "motion"
            window && stillSince > 0 && now - stillSince >= 90_000 && (inFence || dist == null) -> "fence+still"
            window && cellStill != null && cellStill >= 150_000 && sig.cellSince > boardedAt + 3 * 60_000L && hmmAllowsStill() &&
                !recentlyFast && (inFence || dist == null || noLocFor > 60_000L) -> "cell_still"
            now >= expectArrive + 3 * 60_000L && !(dist != null && dist > 1_500 && recentlyFast && now < expectArrive + 10 * 60_000L) -> "time"
            else -> null
        } ?: return
        val at = when (by) {
            "motion" -> walkStart ?: now
            "cell_still" -> sig.cellSince.coerceIn(boardedAt, now)
            else -> now
        }
        markAlight(at, now, by)
    }

    /** HMM 已有足够观测且终点概率低于 0.5 时，基站长时间不变不视为下车（列车停在区间或站间距较长）。 */
    private fun hmmAllowsStill(): Boolean { val h = hmm ?: return true; return h.observed < 3 || h.pTerminal() >= 0.5 }

    companion object {
        const val HMM_LEAD_MS = 150_000L
        const val HMM_TERM_P = 0.8
        const val HMM_GATE_M = 2_500.0
        const val KF_LEAD_MS = 150_000L
        const val KF_MAX_EXTRAP_MS = 180_000L
        const val ENTER_VETO_ACC = 50f
        const val ENTER_VETO_NET_M = 3_000.0
        const val ENTER_RETRY_MS = 120_000L
        const val XFER_HOLD_MS = 90_000L
    }
}
