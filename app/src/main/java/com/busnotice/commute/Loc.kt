package com.busnotice.commute

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Loc {
    private val exec = Executors.newSingleThreadExecutor()

    /** 尽快拿一个当前位置：先用 8 秒内的新鲜定位，失败退回最近一次已知位置 */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Location? {
        val m = ctx.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
        val last = providers.mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 60_000) return last
        val p = providers.firstOrNull() ?: return last
        val fresh = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                try {
                    m.getCurrentLocation(p, null, exec) { cont.resume(it) }
                } catch (e: Exception) { cont.resume(null) }
            }
        }
        return fresh ?: last
    }

    // ---------- 界面按钮用：区分失败原因 ----------

    /**
     * 用户点按钮取当前位置：先检查权限和系统定位开关，再取位置；失败时给出原因。
     * 只供界面使用，服务和后台采样仍然调用 [current]，行为不变。
     */
    suspend fun currentForUser(ctx: Context): LocResult {
        val m = ctx.getSystemService(LocationManager::class.java)
        val hasFine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val enabled = m != null && runCatching { m.isLocationEnabled }.getOrDefault(false)
        val providers = enabledProviders(m)
        LocCheck.preflight(hasFine, hasCoarse, enabled, providers.isNotEmpty())?.let { return LocResult.Failed(it) }
        val l = try {
            fetch(m!!, providers, USER_TIMEOUT_MS)
        } catch (e: SecurityException) {
            // 检查和调用之间权限被撤销
            return LocResult.Failed(LocFailure.NO_PERMISSION)
        }
        return if (l != null) LocResult.Ok(l) else LocResult.Failed(LocFailure.UNAVAILABLE)
    }

    private const val USER_TIMEOUT_MS = 12_000L

    private fun enabledProviders(m: LocationManager?): List<String> {
        if (m == null) return emptyList()
        return listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
    }

    /** 1 分钟内的已知位置直接用；否则同时向所有可用的定位源要一次，取最先返回的，超时退回最近一次已知位置 */
    @SuppressLint("MissingPermission")
    private suspend fun fetch(m: LocationManager, providers: List<String>, timeoutMs: Long): Location? {
        val last = providers.mapNotNull { p ->
            try { m.getLastKnownLocation(p) } catch (e: SecurityException) { throw e } catch (e: Exception) { null }
        }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 60_000) return last
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                // 每个请求各用一个 CancellationSignal（一个 signal 只能挂一个取消监听）
                val signals = providers.map { CancellationSignal() }
                fun cancelAll() = signals.forEach { runCatching { it.cancel() } }
                cont.invokeOnCancellation { cancelAll() }
                val remaining = AtomicInteger(providers.size)
                val done = AtomicBoolean(false)
                providers.forEachIndexed { i, p ->
                    try {
                        m.getCurrentLocation(p, signals[i], exec) { l ->
                            if (l != null) {
                                if (done.compareAndSet(false, true)) { cancelAll(); cont.resume(l) }
                            } else if (remaining.decrementAndGet() == 0 && done.compareAndSet(false, true)) cont.resume(null)
                        }
                    } catch (e: SecurityException) {
                        if (done.compareAndSet(false, true)) { cancelAll(); cont.resumeWithException(e) }
                    } catch (e: Exception) {
                        if (remaining.decrementAndGet() == 0 && done.compareAndSet(false, true)) cont.resume(null)
                    }
                }
            }
        }
        return fresh ?: last
    }
}

/** 取当前位置失败的原因 */
enum class LocFailure {
    /** 没有定位权限（精确和粗略都没有） */
    NO_PERMISSION,
    /** 系统定位开关关闭，或没有可用的定位源 */
    LOCATION_OFF,
    /** 超时或拿不到位置（信号弱） */
    UNAVAILABLE,
}

sealed class LocResult {
    data class Ok(val location: Location) : LocResult()
    data class Failed(val reason: LocFailure) : LocResult()
}

/** 申请定位权限之后的结果 */
enum class LocPermOutcome { GRANTED, DENIED, DENIED_PERMANENTLY }

/** 定位失败原因的判定（不依赖系统接口，便于单元测试） */
object LocCheck {
    /** 取位置之前的检查：返回 null 表示可以开始定位 */
    fun preflight(hasFine: Boolean, hasCoarse: Boolean, locationEnabled: Boolean, anyProviderEnabled: Boolean): LocFailure? = when {
        !hasFine && !hasCoarse -> LocFailure.NO_PERMISSION
        !locationEnabled || !anyProviderEnabled -> LocFailure.LOCATION_OFF
        else -> null
    }

    /**
     * 权限申请返回后：精确或粗略任一授予即可定位；都被拒绝时，系统不再建议说明理由
     * （shouldShowRequestPermissionRationale 为 false）视为已被永久拒绝，只能到应用设置页开启。
     */
    fun afterRequest(fineGranted: Boolean, coarseGranted: Boolean, shouldShowRationale: Boolean): LocPermOutcome = when {
        fineGranted || coarseGranted -> LocPermOutcome.GRANTED
        shouldShowRationale -> LocPermOutcome.DENIED
        else -> LocPermOutcome.DENIED_PERMANENTLY
    }
}
