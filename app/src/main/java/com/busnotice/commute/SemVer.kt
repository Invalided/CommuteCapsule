package com.busnotice.commute

/**
 * 语义化版本号（主.次.修订[-预发布标识][+构建元数据]），按 SemVer 2.0.0 规定的优先级比较：
 * - 主、次、修订号依次按数值比较；
 * - 三者相同时，带预发布标识的版本低于正式版（如 1.0.0-rc.1 < 1.0.0）；
 * - 预发布标识按点分隔逐段比较：纯数字段按数值比较，其余按 ASCII 比较，数字段低于非数字段，
 *   前面各段都相同时段数少的较低（如 1.0.0-alpha < 1.0.0-alpha.1 < 1.0.0-beta < 1.0.0-rc.1）；
 * - 构建元数据不参与比较。
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val pre: List<String> = emptyList(),
) : Comparable<SemVer> {

    val isPrerelease: Boolean get() = pre.isNotEmpty()

    /** 与 app/build.gradle.kts 一致的 versionCode：主*10000+次*100+修订，不含预发布标识 */
    val code: Int get() = major * 10000 + minor * 100 + patch

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        if (pre.isEmpty() || other.pre.isEmpty()) return compareValues(other.pre.size, pre.size).coerceIn(-1, 1)
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val c = compareIdentifier(pre[i], other.pre[i])
            if (c != 0) return c
        }
        return compareValues(pre.size, other.pre.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (pre.isEmpty()) "" else pre.joinToString(".", prefix = "-")

    companion object {
        private val PATTERN = Regex(
            """^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z.-]+)?$"""
        )

        /** 解析版本号或标签（允许前缀 v），格式不符或数值超出范围时返回 null */
        fun parse(text: String): SemVer? {
            val m = PATTERN.find(text.trim()) ?: return null
            val major = m.groupValues[1].toIntOrNull() ?: return null
            val minor = m.groupValues[2].toIntOrNull() ?: return null
            val patch = m.groupValues[3].toIntOrNull() ?: return null
            val pre = m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            return SemVer(major, minor, patch, pre)
        }

        private fun isNumeric(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

        private fun compareIdentifier(a: String, b: String): Int {
            val na = isNumeric(a)
            val nb = isNumeric(b)
            return when {
                na && nb -> if (a.length != b.length) compareValues(a.length, b.length) else a.compareTo(b).coerceIn(-1, 1)
                na -> -1
                nb -> 1
                else -> a.compareTo(b).coerceIn(-1, 1)
            }
        }
    }
}
