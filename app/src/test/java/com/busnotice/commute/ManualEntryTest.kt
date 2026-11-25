package com.busnotice.commute

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 直接记下的线路（没有坐标）与有坐标的线路：序列化、距离、推算文案和回程。数值均为虚构 */
class ManualEntryTest {
    private val sourced = Candidate(Candidate.Type.BUS, "15", "演示站甲", 30.0, 100.0, lineId = "L1", stationId = "S1", stopIndex = 2)
    private val manualBus = Candidate(Candidate.Type.BUS, "105", "演示站乙", Double.NaN, Double.NaN,
        alightName = "演示站丙", origin = Candidate.Origin.MANUAL)
    private val manualMetro = Candidate(Candidate.Type.METRO, "9号线", "演示站丁", Double.NaN, Double.NaN,
        alightName = "演示站戊", headwayMin = 5, origin = Candidate.Origin.MANUAL)

    @Test fun manualCandidateSerializesWithoutCoordinates() {
        val o = manualMetro.toJson()
        assertFalse(o.has("lat"))
        assertFalse(o.has("lng"))
        assertEquals("MANUAL", o.getString("origin"))
        val back = Candidate.fromJson(JSONObject(o.toString()))
        assertTrue(back.lat.isNaN() && back.lng.isNaN())
        assertEquals(Candidate.Origin.MANUAL, back.origin)
        assertEquals(manualMetro, back)
    }

    @Test fun sourcedCandidateJsonKeepsPreviousShape() {
        val o = sourced.toJson()
        assertEquals(30.0, o.getDouble("lat"), 0.0)
        assertEquals(100.0, o.getDouble("lng"), 0.0)
        assertFalse(o.has("origin"))
        assertEquals(
            setOf("type", "name", "stopName", "lat", "lng", "regionId", "lineId", "stationId", "lastStop", "stopIndex",
                "alightName", "departures", "headwayMin", "offPeakHeadwayMin", "metroDir"),
            o.keys().asSequence().toSet(),
        )
        val back = Candidate.fromJson(JSONObject(o.toString()))
        assertEquals(sourced, back)
        assertEquals(Candidate.Origin.SOURCE, back.origin)
    }

    @Test fun legacyJsonWithoutOriginReadsAsSource() {
        val legacy = JSONObject("""{"type":"METRO","name":"1号线","stopName":"演示站甲","lat":30.0,"lng":100.0,"headwayMin":6}""")
        val c = Candidate.fromJson(legacy)
        assertEquals(Candidate.Origin.SOURCE, c.origin)
        assertTrue(c.hasCoord())
    }

    @Test fun tripWithManualLegRoundTrips() {
        val t = Trip(id = "t1", legs = listOf(Leg(listOf(manualBus)), Leg(listOf(manualMetro), rideMin = 20)))
        val back = Trip.fromJson(JSONObject(t.toJson().toString()))
        assertEquals(t, back)
        assertTrue(back.legs.all { it.needsStopLearning() })
        assertFalse(Leg(listOf(sourced)).needsStopLearning())
    }

    @Test fun distanceIsNullWithoutCoordinates() {
        assertNull(manualBus.distanceFrom(30.0, 100.0))
        val d = sourced.distanceFrom(30.001, 100.0)
        assertNotNull(d)
        assertEquals(Geo.distanceM(30.001, 100.0, 30.0, 100.0), d!!, 0.0)
    }

    @Test fun manualMetroAdviceExplainsTimeEstimate() {
        val a = Decision.decide(listOf(Decision.Option(manualMetro, 0, emptyList(), located = false)))
        assertEquals(Decision.Mode.METRO, a.mode)
        assertTrue(a.detail, a.detail.contains(Decision.LEARNING_TIP))
        assertFalse(a.detail.contains("定位中"))
    }

    @Test fun locatedMetroAdviceUnchanged() {
        val metro = manualMetro.copy(lat = 30.0, lng = 100.0, origin = Candidate.Origin.SOURCE)
        val a = Decision.decide(listOf(Decision.Option(metro, 0, emptyList(), located = false)))
        assertEquals("定位中", a.headline)
        assertTrue(a.detail.startsWith("演示站丁（定位中）"))
    }

    @Test fun manualBusShowsLearningInsteadOfNoBus() {
        val a = Decision.decide(listOf(Decision.Option(manualBus, 0, emptyList(), Decision.LEARNING_TIP)))
        assertEquals(Decision.Mode.NO_BUS, a.mode)
        assertEquals("学习中", a.headline)
        assertEquals(Decision.LEARNING_TIP, a.detail)
        // 有线路编号、只是暂无来车的公交：文案不变
        val b = Decision.decide(listOf(Decision.Option(sourced, 0, emptyList())))
        assertEquals("无车", b.headline)
        assertEquals("暂无车辆信息", b.detail)
    }

    @Test fun metroRouteTargetSkipsNextStopWithoutCoordinates() {
        val first = manualMetro.copy(lat = 30.0, lng = 100.0, alightName = "", origin = Candidate.Origin.SOURCE)
        val withManualNext = Trip(id = "t2", legs = listOf(Leg(listOf(first)), Leg(listOf(manualBus))))
        assertNull(MetroRoutes.target(withManualNext, 0))
        val withSourcedNext = Trip(id = "t3", legs = listOf(Leg(listOf(first)), Leg(listOf(sourced))))
        assertEquals(Triple("演示站甲", 30.0, 100.0), MetroRoutes.target(withSourcedNext, 0))
    }

    @Test fun reverseSwapsManualStops() {
        val src = Trip(id = "t4", name = "上班", legs = listOf(Leg(listOf(manualBus)), Leg(listOf(manualMetro), transferWalkMin = 4)))
        val r = Reverse.build(src)
        assertEquals("下班", r.trip.name)
        assertEquals(2, r.trip.legs.size)
        val m = r.trip.legs[0].candidates.single()
        assertEquals("演示站戊", m.stopName)
        assertEquals("演示站丁", m.alightName)
        assertEquals(Candidate.Origin.MANUAL, m.origin)
        assertFalse(m.hasCoord())
        val b = r.trip.legs[1].candidates.single()
        assertEquals("演示站丙", b.stopName)
        assertEquals("演示站乙", b.alightName)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun reverseManualWithoutAlightWarns() {
        val r = Reverse.build(Trip(id = "t5", legs = listOf(Leg(listOf(manualBus.copy(alightName = ""))))))
        assertTrue(r.trip.legs.isEmpty())
        assertEquals(1, r.warnings.size)
    }

    @Test fun boardPointRequiresRecentAccurateFix() {
        val at = 1_000_000L
        val p = BoardPoint.pick(at, 1.2345678, 2.3456789, 35f, at - 20_000L)
        assertNotNull(p)
        assertEquals(1.234568, p!!.lat, 1e-9)
        assertEquals(2.345679, p.lng, 1e-9)
        assertEquals(35, p.accM)
        assertNull(BoardPoint.pick(at, 30.0, 100.0, 350f, at))
        assertNull(BoardPoint.pick(at, 30.0, 100.0, 20f, at - 5 * 60_000L))
        assertNull(BoardPoint.pick(at, Double.NaN, 100.0, 20f, at))
    }
}
