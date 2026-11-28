package com.busnotice.commute.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.unit.sp
import com.busnotice.commute.Places
import com.busnotice.commute.Transit

/** 设置：家和公司、线路数据、设置检查、首次引导、系统备份、迁移、更新、学习数据 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChecks: (() -> Unit)? = null,
    onOnboarding: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
    onRoutes: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    var sourceOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var resetVer by remember { mutableStateOf(0) }
    var exportOpen by remember { mutableStateOf(false) }
    var undoOpen by remember { mutableStateOf(false) }
    val undoAt = remember(undoOpen) { com.busnotice.commute.MigrationStore.undoAt(ctx) }
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
        if (onRoutes != null) item {
            GlassRow(headline = "我的线路", supporting = "按站名新建线路、生成反向，导入或导出线路文件", onClick = onRoutes)
        }
        if (onChecks != null || onOnboarding != null) sectionHeader("权限与引导")
        if (onChecks != null) item {
            GlassRow(headline = "设置检查", supporting = "权限、电池与手机厂商的后台设置，与首页「设置检查」相同", onClick = onChecks)
        }
        if (onOnboarding != null) item {
            GlassRow(headline = "重看首页介绍", supporting = "回到首页重新逐项介绍，已有的行程和设置不受影响", onClick = onOnboarding)
        }
        sectionHeader("备份")
        item {
            var backupOn by remember { mutableStateOf(com.busnotice.commute.SystemBackup.enabled(ctx)) }
            GlassRow(
                headline = "参与系统备份",
                onClick = { backupOn = !backupOn; com.busnotice.commute.SystemBackup.setEnabled(ctx, backupOn) },
                supportingContent = {
                    androidx.compose.material3.Text(
                        "开启后，行程、设置和学习结果会随手机云备份和换机迁移恢复；原始定位记录不备份。关闭后，下一次系统备份起不再带走本 App 的数据。",
                        fontSize = 13.sp, lineHeight = 18.sp, color = Fuse.dim(dark),
                    )
                },
                trailing = {
                    androidx.compose.material3.Switch(checked = backupOn, onCheckedChange = {
                        backupOn = it; com.busnotice.commute.SystemBackup.setEnabled(ctx, it)
                    })
                },
            )
        }
        sectionHeader("迁移")
        item {
            GlassRow(headline = "迁移到新手机",
                supporting = "导出加密的迁移文件和恢复码：行程、设置、学习结果，可选包含行程轨迹和原始定位",
                onClick = { exportOpen = true })
        }
        if (onImport != null) item {
            GlassRow(headline = "从迁移文件恢复", supporting = "用旧手机导出的迁移文件和恢复码替换这台手机上的数据",
                onClick = onImport)
        }
        if (undoAt != null) item {
            GlassRow(headline = "撤销上次导入",
                supporting = "恢复到 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(undoAt)) +
                    " 导入之前的数据；导入后 7 天内可撤销一次",
                onClick = { undoOpen = true })
        }
        sectionHeader("更新")
        item {
            var preOn by remember { mutableStateOf(com.busnotice.commute.Updater.includePrerelease(ctx)) }
            GlassRow(
                headline = "接收测试版更新",
                supporting = "测试版可能不稳定",
                onClick = { preOn = !preOn; com.busnotice.commute.Updater.setIncludePrerelease(ctx, preOn) },
                trailing = {
                    androidx.compose.material3.Switch(checked = preOn, onCheckedChange = {
                        preOn = it; com.busnotice.commute.Updater.setIncludePrerelease(ctx, it)
                    })
                },
            )
        }
        sectionHeader("学习数据")
        item {
            GlassRow(headline = "重置自动学习的常去地点", supporting = "清空自动学到的家和公司，手动设置的保留",
                onClick = { confirmReset = true })
        }
    }
    if (sourceOpen) SourceDialog(dark) { sourceOpen = false }
    if (exportOpen) ExportMigrationDialog { exportOpen = false }
    if (undoOpen) UndoImportDialog { undoOpen = false }
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
