package com.busnotice.commute.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import com.busnotice.commute.Candidate
import com.busnotice.commute.Leg
import com.busnotice.commute.Trip

sealed interface Screen {
    data object Home : Screen
    data object Edit : Screen
    data class PickBus(val legIndex: Int) : Screen
    data class PickMetro(val legIndex: Int) : Screen
    data class EditShuttle(val legIndex: Int, val candidateIndex: Int) : Screen
    data object Settings : Screen
    data object Checks : Screen
}

@Composable
fun App(resumeTick: Int) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var draft by remember { mutableStateOf(Trip()) }
    // 首次启动在首页叠加聚光引导（升级上来的老用户不显示）
    var coachOn by remember { mutableStateOf(OnboardingState.needed(ctx)) }
    var coachStep by remember { mutableStateOf(OnboardingState.step(ctx)) }
    val coachTargets = remember { CoachTargets() }
    // 进入编辑页时的行程：用来判断返回时有没有未保存的改动
    var baseline by remember { mutableStateOf<Trip?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }

    fun openEditor(t: Trip) { draft = t; baseline = t; screen = Screen.Edit }
    fun leaveEditor() {
        if (draftNeedsConfirm(ctx, draft, baseline)) confirmLeave = true else { baseline = null; screen = Screen.Home }
    }

    fun addCandidate(legIndex: Int, c: Candidate, replaceAt: Int = -1) {
        val legs = draft.legs.toMutableList()
        while (legs.size <= legIndex) legs += Leg(emptyList())
        val cands = legs[legIndex].candidates.toMutableList()
        if (replaceAt in cands.indices) cands[replaceAt] = c else cands += c
        legs[legIndex] = legs[legIndex].copy(candidates = cands)
        draft = draft.copy(legs = legs)
        screen = Screen.Edit
    }

    // 通知权限在保存行程时按需申请（Android 13 及以上），只申请一次；之后在首页「设置检查」里开启
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Toast.makeText(ctx, "没有通知权限，到点时看不到胶囊；可在首页「设置检查」里开启", Toast.LENGTH_LONG).show()
    }
    fun afterSave() {
        baseline = null; screen = Screen.Home
        val progress = OnboardingState.progress(ctx)
        if (NotifyAsk.shouldAsk(Build.VERSION.SDK_INT, granted(ctx, Manifest.permission.POST_NOTIFICATIONS), progress.notifyAsked)) {
            progress.markNotifyAsked()
            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = screen != Screen.Home) {
        when (screen) {
            Screen.Edit -> leaveEditor()
            Screen.Settings -> screen = Screen.Home
            Screen.Checks -> screen = Screen.Settings
            else -> screen = Screen.Edit
        }
    }

    CompositionLocalProvider(LocalCoachTargets provides coachTargets) {
        Box(Modifier.fillMaxSize()) {
            when (val s = screen) {
                Screen.Home -> HomeScreen(
                    resumeTick = resumeTick,
                    onNew = { openEditor(Trip(legs = listOf(Leg(emptyList())))) },
                    onEdit = { openEditor(it) },
                    onSettings = { screen = Screen.Settings },
                )
                Screen.Settings -> SettingsScreen(
                    onBack = { screen = Screen.Home },
                    onChecks = { screen = Screen.Checks },
                    onOnboarding = {
                        OnboardingState.replay(ctx); coachStep = OnboardingState.step(ctx); coachOn = true; screen = Screen.Home
                    },
                )
                Screen.Checks -> SetupScreen(resumeTick) { screen = Screen.Settings }
                Screen.Edit -> TripEditor(
                    trip = draft,
                    onChange = { draft = it },
                    onBack = { leaveEditor() },
                    onSaved = { afterSave() },
                    onAddBus = { screen = Screen.PickBus(it) },
                    onAddMetro = { screen = Screen.PickMetro(it) },
                    onEditShuttle = { leg, idx -> screen = Screen.EditShuttle(leg, idx) },
                )
                is Screen.PickBus -> BusPicker(
                    onBack = { screen = Screen.Edit },
                    onPicked = { addCandidate(s.legIndex, it) },
                )
                is Screen.PickMetro -> MetroPicker(
                    onBack = { screen = Screen.Edit },
                    onPicked = { addCandidate(s.legIndex, it) },
                )
                is Screen.EditShuttle -> ShuttleEditor(
                    initial = draft.legs.getOrNull(s.legIndex)?.candidates?.getOrNull(s.candidateIndex),
                    onBack = { screen = Screen.Edit },
                    onDone = { addCandidate(s.legIndex, it, s.candidateIndex) },
                )
            }
            if (coachOn && screen == Screen.Home) {
                CoachMarks(
                    targets = coachTargets,
                    step = coachStep,
                    onStep = { coachStep = it; OnboardingState.saveStep(ctx, it) },
                    onDone = { coachOn = false; OnboardingState.finish(ctx) },
                )
            }
        }
    }

    if (confirmLeave) {
        val dark = isSystemInDarkTheme()
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            shape = DialogShape, containerColor = Fuse.surface(dark),
            titleContentColor = Fuse.ink(dark), textContentColor = Fuse.ink(dark),
            title = { Text("保存修改？") },
            text = { Text("这条行程有还没保存的修改。") },
            confirmButton = {
                DialogButton("保存", primary = true, onClick = {
                    confirmLeave = false
                    if (saveTrip(ctx, draft)) afterSave()
                })
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    DialogButton("继续编辑", onClick = { confirmLeave = false })
                    DialogButton("放弃", onClick = { confirmLeave = false; baseline = null; screen = Screen.Home })
                }
            },
        )
    }
}
