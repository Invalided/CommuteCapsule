package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetroCellsTest {
    private val UNDER = 1001   // 地下专网区号（占位）
    private val UP = 2002      // 地面区号（占位）

    private fun snap(t: Long, tac: Int?, lte: Int? = null) = CellSnap(t, tac, null, null, lte)

    private fun ride(l: MetroTacLearner, tac: Int, n: Int = 40, step: Long = 5_000L) {
        repeat(n) { l.sampleRide(snap(it * step, tac)) }
        l.endRide()
    }

    @Test fun learnsAfterTwoRides() {
        val l = MetroTacLearner()
        ride(l, UNDER); assertFalse(l.isMetroTac(UNDER))
        ride(l, UNDER); assertTrue(l.isMetroTac(UNDER))
    }

    @Test fun shortRideNotCounted() {
        val l = MetroTacLearner()
        ride(l, UNDER, n = 10); ride(l, UNDER, n = 10)
        assertFalse(l.isMetroTac(UNDER))
    }

    @Test fun mixedRideNotCounted() {
        val l = MetroTacLearner()
        repeat(20) { l.sampleRide(snap(it * 5_000L, UNDER)) }
        repeat(20) { l.sampleRide(snap(it * 5_000L, UP)) }
        assertNull(l.endRide())
    }

    private val gpsFast = SurfaceFix("gps", 8f, 10f)

    /** 一趟公交：每 5 秒一次地面采样，共 n 次 */
    private fun surfaceTrip(l: MetroTacLearner, tac: Int, fix: SurfaceFix = gpsFast, n: Int = 20): List<MetroTacLearner.SurfaceHit> {
        repeat(n) { l.sampleSurface(snap(it * 5_000L, tac), fix) }
        return l.endSurface()
    }

    @Test fun seenOnSurfaceIsVetoed() {
        val l = MetroTacLearner()
        ride(l, UNDER); ride(l, UNDER)
        surfaceTrip(l, UNDER); surfaceTrip(l, UNDER)
        assertFalse(l.isMetroTac(UNDER))
    }

    @Test fun networkSpeedNotCountedAsSurface() {
        val l = MetroTacLearner()
        ride(l, UNDER); ride(l, UNDER)
        repeat(3) { assertTrue(surfaceTrip(l, UNDER, SurfaceFix("network", 30f, 25f)).isEmpty()) }
        assertEquals(0, l.surfaceTrips[UNDER] ?: 0)
        assertTrue(l.isMetroTac(UNDER))
    }

    @Test fun slowOrCoarseOrNearStationGpsNotCounted() {
        val l = MetroTacLearner()
        assertTrue(surfaceTrip(l, UP, SurfaceFix("gps", 3f, 10f)).isEmpty())
        assertTrue(surfaceTrip(l, UP, SurfaceFix("gps", null, 10f)).isEmpty())
        assertTrue(surfaceTrip(l, UP, SurfaceFix("gps", 8f, 80f)).isEmpty())
        assertTrue(surfaceTrip(l, UP, SurfaceFix("gps", 8f, 10f, stationM = 120.0)).isEmpty())
        assertEquals(1, surfaceTrip(l, UP, SurfaceFix("gps", 8f, 10f, stationM = 900.0)).size)
    }

    @Test fun briefSurfaceSightingNotCounted() {
        val l = MetroTacLearner()
        assertTrue(surfaceTrip(l, UP, n = 2).isEmpty())                  // 次数不足
        repeat(5) { l.sampleSurface(snap(it * 10_000L, UP), gpsFast) }    // 5 次但只跨 40 秒
        assertTrue(l.endSurface().isEmpty())
        val hit = surfaceTrip(l, UP, n = 13).single()                     // 13 次、跨 60 秒
        assertEquals(UP, hit.tac); assertEquals(13, hit.samples); assertEquals(60L, hit.spanSec); assertEquals("gps", hit.provider)
    }

    @Test fun surfaceCountedOncePerTrip() {
        val l = MetroTacLearner()
        repeat(4) { i -> repeat(5) { l.sampleSurface(snap(i * 100_000L + it * 5_000L, UP), gpsFast) } }
        assertEquals(1, l.endSurface().size)
        assertEquals(1, l.surfaceTrips[UP])
    }

    @Test fun establishedTacSurvivesOccasionalSurface() {
        val l = MetroTacLearner()
        repeat(6) { ride(l, UNDER) }
        repeat(3) { surfaceTrip(l, UNDER) }
        assertTrue(l.isMetroTac(UNDER))                                   // 地面 3 趟 ≤ 地下 6 趟
        repeat(4) { surfaceTrip(l, UNDER) }
        assertFalse(l.isMetroTac(UNDER))                                  // 地面 7 趟 > 地下 6 趟
    }

    @Test fun sparseRideWithEnoughSpanCounts() {
        val l = MetroTacLearner()
        ride(l, UNDER, n = 9, step = 60_000L)                             // 9 次采样、跨 8 分钟
        ride(l, UNDER, n = 25, step = 30_000L)
        assertTrue(l.isMetroTac(UNDER))
        assertEquals(25, l.lastRideSamples); assertEquals(720L, l.lastRideSpanSec)
    }

    @Test fun sparseRideTooShortNotCounted() {
        val l = MetroTacLearner()
        ride(l, UNDER, n = 7, step = 60_000L)                             // 采样不足 8 次
        ride(l, UNDER, n = 20, step = 7_000L)                             // 跨度 133 秒，不足 150 秒
        assertEquals(0, l.rideTrips[UNDER] ?: 0)
    }

    @Test fun legacyJsonUpgradeRestoresLearned() {
        val legacy = org.json.JSONObject()
            .put("ride", org.json.JSONObject().put(UNDER.toString(), 14).put(UP.toString(), 1))
            .put("surface", org.json.JSONObject().put(UNDER.toString(), 3).put(UP.toString(), 9))
        val l = MetroTacLearner.fromJson(legacy)
        assertTrue(l.migrated)
        assertTrue(l.isMetroTac(UNDER))
        assertEquals(14, l.rideTrips[UNDER]); assertTrue(l.surfaceTrips.isEmpty())
        val again = MetroTacLearner.fromJson(l.toJson())
        assertFalse(again.migrated)
        assertTrue(again.isMetroTac(UNDER))
    }

    @Test fun currentJsonKeepsSurfaceCounts() {
        val l = MetroTacLearner(); ride(l, UNDER); ride(l, UNDER); surfaceTrip(l, UP)
        val back = MetroTacLearner.fromJson(l.toJson())
        assertFalse(back.migrated)
        assertEquals(1, back.surfaceTrips[UP])
    }

    @Test fun jsonRoundTrip() {
        val l = MetroTacLearner(); ride(l, UNDER); ride(l, UNDER)
        assertTrue(MetroTacLearner.fromJson(l.toJson()).isMetroTac(UNDER))
    }

    @Test fun gateEnterAndExit() {
        val g = MetroGate { it == UNDER }
        assertNull(g.feed(snap(0, UP)))
        assertNull(g.feed(snap(5_000, UNDER)))
        assertEquals(MetroGate.Edge.ENTER, g.feed(snap(10_000, UNDER))?.first)
        assertNull(g.feed(snap(15_000, null)))       // 掉到 4G 不算离开
        assertNull(g.feed(snap(20_000, UP)))
        assertNull(g.feed(snap(25_000, UP)))
        val exit = g.feed(snap(30_000, UP))
        assertEquals(MetroGate.Edge.EXIT, exit?.first)
        assertEquals(20_000L, exit?.second)          // 出站时刻 = 第一次离开
    }

    @Test fun gateBlipIgnored() {
        val g = MetroGate { it == UNDER }
        g.feed(snap(0, UNDER)); g.feed(snap(5_000, UNDER))
        assertNull(g.feed(snap(10_000, UP)))
        assertNull(g.feed(snap(15_000, UNDER)))      // 抖一下又回来
        assertNull(g.feed(snap(20_000, UP)))
        assertNull(g.feed(snap(25_000, UP)))
    }

    @Test fun enbFromCellId() = assertEquals(12345, CellSnap(0, null, null, null, 12345 * 256 + 150).enb)

    @Test fun fpCollapsesSectors() {
        val r = MetroFpRecorder()
        listOf(10 * 256 + 1, 10 * 256 + 2, 11 * 256 + 1, 12 * 256 + 1).forEachIndexed { i, id -> r.sample(CellSnap(i * 5_000L, null, null, null, id)) }
        assertEquals(3, r.finish()?.length())
    }
}
