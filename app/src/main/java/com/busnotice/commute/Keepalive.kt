package com.busnotice.commute

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台保活体检：按手机品牌列出需要用户在系统里手动打开的后台设置，并尽量直接跳到对应页面。
 *
 * - 厂商设置页的组件名随系统版本变化，跳不过去时依次退到「应用详情」、系统设置首页，并提示在设置里搜索什么；
 * - 这些厂商设置 App 读不到，由用户设置后自己勾「已设置」，结果只存本机；
 * - 系统能读到的项（电池优化、后台限制、省电模式）由 [readable] 给出。
 */
object Keepalive {
    enum class Brand(val title: String) {
        OPPO("OPPO / 一加 / realme"), XIAOMI("小米 / 红米"), HUAWEI("华为"), HONOR("荣耀"),
        VIVO("vivo / iQOO"), SAMSUNG("三星"), OTHER("其他品牌"),
    }

    /** 跳转目标：系统动作或厂商组件 */
    data class Target(
        val action: String? = null,
        val pkg: String? = null,
        val cls: String? = null,
        val packageUri: Boolean = false,   // data = package:<本应用>
        val withPackageExtras: Boolean = false,
    )

    /** 一项厂商设置：说明 + 依次尝试的跳转目标 + 跳转失败时在设置里搜索的关键词 */
    data class Step(val id: String, val title: String, val desc: String, val targets: List<Target>, val search: String)

    private val APP_DETAILS = Target(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri = true)

    fun brand(manufacturer: String = Build.MANUFACTURER.orEmpty(), brand: String = Build.BRAND.orEmpty()): Brand {
        val m = (manufacturer + " " + brand).lowercase()
        return when {
            listOf("oppo", "oneplus", "realme").any { it in m } -> Brand.OPPO
            listOf("xiaomi", "redmi", "poco").any { it in m } -> Brand.XIAOMI
            "honor" in m -> Brand.HONOR
            "huawei" in m -> Brand.HUAWEI
            listOf("vivo", "iqoo").any { it in m } -> Brand.VIVO
            "samsung" in m -> Brand.SAMSUNG
            else -> Brand.OTHER
        }
    }

    private fun lockRecents(id: String) = Step(id, "最近任务里锁定",
        "打开最近任务（多任务）界面，在通勤胶囊的卡片上下拉或长按，选择「锁定」。清理后台时不会关掉它。",
        emptyList(), "锁定")

    fun steps(b: Brand): List<Step> = when (b) {
        Brand.OPPO -> listOf(
            Step("oppo_bg", "允许完全后台行为",
                "应用详情 → 耗电管理（或「电池」）→ 选「允许完全后台行为」，不要选智能模式。这一项最关键。",
                listOf(APP_DETAILS), "耗电管理"),
            Step("oppo_autostart", "允许自启动", "在自启动列表里打开通勤胶囊，被清理后闹钟和围栏才能把它叫起来。",
                listOf(
                    Target(pkg = "com.coloros.safecenter", cls = "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                    Target(pkg = "com.coloros.safecenter", cls = "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    Target(pkg = "com.oppo.safe", cls = "com.oppo.safe.permission.startup.StartupAppListActivity"),
                    Target(pkg = "com.oneplus.security", cls = "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                    APP_DETAILS,
                ), "自启动"),
            lockRecents("oppo_lock"),
            Step("oppo_sleep", "关闭睡眠待机优化", "设置 → 电池 → 更多设置 → 关闭「睡眠待机优化」，夜里和清晨通勤时不被停掉。",
                listOf(Target(Settings.ACTION_BATTERY_SAVER_SETTINGS)), "睡眠待机"),
        )
        Brand.XIAOMI -> listOf(
            Step("mi_power", "省电策略：无限制", "应用详情 → 省电策略 → 选「无限制」。",
                listOf(Target(pkg = "com.miui.powerkeeper", cls = "com.miui.powerkeeper.ui.HiddenAppsConfigActivity", withPackageExtras = true),
                    APP_DETAILS), "省电策略"),
            Step("mi_autostart", "允许自启动", "在自启动管理里打开通勤胶囊。",
                listOf(Target(pkg = "com.miui.securitycenter", cls = "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    APP_DETAILS), "自启动"),
            lockRecents("mi_lock"),
        )
        Brand.HUAWEI, Brand.HONOR -> listOf(
            Step("hw_launch", "应用启动管理：手动管理", "关闭「自动管理」，在弹出的选项里打开允许自启动、允许关联启动、允许后台活动。",
                (if (b == Brand.HONOR) listOf(
                    Target(pkg = "com.hihonor.systemmanager", cls = "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                else emptyList()) + listOf(
                    Target(pkg = "com.huawei.systemmanager", cls = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    Target(pkg = "com.huawei.systemmanager", cls = "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                    Target(pkg = "com.huawei.systemmanager", cls = "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                    APP_DETAILS,
                ), "应用启动管理"),
            Step("hw_network", "休眠时保持网络连接", "设置 → 电池 → 更多电池设置 → 打开「休眠时始终保持网络连接」。",
                listOf(Target(Settings.ACTION_BATTERY_SAVER_SETTINGS)), "休眠时始终保持网络连接"),
            lockRecents("hw_lock"),
        )
        Brand.VIVO -> listOf(
            Step("vivo_power", "允许后台高耗电", "设置 → 电池 → 后台耗电管理 → 通勤胶囊 → 选「允许后台高耗电」。",
                listOf(APP_DETAILS), "后台耗电管理"),
            Step("vivo_autostart", "允许自启动", "在自启动管理里打开通勤胶囊。",
                listOf(
                    Target(pkg = "com.vivo.permissionmanager", cls = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    Target(pkg = "com.iqoo.secure", cls = "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                    APP_DETAILS,
                ), "自启动"),
            lockRecents("vivo_lock"),
        )
        Brand.SAMSUNG -> listOf(
            Step("sam_battery", "电池：不受限制", "应用详情 → 电池 → 选「不受限制」。", listOf(APP_DETAILS), "电池"),
            Step("sam_never", "加入永不休眠的应用", "设置 → 电池 → 后台使用限制 → 永不休眠的应用 → 添加通勤胶囊，并关闭「将未使用的应用置于休眠」。",
                listOf(
                    Target(pkg = "com.samsung.android.lool", cls = "com.samsung.android.sm.ui.battery.BatteryActivity"),
                    Target(pkg = "com.samsung.android.sm_cn", cls = "com.samsung.android.sm.ui.battery.BatteryActivity"),
                    Target(Settings.ACTION_BATTERY_SAVER_SETTINGS),
                ), "永不休眠"),
        )
        Brand.OTHER -> listOf(
            Step("other_battery", "电池：不受限制", "应用详情 → 电池（或「应用电池用量」）→ 选「不受限制」，并关闭「暂停未使用应用的活动」。",
                listOf(APP_DETAILS), "电池"),
        )
    }

    // ---------- 用户自报「已设置」（系统读不到） ----------
    private const val PREFS = "keepalive"

    fun isDone(ctx: Context, id: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("done_$id", false)

    fun setDone(ctx: Context, id: String, done: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("done_$id", done).apply()
    }

    /** 本机品牌的专项里已勾选的项数 / 总项数 */
    fun brandProgress(ctx: Context, b: Brand = brand()): Pair<Int, Int> {
        val s = steps(b)
        return s.count { isDone(ctx, it.id) } to s.size
    }

    // ---------- 系统能读到的项 ----------
    data class Check(val id: String, val title: String, val desc: String, val ok: Boolean, val target: Target)

    fun readable(ctx: Context): List<Check> {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val am = ctx.getSystemService(ActivityManager::class.java)
        val out = mutableListOf<Check>()
        out += Check("battery_opt", "系统电池优化：不优化", "允许后添加到电池优化白名单，系统空闲时也能准时唤醒。",
            pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true,
            Target(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri = true))
        out += Check("bg_restricted", "后台活动未被限制", "被限制时，行程中会被系统停掉。在应用详情 → 电池里解除限制。",
            am?.isBackgroundRestricted != true, APP_DETAILS)
        out += Check("power_save", "省电模式已关闭", "省电模式会推迟定位和闹钟，通勤时段建议关闭。",
            pm?.isPowerSaveMode != true, Target(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        return out
    }

    // ---------- 跳转 ----------
    fun intentFor(ctx: Context, t: Target): Intent {
        val i = if (t.pkg != null && t.cls != null) Intent().setComponent(ComponentName(t.pkg, t.cls))
            else Intent(t.action ?: Settings.ACTION_SETTINGS)
        if (t.packageUri) i.data = Uri.parse("package:${ctx.packageName}")
        if (t.withPackageExtras) {
            i.putExtra("package_name", ctx.packageName)
            i.putExtra("package_label", ctx.applicationInfo.loadLabel(ctx.packageManager).toString())
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * 依次尝试 [targets]，第一个能打开的就打开；都不行退到应用详情，再不行打开系统设置首页。
     * 返回 null 表示打开了目标页面；否则返回给用户的提示（在设置里搜索什么）。
     */
    fun open(ctx: Context, targets: List<Target>, search: String): String? {
        for (t in targets + APP_DETAILS) {
            val ok = runCatching { ctx.startActivity(intentFor(ctx, t)) }.isSuccess
            if (ok) return if (t == APP_DETAILS && targets.firstOrNull() != APP_DETAILS) "没能直接打开，已转到应用详情；也可以在系统设置里搜索「$search」" else null
        }
        runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return "请在系统设置顶部搜索「$search」"
    }

    fun open(ctx: Context, step: Step): String? = open(ctx, step.targets, step.search)
}
