package com.busnotice.commute

import com.busnotice.commute.engine.CellKey
import com.busnotice.commute.engine.DevCell
import com.busnotice.commute.engine.DevEvent
import com.busnotice.commute.engine.DevPoint
import com.busnotice.commute.engine.DevTrip
import com.busnotice.commute.engine.DeviationConfig
import com.busnotice.commute.engine.DeviationDetector
import com.busnotice.commute.engine.DeviationModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 偏离识别（观察模式）。线路、区号、基站与坐标均为虚构：
 * 地面区号 [SURFACE_TAC]、地下区号 [METRO_TAC]，沿线地下基站 [LINE]，地面基站 [GROUND]，轨迹为一条虚构的南北直线。
 */
class DeviationTest {
    private val cfg = DeviationConfig()
    private val metro: (Int?) -> Boolean = { it == METRO_TAC }

    /** 一条采样：基站或定位 */
    private sealed class S(val t: Long) {
        class C(t: Long, val cell: DevCell) : S(t)
        class G(t: Long, val lat: Double, val lng: Double, val acc: Float = 10f) : S(t)
    }

    private fun lte(enb: Long, sector: Long = 1) = (enb shl 8) or sector
    private fun ground(t: Long, enb: Long) = S.C(t, DevCell(t, SURFACE_TAC, lte(enb)))
    private fun under(t: Long, enb: Long) = S.C(t, DevCell(t, METRO_TAC, lte(enb)))
    /** 沿虚构轨迹的第 i 个点（约 110 米间距） */
    private fun onPath(t: Long, i: Int) = S.G(t, BASE_LAT + i * 0.001, BASE_LNG)
    /** 离虚构轨迹约 [m] 米的点 */
    private fun offPath(t: Long, m: Double) = S.G(t, BASE_LAT + 0.005, BASE_LNG + m / 111_000.0)

    /** 一趟正常行程：地面步行 → 地下按序经过各基站 → 地面步行，共约 20 分钟 */
    private fun normal(t0: Long): List<S> {
        val out = ArrayList<S>()
        out += ground(t0, GROUND[0]); out += onPath(t0 + 30_000, 0); out += onPath(t0 + 90_000, 1)
        LINE.forEachIndexed { i, e -> out += under(t0 + 180_000 + i * 120_000L, e) }
        val tEnd = t0 + 180_000 + LINE.size * 120_000L
        out += ground(tEnd, GROUND[1]); out += onPath(tEnd + 30_000, 9); out += onPath(tEnd + 60_000, 10)
        return out
    }

    private fun trip(samples: List<S>, flagged: Boolean = false): DevTrip = DevTrip(
        samples.filterIsInstance<S.C>().map { it.cell },
        samples.first().t, samples.last().t,
        samples.filterIsInstance<S.G>().map { DevPoint(it.t, it.lat, it.lng) },
        flagged,
    )

    private fun history(n: Int) = (0 until n).map { trip(normal(DAY * (it + 1))) }
    private val plan = listOf(BASE_LAT to BASE_LNG, BASE_LAT + 0.010 to BASE_LNG)
    private fun model(n: Int = 6, extra: List<DevTrip> = emptyList()) = DeviationModel.learn(history(n) + extra, plan, metro)

    private fun run(d: DeviationDetector, samples: List<S>): List<DevEvent> = samples.flatMap {
        when (it) {
            is S.C -> d.onCell(it.cell, metro(it.cell.nrTac))
            is S.G -> d.onFix(it.t, it.lat, it.lng, it.acc, gps = true)
        }
    }

    private fun DevEvent.f(k: String) = fields.first { it.first == k }.second

    @Test fun modelLearnsCellsLineAndPoints() {
        val m = model()
        assertTrue(m.ready)
        assertEquals(6, m.history)
        assertTrue(LINE.all { CellKey(false, it) in m.seenCells })
        assertEquals(LINE, m.line!!.order)
        assertEquals(0L, m.line!!.remMs.last())
        assertTrue(m.line!!.remMs.toList().zipWithNext().all { (a, b) -> a > b })
        assertTrue(m.nearestM(BASE_LAT + 0.001, BASE_LNG)!! < 50.0)
        assertTrue(m.nearestM(BASE_LAT + 0.005, BASE_LNG + 0.02)!! > cfg.gpsFarM)
    }

    @Test fun normalTripIsNotFlagged() {
        val d = DeviationDetector(model(), T0)
        val ev = run(d, normal(T0))
        assertFalse(d.flagged)
        assertTrue(ev.none { it.name == "dev_flag" })
        assertTrue(ev.none { it.name == "dev_signal" && it.f("state") == "on" })
        val s = d.summary()
        assertEquals(false, s.f("flagged"))
        assertEquals(0L, s.f("coS"))
    }

    @Test fun singleSignalIsOnlyRecorded() {
        // 地面上全程是没见过的基站，但人仍在学到的轨迹上：只有基站一路成立
        val d = DeviationDetector(model(), T0)
        val s = (0 until 20).flatMap { i -> listOf(ground(T0 + i * 30_000L, 7000L + i), onPath(T0 + i * 30_000L + 10_000, i % 10)) }
        val ev = run(d, s)
        assertFalse(d.flagged)
        assertTrue(ev.any { it.name == "dev_signal" && it.f("sig") == "cell" && it.f("state") == "on" })
        assertTrue(ev.none { it.name == "dev_signal" && it.f("sig") == "gps" && it.f("state") == "on" })
        assertTrue((d.summary().f("cellS") as Long) > 0)
    }

    @Test fun twoSignalsShorterThanSustainAreNotFlagged() {
        val d = DeviationDetector(model(), T0)
        val s = ArrayList<S>()
        // 两路同时成立约 90 秒，随后回到学到的轨迹与基站
        for (i in 0 until 4) s += ground(T0 + i * 30_000L, 7000L + i)
        for (i in 2 until 4) s += offPath(T0 + i * 30_000L + 5_000, 2_000.0)
        s += offPath(T0 + 180_000, 2_000.0)
        s += onPath(T0 + 200_000, 3)
        s += ground(T0 + 210_000, GROUND[0])
        for (i in 0 until 10) s += onPath(T0 + 240_000 + i * 30_000L, i)
        val ev = run(d, s.sortedBy { it.t })
        assertFalse(d.flagged)
        assertTrue(ev.none { it.name == "dev_flag" })
        val co = d.summary().f("coMaxS") as Long
        assertTrue("co=$co", co in 1 until 120)
    }

    @Test fun sustainedCellAndGpsAreFlagged() {
        val d = DeviationDetector(model(), T0)
        val s = ArrayList<S>()
        for (i in 0 until 12) { s += ground(T0 + i * 30_000L, 7000L + i); s += offPath(T0 + i * 30_000L + 5_000, 1_500.0) }
        val ev = run(d, s)
        assertTrue(d.flagged)
        val flag = ev.single { it.name == "dev_flag" }
        assertEquals("cell+gps", flag.f("signals"))
        assertTrue(d.flagAt - d.flagOnset >= cfg.sustainMs)
        // 基站第 3 条未见（60 秒）时成立，GPS 同时成立，持续 2 分钟后判定
        assertEquals(T0 + 60_000, d.flagOnset)
        assertEquals(true, d.summary().f("flagged"))
        // 判定只发生一次
        run(d, (12 until 20).flatMap { i -> listOf(ground(T0 + i * 30_000L, 7100L + i), offPath(T0 + i * 30_000L + 5_000, 1_500.0)) })
        assertEquals(d.flagAt, flag.f("at"))
    }

    @Test fun undergroundOnAnotherLineIsFlaggedByCellAndHmm() {
        val d = DeviationDetector(model(), T0)
        val s = ArrayList<S>()
        s += ground(T0, GROUND[0]); s += onPath(T0 + 30_000, 0)
        // 进站后全是另一条线的地下基站
        for (i in 0 until 8) s += under(T0 + 120_000 + i * 60_000L, 8000L + i)
        val ev = run(d, s)
        assertTrue(d.flagged)
        assertEquals("cell+hmm", ev.single { it.name == "dev_flag" }.f("signals"))
        assertTrue(ev.any { it.name == "dev_signal" && it.f("sig") == "hmm" && it.f("state") == "on" })
    }

    @Test fun normalUndergroundSequenceKeepsHmmOff() {
        val d = DeviationDetector(model(), T0)
        val ev = run(d, normal(T0))
        assertTrue(ev.none { it.name == "dev_signal" && it.f("sig") == "hmm" && it.f("state") == "on" })
        assertEquals(LINE.size, d.summary().f("nHmm"))
    }

    @Test fun insufficientHistoryIsNotEvaluated() {
        val m = model(n = 4)
        assertFalse(m.ready)
        val d = DeviationDetector(m, T0)
        val s = (0 until 12).flatMap { i -> listOf(ground(T0 + i * 30_000L, 7000L + i), offPath(T0 + i * 30_000L + 5_000, 1_500.0)) }
        assertTrue(run(d, s).isEmpty())
        assertFalse(d.flagged)
        assertEquals(false, d.summary().f("ready"))
        assertEquals(0, d.summary().f("nCell"))
        assertEquals(false, DeviationDetector.modelEvent(m).f("ready"))
    }

    @Test fun flaggedTripsAreExcludedFromLearning() {
        val odd = (0 until 6).map { ground(DAY * 20 + it * 30_000L, 9900L + it) }
        val m = model(n = 5, extra = listOf(trip(odd, flagged = true)))
        assertEquals(5, m.history)
        assertEquals(1, m.excluded)
        assertTrue(m.ready)
        assertFalse(CellKey(false, 9900L) in m.seenCells)
        // 已判偏离的行程不计入历史趟数
        assertFalse(model(n = 4, extra = listOf(trip(odd, flagged = true))).ready)
    }

    @Test fun signalsExpireAfterFreezeGapAndMustSustainAgain() {
        val d = DeviationDetector(model(), T0)
        val s = ArrayList<S>()
        // 两路成立约 1 分钟后进程被冻结 15 分钟
        for (i in 0 until 5) { s += ground(T0 + i * 30_000L, 7000L + i); s += offPath(T0 + i * 30_000L + 5_000, 1_500.0) }
        val resume = T0 + 120_000 + 15 * 60_000L
        // 恢复后先只有基站采样：GPS 已超过保持期，视为未知，不判
        s += ground(resume, 7100L)
        val ev1 = run(d, s)
        assertFalse(d.flagged)
        assertTrue(ev1.any { it.name == "dev_signal" && it.f("sig") == "gps" && it.f("state") == "unknown" })
        // GPS 恢复后两路重新同时成立，须再持续 2 分钟
        val more = (1 until 8).flatMap { i -> listOf(offPath(resume + i * 30_000L - 5_000, 1_500.0), ground(resume + i * 30_000L, 7100L + i)) }
        run(d, more)
        assertTrue(d.flagged)
        assertTrue(d.flagOnset >= resume)
        // 冻结期间的累计时长按保持期截止：冻结前约 2 分钟 + 保持期 + 恢复后约 3 分钟，小于实际经过的时长
        val gpsS = d.summary().f("gpsS") as Long
        assertTrue("gpsS=$gpsS", gpsS <= 120 + cfg.holdMs / 1000 + 4 * 60)
        assertTrue("gpsS=$gpsS", gpsS < (d.lastSampleAt - T0) / 1000 - 5 * 60)
    }

    @Test fun lowAccuracyAndNetworkFixesAreIgnored() {
        val d = DeviationDetector(model(), T0)
        assertTrue(d.onFix(T0, BASE_LAT + 0.05, BASE_LNG, 120f, gps = true).isEmpty())
        assertTrue(d.onFix(T0 + 1, BASE_LAT + 0.05, BASE_LNG, 10f, gps = false).isEmpty())
        assertEquals(0, d.summary().f("nGps"))
    }

    @Test fun eventsCarryNoCoordinatesOrCellValues() {
        val d = DeviationDetector(model(), T0)
        val s = (0 until 12).flatMap { i -> listOf(ground(T0 + i * 30_000L, 7000L + i), offPath(T0 + i * 30_000L + 5_000, 1_500.0)) }
        val keys = (run(d, s) + d.summary() + DeviationDetector.modelEvent(model())).flatMap { e -> e.fields.map { it.first } }.toSet()
        assertTrue(keys.none { it in setOf("lat", "lng", "enb", "tac", "cell", "id") })
        assertTrue(keys.containsAll(listOf("sig", "state", "value", "signals", "onset")))
    }

    // ---------- 行程记录解析 ----------

    private fun ev(t: Long, e: String) = JSONObject().put("k", "ev").put("e", e).put("t", t).toString()
    private fun cellRow(t: Long, tac: Int, enb: Long) = JSONObject().put("k", "cell").put("t", t).put("cells", JSONArray()
        .put(JSONObject().put("type", "nr").put("id", 1L).put("tac", tac))
        .put(JSONObject().put("type", "lte").put("id", lte(enb)).put("tac", 1))).toString()
    private fun locRow(t: Long, p: String, acc: Int, lat: Double = BASE_LAT) =
        JSONObject().put("k", "loc").put("t", t + 500).put("ft", t).put("p", p).put("acc", acc).put("lat", lat).put("lng", BASE_LNG).toString()

    @Test fun traceParsingFollowsTripWindowAndFlags() {
        val lines = listOf(
            ev(1_000, "start"), ev(3_000, "false_start"), ev(4_000, "finish"),
            ev(100_000, "start"), cellRow(110_000, METRO_TAC, LINE[0]), locRow(120_000, "gps", 20), locRow(121_000, "gps", 80),
            locRow(122_000, "network", 10), cellRow(130_000, SURFACE_TAC, GROUND[0]), ev(200_000, "user_end"), ev(201_000, "finish"),
        )
        val tr = DeviationTrace.parse(lines)!!
        assertEquals(100_000L, tr.startAt)
        assertEquals(200_000L, tr.endAt)
        assertEquals(2, tr.cells.size)
        assertEquals(LINE[0], tr.cells[0].enb)
        assertEquals(METRO_TAC, tr.cells[0].nrTac)
        assertEquals(1, tr.gps.size)
        assertFalse(tr.flagged)
        assertTrue(DeviationTrace.parse(lines + ev(150_000, "dev_flag"))!!.flagged)
        assertNull(DeviationTrace.parse(emptyList()))
    }

    @Test fun currentRunResumesAfterLastFinishOnly() {
        val lines = listOf(
            ev(1_000, "start"), cellRow(2_000, SURFACE_TAC, GROUND[0]), ev(5_000, "finish"),
            ev(10_000, "start"), cellRow(11_000, SURFACE_TAC, 7000), locRow(12_000, "gps", 10),
            // 进程被系统重启：没有结束事件，接着写
            ev(20_000, "start"), cellRow(21_000, SURFACE_TAC, 7001), ev(22_000, "dev_flag"),
        )
        val c = DeviationTrace.current(lines)
        assertEquals(10_000L, c.startAt)
        assertEquals(3, c.samples.size)
        assertTrue(c.flagLogged)
        val none = DeviationTrace.current(listOf(ev(1_000, "start"), ev(2_000, "finish")))
        assertNull(none.startAt)
        assertTrue(none.samples.isEmpty())
    }

    @Test fun replayingTheCurrentRunReproducesTheDecision() {
        // 同一组采样一次性处理与「先重放已有记录、再继续」得到相同的判定
        val s = (0 until 12).flatMap { i -> listOf(ground(T0 + i * 30_000L, 7000L + i), offPath(T0 + i * 30_000L + 5_000, 1_500.0)) }
        val whole = DeviationDetector(model(), T0).also { run(it, s) }
        val resumed = DeviationDetector(model(), T0)
        run(resumed, s.take(9))
        run(resumed, s.drop(9).filter { it.t > resumed.lastSampleAt })
        assertNotNull(whole.flagAt.takeIf { it > 0 })
        assertEquals(whole.flagAt, resumed.flagAt)
        assertEquals(whole.flagSignals, resumed.flagSignals)
    }

    companion object {
        const val SURFACE_TAC = 4101
        const val METRO_TAC = 4999
        val LINE = listOf(601L, 602L, 603L, 604L, 605L, 606L)
        val GROUND = listOf(501L, 502L)
        const val BASE_LAT = 0.100
        const val BASE_LNG = 0.100
        const val DAY = 86_400_000L
        const val T0 = 100 * DAY
    }
}
