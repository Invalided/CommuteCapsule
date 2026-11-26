package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

/** 家 / 公司的采样结果快照：采样足够时以采样为准并刷新，不足时兜底，手动设置始终优先（坐标为虚构值） */
class PlaceSnapshotTest {
    private val zone = ZoneOffset.UTC
    private val home = 1.0 to 2.0
    private val work = 1.01 to 2.0          // 约 1.1 公里外
    private val start = LocalDate.of(2030, 1, 7)

    private fun at(d: LocalDate, h: Int) = d.atTime(h, 0).toInstant(zone).toEpochMilli()

    /** [days] 天的夜间家中采样，以及其中工作日下午的公司采样 */
    private fun samples(days: Int, homeAt: Pair<Double, Double> = home): List<Places.Sample> =
        (0 until days).flatMap { i ->
            val d = start.plusDays(i.toLong())
            val out = mutableListOf(
                Places.Sample(homeAt.first, homeAt.second, 20f, at(d, 22)),
                Places.Sample(homeAt.first, homeAt.second, 20f, at(d, 23)))
            if (d.dayOfWeek <= DayOfWeek.FRIDAY) {
                out += Places.Sample(work.first, work.second, 20f, at(d, 11))
                out += Places.Sample(work.first, work.second, 20f, at(d, 15))
            }
            out
        }

    private fun near(a: Pair<Double, Double>?, b: Pair<Double, Double>) =
        a != null && Geo.distanceM(a.first, a.second, b.first, b.second) < 5.0

    @Test fun enoughSamplesWinAndRefreshSnapshot() {
        val d = Places.deriveFrom(samples(5), zone)
        assertNotNull(d.spots[Places.Kind.HOME]); assertNotNull(d.spots[Places.Kind.WORK])
        val snap = Places.refreshSnapshot(emptyMap(), d.spots, 100L)!!
        assertTrue(near(snap.getValue(Places.Kind.HOME).let { it.lat to it.lng }, home))
        assertEquals(10, snap.getValue(Places.Kind.HOME).samples)
        assertEquals(100L, snap.getValue(Places.Kind.WORK).updatedAt)
        val sampled = d.spots[Places.Kind.HOME]?.let { it.lat to it.lng }
        val old = 5.0 to 6.0
        assertTrue(near(Places.choose(null, sampled, old, null), home))
    }

    @Test fun insufficientSamplesFallBackToSnapshot() {
        // 恢复到新手机后只有 1 天采样，不足以独立算出
        val d = Places.deriveFrom(samples(1), zone)
        assertNull(d.spots[Places.Kind.HOME])
        val snap = Places.decodeSnapshot(Places.encodeSnapshot(mapOf(Places.Kind.HOME to Places.Snap(home.first, home.second, 150.0, 12, 5L))))
        val voted = 3.0 to 4.0
        val r = Places.choose(null, d.spots[Places.Kind.HOME]?.let { it.lat to it.lng }, snap[Places.Kind.HOME]?.let { it.lat to it.lng }, voted)
        assertTrue(near(r, home))
        // 采样不足时快照不被改写
        assertNull(Places.refreshSnapshot(snap, d.spots, 6L))
    }

    @Test fun noSnapshotUsesVotes() {
        val d = Places.deriveFrom(samples(1), zone)
        val voted = 3.0 to 4.0
        assertEquals(voted, Places.choose(null, d.spots[Places.Kind.WORK]?.let { it.lat to it.lng },
            Places.decodeSnapshot(null)[Places.Kind.WORK]?.let { it.lat to it.lng }, voted))
        assertNull(Places.choose(null, null, null, null))
    }

    @Test fun manualOverridesEverything() {
        val manual = 7.0 to 8.0
        assertEquals(manual, Places.choose(manual, home, work, 3.0 to 4.0))
    }

    @Test fun snapshotRefreshReplacesOnlyRecomputedKind() {
        val old = mapOf(
            Places.Kind.HOME to Places.Snap(5.0, 6.0, 150.0, 8, 1L),
            Places.Kind.WORK to Places.Snap(work.first, work.second, 150.0, 8, 1L))
        // 搬家后的新采样只算出了家
        val moved = 1.1 to 2.1
        val d = Places.deriveFrom(samples(4, moved).filter { java.time.Instant.ofEpochMilli(it.t).atZone(zone).hour >= 21 }, zone)
        assertNull(d.spots[Places.Kind.WORK])
        val snap = Places.refreshSnapshot(old, d.spots, 9L)!!
        assertTrue(near(snap.getValue(Places.Kind.HOME).let { it.lat to it.lng }, moved))
        assertEquals(9L, snap.getValue(Places.Kind.HOME).updatedAt)
        assertEquals(old[Places.Kind.WORK], snap[Places.Kind.WORK])
        // 同样的采样再算一次：没有变化，不重写
        assertNull(Places.refreshSnapshot(snap, Places.deriveFrom(samples(4, moved).filter { java.time.Instant.ofEpochMilli(it.t).atZone(zone).hour >= 21 }, zone).spots, 10L))
    }

    @Test fun snapshotHoldsOnlyPointsAndMetadata() {
        val raw = Places.encodeSnapshot(mapOf(Places.Kind.HOME to Places.Snap(1.0, 2.0, 150.0, 3, 4L)))
        val o = org.json.JSONObject(raw).getJSONObject("HOME")
        assertEquals(setOf("lat", "lng", "r", "n", "t"), o.keys().asSequence().toSet())
        assertTrue(Places.decodeSnapshot("not json").isEmpty())
        assertTrue(Places.decodeSnapshot("""{"HOME":{"lat":"x"}}""").isEmpty())
    }
}
