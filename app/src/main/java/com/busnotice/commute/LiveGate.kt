package com.busnotice.commute

/**
 * 实时通知的发布节流（纯逻辑，不依赖 Android，便于单元测试）。
 *
 * 胶囊被提醒展开成卡片后有一段保持期（[holdMs]），期间的常规刷新若直接发出，会把展开的卡片刷回小胶囊。
 * 规则：
 * - 保持期内阶段未变化的更新不立即发出，只记为待发内容（仅保留最新一条），保持期结束时补发；
 * - 保持期内阶段发生变化的更新（进站、即将到站、下车等）视为状态变化，立即发出，并保持卡片展开；
 *   同时仍记为待发内容，保持期结束时以常规样式补发一次；
 * - 用户隐藏胶囊或通知被关闭时不发出，也不记为待发内容；恢复显示时由调用方发出最新内容。
 *
 * 阶段由 [phaseOf] 从胶囊短文字得出：数字部分归一化后比较，
 * 因此「约5分」→「约4分」属于同一阶段，「约5分」→「快到了」→「下车」属于阶段变化。
 */
class LiveGate<T>(private val holdMs: Long) {

    enum class Action {
        /** 按常规样式立即发出 */
        POST,
        /** 保持期内的状态变化：立即发出，并保持卡片展开（不再次提醒） */
        POST_EXPANDED,
        /** 保持期内：暂不发出，到期补发 */
        HOLD,
        /** 胶囊被用户隐藏或通知不可用：不发出 */
        HIDDEN,
    }

    data class Decision(val action: Action, val flushAt: Long = 0L) {
        val posted: Boolean get() = action == Action.POST || action == Action.POST_EXPANDED
    }

    var holdUntil = 0L
        private set
    /** 最近一次实际发出内容的阶段 */
    var postedPhase: String? = null
        private set
    /** 保持期结束后需要补发的内容 */
    var pending: T? = null
        private set

    fun holding(now: Long): Boolean = now < holdUntil

    /** 提醒已将胶囊展开：开始保持期。展开时发出的内容即当前显示的内容，此前的待发内容作废 */
    fun expanded(now: Long, phase: String) {
        holdUntil = now + holdMs
        postedPhase = phase
        pending = null
    }

    /** 提交一条新内容，返回处理方式；[Decision.flushAt] 非零时调用方应在该时刻调用 [due] */
    fun offer(content: T, phase: String, now: Long, visible: Boolean = true): Decision {
        if (!visible) {
            pending = null
            return Decision(Action.HIDDEN)
        }
        if (!holding(now)) {
            pending = null
            postedPhase = phase
            return Decision(Action.POST)
        }
        pending = content
        if (phase != postedPhase) {
            postedPhase = phase
            return Decision(Action.POST_EXPANDED, holdUntil)
        }
        return Decision(Action.HOLD, holdUntil)
    }

    /** 保持期到期时调用：返回需要补发的内容（没有或尚未到期时返回 null），并清除待发状态 */
    fun due(now: Long): T? {
        if (holding(now)) return null
        val p = pending ?: return null
        pending = null
        return p
    }

    /** 内容被直接替换（不经节流）或行程结束：清除保持期与待发内容 */
    fun reset() {
        holdUntil = 0L
        pending = null
        postedPhase = null
    }

    companion object {
        private val DIGITS = Regex("\\d+")

        /** 胶囊短文字的阶段：数字归一化为「#」，用于区分计数刷新与状态变化 */
        fun phaseOf(short: String): String = short.replace(DIGITS, "#")
    }
}
