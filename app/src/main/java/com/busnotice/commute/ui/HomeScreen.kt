@file:OptIn(ExperimentalMaterial3Api::class)

package com.busnotice.commute.ui

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.Candidate
import com.busnotice.commute.CommuteService
import com.busnotice.commute.Learn
import com.busnotice.commute.LiveState
import com.busnotice.commute.Loc
import com.busnotice.commute.Places
import com.busnotice.commute.Reverse
import com.busnotice.commute.Scheduler
import com.busnotice.commute.Trip
import com.busnotice.commute.TripStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    resumeTick: Int, onNew: () -> Unit, onEdit: (Trip) -> Unit, onSettings: () -> Unit = {},
    onImport: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dimColor = Fuse.dim(dark)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var version by remember { mutableStateOf(0) }
    val trips = remember(resumeTick, version) { TripStore.all(ctx) }
    val live by LiveState.snapshot.collectAsState()
    val demo by com.busnotice.commute.DemoPlayer.snapshot.collectAsState()
    val demoCount by com.busnotice.commute.DemoPlayer.countdown.collectAsState()
    // 演示（含倒计时）期间：首页只放虚构的行程卡、不显示当前地点，录屏不露真实信息
    val demoOn = demo != null || demoCount != null
    val adviceFlow by com.busnotice.commute.DepartAdvisor.current.collectAsState()
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    // 出发建议卡片：每 20 秒刷新倒计时；过期 / 行程开始了就自动消失
    LaunchedEffect(resumeTick) {
        while (true) {
            nowTick = System.currentTimeMillis()
            com.busnotice.commute.DepartAdvisor.load(ctx, nowTick)
            delay(20_000L)
        }
    }
    val advice = adviceFlow?.takeIf { nowTick < it.busAt + 2 * 60_000L }
    var confirmDelete by remember { mutableStateOf<Trip?>(null) }
    var confirmSwitch by remember { mutableStateOf<Trip?>(null) }
    var confirmRestart by remember { mutableStateOf<Trip?>(null) }
    var reversing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var sourceOpen by remember { mutableStateOf(false) }
    var permSheet by remember { mutableStateOf(false) }
    // 从迁移文件恢复后首页提示一次，可撤销
    var importedNotice by remember(resumeTick) { mutableStateOf(com.busnotice.commute.MigrationStore.noticePending(ctx)) }
    var undoOpen by remember { mutableStateOf(false) }
    // 当前用演示数据时首页提示；切换后立即刷新。点「稍后」存本机，切回演示数据时重新显示
    var dataVer by remember { mutableStateOf(0) }
    val usingDemoData = remember(resumeTick, dataVer, sourceOpen) { com.busnotice.commute.Transit.usingDemo }
    var demoHintLater by remember(resumeTick, dataVer, sourceOpen) { mutableStateOf(com.busnotice.commute.Transit.demoHintSnoozed(ctx)) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { version++ }
    val perms = remember(resumeTick, version) { permChecks(ctx, { p -> permLauncher.launch(p) }) { version++ } }
    val batch = remember(resumeTick, version) { batchPermissions(ctx) }
    val days = remember(resumeTick) { com.busnotice.commute.TraceLog.dayCount(ctx) }
    // 可读密钥有变化（例如旧密钥恢复可读）时，后台重新统计无法读取的记录，设置检查随之更新
    LaunchedEffect(resumeTick) {
        if (com.busnotice.commute.TraceStore.lockedStale()) {
            withContext(Dispatchers.IO) { runCatching { com.busnotice.commute.TraceStore.refreshLocked(ctx) } }
            version++
        }
    }
    val diag = remember(resumeTick, version) { com.busnotice.commute.Diag.last(ctx) }

    // 进程重启后内存里的看板是空的：先用本地存的那份顶上；还没有的话显示「正在恢复…」，30 秒后服务还没起来就不再显示
    LaunchedEffect(resumeTick) { LiveState.ensureRestored(ctx) }
    // 要显示「正在恢复…」的行程：内存里没看板但 run_state 有没到期的行程，或者看板太旧（5 分钟没更新，多半是存下来的旧状态）
    val pendingTrip = remember(resumeTick, live) {
        val s = live
        when {
            s == null -> LiveState.activeRunTrip(ctx)
            System.currentTimeMillis() - s.updatedAt > 5 * 60_000L -> s.tripId
            else -> null
        }
    }
    var pendingExpired by remember(resumeTick) { mutableStateOf(false) }
    LaunchedEffect(resumeTick, pendingTrip) {
        if (pendingTrip != null) {
            delay(30_000)
            if (CommuteService.instance == null) pendingExpired = true
        }
    }

    // 常去地点：回到前台时用一次当前位置判断「在家 / 在公司 / 路上」，只在本机算
    var placeVer by remember { mutableStateOf(0) }
    var placeText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resumeTick, placeVer) {
        val ok = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        val l = if (ok) runCatching { Loc.current(ctx) }.getOrNull() else null
        placeText = runCatching {
            if (l != null) Places.passive(ctx, l.latitude, l.longitude, l.accuracy, l.time)
            val label = l?.let { Places.text(Places.classify(ctx, it.latitude, it.longitude, it.accuracy)) }
            label ?: if (!Places.bothConfirmed(ctx)) "学习中 家 ${Places.progressOf(ctx, Places.Kind.HOME)}/3 · 公司 ${Places.progressOf(ctx, Places.Kind.WORK)}/3" else null
        }.getOrNull()
    }

    val listState = rememberLazyListState()
    // 设置检查卡在列表中的位置（每次组成列表时更新），聚光引导指到它时用来把它滚进屏幕
    val permAt = remember { intArrayOf(-1) }
    val coach = LocalCoachTargets.current
    val coachFocus = coach?.focus
    LaunchedEffect(coachFocus) {
        if (coach == null || coachFocus == null || coach.available(coachFocus)) return@LaunchedEffect
        when (coachFocus) {
            CoachStep.PLACES, CoachStep.MENU -> listState.animateScrollToItem(0)
            CoachStep.CHECKS -> permAt[0].takeIf { it >= 0 }?.let { listState.animateScrollToItem(it) }
            CoachStep.NEW_TRIP -> Unit
        }
    }
    // 点「测试」或行程开始后，实时看板插到列表顶部：滚回顶部让看板完整露出来，不被挤到上面
    val liveId = live?.tripId
    LaunchedEffect(liveId) { if (liveId != null) listState.animateScrollToItem(0) }

    Box(Modifier.fillMaxSize()) {
        GradientBackground(dark)
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = ink,
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onNew, modifier = Modifier.coachTarget(CoachStep.NEW_TRIP), icon = { Icon(Icons.Default.Add, null) }, text = { Text("新建行程", fontWeight = FontWeight.Bold) },
                    shape = CircleShape,
                    containerColor = if (dark) Fuse.Yellow else Fuse.Night,
                    contentColor = if (dark) Color(0xFF111111) else Color.White,
                )
            },
            snackbarHost = { SnackbarHost(snack) { Snackbar(it, shape = RoundedCornerShape(16.dp)) } },
        ) { pad ->
            // 没有顶栏：标题放进列表第一项；列表本身从状态栏下面开始（会裁切），内容不会画到状态栏文字下面
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
                state = listState,
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = pad.calculateBottomPadding() + 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "title") {
                  Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("通勤胶囊", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ink, modifier = Modifier.weight(1f))
                        Box(Modifier.coachTarget(CoachStep.MENU)) {
                            RoundButton(onClick = { menuOpen = true }, dark = dark) {
                                Text("⋯", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
                            }
                            DropdownMenu(
                                expanded = menuOpen, onDismissRequest = { menuOpen = false },
                                shape = MenuShape, containerColor = Fuse.surface(dark),
                            ) {
                                DropdownMenuItem(
                                    text = { Text("v${com.busnotice.commute.Updater.currentName(ctx)} · 检查更新") },
                                    onClick = { menuOpen = false; com.busnotice.commute.Updater.force.intValue++ },
                                )
                                if (days > 0) DropdownMenuItem(
                                    text = { Text("导出行程记录（${days} 天）") },
                                    onClick = { menuOpen = false; com.busnotice.commute.TraceLog.share(ctx) },
                                )
                                // 行程进行中不能回放（会和真实胶囊打架），但入口留着、置灰说明原因
                                val tripOn = com.busnotice.commute.CommuteService.instance != null
                                listOf(30, 60).forEach { sp ->
                                    DropdownMenuItem(
                                        text = { val len = if (sp >= 60) "约 1 分钟" else "约 2 分钟"; Text(if (tripOn) "演示回放（$len）· 行程中不可用" else "演示回放（$len）") },
                                        enabled = !tripOn,
                                        onClick = { menuOpen = false; com.busnotice.commute.DemoPlayer.start(ctx, sp) },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("设置") },
                                    onClick = { menuOpen = false; onSettings() },
                                )
                                DropdownMenuItem(
                                    text = { Text("数据源") },
                                    onClick = { menuOpen = false; sourceOpen = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("重置常去地点") },
                                    onClick = {
                                        menuOpen = false; Places.reset(ctx); placeVer++
                                        scope.launch { snack.showSnackbar("已清空自动学到的常去地点，会重新学习；手动设置的保留") }
                                    },
                                )
                                diag?.let { d ->
                                    DropdownMenuItem(
                                        text = {
                                            Text("最近状态：$d", style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.widthIn(max = 280.dp))
                                        },
                                        onClick = {}, enabled = false,
                                    )
                                }
                            }
                        }
                    }
                    if (!demoOn) placeText?.let { PlaceChip(it, dark, Modifier.coachTarget(CoachStep.PLACES)) }
                  }
                }
                // 演示数据且还没有自己的行程：顶部放一条内置演示行程（只显示，不写入行程存储）
                var idx = 1
                val showSample = !demoOn && usingDemoData && trips.isEmpty()
                if (showSample) {
                    item(key = "sample") {
                        TripCard(
                            trip = com.busnotice.commute.MockSource.sampleTrip, running = false, dark = dark, sample = true,
                            onToggle = {}, onEdit = {
                                scope.launch { snack.showSnackbar("这是演示行程，不会提醒；点「开始」看一遍演示回放，点「新建行程」建你自己的一条") }
                            },
                            onTest = {
                                if (CommuteService.instance != null) {
                                    scope.launch { snack.showSnackbar("行程进行中，演示回放不可用") }
                                } else {
                                    com.busnotice.commute.DemoPlayer.start(ctx, 30)
                                }
                            },
                            onReverse = {}, onDelete = {}, onRestart = {}, onApplyLearned = {},
                        )
                    }
                    idx++
                }
                if (!demoOn && usingDemoData && !demoHintLater) {
                    item(key = "demo-data") {
                        DemoDataNotice(
                            dark,
                            onSwitch = {
                                com.busnotice.commute.Transit.setMode(ctx, com.busnotice.commute.Transit.DataMode.LEARN); dataVer++
                                scope.launch { snack.showSnackbar("已切换到边用边学：添加线路时可以直接记下线路名和站名") }
                            },
                            onLater = { com.busnotice.commute.Transit.snoozeDemoHint(ctx); demoHintLater = true },
                            onImport = onImport,
                        )
                    }
                    idx++
                }
                if (!demoOn && importedNotice) {
                    item(key = "imported") {
                        ImportedNotice(dark, com.busnotice.commute.MigrationStore.importedTrips(ctx),
                            onUndo = { undoOpen = true },
                            onDismiss = { com.busnotice.commute.MigrationStore.dismissNotice(ctx); importedNotice = false })
                    }
                    idx++
                }
                val demoBoard = demo
                demoCount?.let { n ->
                    item(key = "demo-count") { PlaceChip("演示 $n 秒后开始 · 现在可以回到桌面", dark) }
                    idx++
                }
                if (demoBoard != null) {
                    item(key = "demo-badge") { PlaceChip(com.busnotice.commute.DemoPlayer.BADGE, dark) }
                    item(key = "demo") { LiveBoard(demoBoard, com.busnotice.commute.DemoPlayer.trip, dark) }
                    idx += 2
                }
                val board = if (demoBoard != null) null else live
                if (!demoOn && board == null && pendingTrip == null && advice != null) {
                    item(key = "depart") { DepartBoard(advice!!, nowTick, dark) }
                    idx++
                }
                if (demoOn) Unit
                else if (board != null && pendingTrip == null) {
                    item(key = "live") { LiveBoard(board, trips.firstOrNull { it.id == board.tripId }, dark) }
                    idx++
                } else if (pendingTrip != null && !pendingExpired) {
                    // 服务 30 秒内还没起来就不再显示占位
                    val name = board?.tripName ?: trips.firstOrNull { it.id == pendingTrip }?.name ?: "通勤"
                    item(key = "live") { RestoringBoard(name, dark) }
                    idx++
                }
                // 缺必需设置时放上面显眼处（有演示行程时也紧跟在演示数据提示之后）；
                // 必需项都齐了（只差手机厂商设置或可选项）就放到列表最后
                val permTop = summaryOf(perms).urgent || showSample
                if (permTop) {
                    item(key = "perm") { PermRow(perms, dark, Modifier.coachTarget(CoachStep.CHECKS)) { permSheet = true } }
                    permAt[0] = idx
                    idx++
                }
                if (!demoOn && trips.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (showSample) "还没有你自己的行程" else "还没有行程", style = MaterialTheme.typography.titleMedium, color = ink)
                            Spacer(Modifier.height(4.dp))
                            Text(if (showSample) "上面是演示行程。点「新建行程」建你自己的一条，选好上车站和时间就行" else "新建一条，选好上车站和时间就行",
                                style = MaterialTheme.typography.bodyMedium, color = dimColor)
                        }
                    }
                    idx++
                }
                if (demoOn) items(com.busnotice.commute.DemoPlayer.showTrips, key = { "demo-" + it.id }) { t ->
                    TripCard(
                        trip = t, running = demo?.tripId == t.id, dark = dark,
                        onToggle = {}, onTest = {}, onEdit = {}, onReverse = {}, onDelete = {}, onRestart = {}, onApplyLearned = {},
                    )
                } else items(trips, key = { it.id }) { t ->
                    TripCard(
                        trip = t,
                        running = live?.tripId == t.id,
                        dark = dark,
                        onToggle = { on ->
                            val nt = t.copy(enabled = on)
                            TripStore.upsert(ctx, nt); Scheduler.schedule(ctx, nt); version++
                        },
                        onTest = {
                            // 正在跑的那趟不许被重复点击清零；跑着别的趟要先确认再切换
                            val cur = CommuteService.runningTripId()
                            when {
                                cur == t.id -> scope.launch { snack.showSnackbar("这趟已在进行中；要从头来请在「⋯」里选重新开始") }
                                cur != null -> confirmSwitch = t
                                else -> {
                                    CommuteService.start(ctx, t.id, manual = true)
                                    scope.launch { snack.showSnackbar("已手动开始，切到别的 App 看胶囊") }
                                }
                            }
                        },
                        onRestart = { confirmRestart = t },
                        onEdit = { onEdit(t) },
                        onReverse = {
                            if (!reversing) {
                                reversing = true
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { runCatching { Reverse.build(t) } }
                                    reversing = false
                                    r.onSuccess { res ->
                                        if (res.warnings.isNotEmpty()) snack.showSnackbar(res.warnings.joinToString("；"))
                                        if (res.trip.legs.isNotEmpty()) onEdit(res.trip)
                                    }.onFailure { snack.showSnackbar("生成回程失败：网络不通？") }
                                }
                            }
                        },
                        onDelete = { confirmDelete = t },
                        onApplyLearned = { sg ->
                            val nt = t.copy(startMinute = sg.start, endMinute = sg.end)
                            TripStore.upsert(ctx, nt); Scheduler.schedule(ctx, nt); version++
                            scope.launch { snack.showSnackbar("时间窗改为 ${hhmm(sg.start)}–${hhmm(sg.end)}") }
                        },
                    )
                }
                if (!permTop && perms.isNotEmpty()) {
                    permAt[0] = idx + (if (demoOn) com.busnotice.commute.DemoPlayer.showTrips.size else trips.size)
                    item(key = "perm") { PermRow(perms, dark, Modifier.coachTarget(CoachStep.CHECKS)) { permSheet = true } }
                }
            }
        }
    }

    if (permSheet) {
        ModalBottomSheet(
            onDismissRequest = { permSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Fuse.surface(dark), contentColor = ink,
        ) { PermSheet(perms, onBatch = if (batch.isNotEmpty()) ({ permLauncher.launch(batch) }) else null) }
    }

    if (sourceOpen) SourceDialog(dark) { sourceOpen = false }
    if (undoOpen) UndoImportDialog { undoOpen = false }

    confirmSwitch?.let { t ->
        val curName = CommuteService.runningTripId()?.let { id -> trips.firstOrNull { it.id == id }?.name } ?: "另一趟行程"
        AlertDialog(
            onDismissRequest = { confirmSwitch = null },
            shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
            title = { Text("正在进行「$curName」") },
            text = { Text("要结束它并切换到「${t.name}」吗？当前进度会丢失。") },
            confirmButton = {
                DialogButton("切换", primary = true, onClick = {
                    CommuteService.start(ctx, t.id, manual = true, restart = true); confirmSwitch = null
                    scope.launch { snack.showSnackbar("已切换到「${t.name}」") }
                })
            },
            dismissButton = { DialogButton("取消", onClick = { confirmSwitch = null }) },
        )
    }

    confirmRestart?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmRestart = null },
            shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
            title = { Text("重新开始「${t.name}」？") },
            text = { Text("会按你现在的位置重新判断走到了第几段。") },
            confirmButton = {
                DialogButton("重新开始", primary = true, onClick = {
                    CommuteService.start(ctx, t.id, manual = true, restart = true); confirmRestart = null
                })
            },
            dismissButton = { DialogButton("取消", onClick = { confirmRestart = null }) },
        )
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
            title = { Text("删除「${t.name}」？") },
            confirmButton = {
                DialogButton("删除", primary = true, onClick = {
                    Scheduler.cancel(ctx, t.id); TripStore.delete(ctx, t.id); confirmDelete = null; version++
                })
            },
            dismissButton = { DialogButton("取消", onClick = { confirmDelete = null }) },
        )
    }
}

/** 当前用的是虚构演示数据：说明搜不到真实线路，并可一键切换到「边用边学」 */
@Composable
private fun DemoDataNotice(dark: Boolean, onSwitch: () -> Unit, onLater: () -> Unit, onImport: () -> Unit) {
    val ink = Fuse.ink(dark)
    Column(
        Modifier.fillMaxWidth().glass(dark)
            .background(Fuse.Amber.copy(alpha = if (dark) 0.14f else 0.10f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Fuse.Amber))
            Spacer(Modifier.width(8.dp))
            Text("当前是演示数据", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink)
        }
        Spacer(Modifier.height(4.dp))
        Text("线路和站点来自虚构的「演示城市」，搜不到你所在城市。切换到「边用边学」后，查不到的线路可以直接记下名字，位置靠行程记录逐步学习。",
            fontSize = 13.sp, lineHeight = 18.sp, color = Fuse.dim(dark))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(onClick = onSwitch) { Text("切换到边用边学") }
            PillButton(secondary = true, onClick = onLater) { Text("稍后") }
        }
        TextButton(onClick = onImport, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text("换了新手机？从旧手机搬过来", fontSize = 13.sp, color = Fuse.accent(dark))
        }
    }
}

/** 标题下的小玻璃胶囊：在家 / 在公司 / 路上 / 学习中 */
@Composable
private fun PlaceChip(text: String, dark: Boolean, modifier: Modifier = Modifier) {
    Row(
        Modifier.padding(start = 8.dp, bottom = 2.dp).then(modifier).clip(CircleShape)
            .background(Color.White.copy(alpha = if (dark) 0.10f else 0.55f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (text.startsWith("常去")) Fuse.dim(dark) else Fuse.BusGreen))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = Fuse.ink(dark), style = TextStyle(fontFeatureSettings = "tnum"))
    }
}

/** 设置检查一行：「设置检查  N / M ›」，缺必需项时显示琥珀色提示 */
@Composable
private fun PermRow(perms: List<Perm>, dark: Boolean, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val ink = Fuse.ink(dark)
    val sum = summaryOf(perms)
    val ok = sum.ok
    val missing = sum.missing
    Row(
        Modifier.fillMaxWidth().then(modifier).glass(dark).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("设置检查", fontSize = 14.sp, color = ink)
        Spacer(Modifier.weight(1f))
        if (missing > 0) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Fuse.Amber))
            Spacer(Modifier.width(6.dp))
            Text("还差 $missing 项设置", fontSize = 13.sp, color = Fuse.Amber, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
        } else if (perms.any { it.id == PermId.LOCKED }) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Fuse.Amber))
            Spacer(Modifier.width(6.dp))
            Text("有旧记录无法读取", fontSize = 13.sp, color = Fuse.Amber)
            Spacer(Modifier.width(10.dp))
        }
        Text("$ok / ${sum.total} ›", fontSize = 14.sp, color = ink, style = TextStyle(fontFeatureSettings = "tnum"))
    }
}

@Composable
private fun PermSheet(perms: List<Perm>, onBatch: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置检查", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        SetupChecks(perms, onBatch)
    }
}

@Composable
private fun TripCard(
    trip: Trip, running: Boolean, dark: Boolean, sample: Boolean = false,
    onToggle: (Boolean) -> Unit, onTest: () -> Unit, onEdit: () -> Unit, onReverse: () -> Unit, onDelete: () -> Unit,
    onRestart: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onApplyLearned: (Learn.Suggestion) -> Unit,
) {
    val ctx = LocalContext.current
    val ink = Fuse.ink(dark)
    val dimColor = Fuse.dim(dark)
    val fade = if (trip.enabled) 1f else 0.55f
    var more by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().glass(dark).clickable(onClick = onEdit).padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(trip.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(fade))
                Text("${trip.daysText()} · 至 ${hhmm(trip.endMinute)}", fontSize = 13.sp, color = dimColor,
                    modifier = Modifier.padding(top = 3.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(hhmm(trip.startMinute), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ink,
                    style = TextStyle(fontFeatureSettings = "tnum"), modifier = Modifier.alpha(fade))
                // 演示行程不排闹钟：不显示开关
                if (!sample) Switch(
                    checked = trip.enabled, onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = if (dark) Fuse.Yellow else Fuse.Night,
                        checkedThumbColor = if (dark) Fuse.Night else Color.White,
                        checkedBorderColor = Color.Transparent,
                        uncheckedTrackColor = Color(0x667F7F8C),
                        uncheckedThumbColor = Color.White,
                        uncheckedBorderColor = Color.Transparent,
                    ),
                )
            }
        }
        RouteRow(trip, dark, Modifier.padding(top = 6.dp).alpha(fade))
        // 下次触发的说明可能较长：单独占一行（最多两行），不和「测试」「⋯」挤在一起被截断
        val nextText = when {
            sample -> "演示行程 · 不会提醒，也不会自动开始；点「开始」播放演示回放"
            !running && trip.enabled -> Scheduler.nextTriggerText(trip, ctx)
            else -> null
        }
        if (nextText != null) {
            Text(nextText, fontSize = 13.sp, color = dimColor, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp, end = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (nextText != null) 0.dp else 6.dp)) {
            Box(Modifier.weight(1f)) {
                when {
                    running -> Text(
                        "运行中", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (dark) Color(0xFF7FE0A6) else Color(0xFF167A40),
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(Fuse.BusGreen.copy(alpha = if (dark) 0.25f else 0.14f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    nextText != null -> {}
                    else -> Text("已暂停", fontSize = 13.sp, color = dimColor)
                }
            }
            TextButton(onClick = onTest, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(if (running) "进行中" else "开始", fontSize = 13.sp, color = dimColor)
            }
            // 演示行程不能编辑、删除或生成回程
            if (!sample) Box {
                TextButton(onClick = { more = true }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text("⋯", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = dimColor)
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }, shape = MenuShape, containerColor = Fuse.surface(dark)) {
                    if (running) DropdownMenuItem(text = { Text("重新开始") }, onClick = { more = false; onRestart() })
                    DropdownMenuItem(text = { Text("生成回程") }, onClick = { more = false; onReverse() })
                    DropdownMenuItem(text = { Text("编辑") }, onClick = { more = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { more = false; onDelete() })
                }
            }
        }
    }
}

/** 线路小牌：步行 — 各段线路牌 — 步行，段内多个可选线路用「/」隔开 */
@Composable
private fun RouteRow(trip: Trip, dark: Boolean, modifier: Modifier = Modifier) {
    val ink = Fuse.ink(dark)
    val legs = trip.legs.filter { it.candidates.isNotEmpty() }
    if (legs.isEmpty()) {
        Text("还没有设置线路", fontSize = 13.sp, color = Fuse.dim(dark), modifier = modifier.padding(vertical = 4.dp))
        return
    }
    @Composable
    fun Dash() = Text("—", fontSize = 13.sp, color = ink.copy(alpha = 0.35f), modifier = Modifier.padding(horizontal = 6.dp))

    // 步行小牌：开头走到第一段上车点、结尾从最后下车走到目的地，两头对称
    @Composable
    fun Walk() = Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.07f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) { Text("步", fontSize = 12.sp, color = ink) }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Walk()
        legs.take(4).forEach { leg ->
            Dash()
            leg.candidates.take(3).forEachIndexed { i, c: Candidate ->
                if (i > 0) Text("/", fontSize = 12.sp, color = ink.copy(alpha = 0.35f), modifier = Modifier.padding(horizontal = 3.dp))
                LineBadge(shortLine(c.name.ifEmpty { c.label() }), lineColor(c))
            }
        }
        if (legs.size > 4) { Dash(); Text("…", fontSize = 13.sp, color = ink) }
        Dash()
        Walk()
    }
}

/** 下班出发建议：「约 8 分钟后出发」+ 几点走、接哪班车；错过了会写「错过 18:47 那班」 */
@Composable
private fun DepartBoard(a: com.busnotice.commute.DepartAdvisor.Advice, now: Long, dark: Boolean) {
    val ink = Fuse.ink(dark)
    val dimColor = Fuse.dim(dark)
    val D = com.busnotice.commute.DepartAdvisor
    Column(Modifier.fillMaxWidth().glass(dark).padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (a.missedLeaveAt > 0) Fuse.Amber else Fuse.BusGreen))
            Spacer(Modifier.width(8.dp))
            Text("${a.tripName} · 出发建议", fontSize = 13.sp, color = dimColor)
            Spacer(Modifier.weight(1f))
            Text("${D.hm(a.leaveAt)} 走", fontSize = 13.sp, color = dimColor, style = TextStyle(fontFeatureSettings = "tnum"))
        }
        Spacer(Modifier.height(8.dp))
        Text(D.headline(a, now), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink)
        Spacer(Modifier.height(4.dp))
        Text(D.detail(a), fontSize = 13.sp, color = dimColor, style = TextStyle(fontFeatureSettings = "tnum"))
    }
}
