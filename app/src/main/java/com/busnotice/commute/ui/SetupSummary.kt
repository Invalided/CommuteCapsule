package com.busnotice.commute.ui

/**
 * 设置检查的分组、计数与「一次开启」的权限清单（纯逻辑，不依赖系统接口，便于单元测试）。
 */
internal enum class PermGroup {
    /** 必需：缺了会影响提醒，计入「还差」，缺项时设置检查卡放在列表上方 */
    REQUIRED,
    /** 手机厂商的后台设置：App 读不到，由用户勾选；计入「还差」，但不把卡片提到上方 */
    VENDOR,
    /** 可选增强：只计入完成数，不计入「还差」 */
    OPTIONAL,
    /** 提示：当前状态或说明，不计入任何计数 */
    INFO,
}

internal object SetupSummary {
    data class Counts(
        /** 已满足的项数（不含提示） */
        val ok: Int,
        /** 参与计数的总项数（不含提示） */
        val total: Int,
        /** 还差几项：必需项与厂商项中未满足的 */
        val missing: Int,
        /** 有必需项未满足 */
        val urgent: Boolean,
    )

    fun counts(items: List<Pair<PermGroup, Boolean>>): Counts {
        val counted = items.filter { it.first != PermGroup.INFO }
        return Counts(
            ok = counted.count { it.second },
            total = counted.size,
            missing = counted.count { !it.second && (it.first == PermGroup.REQUIRED || it.first == PermGroup.VENDOR) },
            urgent = counted.any { !it.second && it.first == PermGroup.REQUIRED },
        )
    }

    const val FINE = "android.permission.ACCESS_FINE_LOCATION"
    const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
    const val NOTIFY = "android.permission.POST_NOTIFICATIONS"
    const val ACTIVITY = "android.permission.ACTIVITY_RECOGNITION"

    /**
     * 「一次开启」：可以在同一个系统授权流程中连续申请的运行时权限（前台定位、通知、身体活动），只含尚未授予的。
     * 后台定位、电池优化、实时通知、厂商设置等须单独跳转，不在其中。
     */
    fun batch(granted: (String) -> Boolean, sdk: Int): List<String> {
        val out = mutableListOf<String>()
        if (!granted(FINE) || !granted(COARSE)) { out += FINE; out += COARSE }
        if (sdk >= 33 && !granted(NOTIFY)) out += NOTIFY
        if (!granted(ACTIVITY)) out += ACTIVITY
        return out
    }
}
