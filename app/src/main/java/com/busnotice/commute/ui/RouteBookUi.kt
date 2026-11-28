package com.busnotice.commute.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
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

private sealed interface RouteStep {
    object List : RouteStep
    object New : RouteStep
    class Preview(val routes: kotlin.collections.List<RouteDef>) : RouteStep
    object Files : RouteStep
}

private val MODE_LABELS = listOf(Candidate.Type.BUS to "公交", Candidate.Type.METRO to "地铁", Candidate.Type.SHUTTLE to "班车")

/** 线路：新建（粘贴站名、一键生成反向）、预览微调、线路文件导入导出 */
@Composable
fun RouteBookScreen(onBack: () -> Unit) {
    var step by remember { mutableStateOf<RouteStep>(RouteStep.List) }
    var ver by remember { mutableIntStateOf(0) }
    val back = { if (step == RouteStep.List) onBack() else step = RouteStep.List }
    BackHandler { back() }
    when (val s = step) {
        RouteStep.List -> RouteListPage(ver, onBack, onNew = { step = RouteStep.New }, onFiles = { step = RouteStep.Files }, onChanged = { ver++ })
        RouteStep.New -> RouteNewPage(onBack = back, onNext = { step = RouteStep.Preview(it) })
        is RouteStep.Preview -> RoutePreviewPage(s.routes, onBack = { step = RouteStep.New }, onSaved = { ver++; step = RouteStep.List })
        RouteStep.Files -> RouteFilesPage(onBack = back, onImported = { ver++ })
    }
}

@Composable
private fun Hint(text: String) = Text(text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp), color = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.65f))

@Composable
private fun RouteListPage(ver: Int, onBack: () -> Unit, onNew: () -> Unit, onFiles: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val routes = remember(ver) { RouteStore.all(ctx) }
    var confirmDelete by remember { mutableStateOf<RouteDef?>(null) }
    GlassPage(title = "我的线路", onBack = onBack) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(onClick = onNew, modifier = Modifier.weight(1f)) { Text("新建线路") }
                PillButton(onClick = onFiles, secondary = true, modifier = Modifier.weight(1f)) { Text("线路文件") }
            }
        }
        if (routes.isEmpty()) item { Hint("还没有线路。新建时只需按乘车顺序填站名，不用坐标，站点位置坐几趟后自动学会。") }
        glassGroup(routes, key = "routes") { r ->
            GlassRow(
                headline = r.fullName(),
                supporting = "${MODE_LABELS.first { it.first == r.mode }.second} · ${r.stops.size} 站" +
                    (if (r.stopPattern == RouteDef.StopPattern.ENDS) " · 只停起终点" else "") +
                    " · " + scheduleText(r),
                onClick = { confirmDelete = r },
            )
        }
    }
    confirmDelete?.let { r ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(r.fullName()) },
            text = { Text(r.stops.joinToString(" → ")) },
            confirmButton = { DialogButton("删除", onClick = { RouteStore.delete(ctx, r.id); confirmDelete = null; onChanged() }) },
            dismissButton = { DialogButton("关闭", onClick = { confirmDelete = null }, primary = true) },
        )
    }
}

private fun scheduleText(r: RouteDef): String {
    val s = r.schedule
    return when {
        s.departures.isNotEmpty() -> "发车 ${s.departures.size} 班"
        s.peakHeadwayMin > 0 -> "高峰约 ${s.peakHeadwayMin} 分钟一班"
        RouteTiming.peak(r) > 0 -> "间隔按默认估算"
        else -> "时刻靠学习"
    }
}

@Composable
private fun RouteNewPage(onBack: () -> Unit, onNext: (List<RouteDef>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(Candidate.Type.BUS) }
    var ends by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var endA by remember { mutableStateOf("") }
    var endB by remember { mutableStateOf("") }
    var reverse by remember { mutableStateOf(true) }
    var firstMin by remember { mutableStateOf("") }
    var lastMin by remember { mutableStateOf("") }
    var peak by remember { mutableStateOf("") }
    var offPeak by remember { mutableStateOf("") }
    var deps by remember { mutableStateOf("") }

    val parsed = remember(raw) { RouteText.parse(raw) }
    val stops = if (ends) listOf(endA.trim(), endB.trim()).filter { it.isNotEmpty() } else parsed.stops
    val firstT = firstMin.takeIf { it.isNotBlank() }?.let { RouteText.parseTime(it) }
    val lastT = lastMin.takeIf { it.isNotBlank() }?.let { RouteText.parseTime(it) }
    val peakN = peak.takeIf { it.isNotBlank() }?.toIntOrNull()
    val offN = offPeak.takeIf { it.isNotBlank() }?.toIntOrNull()
    val depsL = deps.takeIf { it.isNotBlank() }?.let { RouteText.parseTimes(it) }
    val timeErr = (firstMin.isNotBlank() && firstT == null) || (lastMin.isNotBlank() && lastT == null) ||
        (peak.isNotBlank() && peakN !in 1..180) || (offPeak.isNotBlank() && offN !in 1..180) || (deps.isNotBlank() && depsL == null)
    val ok = name.isNotBlank() && stops.size >= 2 && !timeErr

    GlassPage(title = "新建线路", onBack = onBack) {
        item {
            GlassCard {
                GlassField(name, { name = it }, label = "线路名称", modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            GlassCard {
                SectionLabel("交通方式")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    MODE_LABELS.forEach { (t, l) -> GlassChip(mode == t, { mode = t }, l, Modifier.weight(1f)) }
                }
                SectionLabel("停站方式", Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    GlassChip(!ends, { ends = false }, "站站停", Modifier.weight(1f))
                    GlassChip(ends, {
                        if (!ends) { endA = endA.ifEmpty { parsed.stops.firstOrNull().orEmpty() }; endB = endB.ifEmpty { parsed.stops.lastOrNull().orEmpty() } }
                        ends = true
                    }, "只停起终点", Modifier.weight(1f))
                }
            }
        }
        item {
            GlassCard {
                if (ends) {
                    Text("起点和终点", fontWeight = FontWeight.SemiBold)
                    GlassField(endA, { endA = it }, label = "起点站", modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    GlassField(endB, { endB = it }, label = "终点站", modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Hint("途中不停的车只填两头。按整段用时学习，路上不经过站点不算异常。")
                } else {
                    Text("站点（按乘车顺序，一行一个）", fontWeight = FontWeight.SemiBold)
                    GlassField(raw, { raw = it }, singleLine = false, placeholder = "起点站\n第二站\n…\n终点站",
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (parsed.stops.isNotEmpty()) {
                        val cleaned = listOfNotNull(
                            parsed.droppedBlank.takeIf { it > 0 }?.let { "$it 个空行" },
                            parsed.strippedNumbers.takeIf { it > 0 }?.let { "$it 处序号" },
                        )
                        Text("已识别 ${parsed.stops.size} 站" + if (cleaned.isEmpty()) "" else " · 已去掉" + cleaned.joinToString("和"),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    }
                    Hint("可以从聊天记录或网页直接粘贴。只要站名，不用坐标，位置坐几趟就学会。")
                }
            }
        }
        item {
            GlassCard {
                Text("时刻（可选）", fontWeight = FontWeight.SemiBold)
                if (mode == Candidate.Type.SHUTTLE) {
                    GlassField(deps, { deps = it }, label = "发车时刻", placeholder = "例如 07:10 07:40",
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Hint("班次制的车填起点发车时刻。不填就只靠坐过的记录学习。")
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        GlassField(firstMin, { firstMin = it }, label = "首班", placeholder = "HH:MM", modifier = Modifier.weight(1f))
                        GlassField(lastMin, { lastMin = it }, label = "末班", placeholder = "HH:MM", modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        GlassField(peak, { peak = it.filter(Char::isDigit) }, label = "高峰间隔（分钟）", modifier = Modifier.weight(1f))
                        GlassField(offPeak, { offPeak = it.filter(Char::isDigit) }, label = "平峰间隔（分钟）", modifier = Modifier.weight(1f))
                    }
                    val d = RouteTiming.defaultPeak(mode)
                    Hint("不填就按默认估算：高峰约 $d 分钟一班，平峰约 ${(d * 3 + 1) / 2} 分钟。填了以你填的为准。")
                }
                if (timeErr) Text("时间写成 HH:MM，间隔填 1–180 的分钟数", fontSize = 13.sp, color = androidx.compose.ui.graphics.Color(0xFFD64545), modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("同时生成反向线路", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(checked = reverse, onCheckedChange = { reverse = it })
                }
            }
        }
        item {
            PillButton(enabled = ok, modifier = Modifier.fillMaxWidth(), onClick = {
                val sch = if (mode == Candidate.Type.SHUTTLE) RouteSchedule(departures = depsL.orEmpty())
                    else RouteSchedule(firstT ?: -1, lastT ?: -1, peakN ?: 0, offN ?: 0)
                val r = RouteDef(name = name.trim(), direction = if (reverse) "去程" else "", mode = mode,
                    stopPattern = if (ends) RouteDef.StopPattern.ENDS else RouteDef.StopPattern.ALL, stops = stops, schedule = sch)
                onNext(if (reverse) listOf(r, r.reversed()) else listOf(r))
            }) { Text("下一步：预览") }
        }
    }
}

@Composable
private fun RoutePreviewPage(initial: List<RouteDef>, onBack: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    var routes by remember { mutableStateOf(initial) }
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) } // 线路序号, 站序号（-1 = 插入到末站前）
    var text by remember { mutableStateOf("") }
    GlassPage(title = "预览", onBack = onBack) {
        item { Text("将保存 ${routes.size} 条线路", fontWeight = FontWeight.SemiBold) }
        routes.forEachIndexed { ri, r ->
            item(key = r.id) {
                GlassCard {
                    Text(r.fullName(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    r.stops.forEachIndexed { si, st ->
                        val passOnly = r.stopPattern == RouteDef.StopPattern.ENDS && si in 1 until r.stops.lastIndex
                        Row(Modifier.fillMaxWidth().clickable { editing = ri to si; text = st }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("●", fontSize = 10.sp, modifier = Modifier.width(20.dp))
                            Text(st, modifier = Modifier.weight(1f),
                                fontWeight = if (si == 0 || si == r.stops.lastIndex) FontWeight.SemiBold else FontWeight.Normal)
                            Text(if (passOnly) "途经，不停" else "修改", fontSize = 13.sp,
                                color = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.5f))
                        }
                    }
                    Text("+ 插入站", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { editing = ri to -1; text = "" }.padding(top = 4.dp))
                    Hint(scheduleText(r))
                }
            }
        }
        item { Hint("去程和返程可以分别修改，适合单行道、来回停靠站点不同的线路。") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(onClick = onBack, secondary = true, modifier = Modifier.weight(1f)) { Text("返回修改") }
                PillButton(onClick = {
                    RouteStore.save(ctx, RouteMerge.merge(RouteStore.all(ctx), routes, RouteMerge.Conflict.KEEP_BOTH))
                    Toast.makeText(ctx, "已保存 ${routes.size} 条线路", Toast.LENGTH_SHORT).show()
                    onSaved()
                }, enabled = routes.all { it.stops.size >= 2 }, modifier = Modifier.weight(1f)) { Text("保存") }
            }
        }
    }
    editing?.let { (ri, si) ->
        val r = routes[ri]
        fun update(newStops: List<String>) {
            routes = routes.toMutableList().also { it[ri] = r.copy(stops = newStops) }; editing = null
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (si < 0) "插入站（${r.direction.ifEmpty { r.name }}）" else "编辑「${r.stops[si]}」") },
            text = {
                Column {
                    GlassField(text, { text = it }, label = "站名", modifier = Modifier.fillMaxWidth())
                    if (si < 0) Hint("插入到终点站之前；需要调整位置可再修改。")
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
                if (si >= 0) DialogButton("删除这站", enabled = r.stops.size > 2, onClick = { update(r.stops.filterIndexed { i, _ -> i != si }) })
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
        if (text == null) { error = "无法读取文件，或文件过大"; return@rememberLauncherForActivityResult }
        try { incoming = RouteFile.read(text) } catch (e: RouteFile.FormatError) { error = "文件格式有误，${e.message}。已有线路未改动。" }
    }

    GlassPage(title = "线路文件", onBack = onBack) {
        item {
            GlassCard {
                Text("导出", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                if (routes.isEmpty()) Hint("还没有可导出的线路。")
                else {
                    Hint("选择要导出的线路（默认全选）")
                    routes.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                            .clickable { selected = if (r.id in selected) selected - r.id else selected + r.id }) {
                            Checkbox(checked = r.id in selected, onCheckedChange = { selected = if (it) selected + r.id else selected - r.id })
                            Text(r.fullName())
                        }
                    }
                    Hint("已选 ${selected.size} 条 · 不含行程记录和位置")
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
                    error?.let { Text(it, fontSize = 13.sp, color = androidx.compose.ui.graphics.Color(0xFFD64545), modifier = Modifier.padding(top = 6.dp)) }
                } else {
                    Hint("文件里有 ${inc.size} 条线路")
                    inc.forEach { r ->
                        val same = RouteMerge.status(routes, r) == RouteMerge.Status.SAME_NAME
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(r.fullName(), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("${r.stops.size} 站", modifier = Modifier.padding(horizontal = 8.dp))
                            Text(if (same) "同名已存在" else "新增", fontWeight = FontWeight.SemiBold,
                                color = if (same) androidx.compose.ui.graphics.Color(0xFFD9822B) else androidx.compose.ui.graphics.Color(0xFF2E9E5B))
                        }
                    }
                    if (inc.any { RouteMerge.status(routes, it) == RouteMerge.Status.SAME_NAME }) {
                        SectionLabel("同名线路", Modifier.padding(top = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            GlassChip(policy == RouteMerge.Conflict.KEEP_BOTH, { policy = RouteMerge.Conflict.KEEP_BOTH }, "保留两份", Modifier.weight(1f))
                            GlassChip(policy == RouteMerge.Conflict.REPLACE, { policy = RouteMerge.Conflict.REPLACE }, "用文件里的替换", Modifier.weight(1f))
                        }
                        Hint("替换只改站点和时刻，已学到的位置和用时保留。")
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
        item { Hint("文件只在本机读取，不上传。格式出错会指出第几行，不改动已有线路。") }
    }
}
