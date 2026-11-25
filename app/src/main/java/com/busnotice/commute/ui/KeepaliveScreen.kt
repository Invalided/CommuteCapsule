package com.busnotice.commute.ui

import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.Keepalive

/** 后台保活体检页 */
@Composable
fun KeepaliveScreen(resumeTick: Int, onBack: () -> Unit) {
    GlassPage(title = "后台保活体检", onBack = onBack) {
        item { KeepaliveChecks(resumeTick) }
    }
}

/**
 * 体检内容：系统能读到的项自动打勾；手机厂商的后台设置读不到，跳转过去设置后由用户勾「已设置」。
 * 引导页和体检页共用。
 */
@Composable
fun KeepaliveChecks(resumeTick: Int) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    var ver by remember { mutableStateOf(0) }
    val brand = remember { Keepalive.brand() }
    val checks = remember(resumeTick, ver) { runCatching { Keepalive.readable(ctx) }.getOrDefault(emptyList()) }
    val steps = remember(brand) { Keepalive.steps(brand) }

    fun go(targets: List<Keepalive.Target>, search: String) {
        Keepalive.open(ctx, targets, search)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("行程中 App 要在后台持续定位和计时。很多手机默认会冻结或清理后台应用，提醒就会变晚甚至消失。" +
            "下面的设置能明显降低这种情况，但不能完全避免。",
            fontSize = 13.sp, lineHeight = 18.sp, color = dim)
        SectionLabel("系统设置（自动检测）")
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                checks.forEach { c ->
                    CheckRow(c.title, c.desc, done = c.ok, readable = true,
                        onGo = { go(listOf(c.target), c.title) }, onToggle = null)
                }
            }
        }
        SectionLabel("${brand.title} 后台设置（App 读不到，设置后请勾选）")
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                steps.forEach { st ->
                    val done = remember(ver, st.id) { Keepalive.isDone(ctx, st.id) }
                    CheckRow(st.title, st.desc, done = done, readable = false,
                        onGo = if (st.targets.isNotEmpty()) ({ go(st.targets, st.search) }) else null,
                        onToggle = { Keepalive.setDone(ctx, st.id, !done); ver++ })
                }
            }
        }
        Text("跳转页面因系统版本而异；打不开时会转到应用详情，或提示在系统设置里搜索。勾选结果只存在本机。",
            fontSize = 12.sp, lineHeight = 17.sp, color = dim)
        Spacer(Modifier.height(2.dp))
        val (n, total) = remember(ver) { Keepalive.brandProgress(ctx, brand) }
        Text("厂商设置已完成 $n / $total", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink)
    }
}

@Composable
private fun CheckRow(title: String, desc: String, done: Boolean, readable: Boolean, onGo: (() -> Unit)?, onToggle: (() -> Unit)?) {
    val dark = isSystemInDarkTheme()
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Fuse.ink(dark), modifier = Modifier.weight(1f))
            if (done) {
                Icon(Icons.Default.Check, null, tint = Fuse.BusGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (readable) "已满足" else "已设置", fontSize = 13.sp, color = Fuse.BusGreen)
            }
        }
        Text(desc, fontSize = 12.sp, lineHeight = 17.sp, color = Fuse.dim(dark))
        if (onGo != null || onToggle != null) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onGo != null && !(readable && done)) PillButton(secondary = done, onClick = onGo) { Text("去设置") }
                if (onToggle != null) GlassChip(selected = done, onClick = onToggle, label = if (done) "已设置 ✓" else "我已设置")
            }
        }
    }
}
