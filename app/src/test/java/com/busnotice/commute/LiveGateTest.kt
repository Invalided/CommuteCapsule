package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 实时通知节流：保持期内暂存、到期补发、状态变化立即发出 */
class LiveGateTest {
    private val hold = 25_000L
    private fun gate() = LiveGate<String>(hold)
    private fun ph(s: String) = LiveGate.phaseOf(s)

    @Test fun postsImmediatelyWithoutHold() {
        val g = gate()
        val d = g.offer("a", ph("约5分"), now = 1_000L)
        assertEquals(LiveGate.Action.POST, d.action)
        assertTrue(d.posted)
        assertEquals(0L, d.flushAt)
        assertNull(g.due(1_000L))
    }

    @Test fun holdsSamePhaseAndFlushesLatestAtExpiry() {
        val g = gate()
        g.expanded(now = 0L, phase = ph("快到了"))
        val d1 = g.offer("first", ph("快到了"), now = 1_000L)
        val d2 = g.offer("second", ph("快到了"), now = 5_000L)
        assertEquals(LiveGate.Action.HOLD, d1.action)
        assertEquals(LiveGate.Action.HOLD, d2.action)
        assertFalse(d2.posted)
        assertEquals(hold, d2.flushAt)
        // 未到期不补发
        assertNull(g.due(hold - 1))
        // 到期补发最新一条，且只补发一次
        assertEquals("second", g.due(hold))
        assertNull(g.due(hold + 1))
    }

    @Test fun countdownChangeIsSamePhase() {
        assertEquals(ph("约5分"), ph("约4分"))
        assertEquals(ph("3站"), ph("12站"))
        assertEquals(ph("待15分"), ph("待9分"))
        assertTrue(ph("约5分") != ph("快到了"))
        assertTrue(ph("快到了") != ph("下车"))
        assertTrue(ph("即将到站") != ph("下车"))
    }

    @Test fun phaseChangeBypassesHoldAndStillFlushes() {
        val g = gate()
        g.expanded(now = 0L, phase = ph("约5分"))
        val d = g.offer("alight", ph("下车"), now = 3_000L)
        assertEquals(LiveGate.Action.POST_EXPANDED, d.action)
        assertTrue(d.posted)
        assertEquals(hold, d.flushAt)
        // 同一阶段的后续刷新仍按保持期处理
        assertEquals(LiveGate.Action.HOLD, g.offer("alight2", ph("下车"), now = 4_000L).action)
        // 到期后以常规样式补发最新内容
        assertEquals("alight2", g.due(hold))
    }

    @Test fun expansionDiscardsOlderPending() {
        val g = gate()
        g.expanded(now = 0L, phase = ph("约5分"))
        g.offer("stale", ph("约5分"), now = 1_000L)
        // 第二次展开发生在保持期内：之前暂存的内容已被展开内容取代
        g.expanded(now = 10_000L, phase = ph("快到了"))
        assertNull(g.due(10_000L + hold))
    }

    @Test fun expiryReturnsToNormalPosting() {
        val g = gate()
        g.expanded(now = 0L, phase = ph("快到了"))
        g.offer("held", ph("快到了"), now = 1_000L)
        val d = g.offer("after", ph("快到了"), now = hold + 1)
        assertEquals(LiveGate.Action.POST, d.action)
        // 已按常规发出，不再补发保持期内的旧内容
        assertNull(g.due(hold + 2))
    }

    @Test fun hiddenIsNotPostedNorQueued() {
        val g = gate()
        val d = g.offer("x", ph("约5分"), now = 0L, visible = false)
        assertEquals(LiveGate.Action.HIDDEN, d.action)
        assertFalse(d.posted)
        assertNull(g.due(0L))
        g.expanded(now = 0L, phase = ph("快到了"))
        g.offer("y", ph("快到了"), now = 1_000L)
        g.offer("z", ph("快到了"), now = 2_000L, visible = false)
        assertNull(g.due(hold))
    }

    @Test fun resetClearsHoldAndPending() {
        val g = gate()
        g.expanded(now = 0L, phase = ph("快到了"))
        g.offer("x", ph("快到了"), now = 1_000L)
        g.reset()
        assertFalse(g.holding(1_000L))
        assertNull(g.due(hold))
        assertEquals(LiveGate.Action.POST, g.offer("y", ph("快到了"), now = 2_000L).action)
    }
}
