package com.busnotice.commute.extra

/** App 启动时读取这里列出的数据源，用第一个可用的 */
object Extras {
    val sources: List<Any> = listOf(JsonSource)
}
