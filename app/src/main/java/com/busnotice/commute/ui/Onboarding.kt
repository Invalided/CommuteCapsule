package com.busnotice.commute.ui

import android.content.Context
import com.busnotice.commute.TripStore

/**
 * 首页聚光引导的进度：只在首次启动出现，进度存本机，中途退出后从当前步继续；设置里可以重新播放。
 * 升级上来的老用户（已有行程或学习数据，判定见 [OnboardingGate]）不显示。
 */
object OnboardingState {
    private const val PREFS = "onboarding"

    private class PrefsStore(ctx: Context) : CoachStore {
        private val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun getBoolean(key: String) = sp.getBoolean(key, false)
        override fun getString(key: String): String? = sp.getString(key, null)
        override fun put(values: Map<String, Any?>) {
            val e = sp.edit()
            values.forEach { (k, v) ->
                when (v) {
                    null -> e.remove(k)
                    is Boolean -> e.putBoolean(k, v)
                    else -> e.putString(k, v.toString())
                }
            }
            e.apply()
        }
    }

    fun progress(ctx: Context) = CoachProgress(PrefsStore(ctx))

    fun needed(ctx: Context): Boolean {
        val p = progress(ctx)
        if (p.started) return OnboardingGate.shouldShow(p.done, started = true, hasUserData = false)
        val prefs = OnboardingGate.PREF_FILES.associateWith { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).all }
        val existing = OnboardingGate.hasUserData(TripStore.all(ctx).isNotEmpty(), prefs)
        if (!OnboardingGate.shouldShow(p.done, started = false, hasUserData = existing)) {
            p.markExisting(); return false
        }
        p.start()
        return true
    }

    fun step(ctx: Context): CoachStep = progress(ctx).step

    fun saveStep(ctx: Context, s: CoachStep) = progress(ctx).save(s)

    fun finish(ctx: Context) = progress(ctx).finish()

    /** 从设置里重新播放：回到第一步，已有行程和设置不受影响 */
    fun replay(ctx: Context) = progress(ctx).replay()
}
