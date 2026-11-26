package com.busnotice.commute.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.busnotice.commute.Keepalive

/**
 * 设置检查的内容：「一次开启」、必需、手机厂商设置、可选、提示。
 * 首页底部弹层和设置页里的「设置检查」页共用。
 * [onBatch] 为 null 时不显示「一次开启」（前台定位、通知、身体活动都已授予）。
 */
@Composable
internal fun SetupChecks(perms: List<Perm>, onBatch: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onBatch != null) {
            Column(Modifier.fillMaxWidth()) {
                PillButton(onClick = onBatch, modifier = Modifier.fillMaxWidth()) { Text("一次开启", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(4.dp))
                Text("定位、通知、身体活动会依次弹出系统授权框；其余各项系统要求单独开启。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Group("必需", perms.filter { it.group == PermGroup.REQUIRED })
        Group("手机厂商设置（${Keepalive.brand().title}，App 读不到，设置后请勾选）", perms.filter { it.group == PermGroup.VENDOR })
        Group("可选", perms.filter { it.group == PermGroup.OPTIONAL })
        Group("提示", perms.filter { it.group == PermGroup.INFO })
        if (perms.any { it.group == PermGroup.VENDOR }) {
            Text("厂商设置页因系统版本而异；打不开时会转到应用详情，或提示在系统设置里搜索。勾选结果只存在本机。" +
                "这些设置能明显降低提醒变晚或消失的情况，但不能完全避免。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Group(title: String, items: List<Perm>) {
    if (items.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp))
    items.forEach { PermItem(it) }
}

@Composable
private fun PermItem(p: Perm) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.bodyLarge)
                Text(p.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!p.granted && p.note != null) {
                    Text(p.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                // 设置类提示（如系统备份）：直接给切换按钮
                p.id == PermId.BACKUP && p.action != null -> PillButton(secondary = true, onClick = p.action) { Text(p.actionLabel) }
                p.granted && p.group != PermGroup.VENDOR -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, tint = Fuse.BusGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (p.group == PermGroup.INFO) "正常" else "已开启", style = MaterialTheme.typography.labelLarge, color = Fuse.BusGreen)
                }
                p.group != PermGroup.VENDOR && p.action != null -> PillButton(onClick = p.action) { Text(p.actionLabel) }
            }
        }
        if (p.group == PermGroup.VENDOR) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.action != null) PillButton(secondary = p.granted, onClick = p.action) { Text(p.actionLabel) }
                if (p.toggle != null) GlassChip(selected = p.granted, onClick = p.toggle, label = if (p.granted) "已设置 ✓" else "我已设置")
            }
        }
    }
}

/** 设置页进入的「设置检查」整页 */
@Composable
fun SetupScreen(resumeTick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { version++ }
    val perms = remember(resumeTick, version) { permChecks(ctx, { p -> launcher.launch(p) }) { version++ } }
    val batch = remember(resumeTick, version) { batchPermissions(ctx) }
    GlassPage(title = "设置检查", onBack = onBack) {
        item {
            GlassCard {
                SetupChecks(perms, onBatch = if (batch.isNotEmpty()) ({ launcher.launch(batch) }) else null)
            }
        }
    }
}
