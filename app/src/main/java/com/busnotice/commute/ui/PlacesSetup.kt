package com.busnotice.commute.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import com.busnotice.commute.Places

/**
 * 家和公司：可以用当前位置手动设置，也可以交给 App 自己学（约 3 天）。
 * 手动设置优先，自动学习不会覆盖；清除手动设置后改用自动学到的结果。
 */
@Composable
fun PlacesSetup(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    var ver by remember { mutableStateOf(0) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlaceRow(Places.Kind.HOME, "家", ver) { ver++ }
        PlaceRow(Places.Kind.WORK, "公司", ver) { ver++ }
        val locOk = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        Text(
            (if (locOk) "" else "用当前位置设置需要先允许定位。") +
                "手动设置的位置优先使用，自动学习不会覆盖它；不设置的话 App 会在约 3 天内自己学会。位置只存在本机。",
            fontSize = 12.sp, lineHeight = 17.sp, color = Fuse.dim(dark),
        )
    }
}

@Composable
private fun PlaceRow(kind: Places.Kind, name: String, ver: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val locate = rememberLocateAction { l ->
        if (l.accuracy > 200f) Toast.makeText(ctx, "定位误差较大（约 ${l.accuracy.toInt()} 米），稍后再试", Toast.LENGTH_SHORT).show()
        else {
            Places.setManual(ctx, kind, l.latitude, l.longitude)
            Toast.makeText(ctx, "已把当前位置设为$name", Toast.LENGTH_SHORT).show()
            onChanged()
        }
    }
    val manual = remember(ver) { Places.manual(ctx, kind) != null }
    val learned = remember(ver) { runCatching { Places.learned(ctx, kind) != null }.getOrDefault(false) }
    val progress = remember(ver) { runCatching { Places.progressOf(ctx, kind) }.getOrDefault(0) }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Fuse.ink(dark))
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    manual -> "已手动设置"
                    learned -> "已自动学到"
                    else -> "学习中 $progress/3"
                },
                fontSize = 13.sp, color = if (manual || learned) Fuse.BusGreen else Fuse.dim(dark),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(secondary = manual, onClick = { locate.start() }) {
                Text(if (locate.busy) "定位中…" else if (manual) "重新设为当前位置" else "我现在在$name")
            }
            if (manual) PillButton(secondary = true, onClick = { Places.clearManual(ctx, kind); onChanged() }) {
                Text("改为自动学习")
            }
        }
    }
}
