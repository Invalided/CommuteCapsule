package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 选择器搜索「我的线路」：线路名与站名匹配。线路与站名均为虚构 */
class RouteSearchTest {
    private val go = RouteDef(name = "示例线1", direction = "去程", stops = listOf("起点站", "甲站", "乙站", "终点站"))
    private val back = go.reversed()
    private val metro = RouteDef(name = "示例2号线", mode = Candidate.Type.METRO, stops = listOf("东站", "甲站", "西站"))
    private val all = listOf(go, back, metro)

    @Test fun lineNameMatchesEveryDirection() {
        val hits = RouteSearch.search(all, Candidate.Type.BUS, "示例线1")
        assertEquals(listOf(go.id to null, back.id to null), hits.map { it.route.id to it.stop })
    }

    @Test fun lineNameIgnoresTrailingLuAndSpaces() {
        val r = go.copy(name = "105")
        assertEquals(1, RouteSearch.search(listOf(r), Candidate.Type.BUS, " 105路 ").size)
    }

    @Test fun stationMatchGivesBoardableStopOnly() {
        val hits = RouteSearch.search(all, Candidate.Type.BUS, "终点")
        // 去程的终点站之后没有站，不能上车；返程从终点站出发
        assertEquals(listOf(back.id to "终点站"), hits.map { it.route.id to it.stop })
    }

    @Test fun stationMatchIgnoresTrailingZhan() {
        val hits = RouteSearch.search(all, Candidate.Type.BUS, "甲站")
        assertEquals(setOf(go.id to "甲站", back.id to "甲站"), hits.map { it.route.id to it.stop }.toSet())
    }

    @Test fun modeIsRespected() {
        val hits = RouteSearch.search(all, Candidate.Type.METRO, "甲")
        assertEquals(listOf(metro.id to "甲站"), hits.map { it.route.id to it.stop })
    }

    @Test fun endsOnlyRouteOffersOnlyTheFirstStop() {
        val r = go.copy(stopPattern = RouteDef.StopPattern.ENDS)
        assertTrue(RouteSearch.search(listOf(r), Candidate.Type.BUS, "甲").isEmpty())
        assertEquals(listOf("起点站"), RouteSearch.search(listOf(r), Candidate.Type.BUS, "起点").map { it.stop })
    }

    @Test fun blankQueryReturnsNothing() {
        assertTrue(RouteSearch.search(all, Candidate.Type.BUS, "  ").isEmpty())
    }
}
