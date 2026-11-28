package com.busnotice.commute

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * 实时通知：Android 16 promoted ongoing，ColorOS 16 渲染成流体云胶囊。
 * 准入条件：ongoing、有标题、请求提升、标准样式（ProgressStyle）、不着色、无自定义视图。
 * 能定制的只有进度条：分段颜色、站点圆点、移动的小图标、两端图标。
 */
object LiveNotifier {
    const val CHANNEL_LIVE = "commute_live"
    const val CHANNEL_ALERT = "commute_alert_v2"
    const val CHANNEL_QUIET = "commute_quiet"
    const val CHANNEL_POP = "commute_pop"
    const val LIVE_ID = 1001
    private const val ALERT_ID = 1002

    const val BLUE = 0xFF3D7BFF.toInt()
    private const val BLUE_SOFT = 0xFF9DB9FF.toInt()
    private const val ORANGE = 0xFFFF8A3D.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    // 地铁用绿色、地铁图标，和公交（蓝色、大巴图标）一眼区分
    const val GREEN = 0xFF1AAE6F.toInt()
    private const val GREEN_SOFT = 0xFF8FD9B6.toInt()

    /** 胶囊要显示的内容 */
    data class Visual(
        val title: String,
        val text: String,
        val short: String,
        val sub: String = "",
        val countdownTo: Long? = null,
        val progress: Int? = null,          // 0..100
        val stops: Int = 0,                 // 进度条上画几个站点圆点
        val urgent: Boolean = false,
        val trackerRes: Int = R.drawable.ic_bus,
        val endRes: Int = R.drawable.ic_stop,
        val metro: Boolean = false,
        val riding: Boolean = false,        // 已在车上：不再显示「已上车」
    )

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("commute_alert")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, "通勤实时胶囊", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "出发倒计时和到站信息（流体云）"
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUIET, "到站后记录", NotificationManager.IMPORTANCE_MIN).apply {
                description = "到站后记录走到目的地的那段路（用来学习常去地点），最多 25 分钟"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_POP, "车快到了（展开流体云）", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "车快到、该出发、快到站时，把流体云胶囊直接展开成卡片；震动由 App 自己控制"
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "该出门了提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "需要马上出发、或换乘紧张时横幅 + 震动"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
            }
        )
    }

    /** 到站后记录最后一段步行：不上流体云、不响不震，只在通知栏折叠一条（前台服务必须有通知） */
    fun quiet(ctx: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_QUIET)
            .setSmallIcon(R.drawable.ic_walk)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp(ctx))
            .addAction(0, "停止记录", actionIntent(ctx, ActionReceiver.ACTION_STOP))
            .build()

    /** 直接替换前台通知（不管胶囊是否被划掉隐藏） */
    fun replace(ctx: Context, n: Notification) {
        resetGate()
        last = n
        try { NotificationManagerCompat.from(ctx).notify(LIVE_ID, n) } catch (_: SecurityException) { }
    }

    fun warming(ctx: Context, tripName: String): Notification =
        base(ctx, Visual(tripName, "正在定位、查询实时公交…", "定位中")).build()

    /** [alerting] 只对 CHANNEL_POP 有效：false 时以展开渠道更新内容，但不再次提醒 */
    fun build(ctx: Context, v: Visual, channel: String = CHANNEL_LIVE, alerting: Boolean = true): Notification {
        if (channel == CHANNEL_LIVE) lastVisual = v
        val b = base(ctx, v, channel, alerting)
        if (v.countdownTo != null && v.countdownTo > System.currentTimeMillis()) {
            b.setWhen(v.countdownTo).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            b.setShowWhen(false)
        }
        if (v.progress != null) {
            val p = v.progress.coerceIn(0, 100)
            val color = if (v.urgent) ORANGE else if (v.metro) GREEN else BLUE
            val soft = if (v.metro) GREEN_SOFT else BLUE_SOFT
            val style = NotificationCompat.ProgressStyle()
                .setStyledByProgress(true)
                .setProgressTrackerIcon(IconCompat.createWithResource(ctx, badge(v.trackerRes)))
                .setProgressEndIcon(IconCompat.createWithResource(ctx, v.endRes))
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(100).setColor(color))
                .setProgress(p)
            val n = v.stops.coerceIn(0, 10)
            for (i in 1 until n) {
                val pos = i * 100 / n
                style.addProgressPoint(NotificationCompat.ProgressStyle.Point(pos).setColor(if (pos <= p) WHITE else soft))
            }
            b.setStyle(style)
        } else {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(v.text))
        }
        return b.build()
    }

    /** 进度条上的移动图标换成彩色徽标（绿底地铁 / 蓝底公交） */
    private fun badge(res: Int) = when (res) {
        R.drawable.ic_metro -> R.drawable.ic_badge_metro
        R.drawable.ic_bus -> R.drawable.ic_badge_bus
        else -> res
    }

    @Volatile var last: Notification? = null
    @Volatile private var lastVisual: Visual? = null
    /** 最近一次常规刷新的内容（与 [last] 对应），用于补发时的日志 */
    @Volatile private var lastLiveVisual: Visual? = null
    private const val POP_HOLD_MS = 25_000L
    /**
     * 胶囊刚展开成卡片：保持期内阶段未变化的常规刷新暂不发出（免得把展开的卡片刷回小胶囊），到期补发最新内容；
     * 阶段变化（进站、即将到站、下车等）立即发出并保持展开。规则见 [LiveGate]。
     */
    private val gate = LiveGate<Pair<Notification, Visual?>>(POP_HOLD_MS)
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var appCtx: Context? = null
    private val flushTask = Runnable { flush() }
    /** 用户划掉了胶囊：行程照常在后台跑，但先不显示，等下一次提醒（到站、该出发、换乘）或打开 App 再出来 */
    @Volatile var hidden = false

    /** 常规刷新：经节流后发出；[v] 为该通知的内容，用于状态判断与行程日志 */
    @Synchronized
    fun update(ctx: Context, n: Notification, v: Visual? = null) {
        last = n
        if (v != null) lastLiveVisual = v
        appCtx = ctx.applicationContext
        val now = System.currentTimeMillis()
        val enabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        val d = gate.offer(n to v, LiveGate.phaseOf(v?.short ?: ""), now, visible = !hidden && enabled)
        when (d.action) {
            LiveGate.Action.POST -> if (notify(ctx, n)) logPosted(v) else logSuppressed(v, "error")
            LiveGate.Action.POST_EXPANDED -> {
                val ok = v != null && notify(ctx, build(ctx, v.copy(urgent = true), CHANNEL_POP, alerting = false))
                if (ok) logPosted(v) else logSuppressed(v, "error")
            }
            LiveGate.Action.HOLD -> logSuppressed(v, "hold")
            LiveGate.Action.HIDDEN -> logSuppressed(v, if (hidden) "hidden" else "disabled")
        }
        if (d.flushAt > 0) schedule(d.flushAt - now)
    }

    /** 保持期到期：补发最新的常规内容（行程已结束或胶囊被隐藏时不补发） */
    @Synchronized
    private fun flush() {
        val ctx = appCtx ?: return
        val now = System.currentTimeMillis()
        if (gate.holding(now)) { schedule(gate.holdUntil - now); return }
        val p = gate.due(now) ?: return
        if (hidden || CommuteService.instance == null) return
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        if (notify(ctx, p.first)) logPosted(p.second, flushed = true)
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(flushTask)
        handler.postDelayed(flushTask, delayMs.coerceAtLeast(0L) + 50L)
    }

    /** 行程结束或内容被直接替换：取消待补发的内容 */
    @Synchronized
    fun resetGate() {
        gate.reset()
        lastPostedKey = ""; lastSuppressedKey = ""
        runCatching { handler.removeCallbacks(flushTask) }
    }

    private fun notify(ctx: Context, n: Notification): Boolean = try {
        NotificationManagerCompat.from(ctx).notify(LIVE_ID, n); true
    } catch (_: SecurityException) { false }

    private var lastPostedKey = ""
    private var lastSuppressedKey = ""

    /** 行程日志：只把实际发出的内容记为 live；未发出的单独记为 live_suppressed（内容变化时各记一行） */
    private fun logPosted(v: Visual?, flushed: Boolean = false) {
        if (v == null) return
        val key = "${v.title}|${v.short}|${v.stops}"
        lastSuppressedKey = ""
        if (key == lastPostedKey) return
        lastPostedKey = key
        TraceLog.event("live", "title" to v.title, "short" to v.short, "stops" to v.stops, "p" to v.progress,
            "out" to "posted", "flush" to flushed)
    }

    private fun logSuppressed(v: Visual?, why: String) {
        if (v == null) return
        val key = "${v.title}|${v.short}|${v.stops}|$why"
        if (key == lastSuppressedKey) return
        lastSuppressedKey = key
        TraceLog.event("live_suppressed", "title" to v.title, "short" to v.short, "stops" to v.stops, "why" to why)
    }

    /** 需要马上出发 / 换乘紧张时，额外弹一条横幅并震动 */
    /**
     * [visual] 为本轮新算出的胶囊内容：展开的卡片用它的短文字和进度，标题、正文替换为提醒内容。
     * 不传时沿用最近一次常规刷新的内容。
     */
    fun alert(ctx: Context, title: String, text: String, metro: Boolean = false, id: Int = ALERT_ID, locSettings: Boolean = false,
              visual: Visual? = null) {
        if (visual != null) lastVisual = visual
        unhide(ctx)
        val popped = pop(ctx, title, text)
        TraceLog.event("alert", "title" to title, "text" to text, "pop" to popped)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
            .setSmallIcon(if (metro) R.drawable.ic_metro else R.drawable.ic_bus)
            .setColor(if (metro) GREEN else BLUE)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(2 * 60_000L)
            .setContentIntent(if (locSettings) locationSettings(ctx) else openApp(ctx))
            // 已经用流体云卡片提醒过了：通知栏这条只留底，不再响/震第二次
            .setSilent(popped)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) { }
        buzz(ctx)
    }

    /**
     * 把流体云胶囊本身重发成「会提醒」的样子（高重要级渠道、不静音、不只提醒一次）：
     * ColorOS 对会提醒的实时通知直接弹出展开的卡片，不用下拉通知栏。下一次常规刷新（POP_HOLD_MS 之后）回到小胶囊。
     */
    @Synchronized
    private fun pop(ctx: Context, title: String, text: String): Boolean {
        val v = lastVisual ?: return false
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false
        val shown = v.copy(title = title, text = text, urgent = true)
        val n = build(ctx, shown, CHANNEL_POP)
        if (!notify(ctx, n)) return false
        appCtx = ctx.applicationContext
        gate.expanded(System.currentTimeMillis(), LiveGate.phaseOf(shown.short))
        logPosted(shown)
        return true
    }

    /**
     * 直接调马达再震一次：ColorOS 有时会把通知渠道的震动吞掉（渠道被改成静默、同一条通知反复更新等），
     * 「该走了」这种提醒不能只靠通知。两短一长，手机在静音模式下也会震（勿扰模式由系统决定）。
     */
    private fun buzz(ctx: Context) {
        runCatching {
            val v = if (android.os.Build.VERSION.SDK_INT >= 31)
                ctx.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(android.os.Vibrator::class.java)
            if (v == null || !v.hasVibrator()) return
            v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 200, 120, 200, 120, 450), -1))
        }
    }

    /** 重新显示胶囊（有提醒或打开 App 时） */
    fun unhide(ctx: Context) {
        if (!hidden) return
        hidden = false
        last?.let { update(ctx, it, lastLiveVisual) }
    }

    /** 后台启动被系统拦截时的兜底：普通提醒，点一下在前台启动 */
    fun fallback(ctx: Context, trip: Trip) {
        val pi = PendingIntent.getActivity(
            ctx, 7, Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_START_TRIP, trip.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_bus)
            .setColor(BLUE)
            .setContentTitle("${trip.name}：点一下开始")
            .setContentText("系统没让胶囊在后台自动启动，点这里启动")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ALERT_ID + 1, n)
        } catch (_: SecurityException) { }
    }

    private fun base(ctx: Context, v: Visual, channel: String = CHANNEL_LIVE, alerting: Boolean = true): NotificationCompat.Builder =
        NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(if (v.metro) R.drawable.ic_metro else R.drawable.ic_bus)
            .setColor(if (v.metro) GREEN else BLUE)
            .setContentTitle(v.title)
            .setContentText(v.text)
            .setSubText(v.sub.ifEmpty { null })
            .setShortCriticalText(v.short)
            .setOngoing(true)
            .setOnlyAlertOnce(channel != CHANNEL_POP || !alerting)
            .setSilent(channel != CHANNEL_POP || !alerting)
            .setRequestPromotedOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(openApp(ctx))
            // 划走不等于结束：先隐藏，下次提醒再出来；要结束请点「结束/关闭」
            .setDeleteIntent(actionIntent(ctx, ActionReceiver.ACTION_DISMISSED))
            .apply {
                if (CommuteService.undoActive()) addAction(0, if (CommuteService.undoAuto.value) "未上车" else "撤销上车", actionIntent(ctx, ActionReceiver.ACTION_BOARDED))
                else if (!v.riding) addAction(0, "已上车", actionIntent(ctx, ActionReceiver.ACTION_BOARDED))
            }
            .addAction(0, if (v.riding) "结束" else "关闭", actionIntent(ctx, ActionReceiver.ACTION_STOP))

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 系统定位设置页 */
    private fun locationSettings(ctx: Context) = PendingIntent.getActivity(
        ctx, 8, Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(ctx: Context, action: String) = PendingIntent.getBroadcast(
        ctx, action.hashCode(), Intent(ctx, ActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
