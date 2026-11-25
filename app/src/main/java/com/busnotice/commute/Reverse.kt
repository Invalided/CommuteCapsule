package com.busnotice.commute

/** 根据去程生成回程：段顺序倒过来，每路公交换成反方向、在原下车站上车 */
object Reverse {
    data class Result(val trip: Trip, val warnings: List<String>)

    /** 网络请求，需在 IO 线程调用 */
    fun build(src: Trip): Result {
        val warnings = mutableListOf<String>()
        val n = src.legs.size
        val legs = src.legs.reversed().mapIndexed { j, leg ->
            val cands = leg.candidates.mapNotNull { c -> reverseCandidate(c, warnings) }
            val walk = if (j == 0) leg.transferWalkMin else src.legs[n - j].transferWalkMin
            Leg(cands, rideMin = leg.rideMin, transferWalkMin = walk)
        }.filter { it.candidates.isNotEmpty() }
        val trip = Trip(
            name = if (src.name.contains("上班")) "下班" else src.name + "·回程",
            legs = legs, dayMask = src.dayMask,
            startMinute = 18 * 60, endMinute = 18 * 60 + 30, warmupMin = src.warmupMin,
        )
        return Result(trip, warnings)
    }

    /**
     * 直接记下的线路（没有线路编号）：在原下车站上车、原上车站下车，坐标随之对调（通常为空，待学习）。
     * 地铁先按站名查一次数据源，查到了用数据源的站点。
     */
    private fun reverseManual(c: Candidate, warnings: MutableList<String>): Candidate? {
        if (c.alightName.isEmpty()) { warnings += "${c.label()} 没填下车站，回程请手动添加"; return null }
        if (c.isMetro()) {
            val st = runCatching { Transit.search(c.alightName).stations.firstOrNull { it.isSubway && it.name == c.alightName } }.getOrNull()
            if (st != null) return c.copy(stopName = st.name, lat = st.lat, lng = st.lng, alightName = c.stopName,
                alightLat = Double.NaN, alightLng = Double.NaN, metroDir = "", origin = Candidate.Origin.SOURCE)
        }
        return c.copy(
            stopName = c.alightName, alightName = c.stopName, lat = c.alightLat, lng = c.alightLng,
            alightLat = c.lat, alightLng = c.lng, metroDir = "", lastStop = "", stationId = "", stopIndex = -1,
        )
    }

    private fun reverseCandidate(c: Candidate, warnings: MutableList<String>): Candidate? =
        if (c.isManual() && c.lineId.isEmpty() && c.type != Candidate.Type.SHUTTLE) reverseManual(c, warnings)
        else reverseSourced(c, warnings)

    private fun reverseSourced(c: Candidate, warnings: MutableList<String>): Candidate? = when (c.type) {
        Candidate.Type.SHUTTLE -> { warnings += "${c.label()} 是自定义通勤车，回程请手动添加"; null }
        Candidate.Type.METRO -> {
            if (c.alightName.isEmpty()) { warnings += "${c.label()} 没填下车站，回程请手动添加"; null }
            else runCatching {
                val st = Transit.search(c.alightName).stations
                    .firstOrNull { it.isSubway && it.name == c.alightName }
                    ?: Transit.search(c.alightName).stations.first { it.isSubway }
                c.copy(stopName = st.name, lat = st.lat, lng = st.lng, alightName = c.stopName)
            }.getOrElse { warnings += "没找到地铁站「${c.alightName}」"; null }
        }
        Candidate.Type.BUS -> {
            if (c.alightName.isEmpty()) { warnings += "${c.label()} 没选下车站，回程请手动添加"; null }
            else runCatching {
                val fwd = Transit.lineDetail(c.lineId, c.regionId)
                val revId = fwd.reverseLineId.ifEmpty {
                    Transit.search(c.name, c.regionId).lines.firstOrNull { it.name == c.name }
                        ?.dirs?.firstOrNull { it.lineId != c.lineId }?.lineId ?: ""
                }
                require(revId.isNotEmpty())
                val rev = Transit.lineDetail(revId, c.regionId)
                val st = rev.stations.first { it.name == c.alightName }
                c.copy(
                    stopName = st.name, lat = st.lat, lng = st.lng, lineId = revId, stationId = st.stopId,
                    lastStop = rev.lastStop, stopIndex = st.order, alightName = c.stopName,
                )
            }.getOrElse { warnings += "${c.label()} 反方向没找到「${c.alightName}」站"; null }
        }
    }
}
