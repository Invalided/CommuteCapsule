package com.busnotice.commute.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
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

/** 「数据源」：看当前用的是哪个数据源、改区域编号 */
@Composable
fun SourceDialog(dark: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val ink = Fuse.ink(dark)
    var region by remember { mutableStateOf(Transit.region) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
        title = { Text("数据源") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("线路和站点：${Transit.static.title}", fontSize = 14.sp)
                Text("实时到站：${Transit.realtime?.title ?: "无（用学到的时刻表和估算）"}", fontSize = 14.sp)
                GlassField(
                    region, { region = it }, label = "区域编号",
                    supportingText = { Text("数据源自己的城市编号，换城市时才需要改") },
                )
            }
        },
        confirmButton = {
            DialogButton("保存", primary = true, onClick = {
                if (region.trim() != Transit.region) Transit.setRegion(ctx, region)
                onDismiss()
            })
        },
        dismissButton = { DialogButton("关闭", onClick = onDismiss) },
    )
}
