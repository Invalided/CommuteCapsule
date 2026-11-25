package com.busnotice.commute.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** 一项设置检查：granted = 已满足；optional = 可选项（不算「还差」） */
internal data class Perm(
    val id: String, val title: String, val desc: String, val granted: Boolean, val optional: Boolean = false,
    val action: () -> Unit,
)

internal object PermId {
    const val FINE = "fine"
    const val BACKGROUND = "background"
    const val ACTIVITY = "activity"
    const val NOTIFY = "notify"
    const val PROMOTED = "promoted"
    const val EXACT = "exact"
    const val BATTERY = "battery"
    const val NOTIF_LISTENER = "notif_listener"
    const val A11Y = "a11y"
}

internal fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

/** 打开系统设置页；打不开时退到本应用的应用详情 */
internal fun openSettings(ctx: Context, i: Intent) {
    runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.recoverCatching {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** 全部设置检查（含已满足项）。首页「设置检查」和首次引导共用这一份 */
internal fun permChecks(ctx: Context, request: (Array<String>) -> Unit): List<Perm> {
    val out = mutableListOf<Perm>()
    val pkg = Uri.parse("package:${ctx.packageName}")
    fun open(i: Intent) = openSettings(ctx, i)

    val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    out += Perm(PermId.FINE, "精确定位", "算你走到车站要多久", fine) {
        request(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    out += Perm(PermId.BACKGROUND, "后台定位：始终允许", "闹钟在后台叫醒时也能定位",
        fine && granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
        // 系统要求先有前台定位才能申请后台定位
        if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION))
            request(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        else request(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }
    out += Perm(PermId.ACTIVITY, "身体活动（计步）", "判断你下了地铁、开始走路", granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION)) {
        request(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
    }
    val notif = granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    out += Perm(PermId.NOTIFY, "通知", "显示流体云胶囊", notif) { request(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
    if (Build.VERSION.SDK_INT >= 36) {
        val promoted = notif && ctx.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        out += Perm(PermId.PROMOTED, "实时通知 / 流体云", "让胶囊出现在状态栏", promoted) {
            if (!granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) request(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            else open(Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS").putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
    }
    out += Perm(PermId.EXACT, "精确闹钟", "准时预热", ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
        open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
    }
    out += Perm(PermId.BATTERY, "电池不限制", "也请在「后台保活体检」里完成手机厂商的后台设置",
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) {
        open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
    }
    out += Perm(PermId.NOTIF_LISTENER, "通知使用权", "读 NFC 交通卡刷卡和系统到站提醒，进出站时间更准；只看出行通知",
        com.busnotice.commute.TransitNotif.enabled(ctx), optional = true) {
        open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    out += Perm(PermId.A11Y, "出行识别", "无障碍：行程中读系统出行胶囊、交通卡弹窗的文字，只记在本机",
        com.busnotice.commute.TransitA11y.enabled(ctx), optional = true) {
        open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    return out
}
