package com.busnotice.commute

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 选择器搜索来源、直接记下与站名比对。站名、线路名均为虚构 */
class StopSearchTest {
    private class FakeSource(
        override val id: String, private val ok: Boolean = true, private val fail: Boolean = false,
        private val result: SearchResult = SearchResult(emptyList(), emptyList()),
    ) : StopSearchSource {
        override val title = id
        override fun available() = ok
        override fun search(key: String): SearchResult = if (fail) error("unavailable") else result
    }

    private fun st(id: String, name: String, subway: Boolean = true) =
        SearchStation(id, name, 1.0, 2.0, subway, "", "")

    @After fun cleanup() { listOf("a", "b", "c").forEach { StopSearch.unregister(it) } }

    @Test fun noUsableSourceGivesEmptyResult() {
        // 测试环境没有初始化数据源：默认来源不可用
        assertFalse(StopSearch.available())
        val r = StopSearch.search("演示")
        assertTrue(r!!.lines.isEmpty() && r.stations.isEmpty())
    }

    @Test fun registeredSourcesAreMergedInOrder() {
        StopSearch.register(FakeSource("a", result = SearchResult(
            listOf(SearchLine("8", false, emptyList())), listOf(st("1", "演示站甲")))))
        StopSearch.register(FakeSource("b", result = SearchResult(
            listOf(SearchLine("8", false, emptyList()), SearchLine("9", false, emptyList())),
            listOf(st("1", "演示站甲"), st("2", "演示站乙")))))
        StopSearch.register(FakeSource("c", ok = false, result = SearchResult(emptyList(), listOf(st("3", "演示站丙")))))
        assertTrue(StopSearch.available())
        val r = StopSearch.search("演示")!!
        assertEquals(listOf("8", "9"), r.lines.map { it.name })
        assertEquals(listOf("演示站甲", "演示站乙"), r.stations.map { it.name })
    }

    @Test fun allSourcesFailingGivesNull() {
        StopSearch.register(FakeSource("a", fail = true))
        assertNull(StopSearch.search("演示"))
        StopSearch.register(FakeSource("b", result = SearchResult(emptyList(), listOf(st("1", "演示站甲")))))
        assertEquals(1, StopSearch.search("演示")!!.stations.size)
    }

    @Test fun registerReplacesSameId() {
        StopSearch.register(FakeSource("a", result = SearchResult(emptyList(), listOf(st("1", "演示站甲")))))
        StopSearch.register(FakeSource("a", result = SearchResult(emptyList(), listOf(st("2", "演示站乙")))))
        assertEquals(listOf("演示站乙"), StopSearch.search("演示")!!.stations.map { it.name })
    }

    @Test fun manualLineNames() {
        assertEquals("105", ManualEntry.lineName(" 105路 "))
        assertEquals("2号线", ManualEntry.lineName("2号线"))
        assertEquals("路", ManualEntry.lineName("路"))
        assertTrue(ManualEntry.looksLikeLine("105"))
        assertTrue(ManualEntry.looksLikeLine("2号线"))
        assertFalse(ManualEntry.looksLikeLine("演示站甲"))
    }

    @Test fun manualBusAndMetroCandidates() {
        val b = ManualEntry.bus("105路", " 演示站甲 ", "演示站乙")
        assertEquals(Candidate.Type.BUS, b.type)
        assertEquals("105", b.name)
        assertEquals("105路", b.label())
        assertEquals("演示站甲", b.stopName)
        assertEquals(Candidate.Origin.MANUAL, b.origin)
        assertFalse(b.hasCoord())
        // 在公交里记下的地铁线按地铁处理
        assertEquals(Candidate.Type.METRO, ManualEntry.bus("2号线", "演示站甲").type)
        val m = ManualEntry.metro("", "演示站丁", "", 4, 6)
        assertEquals("地铁", m.name)
        assertEquals(4, m.peakHw())
        assertEquals(6, m.offPeakHw())
        assertFalse(m.hasCoord())
    }

    @Test fun stationMatch() {
        val names = listOf("演示甲", "演示乙北", "示例丙")
        assertEquals("演示甲", StationMatch.check("演示甲站", names).exact)
        assertEquals("演示乙北", StationMatch.check("演示乙北", names).exact)
        val c = StationMatch.check("演示乙", names)
        assertNull(c.exact)
        // 包含关系的排在前面，只是前缀相同的排在后面
        assertEquals(listOf("演示乙北", "演示甲"), c.suggestions)
        assertTrue(StationMatch.check("其他", names).suggestions.isEmpty())
        assertTrue(StationMatch.check("  ", names).let { it.exact == null && it.suggestions.isEmpty() })
    }
}
