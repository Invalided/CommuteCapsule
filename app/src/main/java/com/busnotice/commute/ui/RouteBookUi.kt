package com.busnotice.commute.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.Candidate
import com.busnotice.commute.RouteDef
import com.busnotice.commute.RouteFile
import com.busnotice.commute.RouteMerge
import com.busnotice.commute.RouteSchedule
import com.busnotice.commute.RouteStore
import com.busnotice.commute.RouteText
import com.busnotice.commute.RouteTiming

/** 新建线路页的草稿；预览页返回时据此恢复填写内容 */
private data class RouteDraft(
    val defaultName: String,
    val name: String = "",
    val mode: Candidate.Type = Candidate.Type.BUS,
    val ends: Boolean = false,
    val raw: String = "",
    val endA: String = "",
    val endB: String = "",
    val reverse: Boolean = true,
    val firstMin: Int = -1,
    val lastMin: Int = -1,
    val peak: Int = 0,
    val offPeak: Int = 0,
    val departures: List<Int> = emptyList(),
) {
    /** 未填写名称时使用默认名称（输入框中以占位文字显示） */
    fun effectiveName(): String = name.trim().ifEmpty { defaultName }

    fun stops(): List<String> = if (ends) listOf(endA.trim(), endB.trim()).filter { it.isNotEmpty() } else RouteText.parse(raw).stops

    fun isBlank() = raw.isBlank() && endA.isBlank() && endB.isBlank() && firstMin < 0 && lastMin < 0 && peak == 0 && offPeak == 0 && departures.isEmpty()

    fun build(): List<RouteDef> {
        val sch = if (mode == Candidate.Type.SHUTTLE) RouteSchedule(departures = departures)
            else RouteSchedule(firstMin, lastMin, peak, offPeak)
        val r = RouteDef(name = effectiveName(), direction = if (reverse) "去程" else "", mode = mode,
            stopPattern = if (ends) RouteDef.StopPattern.ENDS else RouteDef.StopPattern.ALL, stops = stops(), schedule = sch)
        return if (reverse) listOf(r, r.reversed()) else listOf(r)
    }
}

private sealed interface RouteStep {
    object List : RouteStep
    object New : RouteStep
    class Preview(val routes: kotlin.collections.List<RouteDef>) : RouteStep
    object Files : RouteStep
}

private val MODE_LABELS = listOf(Candidate.Type.BUS to "公交", Candidate.Type.METRO to "地铁", Candidate.Type.SHUTTLE to "班车")

/** 默认线路名称：「线路 N」，N 取未被占用的最小序号 */
private fun defaultName(existing: List<RouteDef>): String {
    var n = 1
    while (existing.any { it.name == "线路 $n" }) n++
    return "线路 $n"
}

/** 我的线路：新建（按站名、可生成反向线路）、预览与编辑、线路文件导入导出 */
@Composable
fun RouteBookScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableStateOf<RouteStep>(RouteStep.List) }
    var ver by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf<RouteDraft?>(null) }
    var confirm by remember { mutableStateOf<ConfirmKind?>(null) }
    var previewDirty by remember { mutableStateOf(false) }

    fun backFromNew() { if (draft?.isBlank() == false) confirm = ConfirmKind.DISCARD_NEW else { draft = null; step = RouteStep.List } }
    fun backFromPreview() { if (previewDirty) confirm = ConfirmKind.LEAVE_PREVIEW else step = RouteStep.New }

    BackHandler {
        when (step) {
            RouteStep.List -> onBack()
            RouteStep.New -> backFromNew()
            is RouteStep.Preview -> backFromPreview()
            RouteStep.Files -> step = RouteStep.List
        }
    }
    when (val s = step) {
        RouteStep.List -> RouteListPage(ver, onBack,
            onNew = { draft = RouteDraft(defaultName = defaultName(RouteStore.all(ctx))); step = RouteStep.New },
            onFiles = { step = RouteStep.Files }, onChanged = { ver++ })
        RouteStep.New -> RouteNewPage(draft ?: RouteDraft(defaultName = defaultName(RouteStore.all(ctx))), onChange = { draft = it },
            onBack = { backFromNew() }, onNext = { previewDirty = false; step = RouteStep.Preview(it) })
        is RouteStep.Preview -> RoutePreviewPage(s.routes,
            onBack = { backFromPreview() },
            onEdited = { routes, renamed ->
                if (renamed == null) previewDirty = true
                if (renamed != null) draft = draft?.copy(name = renamed)
                step = RouteStep.Preview(routes)
            },
            onSaved = { ver++; draft = null; step = RouteStep.List })
        RouteStep.Files -> RouteFilesPage(onBack = { step = RouteStep.List }, onImported = { ver++ })
    }
    when (confirm) {
        ConfirmKind.DISCARD_NEW -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("放弃新建线路？") },
            text = { Text("已填写的内容尚未保存，退出后将丢失。") },
            confirmButton = { DialogButton("退出", danger = true, onClick = { confirm = null; draft = null; step = RouteStep.List }) },
            dismissButton = { DialogButton("继续编辑", primary = true, onClick = { confirm = null }) },
        )
        ConfirmKind.LEAVE_PREVIEW -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("返回编辑？") },
            text = { Text("预览中对站点的修改尚未保存，返回编辑后将不再保留。") },
            confirmButton = { DialogButton("返回编辑", danger = true, onClick = { confirm = null; previewDirty = false; step = RouteStep.New }) },
            dismissButton = { DialogButton("取消", primary = true, onClick = { confirm = null }) },
        )
        null -> Unit
    }
}

private enum class ConfirmKind { DISCARD_NEW, LEAVE_PREVIEW }

@Composable
private fun Hint(text: String) =
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp), color = LocalContentColor.current.copy(alpha = 0.65f))

private fun scheduleText(r: RouteDef): String {
    val s = r.schedule
    return when {
        s.departures.isNotEmpty() -> "固定发车 ${s.departures.size} 班"
        s.peakHeadwayMin > 0 -> "高峰每 ${s.peakHeadwayMin} 分钟一班"
        RouteTiming.peak(r) > 0 -> "发车间隔按默认值估算"
        else -> "时刻依据乘车记录学习"
    }
}

@Composable
private fun RouteListPage(ver: Int, onBack: () -> Unit, onNew: () -> Unit, onFiles: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val routes = remember(ver) { RouteStore.all(ctx) }
    var opened by remember { mutableStateOf<RouteDef?>(null) }
    GlassPage(title = "我的线路", onBack = onBack) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(onClick = onNew, modifier = Modifier.weight(1f)) { Text("新建线路") }
                PillButton(onClick = onFiles, secondary = true, modifier = Modifier.weight(1f)) { Text("线路文件") }
            }
        }
        if (routes.isEmpty()) item { Hint("暂无线路。新建线路时按乘车顺序填写站名即可，无需坐标；站点位置将在乘车过程中自动学习。") }
        glassGroup(routes, key = "routes") { r ->
            GlassRow(
                headline = r.fullName(),
                supporting = listOfNotNull(
                    MODE_LABELS.first { it.first == r.mode }.second, "${r.stops.size} 个站点",
                    "只停起终点".takeIf { r.stopPattern == RouteDef.StopPattern.ENDS }, scheduleText(r),
                ).joinToString(" · "),
                onClick = { opened = r },
            )
        }
    }
    opened?.let { r ->
        AlertDialog(
            onDismissRequest = { opened = null },
            title = { Text(r.fullName()) },
            text = { Text(r.stops.joinToString(" → ")) },
            confirmButton = { DialogButton("关闭", primary = true, onClick = { opened = null }) },
            dismissButton = { DialogButton("删除线路", danger = true, onClick = { RouteStore.delete(ctx, r.id); opened = null; onChanged() }) },
        )
    }
}

/** 时刻选择框：标签在上，框内显示数值；未设置时显示占位符，已设置时框内右侧提供清除按钮 */
@Composable
private fun PickBox(label: String, text: String?, placeholder: String, onClick: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    Column(modifier) {
        Text(label, fontSize = 13.sp, color = Fuse.dim(dark))
        Row(
            Modifier.padding(top = 6.dp).fillMaxWidth().height(48.dp).clip(FieldShape)
                .background(Color.White.copy(alpha = if (dark) 0.08f else 0.55f))
                .border(1.dp, if (dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.08f), FieldShape)
                .clickable(onClick = onClick).padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text ?: placeholder, fontSize = 17.sp, maxLines = 1, softWrap = false,
                fontWeight = if (text != null) FontWeight.SemiBold else FontWeight.Normal,
                color = if (text != null) ink else Fuse.dim(dark).copy(alpha = 0.6f),
                style = TnumStyle, modifier = Modifier.weight(1f))
            if (text != null) Box(Modifier.size(40.dp).clickable(onClick = onClear), contentAlignment = Alignment.Center) {
                Text("×", fontSize = 18.sp, color = Fuse.dim(dark))
            }
        }
    }
}

private enum class PickKind { FIRST, LAST, PEAK, OFF_PEAK, DEPARTURE }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteNewPage(d: RouteDraft, onChange: (RouteDraft) -> Unit, onBack: () -> Unit, onNext: (List<RouteDef>) -> Unit) {
    val dark = isSystemInDarkTheme()
    val parsed = remember(d.raw) { RouteText.parse(d.raw) }
    val stops = d.stops()
    val ok = stops.size >= 2
    var pick by remember { mutableStateOf<PickKind?>(null) }

    GlassPage(title = "新建线路", onBack = onBack) {
        item {
            GlassCard {
                SectionLabel("线路名称")
                GlassField(d.name, { onChange(d.copy(name = it)) }, placeholder = d.defaultName,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                SectionLabel("交通方式", Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    MODE_LABELS.forEach { (t, l) -> GlassChip(d.mode == t, { onChange(d.copy(mode = t)) }, l, Modifier.weight(1f)) }
                }
                SectionLabel("停站方式", Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    GlassChip(!d.ends, { onChange(d.copy(ends = false)) }, "站站停", Modifier.weight(1f))
                    GlassChip(d.ends, {
                        onChange(d.copy(ends = true,
                            endA = d.endA.ifEmpty { parsed.stops.firstOrNull().orEmpty() },
                            endB = d.endB.ifEmpty { parsed.stops.lastOrNull().orEmpty() }))
                    }, "只停起终点", Modifier.weight(1f))
                }
            }
        }
        item {
            GlassCard {
                if (d.ends) {
                    Text("起点站与终点站", fontWeight = FontWeight.SemiBold)
                    GlassField(d.endA, { onChange(d.copy(endA = it)) }, label = "起点站", modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    GlassField(d.endB, { onChange(d.copy(endB = it)) }, label = "终点站", modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Hint("适用于途中不停靠的车辆。系统按全程用时学习，途经站点不作为异常判断依据。")
                } else {
                    Text("站点（按乘车顺序，每行一个）", fontWeight = FontWeight.SemiBold)
                    GlassField(d.raw, { onChange(d.copy(raw = it)) }, singleLine = false, placeholder = "起点站\n第二站\n…\n终点站",
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (parsed.stops.isNotEmpty()) {
                        val cleaned = listOfNotNull(
                            parsed.droppedBlank.takeIf { it > 0 }?.let { "$it 个空行" },
                            parsed.strippedNumbers.takeIf { it > 0 }?.let { "$it 处序号" },
                        )
                        Text("已识别 ${parsed.stops.size} 个站点" + if (cleaned.isEmpty()) "" else "（已去除" + cleaned.joinToString("、") + "）",
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    }
                    Hint("支持直接粘贴站名列表，序号与空行将自动去除。无需填写坐标，站点位置将在乘车过程中自动学习。")
                }
            }
        }
        item {
            GlassCard {
                Text("时刻（选填）", fontWeight = FontWeight.SemiBold)
                if (d.mode == Candidate.Type.SHUTTLE) {
                    Text("发车时刻", fontSize = 14.sp, color = Fuse.dim(dark), modifier = Modifier.padding(top = 10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp)) {
                        d.departures.forEach { m ->
                            GlassChip(true, { onChange(d.copy(departures = d.departures - m)) }, RouteText.hhmm(m) + " ×")
                        }
                        GlassChip(false, { pick = PickKind.DEPARTURE }, "添加时刻")
                    }
                    Hint("填写始发站的发车时刻，点击已添加的时刻可将其移除。未填写时仅依据乘车记录学习。")
                } else {
                    Row(Modifier.padding(top = 10.dp)) {
                        PickBox("首班", d.firstMin.takeIf { it >= 0 }?.let(RouteText::hhmm), "--:--", { pick = PickKind.FIRST },
                            { onChange(d.copy(firstMin = -1)) }, Modifier.weight(1f).padding(end = 6.dp))
                        PickBox("末班", d.lastMin.takeIf { it >= 0 }?.let(RouteText::hhmm), "--:--", { pick = PickKind.LAST },
                            { onChange(d.copy(lastMin = -1)) }, Modifier.weight(1f).padding(start = 6.dp))
                    }
                    Row(Modifier.padding(top = 12.dp)) {
                        PickBox("高峰间隔", d.peak.takeIf { it > 0 }?.let { "$it 分钟" }, "默认 ${RouteTiming.defaultPeak(d.mode)} 分钟", { pick = PickKind.PEAK },
                            { onChange(d.copy(peak = 0)) }, Modifier.weight(1f).padding(end = 6.dp))
                        PickBox("平峰间隔", d.offPeak.takeIf { it > 0 }?.let { "$it 分钟" }, "默认 ${(RouteTiming.defaultPeak(d.mode) * 3 + 1) / 2} 分钟", { pick = PickKind.OFF_PEAK },
                            { onChange(d.copy(offPeak = 0)) }, Modifier.weight(1f).padding(start = 6.dp))
                    }
                                        Hint("未设置时按默认值估算，已设置的以设置值为准。")
                }
            }
        }
        item {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("同时生成反向线路", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(checked = d.reverse, onCheckedChange = { onChange(d.copy(reverse = it)) })
                }
            }
        }
        item {
            PillButton(enabled = ok, modifier = Modifier.fillMaxWidth(), onClick = { onNext(d.build()) }) { Text("下一步：预览") }
            if (!ok) Hint("请至少填写 2 个站点。")
        }
    }

    when (val k = pick) {
        PickKind.FIRST, PickKind.LAST, PickKind.DEPARTURE -> TimeWheelSheet(
            title = when (k) { PickKind.FIRST -> "首班时间"; PickKind.LAST -> "末班时间"; else -> "发车时刻" },
            initialMinute = when (k) {
                PickKind.FIRST -> d.firstMin.takeIf { it >= 0 } ?: (6 * 60)
                PickKind.LAST -> d.lastMin.takeIf { it >= 0 } ?: (22 * 60)
                else -> d.departures.lastOrNull()?.let { (it + 20).coerceAtMost(23 * 60 + 59) } ?: (7 * 60)
            },
            onDismiss = { pick = null },
            onConfirm = { m ->
                onChange(when (k) {
                    PickKind.FIRST -> d.copy(firstMin = m)
                    PickKind.LAST -> d.copy(lastMin = m)
                    else -> d.copy(departures = (d.departures + m).distinct().sorted())
                })
                pick = null
            },
        )
        PickKind.PEAK, PickKind.OFF_PEAK -> {
            val def = RouteTiming.defaultPeak(d.mode).coerceAtLeast(1)
            NumberWheelSheet(
                title = if (k == PickKind.PEAK) "高峰间隔" else "平峰间隔",
                initial = if (k == PickKind.PEAK) d.peak.takeIf { it > 0 } ?: def else d.offPeak.takeIf { it > 0 } ?: ((def * 3 + 1) / 2),
                range = 1..60, unit = "分钟",
                onDismiss = { pick = null },
                onConfirm = { v -> onChange(if (k == PickKind.PEAK) d.copy(peak = v) else d.copy(offPeak = v)); pick = null },
            )
        }
        null -> Unit
    }
}

@Composable
private fun RoutePreviewPage(routes: List<RouteDef>, onBack: () -> Unit, onEdited: (List<RouteDef>, String?) -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) } // 线路序号、站点序号（-1 表示添加站点）
    var renaming by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    GlassPage(title = "预览", onBack = onBack) {
        item { Text("将保存 ${routes.size} 条线路", fontWeight = FontWeight.SemiBold) }
        routes.forEachIndexed { ri, r ->
            item(key = r.id) {
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { renaming = true; text = r.name }) {
                        Text(r.fullName(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text("重命名", fontSize = 13.sp, color = Fuse.dim(dark))
                    }
                    r.stops.forEachIndexed { si, st ->
                        val passOnly = r.stopPattern == RouteDef.StopPattern.ENDS && si in 1 until r.stops.lastIndex
                        Row(Modifier.fillMaxWidth().clickable { editing = ri to si; text = st }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("●", fontSize = 10.sp, modifier = Modifier.width(20.dp))
                            Text(st, modifier = Modifier.weight(1f),
                                fontWeight = if (si == 0 || si == r.stops.lastIndex) FontWeight.SemiBold else FontWeight.Normal)
                            Text(if (passOnly) "途经不停靠" else "编辑", fontSize = 13.sp, color = Fuse.dim(dark))
                        }
                    }
                    Text("添加站点", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { editing = ri to -1; text = "" }.padding(top = 4.dp))
                    Hint(scheduleText(r))
                }
            }
        }
        item { Hint("去程与返程可分别编辑，适用于往返停靠站点不同的线路。") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(onClick = onBack, secondary = true, modifier = Modifier.weight(1f)) { Text("返回编辑") }
                PillButton(onClick = {
                    RouteStore.save(ctx, RouteMerge.merge(RouteStore.all(ctx), routes, RouteMerge.Conflict.KEEP_BOTH))
                    Toast.makeText(ctx, "已保存 ${routes.size} 条线路", Toast.LENGTH_SHORT).show()
                    onSaved()
                }, enabled = routes.all { it.stops.size >= 2 }, modifier = Modifier.weight(1f)) { Text("保存") }
            }
        }
    }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("线路名称") },
            text = {
                Column {
                    GlassField(text, { text = it }, label = "线路名称", modifier = Modifier.fillMaxWidth())
                    if (routes.size > 1) Hint("去程与返程将同步更新。")
                }
            },
            confirmButton = {
                DialogButton("确定", enabled = text.isNotBlank(), primary = true, onClick = {
                    val n = text.trim()
                    renaming = false
                    onEdited(routes.map { it.copy(name = n) }, n)
                })
            },
            dismissButton = { DialogButton("取消", onClick = { renaming = false }) },
        )
    }
    editing?.let { (ri, si) ->
        val r = routes[ri]
        fun update(newStops: List<String>) {
            editing = null
            onEdited(routes.toMutableList().also { it[ri] = r.copy(stops = newStops) }, null)
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (si < 0) "添加站点" else "编辑站点") },
            text = {
                Column {
                    GlassField(text, { text = it }, label = "站点名称", modifier = Modifier.fillMaxWidth())
                    if (si < 0) Hint("新站点将添加在终点站之前。")
                    else if (r.stops.size <= 2) Hint("线路至少需要 2 个站点，当前站点不可删除。")
                }
            },
            confirmButton = {
                DialogButton("确定", enabled = text.isNotBlank(), primary = true, onClick = {
                    val l = r.stops.toMutableList()
                    if (si < 0) l.add(maxOf(l.size - 1, 0), text.trim()) else l[si] = text.trim()
                    update(l)
                })
            },
            dismissButton = {
                if (si >= 0) DialogButton("删除站点", danger = true, enabled = r.stops.size > 2,
                    onClick = { update(r.stops.filterIndexed { i, _ -> i != si }) })
                else DialogButton("取消", onClick = { editing = null })
            },
        )
    }
}

@Composable
private fun RouteFilesPage(onBack: () -> Unit, onImported: () -> Unit) {
    val ctx = LocalContext.current
    var ver by remember { mutableIntStateOf(0) }
    val routes = remember(ver) { RouteStore.all(ctx) }
    var selected by remember(ver) { mutableStateOf(routes.map { it.id }.toSet()) }
    var incoming by remember { mutableStateOf<List<RouteDef>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var policy by remember { mutableStateOf(RouteMerge.Conflict.KEEP_BOTH) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = RouteFile.write(routes.filter { it.id in selected })
        val ok = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
        Toast.makeText(ctx, if (ok) "已导出 ${selected.size} 条线路" else "导出失败", Toast.LENGTH_SHORT).show()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        error = null; incoming = null
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { s -> s.readBytes().takeIf { it.size <= 1_000_000 }?.toString(Charsets.UTF_8) }
        }.getOrNull()
        if (text == null) { error = "无法读取该文件，或文件超过 1 MB。"; return@rememberLauncherForActivityResult }
        try { incoming = RouteFile.read(text) } catch (e: RouteFile.FormatError) { error = "文件格式有误（第 ${e.line} 行）：${e.reason}。已有线路未作更改。" }
    }

    GlassPage(title = "线路文件", onBack = onBack) {
        item {
            GlassCard {
                Text("导出", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                if (routes.isEmpty()) Hint("暂无可导出的线路。")
                else {
                    Hint("选择要导出的线路（默认全选）")
                    routes.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                            .clickable { selected = if (r.id in selected) selected - r.id else selected + r.id }) {
                            Checkbox(checked = r.id in selected, onCheckedChange = { selected = if (it) selected + r.id else selected - r.id })
                            Text(r.fullName())
                        }
                    }
                    Hint("已选 ${selected.size} 条，不含行程记录与位置信息")
                    PillButton(onClick = { exportLauncher.launch("线路.txt") }, enabled = selected.isNotEmpty(), secondary = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("导出线路文件") }
                }
            }
        }
        item {
            GlassCard {
                Text("导入", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                val inc = incoming
                if (inc == null) {
                    PillButton(onClick = { importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
                        secondary = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("选择线路文件") }
                    error?.let { Text(it, fontSize = 13.sp, color = DangerRed, modifier = Modifier.padding(top = 6.dp)) }
                } else {
                    Hint("文件包含 ${inc.size} 条线路")
                    inc.forEach { r ->
                        val same = RouteMerge.status(routes, r) == RouteMerge.Status.SAME_NAME
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(r.fullName(), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("${r.stops.size} 个站点", modifier = Modifier.padding(horizontal = 8.dp))
                            Text(if (same) "同名已存在" else "新增", fontWeight = FontWeight.SemiBold,
                                color = if (same) Color(0xFFD9822B) else Color(0xFF2E9E5B))
                        }
                    }
                    if (inc.any { RouteMerge.status(routes, it) == RouteMerge.Status.SAME_NAME }) {
                        SectionLabel("同名线路处理方式", Modifier.padding(top = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            GlassChip(policy == RouteMerge.Conflict.KEEP_BOTH, { policy = RouteMerge.Conflict.KEEP_BOTH }, "保留两份", Modifier.weight(1f))
                            GlassChip(policy == RouteMerge.Conflict.REPLACE, { policy = RouteMerge.Conflict.REPLACE }, "以文件内容替换", Modifier.weight(1f))
                        }
                        Hint("替换仅更新站点、交通方式与时刻。")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
                        PillButton(onClick = { incoming = null }, secondary = true, modifier = Modifier.weight(1f)) { Text("取消") }
                        PillButton(onClick = {
                            RouteStore.save(ctx, RouteMerge.merge(RouteStore.all(ctx), inc, policy))
                            Toast.makeText(ctx, "已导入 ${inc.size} 条线路", Toast.LENGTH_SHORT).show()
                            incoming = null; ver++; onImported()
                        }, modifier = Modifier.weight(1f)) { Text("导入 ${inc.size} 条") }
                    }
                }
            }
        }
        item { Hint("线路文件仅在本机读取，不会上传。文件格式有误时将提示出错行号，已有线路不受影响。") }
    }
}
