package com.busnotice.commute

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 线路库：粘贴清洗、反向生成、时刻推算、线路文件与导入合并。站名与时刻均为虚构 */
class RouteBookTest {
    private val base = RouteDef(name = "示例线", direction = "去程", stops = listOf("起点站", "甲站", "乙站", "终点站"))

    @Test fun pasteDropsBlankLinesAndLeadingNumbers() {
        val p = RouteText.parse("1. 起点站\n② 甲站\n\n3、乙站\n（4）丙站\n五、丁站\n终点站\n")
        assertEquals(listOf("起点站", "甲站", "乙站", "丙站", "丁站", "终点站"), p.stops)
        assertEquals(1, p.droppedBlank)
        assertEquals(5, p.strippedNumbers)
    }

    @Test fun pasteSplitsArrowSeparatedStops() {
        assertEquals(listOf("甲站", "乙站", "丙站"), RouteText.parse("甲站 → 乙站 - 丙站").stops)
    }

    @Test fun pasteKeepsNumbersThatArePartOfTheName() {
        assertEquals(listOf("1号门", "园区2期"), RouteText.parse("1号门\n园区2期").stops)
    }

    @Test fun reverseFlipsStopsAndDirectionAndDropsFixedDepartures() {
        val r = base.copy(schedule = RouteSchedule(firstMin = 360, peakHeadwayMin = 8, departures = listOf(430, 460)))
        val back = r.reversed()
        assertEquals(listOf("终点站", "乙站", "甲站", "起点站"), back.stops)
        assertEquals("示例线 · 返程", back.fullName())
        assertEquals(8, back.schedule.peakHeadwayMin)
        assertEquals(360, back.schedule.firstMin)
        assertTrue(back.schedule.departures.isEmpty())
        assertFalse(back.id == r.id)
    }

    @Test fun endsOnlyRouteBoardsAtTerminalsOnly() {
        val r = base.copy(stopPattern = RouteDef.StopPattern.ENDS)
        assertEquals(listOf("起点站", "终点站"), r.boardingStops())
    }

    @Test fun userHeadwayOverridesDefault() {
        assertEquals(10, RouteTiming.peak(base))
        assertEquals(15, RouteTiming.offPeak(base))
        val r = base.copy(schedule = RouteSchedule(peakHeadwayMin = 4))
        assertEquals(4, RouteTiming.peak(r))
        assertEquals(6, RouteTiming.offPeak(r))
        assertEquals(6, RouteTiming.peak(base.copy(mode = Candidate.Type.METRO)))
        assertEquals(0, RouteTiming.peak(base.copy(mode = Candidate.Type.SHUTTLE)))
    }

    @Test fun fixedDeparturesTakePrecedence() {
        val r = base.copy(mode = Candidate.Type.SHUTTLE, schedule = RouteSchedule(departures = listOf(430, 460, 490)))
        assertEquals(listOf(10 * 60, 40 * 60), RouteTiming.next(r, 420 * 60, true, 2))
    }

    @Test fun headwayScheduleRespectsFirstAndLast() {
        val r = base.copy(schedule = RouteSchedule(firstMin = 600, lastMin = 620, peakHeadwayMin = 10, offPeakHeadwayMin = 10))
        assertEquals(listOf(600 * 60 - 500 * 60, 610 * 60 - 500 * 60), RouteTiming.next(r, 500 * 60, true, 2))
        assertTrue(RouteTiming.next(r, 630 * 60, true).isEmpty())
    }

    @Test fun shuttleWithoutScheduleHasNoEstimate() {
        assertTrue(RouteTiming.next(base.copy(mode = Candidate.Type.SHUTTLE), 420 * 60, true).isEmpty())
    }

    @Test fun candidateCarriesOptionalSchedule() {
        val r = base.copy(schedule = RouteSchedule(peakHeadwayMin = 8, offPeakHeadwayMin = 12, departures = listOf(430)))
        val c = r.toCandidate("甲站", "终点站")
        assertEquals(Candidate.Origin.MANUAL, c.origin)
        assertEquals(8, c.headwayMin); assertEquals(12, c.offPeakHeadwayMin); assertEquals(listOf(430), c.departures)
        assertTrue(c.lat.isNaN())
        val plain = base.toCandidate("甲站", "终点站")
        assertEquals(0, plain.headwayMin)
    }

    @Test fun jsonRoundTrip() {
        val r = base.copy(mode = Candidate.Type.METRO, stopPattern = RouteDef.StopPattern.ENDS,
            schedule = RouteSchedule(330, 1380, 5, 8, listOf(400)))
        assertEquals(r, RouteDef.fromJson(JSONObject(r.toJson().toString())))
    }

    @Test fun fileRoundTrip() {
        val a = base.copy(schedule = RouteSchedule(firstMin = 360, lastMin = 1350, peakHeadwayMin = 8, offPeakHeadwayMin = 12))
        val b = RouteDef(name = "班车", direction = "去程", mode = Candidate.Type.SHUTTLE,
            stopPattern = RouteDef.StopPattern.ENDS, stops = listOf("起点站", "终点站"),
            schedule = RouteSchedule(departures = listOf(430, 460)))
        val text = RouteFile.write(listOf(a, b))
        assertTrue(text.startsWith(RouteFile.HEADER))
        val back = RouteFile.read(text)
        assertEquals(listOf(a.copy(id = ""), b.copy(id = "")), back.map { it.copy(id = "") })
    }

    @Test fun fileErrorsReportLineNumber() {
        val text = "${RouteFile.HEADER}\n[线路] 示例线\n方式 = 轮渡\n站点:\n甲\n乙\n"
        try { RouteFile.read(text); fail() } catch (e: RouteFile.FormatError) { assertEquals(3, e.line) }
        try { RouteFile.read("${RouteFile.HEADER}\n[线路] 示例线\n站点:\n甲\n"); fail() } catch (e: RouteFile.FormatError) { assertEquals(2, e.line) }
        try { RouteFile.read("随便一段文字"); fail() } catch (e: RouteFile.FormatError) { assertEquals(1, e.line) }
    }

    @Test fun mergeReplaceKeepsIdAndKeepBothRenames() {
        val existing = listOf(base)
        val incoming = listOf(base.copy(id = "x", stops = listOf("起点站", "终点站")), RouteDef(name = "新线", stops = listOf("甲", "乙")))
        assertEquals(RouteMerge.Status.SAME_NAME, RouteMerge.status(existing, incoming[0]))
        assertEquals(RouteMerge.Status.NEW, RouteMerge.status(existing, incoming[1]))
        val replaced = RouteMerge.merge(existing, incoming, RouteMerge.Conflict.REPLACE)
        assertEquals(2, replaced.size)
        assertEquals(base.id, replaced[0].id)
        assertEquals(listOf("起点站", "终点站"), replaced[0].stops)
        val both = RouteMerge.merge(existing, incoming, RouteMerge.Conflict.KEEP_BOTH)
        assertEquals(3, both.size)
        assertEquals("示例线（2） · 去程", both[1].fullName())
    }

    @Test fun parseTimesValidates() {
        assertEquals(listOf(425, 460), RouteText.parseTimes("7:40, 07:05"))
        assertEquals(null, RouteText.parseTimes("7:05 25:00"))
    }

    @Test fun shuttleHeadwayWithoutDeparturesProducesSchedule() {
        val r = base.copy(mode = Candidate.Type.SHUTTLE, schedule = RouteSchedule(peakHeadwayMin = 20, offPeakHeadwayMin = 20))
        val next = RouteTiming.next(r, nowSec = 7 * 3600, weekday = true, n = 2)
        assertEquals(2, next.size)
        assertEquals(20 * 60, next[1] - next[0])
    }

    @Test fun busFixedDeparturesSurviveFileRoundTrip() {
        val r = base.copy(schedule = RouteSchedule(firstMin = 360, lastMin = 1320, peakHeadwayMin = 8, departures = listOf(430, 460)))
        val back = RouteFile.read(RouteFile.write(listOf(r))).single()
        assertEquals(listOf(430, 460), back.schedule.departures)
        assertEquals(8, back.schedule.peakHeadwayMin)
        assertEquals(listOf(430 * 60 - 7 * 3600), RouteTiming.next(back, nowSec = 7 * 3600, weekday = true, n = 1))
    }

    @Test fun alightingStopsFollowBoardingStop() {
        assertEquals(listOf("乙站", "终点站"), base.alightingStops("甲站"))
        assertEquals(emptyList<String>(), base.alightingStops("终点站"))
        assertEquals(listOf("终点站"), base.copy(stopPattern = RouteDef.StopPattern.ENDS).alightingStops("起点站"))
    }

    @Test fun candidateFromRouteIsManualWithoutCoordinates() {
        val r = base.copy(name = "105路", schedule = RouteSchedule(peakHeadwayMin = 8, offPeakHeadwayMin = 12))
        val c = r.toCandidate("甲站", "终点站")
        assertEquals(Candidate.Origin.MANUAL, c.origin)
        assertEquals("105", c.name)
        assertEquals("甲站", c.stopName)
        assertEquals("终点站", c.alightName)
        assertEquals(8, c.headwayMin)
        assertTrue(c.lat.isNaN())
    }

    private fun ride(line: String, board: String, alight: String, type: Candidate.Type = Candidate.Type.BUS) =
        Candidate(type = type, name = line, stopName = board, lat = Double.NaN, lng = Double.NaN, alightName = alight,
            origin = Candidate.Origin.MANUAL)

    @Test fun linkReusesRouteThatCoversTheRide() {
        val r = base.copy(name = "105路")
        val (t, created) = RouteLink.link(Trip(legs = listOf(Leg(listOf(ride("105", "甲站", "终点站"))))), listOf(r))
        assertTrue(created.isEmpty())
        assertEquals(r.id, t.legs[0].candidates[0].routeId)
    }

    @Test fun linkCreatesTwoStopRouteAndSharesItAcrossLegs() {
        val trip = Trip(legs = listOf(Leg(listOf(ride("7", "东站", "西站"))), Leg(listOf(ride("7路", "东站", "西站")))))
        val (t, created) = RouteLink.link(trip, emptyList())
        assertEquals(1, created.size)
        assertEquals("7路", created[0].name)
        assertEquals(listOf("东站", "西站"), created[0].stops)
        assertTrue(created[0].fromTrip)
        assertEquals(created[0].id, t.legs[1].candidates[0].routeId)
    }

    @Test fun linkSkipsRidesWithoutAlightingStopOrAlreadyLinked() {
        val trip = Trip(legs = listOf(Leg(listOf(ride("7", "东站", ""), ride("8", "东站", "西站").copy(routeId = "gone")))))
        val (t, created) = RouteLink.link(trip, emptyList())
        assertTrue(created.isEmpty())
        assertEquals("", t.legs[0].candidates[0].routeId)
        assertEquals("gone", t.legs[0].candidates[1].routeId)
    }

    @Test fun linkDoesNotMatchWrongDirectionOrMode() {
        val (_, created) = RouteLink.link(Trip(legs = listOf(Leg(listOf(ride("示例线", "终点站", "甲站"))))), listOf(base))
        assertEquals(1, created.size)
    }

    @Test fun routeIdAndFromTripSurviveJson() {
        val c = ride("7", "东站", "西站").copy(routeId = "r1")
        assertEquals("r1", Candidate.fromJson(c.toJson()).routeId)
        assertTrue(RouteDef.fromJson(base.copy(fromTrip = true).toJson()).fromTrip)
        assertFalse(RouteDef.fromJson(base.toJson()).fromTrip)
    }

    @Test fun routeLearnKeyNeedsOneLinkedRoute() {
        val a = ride("7", "东站", "西站").copy(routeId = "r1")
        assertEquals("r1|东|西", RouteLearn.keyFor(Leg(listOf(a, a.copy(stopName = "东站 ")))))
        assertEquals(null, RouteLearn.keyFor(Leg(listOf(a, ride("8", "东站", "西站").copy(routeId = "r2")))))
        assertEquals(null, RouteLearn.keyFor(Leg(listOf(a, ride("8", "东站", "西站")))))
        assertEquals(null, RouteLearn.keyFor(Leg(listOf(a.copy(alightName = "")))))
    }

    @Test fun routeLearnUsesSameRulesAsTripLearning() {
        assertEquals(null, RouteLearn.median(listOf(20, 22)))
        assertEquals(22, RouteLearn.median(listOf(25, 20, 22)))
        assertEquals(300.0, RouteLearn.blend(null, 300.0), 0.01)
        assertEquals(230.0, RouteLearn.blend(200.0, 300.0), 0.01)
    }
}
