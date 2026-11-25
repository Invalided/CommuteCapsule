package com.busnotice.commute

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 实时查询失败原因的分类，供实时通知区分无网络、网络未验证、代理或 VPN 与接口异常。
 */
object NetDiag {
    enum class Cause(val tag: String, val text: String) {
        OFFLINE("offline", "查询失败 · 无网络"),
        NOT_VALIDATED("unvalidated", "查询失败 · 网络不可用"),
        PROXY("proxy", "查询失败 · 可能是代理/VPN"),
        SERVER("server", "查询失败 · 接口异常"),
    }

    fun cause(ctx: Context): Cause = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return Cause.SERVER
        val net = cm.activeNetwork ?: return Cause.OFFLINE
        val caps = cm.getNetworkCapabilities(net) ?: return Cause.OFFLINE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Cause.OFFLINE
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        val httpProxy = cm.getLinkProperties(net)?.httpProxy != null ||
            !System.getProperty("http.proxyHost").isNullOrBlank() || !System.getProperty("https.proxyHost").isNullOrBlank()
        when {
            vpn || httpProxy -> Cause.PROXY
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> Cause.NOT_VALIDATED
            else -> Cause.SERVER
        }
    }.getOrDefault(Cause.SERVER)
}
