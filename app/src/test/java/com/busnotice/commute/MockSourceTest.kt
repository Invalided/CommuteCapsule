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

    @Test fun sampleTripUsesThisSourcesLinesAndStops() {
        val t = MockSource.sampleTrip
        assertEquals(MockSource.SAMPLE_TRIP_ID, t.id)
        assertTrue(t.id != DemoPlayer.TRIP_ID)
        assertEquals(2, t.legs.size)

        val bus = t.legs[0].candidates.single()
        assertEquals(Candidate.Type.BUS, bus.type)
        val busLine = MockSource.lineDetail(bus.lineId, "")
        assertEquals(bus.name, busLine.name)
        val board = busLine.stations.single { it.order == bus.stopIndex }
        assertEquals(bus.stopName, board.name)
        assertEquals(bus.stationId, board.stopId)
        val alight = busLine.stations.single { it.name == bus.alightName }
        assertTrue(alight.order > board.order)
        assertEquals(alight.lat, bus.alightLat, 1e-9)

        val metro = t.legs[1].candidates.single()
        assertEquals(Candidate.Type.METRO, metro.type)
        // 换乘站即公交下车站
        assertEquals(bus.alightName, metro.stopName)
        val q = MetroQuery("", metro.name, metro.stopName, metro.lat, metro.lng, metro.alightName, metro.alightLat, metro.alightLng)
        assertEquals(2, MockSource.metroRoute(q)!!.hops)
        assertTrue(MockSource.search(metro.alightName, "").stations.any { it.isSubway })
    }
}
