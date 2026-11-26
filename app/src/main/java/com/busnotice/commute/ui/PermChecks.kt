package com.busnotice.commute.ui

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.busnotice.commute.Keepalive

/**
 * 一项设置检查。
 * - granted：已满足（厂商项为用户已勾选）；
 * - note：为什么这一项需要单独开启（系统不允许和其他权限一起申请）；
 * - toggle：厂商项由用户勾选「已设置」，非空时显示勾选按钮。
 */
internal data class Perm(
    val id: String, val title: String, val desc: String, val granted: Boolean,
    val group: PermGroup = PermGroup.REQUIRED,
    val note: String? = null,
    val actionLabel: String = "去开启",
    val action: (() -> Unit)? = null,
    val toggle: (() -> Unit)? = null,
    /** 非空时，点操作按钮先弹出确认框，内容为此文字 */
    val confirm: String? = null,
) {
    val optional: Boolean get() = group == PermGroup.OPTIONAL
}

internal object PermId {
    const val FINE = "fine"
    const val BACKGROUND = "background"
    const val ACTIVITY = "activity"
    const val NOTIFY = "notify"
    const val PROMOTED = "promoted"
    const val BATTERY = "battery"
    const val BG_RESTRICTED = "bg_restricted"
    const val POWER_SAVE = "power_save"
    const val NOTIF_LISTENER = "notif_listener"
    const val A11Y = "a11y"
    const val BACKUP = "backup"
    const val LOCKED = "locked_records"
}

internal fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

/** 通知是否可用：Android 13 及以上看运行时权限，更早的版本看应用通知开关 */
internal fun notifyGranted(ctx: Context) =
    if (Build.VERSION.SDK_INT >= 33) granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    else NotificationManagerCompat.from(ctx).areNotificationsEnabled()

/** 「一次开启」要申请的权限：前台定位、通知、身体活动中尚未授予的 */
internal fun batchPermissions(ctx: Context): Array<String> =
    SetupSummary.batch({ p -> if (p == SetupSummary.NOTIFY) notifyGranted(ctx) else granted(ctx, p) }, Build.VERSION.SDK_INT)
        .toTypedArray()

/** 打开系统设置页；打不开时退到本应用的应用详情 */
internal fun openSettings(ctx: Context, i: Intent) {
    runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.recoverCatching {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private const val SEPARATE_SETTINGS = "需单独开启：系统要求在设置页里手动打开"

/**
 * 全部设置检查（含已满足项），按 [PermGroup] 分组：必需、手机厂商设置、可选、提示。
 * [request] 申请运行时权限；[changed] 在用户勾选厂商项后调用，用于刷新列表。
 */
internal fun permChecks(ctx: Context, request: (Array<String>) -> Unit, changed: () -> Unit = {}): List<Perm> {
    val out = mutableListOf<Perm>()
    val pkg = Uri.parse("package:${ctx.packageName}")
    fun open(i: Intent) = openSettings(ctx, i)
    val locPair = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    // ---------- 必需 ----------
    val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    out += Perm(PermId.FINE, "精确定位", "算你走到车站要多久", fine, action = { request(locPair) })
    val notif = notifyGranted(ctx)
    out += Perm(PermId.NOTIFY, "通知", "显示流体云胶囊", notif, action = {
        if (Build.VERSION.SDK_INT >= 33) request(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    })
    out += Perm(PermId.BACKGROUND, "后台定位：始终允许", "闹钟在后台叫醒时也能定位，自动开始行程需要",
        fine && granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        note = "需单独开启：系统要求先允许前台定位，再到系统页选「始终允许」",
        action = {
            // 系统要求先有前台定位才能申请后台定位
            if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) request(locPair)
            else request(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        })
    if (Build.VERSION.SDK_INT >= 36) {
        val promoted = notif && ctx.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        out += Perm(PermId.PROMOTED, "实时通知 / 流体云", "让胶囊出现在状态栏", promoted,
            note = "需单独开启：在系统的通知设置页里打开", action = {
                if (!notifyGranted(ctx)) request(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                else open(Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS").putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            })
    }
    val pm = ctx.getSystemService(PowerManager::class.java)
    out += Perm(PermId.BATTERY, "电池优化：不优化", "系统空闲时也能准时唤醒，行程中不易被停掉",
        pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true,
        note = "需单独开启：系统会弹出单独的确认框",
        action = { open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)) })
    val am = ctx.getSystemService(ActivityManager::class.java)
    out += Perm(PermId.BG_RESTRICTED, "后台活动未被限制", "被限制时，行程中会被系统停掉",
        am?.isBackgroundRestricted != true,
        note = "需单独开启：在应用详情 → 电池里解除限制",
        action = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) })

    // ---------- 手机厂商设置（读不到状态，由用户勾选） ----------
    val brand = Keepalive.brand()
    Keepalive.steps(brand).forEach { st ->
        val done = Keepalive.isDone(ctx, st.id)
        out += Perm(st.id, st.title, st.desc, done, group = PermGroup.VENDOR,
            actionLabel = "去设置",
            action = if (st.targets.isNotEmpty()) ({
                Keepalive.open(ctx, st)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
            }) else null,
            toggle = { Keepalive.setDone(ctx, st.id, !done); changed() })
    }

    // ---------- 可选 ----------
    out += Perm(PermId.ACTIVITY, "身体活动（计步）", "更快判断你下了车、开始走路；不开时按定位判断",
        granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION), group = PermGroup.OPTIONAL,
        action = { request(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)) })
    out += Perm(PermId.NOTIF_LISTENER, "通知使用权", "读 NFC 交通卡刷卡和系统到站提醒，进出站时间更准；只看出行通知",
        com.busnotice.commute.TransitNotif.enabled(ctx), group = PermGroup.OPTIONAL, note = SEPARATE_SETTINGS,
        action = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
    out += Perm(PermId.A11Y, "出行识别", "无障碍：行程中读系统出行胶囊、交通卡弹窗的文字，只记在本机",
        com.busnotice.commute.TransitA11y.enabled(ctx), group = PermGroup.OPTIONAL, note = SEPARATE_SETTINGS,
        action = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })

    // ---------- 提示（不计入完成度） ----------
    out += Perm(PermId.POWER_SAVE, "省电模式", if (pm?.isPowerSaveMode == true) "正在开启：会推迟定位和闹钟，通勤时段建议关闭" else "未开启",
        pm?.isPowerSaveMode != true, group = PermGroup.INFO, actionLabel = "去设置",
        action = { open(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) })
    val backup = com.busnotice.commute.SystemBackup.enabled(ctx)
    out += Perm(PermId.BACKUP, "系统备份",
        if (backup) "已开启：行程、设置和学习结果会进入手机云备份和换机迁移（原始定位记录不备份）"
        else "已关闭：换机或恢复手机时不带走本 App 的数据",
        true, group = PermGroup.INFO, actionLabel = if (backup) "关闭" else "开启",
        action = { com.busnotice.commute.SystemBackup.setEnabled(ctx, !backup); changed() })
    lockedRecordsCheck(ctx, changed)?.let { out += it }
    return out
}

/**
 * 系统清空了本机加密密钥、旧记录已无法读取时的提示（不计入完成度）。统计由后台完成，这里只读结果；
 * 删除只在用户确认后进行，不自动删除。
 */
private fun lockedRecordsCheck(ctx: Context, changed: () -> Unit): Perm? {
    val summary = com.busnotice.commute.TraceStore.locked ?: return null
    val st = com.busnotice.commute.LocalCrypto.status(ctx) ?: return null
    val isLost = { id: String -> com.busnotice.commute.LockedRecords.isLost(id, st.readable, st.pending, st.lost) }
    val samplesLost = com.busnotice.commute.Places.sampleKeyIds(ctx).any(isLost)
    val days = summary.lostDays.size
    if (days == 0 && !samplesLost) return null
    val what = buildList {
        if (days > 0) add("$days 天的旧轨迹")
        if (samplesLost) add("部分常去地点的原始采样")
    }.joinToString("和")
    return Perm(PermId.LOCKED, "无法读取的旧记录",
        "系统清空了本机加密密钥，${what}已无法读取。行程、设置和学习结果不受影响；这些记录仍保留在本机，是否删除由你决定。",
        false, group = PermGroup.INFO, actionLabel = "删除无法读取的记录",
        confirm = "将删除${what}。这些记录已无法读取，删除后不影响行程、设置和学习结果。",
        action = {
            val app = ctx.applicationContext
            Thread({
                runCatching { com.busnotice.commute.TraceStore.purgeLost(app) }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(app, "已删除无法读取的记录", Toast.LENGTH_SHORT).show(); changed()
                }
            }, "purge-locked").start()
        })
}

internal fun summaryOf(perms: List<Perm>) = SetupSummary.counts(perms.map { it.group to it.granted })
