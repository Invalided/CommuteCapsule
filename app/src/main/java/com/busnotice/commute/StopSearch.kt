package com.busnotice.commute

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 线路 / 站点选择器的搜索来源。
 *
 * 选择器不直接调用某个数据源，而是经由 [StopSearch] 依次查询已注册的来源并合并结果，
 * 以后增加别的搜索方式（如导入的线路文件）只需实现本接口并注册。
 */
interface StopSearchSource {
    val id: String
    val title: String
    /** 当前能否查询；不能查询的来源会被跳过 */
    fun available(): Boolean = true
    fun search(key: String): SearchResult
}

/** 默认来源：当前接入的数据源（[Transit]），包括内置演示数据 */
object TransitSearchSource : StopSearchSource {
    override val id = "transit"
    override val title get() = Transit.static.title
    override fun available() = Transit.hasStaticData
    override fun search(key: String) = Transit.search(key)
}

object StopSearch {
    private val sources = CopyOnWriteArrayList<StopSearchSource>(listOf(TransitSearchSource))

    /** 注册一个来源；同 id 的旧来源会被替换 */
    fun register(s: StopSearchSource) {
        sources.removeAll { it.id == s.id }
        sources += s
    }

    fun unregister(id: String) { sources.removeAll { it.id == id } }

    /** 至少有一个可以查询的来源 */
    fun available(): Boolean = sources.any { runCatching { it.available() }.getOrDefault(false) }

    /**
     * 查询所有可用来源并合并（按注册顺序，同名线路、同一站点只保留第一次出现的）。
     * 没有可用来源时返回空结果；所有可用来源都查询失败时返回 null。
     */
    fun search(key: String): SearchResult? {
        val usable = sources.filter { runCatching { it.available() }.getOrDefault(false) }
        if (usable.isEmpty()) return SearchResult(emptyList(), emptyList())
        val results = usable.mapNotNull { s -> runCatching { s.search(key) }.getOrNull() }
        if (results.isEmpty()) return null
        return merge(results)
    }

    fun merge(results: List<SearchResult>): SearchResult = SearchResult(
        lines = results.flatMap { it.lines }.distinctBy { "${it.isSubway}|${it.name}" },
        stations = results.flatMap { it.stations }.distinctBy { "${it.isSubway}|${it.stopId}|${it.name}" },
    )
}

/** 查不到时直接记下：只保存用户输入的线路名和站名，没有坐标，位置待学习 */
object ManualEntry {
    /** 去掉首尾空白；公交线路名末尾的「路」去掉（显示时会自动加上），地铁线路名保持原样 */
    fun lineName(raw: String): String {
        val t = raw.trim()
        return if (t.length > 1 && t.endsWith("路") && !Candidate.looksLikeMetro(t)) t.dropLast(1).trim() else t
    }

    /** 搜索框里的内容更像线路名（含数字，如「105」「2号线」）还是站名 */
    fun looksLikeLine(q: String): Boolean {
        val t = q.trim()
        return t.isNotEmpty() && (Candidate.looksLikeMetro(t) || (t.any { it.isDigit() } && t.length <= 8))
    }

    fun bus(line: String, stop: String, alight: String = ""): Candidate = Candidate.normalize(
        Candidate(
            type = Candidate.Type.BUS, name = lineName(line), stopName = stop.trim(), lat = Double.NaN, lng = Double.NaN,
            alightName = alight.trim(), origin = Candidate.Origin.MANUAL,
        )
    )

    fun metro(line: String, stop: String, alight: String, headwayMin: Int, offPeakHeadwayMin: Int, dir: String = ""): Candidate =
        Candidate(
            type = Candidate.Type.METRO, name = line.trim().ifEmpty { "地铁" }, stopName = stop.trim(),
            lat = Double.NaN, lng = Double.NaN, alightName = alight.trim(), headwayMin = headwayMin,
            offPeakHeadwayMin = offPeakHeadwayMin, metroDir = dir.trim(), origin = Candidate.Origin.MANUAL,
        )

    /** 站名比对时忽略末尾的「站」和空白 */
    fun stationKey(name: String) = name.trim().removeSuffix("站").trim()
}

/** 手打的下车站和数据源站名的比对结果 */
data class StationCheck(val exact: String?, val suggestions: List<String>)

object StationMatch {
    /**
     * [typed] 与 [names] 比对：完全一致（忽略末尾「站」）给出 exact；
     * 否则给出包含关系或前缀相同的候选（最多 [max] 个）。
     */
    fun check(typed: String, names: List<String>, max: Int = 5): StationCheck {
        val k = ManualEntry.stationKey(typed)
        if (k.isEmpty()) return StationCheck(null, emptyList())
        val uniq = names.distinct()
        uniq.firstOrNull { ManualEntry.stationKey(it) == k }?.let { return StationCheck(it, emptyList()) }
        fun contains(n: String) = ManualEntry.stationKey(n).let { nk -> nk.contains(k) || k.contains(nk) }
        // 包含关系优先，其次是前缀相同；同类按长度差排序
        val sug = uniq.filter { n ->
            val nk = ManualEntry.stationKey(n)
            nk.isNotEmpty() && (contains(n) || commonPrefix(nk, k) >= 2)
        }.sortedWith(compareBy({ if (contains(it)) 0 else 1 }, { kotlin.math.abs(ManualEntry.stationKey(it).length - k.length) }))
            .take(max)
        return StationCheck(null, sug)
    }

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }
}
