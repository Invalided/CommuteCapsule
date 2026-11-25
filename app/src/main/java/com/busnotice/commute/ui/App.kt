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
}

@Composable
fun App(resumeTick: Int) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var draft by remember { mutableStateOf(Trip()) }
    // 进入编辑页时的行程：用来判断返回时有没有未保存的改动
    var baseline by remember { mutableStateOf<Trip?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

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

    BackHandler(enabled = screen != Screen.Home) {
        if (screen == Screen.Edit) leaveEditor() else screen = Screen.Edit
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            resumeTick = resumeTick,
            onNew = { openEditor(Trip(legs = listOf(Leg(emptyList())))) },
            onEdit = { openEditor(it) },
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
