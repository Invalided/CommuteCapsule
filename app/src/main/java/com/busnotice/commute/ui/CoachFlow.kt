package com.busnotice.commute.ui

/**
 * 首页聚光引导的步骤与状态（不依赖系统接口，便于单元测试）。
 *
 * 步骤按声明顺序展示；某一步的目标在首页上不可见时跳过该步。
 */
enum class CoachStep { NEW_TRIP, PLACES, CHECKS, MENU }

object CoachFlow {
    val STEPS: List<CoachStep> = CoachStep.values().toList()

    /** 按顺序的下一步；已是最后一步时返回 null（引导结束） */
    fun next(step: CoachStep): CoachStep? = STEPS.getOrNull(step.ordinal + 1)

    /** 从 [from]（含）起第一个目标可见的步骤；都不可见时返回 null */
    fun firstAvailable(from: CoachStep?, available: (CoachStep) -> Boolean): CoachStep? {
        if (from == null) return null
        return STEPS.drop(from.ordinal).firstOrNull(available)
    }

    /** 当前步之后是否还有目标可见的步骤；没有时当前步是最后一步，按钮显示「开始吧」 */
    fun isLast(step: CoachStep, available: (CoachStep) -> Boolean): Boolean =
        STEPS.drop(step.ordinal + 1).none(available)

    /**
     * 进度点：返回（当前位置，总数），只计目标可见的步骤；当前步总是计入。
     * 例如第二步的目标不可见时，第三步显示为 2 / 3。
     */
    fun dots(step: CoachStep, available: (CoachStep) -> Boolean): Pair<Int, Int> {
        val shown = STEPS.filter { it == step || available(it) }
        return shown.indexOf(step) to shown.size
    }
}

/** 引导进度的键值存储（Android 上由 SharedPreferences 实现） */
interface CoachStore {
    fun getBoolean(key: String): Boolean
    fun getString(key: String): String?
    fun put(values: Map<String, Any?>)
}

/**
 * 引导进度：是否开始过、是否完成、当前步骤。进度保存在本机，进程被杀后从当前步继续。
 * 是否需要显示由 [OnboardingGate] 判定。
 */
class CoachProgress(private val store: CoachStore) {
    companion object {
        const val KEY_DONE = "done"
        const val KEY_STARTED = "started"
        const val KEY_STEP = "step"
        const val KEY_NOTIFY_ASKED = "notify_asked"
    }

    val started: Boolean get() = store.getBoolean(KEY_STARTED)
    val done: Boolean get() = store.getBoolean(KEY_DONE)

    /** 当前步骤；没有记录或记录无法识别（例如旧版本保存的步骤名）时从第一步开始 */
    val step: CoachStep
        get() = CoachStep.values().firstOrNull { it.name == store.getString(KEY_STEP) } ?: CoachFlow.STEPS.first()

    /** 本机开始引导 */
    fun start() = store.put(mapOf(KEY_STARTED to true, KEY_DONE to false))

    /** 已有用户：标记为完成，不显示引导 */
    fun markExisting() = store.put(mapOf(KEY_DONE to true))

    fun save(step: CoachStep) = store.put(mapOf(KEY_STEP to step.name))

    /** 完成或跳过：此后不再显示 */
    fun finish() = store.put(mapOf(KEY_DONE to true, KEY_STEP to null))

    /** 从设置重新播放：回到第一步 */
    fun replay() = store.put(mapOf(KEY_STARTED to true, KEY_DONE to false, KEY_STEP to CoachFlow.STEPS.first().name))

    /** 是否已在保存行程时申请过通知权限 */
    val notifyAsked: Boolean get() = store.getBoolean(KEY_NOTIFY_ASKED)

    fun markNotifyAsked() = store.put(mapOf(KEY_NOTIFY_ASKED to true))
}

/** 通知权限按需申请：Android 13 及以上、尚未授予、此前未在保存行程时申请过 */
object NotifyAsk {
    fun shouldAsk(sdkInt: Int, granted: Boolean, alreadyAsked: Boolean): Boolean =
        sdkInt >= 33 && !granted && !alreadyAsked
}

/**
 * 气泡的纵向位置：优先放在目标下方，放不下时放在上方；两侧都放不下时放在空间较大的一侧并限制在可用区域内。
 * 所有值为同一坐标系下的像素。
 */
object CoachPlacement {
    data class Result(val y: Float, val below: Boolean)

    fun place(
        targetTop: Float, targetBottom: Float, bubbleHeight: Float,
        areaTop: Float, areaBottom: Float, gap: Float,
    ): Result {
        val spaceBelow = areaBottom - (targetBottom + gap)
        val spaceAbove = (targetTop - gap) - areaTop
        return when {
            bubbleHeight <= spaceBelow -> Result(targetBottom + gap, below = true)
            bubbleHeight <= spaceAbove -> Result(targetTop - gap - bubbleHeight, below = false)
            spaceBelow >= spaceAbove -> Result(
                (targetBottom + gap).coerceAtMost(areaBottom - bubbleHeight).coerceAtLeast(areaTop), below = true,
            )
            else -> Result((targetTop - gap - bubbleHeight).coerceAtLeast(areaTop), below = false)
        }
    }

    /** 小三角的横向中心：对准目标中心，并与气泡圆角保持 [inset] 的距离 */
    fun arrowX(targetCenterX: Float, bubbleLeft: Float, bubbleRight: Float, inset: Float): Float =
        if (bubbleRight - bubbleLeft <= 2 * inset) (bubbleLeft + bubbleRight) / 2
        else targetCenterX.coerceIn(bubbleLeft + inset, bubbleRight - inset)
}
