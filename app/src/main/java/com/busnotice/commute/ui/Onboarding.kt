@file:OptIn(ExperimentalLayoutApi::class)

package com.busnotice.commute.ui

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.Candidate
import com.busnotice.commute.DemoPlayer
import com.busnotice.commute.Reverse
import com.busnotice.commute.Scheduler
import com.busnotice.commute.Transit
import com.busnotice.commute.Trip
import com.busnotice.commute.TripStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 首次引导的步骤（顺序即展示顺序） */
enum class OnboardingStep(val title: String) {
    WELCOME("欢迎"), DATA("线路数据"), PERMS("授权"), PLACES("家和公司"), TRIP("第一条行程"),
    KEEPALIVE("后台设置"), OPTIONAL("可选增强"), DONE("完成");

    fun next() = values().getOrElse(ordinal + 1) { DONE }
    fun prev() = values().getOrElse(ordinal - 1) { WELCOME }
}

/**
 * 引导进度：只在首次启动出现，进度存本机，中途退出下次接着走；设置里可以重新打开。
 * 升级上来的老用户（已有行程或学习数据）不弹引导。
 */
object OnboardingState {
    private const val PREFS = "onboarding"
    private const val KEY_DONE = "done"
    private const val KEY_STARTED = "started"
    private const val KEY_STEP = "step"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun needed(ctx: Context): Boolean {
        val p = sp(ctx)
        if (p.getBoolean(KEY_DONE, false)) return false
        if (p.getBoolean(KEY_STARTED, false)) return true
        val legacy = TripStore.all(ctx).isNotEmpty() ||
            listOf("places", "learn", "learn_ride2", "run_state").any { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).all.isNotEmpty() }
        if (legacy) { p.edit().putBoolean(KEY_DONE, true).apply(); return false }
        p.edit().putBoolean(KEY_STARTED, true).apply()
        return true
    }

    fun step(ctx: Context): OnboardingStep =
        runCatching { OnboardingStep.valueOf(sp(ctx).getString(KEY_STEP, null) ?: "") }.getOrDefault(OnboardingStep.WELCOME)

    fun saveStep(ctx: Context, s: OnboardingStep) { sp(ctx).edit().putString(KEY_STEP, s.name).apply() }

    fun finish(ctx: Context) { sp(ctx).edit().putBoolean(KEY_DONE, true).remove(KEY_STEP).apply() }

    /** 从设置里重新打开：从欢迎页开始，已有行程和设置不受影响 */
    fun restart(ctx: Context) {
        sp(ctx).edit().putBoolean(KEY_STARTED, true).putBoolean(KEY_DONE, false).putString(KEY_STEP, OnboardingStep.WELCOME.name).apply()
    }
}

/** 第一段怎么走 */
enum class LineKind { BUS, METRO, SHUTTLE }

@Composable
fun Onboarding(
    resumeTick: Int,
    draft: Trip,
    onDraftChange: (Trip) -> Unit,
    onAddLine: (legIndex: Int, kind: LineKind) -> Unit,
    onFinish: () -> Unit,
) {
    val ctx = LocalContext.current
    var step by remember { mutableStateOf(OnboardingState.step(ctx)) }
    fun go(s: OnboardingStep) { step = s; OnboardingState.saveStep(ctx, s) }
    fun finish() { OnboardingState.finish(ctx); onFinish() }

    BackHandler(enabled = step != OnboardingStep.WELCOME) { go(step.prev()) }

    if (step == OnboardingStep.WELCOME) {
        Welcome { go(OnboardingStep.DATA) }
        return
    }
    val n = step.ordinal
    val total = OnboardingStep.values().size - 1
    GlassPage(
        title = if (step == OnboardingStep.DONE) "准备好了" else "$n / ${total - 1} · ${step.title}",
        onBack = { go(step.prev()) },
        actions = {
            if (step != OnboardingStep.DONE) TextButton(onClick = { go(step.next()) }) {
                Text("跳过", color = Fuse.dim(isSystemInDarkTheme()))
            }
        },
    ) {
        when (step) {
            OnboardingStep.WELCOME -> {}
            OnboardingStep.DATA -> dataStep { go(step.next()) }
            OnboardingStep.PERMS -> item { PermStep(resumeTick) { go(step.next()) } }
            OnboardingStep.PLACES -> {
                item { Hint("设好家和公司后，在家里或公司里不会误判出发，到家、到公司也能自动结束行程。可以跳过，App 会在约 3 天内自己学会。") }
                item { PlacesSetup() }
                item { NextButton { go(step.next()) } }
            }
            OnboardingStep.TRIP -> item { TripWizard(draft, onDraftChange, onAddLine) { go(step.next()) } }
            OnboardingStep.KEEPALIVE -> {
                item { KeepaliveChecks(resumeTick) }
                item { NextButton { go(step.next()) } }
            }
            OnboardingStep.OPTIONAL -> item { OptionalStep(resumeTick) { go(step.next()) } }
            OnboardingStep.DONE -> item {
                GlassCard {
                    Text("都设置好了。之后在「⋯ → 设置」里可以改家和公司、线路数据，做后台保活体检，或者重新打开这份引导。",
                        fontSize = 14.sp, lineHeight = 20.sp, color = Fuse.ink(isSystemInDarkTheme()))
                    Spacer(Modifier.height(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(onClick = { finish(); DemoPlayer.start(ctx, 60) }, modifier = Modifier.fillMaxWidth()) {
                            Text("看一遍演示回放（约 1 分钟）")
                        }
                        PillButton(secondary = true, onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("回首页") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Welcome(onStart: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    Box(Modifier.fillMaxSize()) {
        GradientBackground(dark)
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text("通勤胶囊", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = ink)
            Spacer(Modifier.height(12.dp))
            Text("到点自动弹出通勤胶囊：什么时候出门、车还有几分钟、快到站了提醒你下车。",
                fontSize = 16.sp, lineHeight = 23.sp, color = ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            GlassCard(Modifier.widthIn(max = 420.dp)) {
                Text("数据只在本机", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink)
                Spacer(Modifier.height(4.dp))
                Text("不用注册账号。行程、位置、学到的习惯都只存在这台手机上，不上传；行程记录只有你自己点导出时才会离开手机。",
                    fontSize = 13.sp, lineHeight = 19.sp, color = Fuse.dim(dark))
            }
            Spacer(Modifier.height(28.dp))
            PillButton(onClick = onStart, modifier = Modifier.widthIn(min = 200.dp)) { Text("开始", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 13.sp, lineHeight = 19.sp, color = Fuse.dim(isSystemInDarkTheme()),
        modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun NextButton(label: String = "下一步", onClick: () -> Unit) {
    PillButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label, fontWeight = FontWeight.Bold) }
}

// ------------------------------------------------------------------ 线路数据

private fun LazyListScope.dataStep(onNext: () -> Unit) = item {
    val ctx = LocalContext.current
    // 默认建议「边用边学」；已经明确选过的保留原选择
    var mode by remember { mutableStateOf(if (Transit.modeChosen(ctx)) Transit.mode else Transit.DataMode.LEARN) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hint("线路和站点从哪来？之后在设置里随时可以改。")
        GlassCard {
            if (Transit.hasExtraSource) {
                Text("已接入数据源：${Transit.static.title}", fontSize = 14.sp, color = Fuse.ink(isSystemInDarkTheme()))
            } else {
                DataModeChoice(mode) { mode = it }
            }
        }
        NextButton {
            if (!Transit.hasExtraSource) Transit.setMode(ctx, mode)
            onNext()
        }
    }
}

// ------------------------------------------------------------------ 授权

@Composable
private fun PermStep(resumeTick: Int, onNext: () -> Unit) {
    val ctx = LocalContext.current
    var ver by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ver++ }
    val perms = remember(resumeTick, ver) { permChecks(ctx) { launcher.launch(it) } }
    val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    fun p(id: String) = perms.firstOrNull { it.id == id }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hint("下面每一项都可以先跳过，之后在首页「设置检查」里补上。")
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                p(PermId.NOTIFY)?.let { PermLine(it, "不开就看不到胶囊和提醒。") }
                p(PermId.PROMOTED)?.let { PermLine(it, null) }
                p(PermId.FINE)?.let { PermLine(it, "先在系统弹窗里选「使用期间允许」，并保留「精确位置」。") }
                p(PermId.BACKGROUND)?.let {
                    PermLine(it, if (fine) "再到系统页面里选「始终允许」：没打开 App 时也能在出发时段自动开始、判断到站。"
                        else "需要先允许上面的定位。", enabled = fine)
                }
                p(PermId.ACTIVITY)?.let { PermLine(it, "用计步判断下车、开始走路，并学习步行用时。") }
                p(PermId.EXACT)?.let { PermLine(it, "准时预热和唤醒。") }
            }
        }
        NextButton(onClick = onNext)
    }
}

@Composable
private fun PermLine(p: Perm, why: String?, enabled: Boolean = true) {
    val dark = isSystemInDarkTheme()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Fuse.ink(dark))
            Text(why ?: p.desc, fontSize = 12.sp, lineHeight = 17.sp, color = Fuse.dim(dark))
        }
        Spacer(Modifier.width(8.dp))
        if (p.granted) {
            Icon(Icons.Default.Check, null, tint = Fuse.BusGreen, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("已开启", fontSize = 13.sp, color = Fuse.BusGreen)
        } else {
            PillButton(onClick = p.action, enabled = enabled) { Text("去开启") }
        }
    }
}

// ------------------------------------------------------------------ 第一条行程

private val DAYS = arrayOf("一", "二", "三", "四", "五", "六", "日")

@Composable
private fun TripWizard(draft: Trip, onChange: (Trip) -> Unit, onAddLine: (Int, LineKind) -> Unit, onNext: () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val scope = rememberCoroutineScope()
    var pickTime by remember { mutableStateOf(false) }
    var saved by remember(draft.id) { mutableStateOf(TripStore.get(ctx, draft.id) != null) }
    var returnStart by remember { mutableStateOf(if (draft.startMinute < 12 * 60) 18 * 60 else 8 * 60) }
    var pickReturn by remember { mutableStateOf(false) }
    var reversing by remember { mutableStateOf(false) }
    val legs = draft.legs.filter { it.candidates.isNotEmpty() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!saved) {
            Hint("先建一条最常用的行程，之后在首页可以细调。")
            GlassCard {
                SectionLabel("上班还是下班")
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(selected = draft.name == "上班", label = "上班", onClick = {
                        onChange(draft.copy(name = "上班", startMinute = 8 * 60, endMinute = 8 * 60 + 30)); returnStart = 18 * 60
                    })
                    GlassChip(selected = draft.name == "下班", label = "下班", onClick = {
                        onChange(draft.copy(name = "下班", startMinute = 18 * 60, endMinute = 18 * 60 + 30)); returnStart = 8 * 60
                    })
                }
                Spacer(Modifier.height(14.dp))
                SectionLabel("哪几天")
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAYS.forEachIndexed { i, d ->
                        GlassChip(selected = draft.dayMask and (1 shl i) != 0, label = d, modifier = Modifier.widthIn(min = 44.dp),
                            onClick = { onChange(draft.copy(dayMask = draft.dayMask xor (1 shl i))) })
                    }
                }
                Spacer(Modifier.height(14.dp))
                SectionLabel("大概几点出门")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillButton(secondary = true, onClick = { pickTime = true }) { Text(hhmm(draft.startMinute), fontSize = 18.sp, style = TnumStyle) }
                    Spacer(Modifier.width(10.dp))
                    Text("前后半小时内自动弹出", fontSize = 12.sp, color = Fuse.dim(dark))
                }
            }
            GlassCard {
                SectionLabel(if (legs.isEmpty()) "第一段怎么走" else "怎么走")
                Spacer(Modifier.height(8.dp))
                legs.forEachIndexed { i, leg ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text("第 ${i + 1} 段", fontSize = 13.sp, color = Fuse.dim(dark), modifier = Modifier.width(56.dp))
                        leg.candidates.take(3).forEach { c ->
                            LineBadge(shortLine(c.name.ifEmpty { c.label() }), lineColor(c)); Spacer(Modifier.width(6.dp))
                        }
                        Text(leg.stopName() + " 上车" + if (leg.candidates.any { !it.hasCoord() && it.type != Candidate.Type.SHUTTLE }) " · 位置学习中" else "",
                            fontSize = 13.sp, color = ink)
                    }
                }
                if (legs.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("要换乘的话再加一段：", fontSize = 12.sp, color = Fuse.dim(dark))
                }
                Spacer(Modifier.height(6.dp))
                // 新的一段放在最后：已有空段就用它，否则在末尾追加
                val nextIdx = draft.legs.indexOfFirst { it.candidates.isEmpty() }.takeIf { it >= 0 } ?: draft.legs.size
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassChip(selected = false, onClick = { onAddLine(nextIdx, LineKind.BUS) }, label = "+ 公交")
                    GlassChip(selected = false, onClick = { onAddLine(nextIdx, LineKind.METRO) }, label = "+ 地铁")
                    GlassChip(selected = false, onClick = { onAddLine(nextIdx, LineKind.SHUTTLE) }, label = "+ 通勤车")
                }
                Spacer(Modifier.height(6.dp))
                Text("查不到的线路可以直接记下名字，比如「2号线」「105」。", fontSize = 12.sp, color = Fuse.dim(dark))
            }
            PillButton(onClick = { if (saveTrip(ctx, draft)) saved = true }, enabled = legs.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()) { Text("保存这条行程", fontWeight = FontWeight.Bold) }
        } else {
            GlassCard {
                Text("「${draft.name}」已保存", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ink)
                Spacer(Modifier.height(6.dp))
                Text("顺便生成回程吗？线路倒过来、在原下车站上车；没填下车站的线路需要之后手动添加。",
                    fontSize = 13.sp, lineHeight = 19.sp, color = Fuse.dim(dark))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("回程大概几点出门", fontSize = 14.sp, color = ink, modifier = Modifier.weight(1f))
                    PillButton(secondary = true, onClick = { pickReturn = true }) { Text(hhmm(returnStart), style = TnumStyle) }
                }
                Spacer(Modifier.height(10.dp))
                PillButton(onClick = {
                    if (reversing) return@PillButton
                    reversing = true
                    scope.launch {
                        val src = TripStore.get(ctx, draft.id) ?: draft
                        val r = withContext(Dispatchers.IO) { runCatching { Reverse.build(src) }.getOrNull() }
                        reversing = false
                        if (r == null || r.trip.legs.isEmpty()) {
                            Toast.makeText(ctx, r?.warnings?.joinToString("；")?.ifEmpty { null } ?: "没能生成回程，可以之后在首页添加", Toast.LENGTH_LONG).show()
                        } else {
                            val t = r.trip.copy(startMinute = returnStart, endMinute = (returnStart + 30).coerceAtMost(23 * 60 + 59))
                            TripStore.upsert(ctx, t); Scheduler.schedule(ctx, t)
                            Toast.makeText(ctx, "已生成「${t.name}」" + (r.warnings.takeIf { it.isNotEmpty() }?.joinToString("；", prefix = "：") ?: ""),
                                Toast.LENGTH_LONG).show()
                            onNext()
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (reversing) "生成中…" else "生成回程") }
                Spacer(Modifier.height(8.dp))
                PillButton(secondary = true, onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("不用，下一步") }
            }
        }
    }
    if (pickTime) TimeWheelSheet("大概几点出门", draft.startMinute, onDismiss = { pickTime = false }) { m ->
        onChange(draft.copy(startMinute = m, endMinute = (m + 30).coerceAtMost(23 * 60 + 59))); pickTime = false
    }
    if (pickReturn) TimeWheelSheet("回程大概几点出门", returnStart, onDismiss = { pickReturn = false }) { m ->
        returnStart = m; pickReturn = false
    }
}

// ------------------------------------------------------------------ 可选增强

@Composable
private fun OptionalStep(resumeTick: Int, onNext: () -> Unit) {
    val ctx = LocalContext.current
    val perms = remember(resumeTick) { permChecks(ctx) {} }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hint("可选：允许读取通知后，手机交通卡刷卡和系统到站提醒能让进出站时刻更准。默认不开。")
        GlassCard {
            perms.firstOrNull { it.id == PermId.NOTIF_LISTENER }?.let {
                PermLine(it, "只识别出行类通知（刷卡、到站），只存在本机；其他通知不读取、不保存。随时可以在系统设置里关闭。")
            }
        }
        NextButton(onClick = onNext)
    }
}
