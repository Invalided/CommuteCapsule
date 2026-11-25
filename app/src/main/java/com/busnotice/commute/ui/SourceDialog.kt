@file:OptIn(ExperimentalLayoutApi::class)

package com.busnotice.commute.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.Transit

/** 「数据源」：看当前用的是哪个数据源、选择演示数据或边用边学；区域编号收在高级选项里 */
@Composable
fun SourceDialog(dark: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    var region by remember { mutableStateOf(Transit.region) }
    var mode by remember { mutableStateOf(Transit.mode) }
    var advanced by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
        title = { Text("数据源") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (Transit.hasExtraSource) {
                    Text("线路和站点：${Transit.static.title}", fontSize = 14.sp)
                    Text("实时到站：${Transit.realtime?.title ?: "无（用学到的时刻表和估算）"}", fontSize = 14.sp)
                } else {
                    DataModeChoice(mode) { mode = it }
                }
                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "收起高级选项" else "高级选项", color = dim)
                }
                if (advanced) {
                    GlassField(
                        region, { region = it }, label = "区域编号",
                        supportingText = { Text("仅供接入的数据源使用，一般不用填；换城市时按数据源说明修改") },
                    )
                }
            }
        },
        confirmButton = {
            DialogButton("保存", primary = true, onClick = {
                if (region.trim() != Transit.region) Transit.setRegion(ctx, region)
                if (!Transit.hasExtraSource && mode != Transit.mode) Transit.setMode(ctx, mode)
                onDismiss()
            })
        },
        dismissButton = { DialogButton("关闭", onClick = onDismiss) },
    )
}

/** 线路数据从哪来：演示数据 / 导入文件（即将支持）/ 边用边学。引导和数据源对话框共用 */
@Composable
fun DataModeChoice(mode: Transit.DataMode, onChange: (Transit.DataMode) -> Unit) {
    val dim = Fuse.dim(androidx.compose.foundation.isSystemInDarkTheme())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GlassChip(selected = mode == Transit.DataMode.LEARN, onClick = { onChange(Transit.DataMode.LEARN) }, label = "边用边学")
            GlassChip(selected = mode == Transit.DataMode.DEMO, onClick = { onChange(Transit.DataMode.DEMO) }, label = "演示数据")
            GlassChip(selected = false, onClick = {}, label = "导入线路文件 · 即将支持", enabled = false)
        }
        Text(
            when (mode) {
                Transit.DataMode.LEARN -> "不接任何数据源：线路名和站名直接记下（如「2号线」「105」），" +
                    "上车点位置和时刻靠你的行程记录逐步学习，全部只在本机。"
                Transit.DataMode.DEMO -> "内置的虚构「演示城市」，用来熟悉流程；搜不到你所在城市的线路和站点。"
            },
            fontSize = 12.sp, lineHeight = 17.sp, color = dim,
        )
    }
}
