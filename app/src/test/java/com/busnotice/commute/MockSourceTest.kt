package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockSourceTest {
    @Test fun searchFindsBusAndMetro() {
        val r = MockSource.search("1", "")
        assertTrue(r.lines.any { it.name == "1" && !it.isSubway && it.dirs.size == 2 })
        assertTrue(r.lines.any { it.isSubway })
    }

    @Test fun lineDetailHasReverse() {
        val d = MockSource.lineDetail("m2", "")
        assertEquals("m2r", d.reverseLineId)
        assertEquals(1, d.stations.first().order)
        assertEquals(d.stations.last().name, MockSource.lineDetail("m2r", "").stations.first().name)
    }

    @Test fun metroRouteCountsHops() {
        val d = MockSource.lineDetail("mt1", "").stations
        val q = MetroQuery("", "1号线", d[0].name, d[0].lat, d[0].lng, d[3].name, d[3].lat, d[3].lng)
        assertEquals(3, MockSource.metroRoute(q)!!.hops)
    }
}
