package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Test

/** 暂时无法读取的原始定位采样恢复后与现有采样合并 */
class LockedSamplesTest {
    private val day = 86_400_000L
    private fun s(t: Long) = Places.Sample(1.0, 2.0, 10f, t)

    @Test fun recoveredSamplesAreMergedInOrderWithoutDuplicates() {
        val now = 100 * day
        val current = listOf(s(now - 2 * day), s(now - day))
        val recovered = listOf(s(now - 5 * day), s(now - 2 * day), s(now - 30 * day))
        val merged = Places.mergeSamples(current, recovered, now)
        // 按时间排序、去重；超过保留期（21 天）的不再保留
        assertEquals(listOf(now - 5 * day, now - 2 * day, now - day), merged.map { it.t })
    }

    @Test fun mergeKeepsTheNewestWithinTheLimit() {
        val now = 100 * day
        val a = (0 until 700).map { s(now - it * 60_000L) }
        val b = (700 until 900).map { s(now - it * 60_000L) }
        val merged = Places.mergeSamples(a, b, now)
        assertEquals(800, merged.size)
        assertEquals(now, merged.last().t)
    }
}
