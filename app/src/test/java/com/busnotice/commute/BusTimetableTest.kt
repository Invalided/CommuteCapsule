package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BusTimetableTest {
    private fun m(h: Int, mi: Int) = h * 60 + mi
    // 两周工作日，晚上 19:12–19:14 一班，18:30–18:34 一班（虚构）
    private val days: Map<LocalDate, List<Int>> = (0 until 14).map { LocalDate.of(2025, 3, 6).plusDays(it.toLong()) }
        .filter { it.dayOfWeek.value <= 5 }
        .mapIndexed { i, d -> d to listOf(m(18, 30 + i % 5), m(19, 12 + i % 3)) }.toMap()

    @Test fun predictsNextClusterAfterNow() {
        val p = BusTimetable.predictFrom(days, LocalDate.of(2025, 3, 21), m(18, 50))!!
        assertEquals(m(19, 13), p.mid)
        assertEquals(m(19, 12), p.lo)
        assertEquals(m(19, 14), p.hi)
    }

    @Test fun weekendUsesOnlyWeekendDays() {
        assertNull(BusTimetable.predictFrom(days, LocalDate.of(2025, 3, 22), m(18, 50)))
    }

    @Test fun irregularGivesNothing() {
        val messy = days.keys.mapIndexed { i, d -> d to listOf(m(19, 0) + (i * 7) % 40) }.toMap()
        assertNull(BusTimetable.predictFrom(messy, LocalDate.of(2025, 3, 21), m(18, 55)))
    }
}
