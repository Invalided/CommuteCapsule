package com.busnotice.commute

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.time.LocalDate
import java.time.LocalTime

/**
 * 没打开 App、也不在计划时间窗里，但人到了上车站附近：
 * 用系统自带的「接近提醒」（不依赖谷歌服务）在第一段上车站周围画围栏，
 * 进入时如果正好是这趟行程平时的出行时段，就自动开始一趟全新的行程（不接中断前的进度）。
 */
object GeoWatch {
    private const val TAG = "GeoWatch"
    const val EXTRA_TRIP = "tripId"
    const val EXTRA_IDX = "idx"
    private const val RADIUS_M = 200f
    /** 上车站距已确认的家/公司小于此距离：围栏可能在家/公司内被触发，须先取当前定位确认已离开 */
    const val NEAR_PLACE_M = 450.0
    /** 确认定位的最长等待时间（毫秒） */
    private const val CONFIRM_TIMEOUT_MS = 20_000L
    /** 用于确认的定位精度上限（米） */
    private const val CONFIRM_MAX_ACC = 150f

    private fun can(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pi(ctx: Context, tripId: String, idx: Int, create: Boolean): PendingIntent? =
        PendingIntent.getBroadcast(
            ctx, (tripId + "#geo" + idx).hashCode(),
            Intent(ctx, GeoReceiver::class.java).putExtra(EXTRA_TRIP, tripId).putExtra(EXTRA_IDX, idx),
            // 系统要往里塞「进入/离开」标记，必须可变
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE or
                (if (create) 0 else PendingIntent.FLAG_NO_CREATE),
        )

    fun register(ctx: Context, trip: Trip) {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        unregister(ctx, trip.id)
        if (!trip.enabled || trip.dayMask == 0 || !can(ctx)) return
        val stops = stops(trip)
        stops.forEachIndexed { i, c ->
            try {
                lm.addProximityAlert(c.lat, c.lng, RADIUS_M, -1, pi(ctx, trip.id, i, true)!!)
            } catch (e: SecurityException) { Log.w(TAG, "没有定位权限", e) }
        }
    }

    private fun stops(trip: Trip) = trip.legs.firstOrNull()?.candidates.orEmpty()
        .filter { !it.lat.isNaN() && !it.lng.isNaN() }
        .distinctBy { "%.4f,%.4f".format(it.lat, it.lng) }.take(3)

    /** 触发的上车站是否靠近已确认的家/公司（idx 未知时检查全部上车站） */
    fun stopNearPlace(ctx: Context, trip: Trip, idx: Int): Boolean {
        val all = stops(trip)
        val list = all.getOrNull(idx)?.let { listOf(it) } ?: all
        return list.any { c -> Places.distanceToConfirmedM(ctx, c.lat, c.lng)?.let { it < NEAR_PLACE_M } == true }
    }

    /**
     * 取一次当前定位（网络与卫星并行，最多等 CONFIRM_TIMEOUT_MS），判断是否已离开家/公司。
     * 拿不到足够精确的定位时按「未离开」处理：靠近家/公司的围栏宁可不拉起，交由离开出发地检测启动。
     */
    fun confirmAway(ctx: Context, done: (away: Boolean, fix: Location?) -> Unit) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (lm == null || !can(ctx)) { done(false, null); return }
        val main = Handler(Looper.getMainLooper())
        val signal = CancellationSignal()
        var best: Location? = null
        var pending = 0
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            signal.cancel()
            val f = best?.takeIf { it.accuracy <= CONFIRM_MAX_ACC }
            val label = f?.let { Places.classify(ctx, it.latitude, it.longitude, it.accuracy) }
            done(f != null && label != Places.Label.HOME && label != Places.Label.WORK, f)
        }
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) { done(false, null); return }
        pending = providers.size
        providers.forEach { pr ->
            try {
                lm.getCurrentLocation(pr, signal, ctx.mainExecutor) { l ->
                    if (l != null && (best == null || l.accuracy < best!!.accuracy)) best = l
                    pending--
                    if (pending <= 0 || (l != null && l.accuracy <= 50f)) finish()
                }
            } catch (e: SecurityException) { pending-- }
        }
        if (pending <= 0) finish() else main.postDelayed({ finish() }, CONFIRM_TIMEOUT_MS)
    }

    fun unregister(ctx: Context, tripId: String) {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        for (i in 0 until 3) pi(ctx, tripId, i, false)?.let { runCatching { lm.removeProximityAlert(it) }; it.cancel() }
    }

    /** 误启动后撤销「今天围栏已拉起过」 */
    fun unmarkToday(ctx: Context, tripId: String) {
        ctx.getSharedPreferences("geo", Context.MODE_PRIVATE).edit().remove(tripId).apply()
    }

    /** 现在算不算这趟行程的出行时段：学到的通常上车时间 ±60 分钟，没学到就用时间窗前后各放 60 分钟 */
    fun inHabitBand(ctx: Context, trip: Trip): Boolean {
        val today = LocalDate.now()
        if (trip.dayMask and (1 shl (today.dayOfWeek.value - 1)) == 0) return false
        val t = LocalTime.now(); val m = t.hour * 60 + t.minute
        val typical = Learn.typicalBoard(ctx, trip.id, today.dayOfWeek.value)
        return if (typical != null) kotlin.math.abs(m - typical) <= 60
        else m >= trip.startMinute - 60 && m <= trip.endMinute + 60
    }
}

class GeoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)) return
        val id = intent.getStringExtra(GeoWatch.EXTRA_TRIP) ?: return
        val trip = TripStore.get(ctx, id) ?: return
        if (CommuteService.instance != null || !trip.enabled) return
        val p = ctx.getSharedPreferences("geo", Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (p.getString(id, null) == today) return           // 同一趟一天只自动拉起一次
        if (!GeoWatch.inHabitBand(ctx, trip)) return
        val idx = intent.getIntExtra(GeoWatch.EXTRA_IDX, -1)
        if (!GeoWatch.stopNearPlace(ctx, trip, idx)) { launch(ctx, trip); return }
        // 上车站靠近家/公司：先确认人已离开，避免在室内因定位漂移误拉起
        val result = goAsync()
        GeoWatch.confirmAway(ctx) { away, fix ->
            try {
                if (away && CommuteService.instance == null && p.getString(id, null) != today) launch(ctx, trip)
                else if (!away) Diag.mark(ctx, "上车站附近围栏触发，但当前仍在家/公司或定位不足（精度 ${fix?.accuracy?.toInt() ?: -1} 米），不自动开始")
            } finally { result.finish() }
        }
    }

    private fun launch(ctx: Context, trip: Trip) {
        ctx.getSharedPreferences("geo", Context.MODE_PRIVATE).edit().putString(trip.id, LocalDate.now().toString()).apply()
        Diag.mark(ctx, "到了上车站附近，自动开始「${trip.name}」")
        try {
            CommuteService.start(ctx, trip.id, manual = false, fresh = true)
        } catch (e: Exception) {
            Diag.mark(ctx, "系统不让后台启动：${e.javaClass.simpleName}")
            LiveNotifier.fallback(ctx, trip)
        }
    }
}
