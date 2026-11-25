package com.busnotice.commute.ui

/**
 * 判断是否需要首次引导（不依赖系统接口，便于单元测试）。
 *
 * 「已有用户」只看用户有意义的数据：已有行程，或自动学到 / 手动设置的家和公司、出发时刻与乘车时长等学习数据。
 * 调度和计时用的键（例如下一次地点采样时间 nextSample）在 App 打开时就会写入，不能作为依据；
 * 行程中断恢复记录（run_state）只在行程运行期间存在，同样不作为依据。
 */
object OnboardingGate {
    /** 参与判断的 SharedPreferences 文件 */
    val PREF_FILES = listOf("places", "learn", "learn_ride2", "learn_bus_speed", "learn_metro")

    /**
     * 各文件中不属于用户数据的键：
     * - places：nextSample（下一次采样的闹钟时刻）、nightDay（夜间投票的当日去重标记，投票本身存在 clusters）
     */
    private val IGNORED_KEYS: Map<String, Set<String>> = mapOf(
        "places" to setOf("nextSample", "nightDay"),
    )

    /** 某个键是否算用户数据：被忽略的键不算；空字符串和空 JSON 数组（学习数据被清空后的状态）不算 */
    fun isUserData(file: String, key: String, value: Any?): Boolean {
        if (key in IGNORED_KEYS[file].orEmpty()) return false
        return when (value) {
            null -> false
            is String -> value.isNotBlank() && value.trim() != "[]"
            is Set<*> -> value.isNotEmpty()
            else -> true
        }
    }

    /** 是否已有用户数据；prefs 为「文件名 → 该文件全部键值」 */
    fun hasUserData(hasTrips: Boolean, prefs: Map<String, Map<String, Any?>>): Boolean =
        hasTrips || prefs.any { (file, entries) -> file in PREF_FILES && entries.any { (k, v) -> isUserData(file, k, v) } }

    /**
     * 是否显示引导：
     * - 本机已经开始过引导（started）：未完成就接着显示，完成后不再显示；
     * - 没有开始过：已有用户数据的不显示，否则显示。此时 done 只可能来自「已有用户」的自动跳过，
     *   不作为依据，以免跳过的判断有误时引导再也不出现。
     */
    fun shouldShow(done: Boolean, started: Boolean, hasUserData: Boolean): Boolean =
        if (started) !done else !hasUserData
}
