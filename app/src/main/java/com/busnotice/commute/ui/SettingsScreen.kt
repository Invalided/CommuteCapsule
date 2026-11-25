package com.busnotice.commute.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.busnotice.commute.Places
import com.busnotice.commute.Transit

/** 设置：家和公司、线路数据、后台保活体检、首次引导 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onKeepalive: (() -> Unit)? = null,
    onOnboarding: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    var sourceOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var resetVer by remember { mutableStateOf(0) }
    GlassPage(title = "设置", onBack = onBack) {
        sectionHeader("家和公司")
        item { androidx.compose.runtime.key(resetVer) { PlacesSetup() } }
        sectionHeader("线路数据")
        item {
            GlassRow(
                headline = "数据源",
                supporting = when {
                    Transit.hasExtraSource -> Transit.static.title
                    Transit.mode == Transit.DataMode.DEMO -> "演示数据（虚构城市）"
                    else -> "边用边学"
                },
                onClick = { sourceOpen = true },
            )
        }
        if (onKeepalive != null || onOnboarding != null) sectionHeader("后台与引导")
        if (onKeepalive != null) item {
            GlassRow(headline = "后台保活体检", supporting = "检查系统和手机厂商的后台设置", onClick = onKeepalive)
        }
        if (onOnboarding != null) item {
            GlassRow(headline = "重新打开首次引导", supporting = "从欢迎页开始，已有的行程和设置不受影响", onClick = onOnboarding)
        }
        sectionHeader("学习数据")
        item {
            GlassRow(headline = "重置自动学习的常去地点", supporting = "清空自动学到的家和公司，手动设置的保留",
                onClick = { confirmReset = true })
        }
    }
    if (sourceOpen) SourceDialog(dark) { sourceOpen = false }
    if (confirmReset) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReset = false },
            shape = DialogShape, containerColor = Fuse.surface(dark),
            titleContentColor = Fuse.ink(dark), textContentColor = Fuse.ink(dark),
            title = { androidx.compose.material3.Text("重置常去地点？") },
            text = { androidx.compose.material3.Text("自动学到的家和公司会清空并重新学习；手动设置的位置保留。") },
            confirmButton = {
                DialogButton("重置", primary = true, onClick = {
                    Places.reset(ctx); confirmReset = false; resetVer++
                    Toast.makeText(ctx, "已清空，会重新学习", Toast.LENGTH_SHORT).show()
                })
            },
            dismissButton = { DialogButton("取消", onClick = { confirmReset = false }) },
        )
    }
}
