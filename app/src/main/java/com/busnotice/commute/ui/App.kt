package com.busnotice.commute.ui

import androidx.activity.compose.BackHandler
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
    data object Onboarding : Screen
    data class Keepalive(val fromSettings: Boolean) : Screen
}

@Composable
fun App(resumeTick: Int) {
    val ctx = LocalContext.current
    // 首次启动进引导（升级上来的老用户不弹）
    var screen by remember { mutableStateOf<Screen>(if (OnboardingState.needed(ctx)) Screen.Onboarding else Screen.Home) }
    var draft by remember { mutableStateOf(Trip()) }
    // 选线路页返回到哪：编辑页，或首次引导里的「第一条行程」
    var pickerReturn by remember { mutableStateOf<Screen>(Screen.Edit) }
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
        screen = pickerReturn
    }

    // 首次引导自己处理返回（欢迎页按返回直接退出 App）
    BackHandler(enabled = screen != Screen.Home && screen != Screen.Onboarding) {
        when (val s = screen) {
            Screen.Edit -> leaveEditor()
            Screen.Settings -> screen = Screen.Home
            is Screen.Keepalive -> screen = if (s.fromSettings) Screen.Settings else Screen.Home
            else -> screen = pickerReturn
        }
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            resumeTick = resumeTick,
            onNew = { pickerReturn = Screen.Edit; openEditor(Trip(legs = listOf(Leg(emptyList())))) },
            onEdit = { pickerReturn = Screen.Edit; openEditor(it) },
            onSettings = { screen = Screen.Settings },
            onKeepalive = { screen = Screen.Keepalive(fromSettings = false) },
        )
        Screen.Settings -> SettingsScreen(
            onBack = { screen = Screen.Home },
            onKeepalive = { screen = Screen.Keepalive(fromSettings = true) },
            onOnboarding = { OnboardingState.restart(ctx); draft = Trip(); screen = Screen.Onboarding },
        )
        is Screen.Keepalive -> KeepaliveScreen(resumeTick) {
            screen = if (s.fromSettings) Screen.Settings else Screen.Home
        }
        Screen.Onboarding -> Onboarding(
            resumeTick = resumeTick,
            draft = draft,
            onDraftChange = { draft = it },
            onAddLine = { leg, kind ->
                pickerReturn = Screen.Onboarding
                screen = when (kind) {
                    LineKind.BUS -> Screen.PickBus(leg)
                    LineKind.METRO -> Screen.PickMetro(leg)
                    LineKind.SHUTTLE -> Screen.EditShuttle(leg, -1)
                }
            },
            onFinish = { pickerReturn = Screen.Edit; draft = Trip(); screen = Screen.Home },
        )
        Screen.Edit -> TripEditor(
            trip = draft,
            onChange = { draft = it },
            onBack = { leaveEditor() },
            onSaved = { baseline = null; screen = Screen.Home },
            onAddBus = { screen = Screen.PickBus(it) },
            onAddMetro = { screen = Screen.PickMetro(it) },
            onEditShuttle = { leg, idx -> screen = Screen.EditShuttle(leg, idx) },
        )
        is Screen.PickBus -> BusPicker(
            onBack = { screen = pickerReturn },
            onPicked = { addCandidate(s.legIndex, it) },
        )
        is Screen.PickMetro -> MetroPicker(
            onBack = { screen = pickerReturn },
            onPicked = { addCandidate(s.legIndex, it) },
        )
        is Screen.EditShuttle -> ShuttleEditor(
            initial = draft.legs.getOrNull(s.legIndex)?.candidates?.getOrNull(s.candidateIndex),
            onBack = { screen = pickerReturn },
            onDone = { addCandidate(s.legIndex, it, s.candidateIndex) },
        )
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
                    if (saveTrip(ctx, draft)) { baseline = null; screen = Screen.Home }
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
