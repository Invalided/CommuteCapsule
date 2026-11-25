package com.busnotice.commute.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.busnotice.commute.Loc
import com.busnotice.commute.LocCheck
import com.busnotice.commute.LocFailure
import com.busnotice.commute.LocPermOutcome
import com.busnotice.commute.LocResult
import kotlinx.coroutines.launch

private val LOCATION_PERMS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** 按钮触发的「取当前位置」：busy 为 true 时正在申请权限或定位 */
class LocateAction internal constructor(private val startFn: () -> Unit, private val busyFn: () -> Boolean) {
    val busy: Boolean get() = busyFn()
    fun start() = startFn()
}

private enum class LocPrompt { PERMISSION_SETTINGS, LOCATION_SETTINGS }

/**
 * 取当前位置并处理失败原因：
 * 没有定位权限时当场申请（精确 + 粗略），授予后自动继续定位；被永久拒绝时提示并可跳转应用设置页；
 * 系统定位开关关闭时提示并可跳转系统定位设置；超时或拿不到位置时提示 [weakSignalTip]。
 */
@Composable
fun rememberLocateAction(weakSignalTip: String = "定位信号弱，稍后再试", onLocated: (Location) -> Unit): LocateAction {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onLocated)
    val tip by rememberUpdatedState(weakSignalTip)
    var busy by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<LocPrompt?>(null) }

    fun locate() {
        busy = true
        scope.launch {
            val r = Loc.currentForUser(ctx)
            busy = false
            when (r) {
                is LocResult.Ok -> latest(r.location)
                is LocResult.Failed -> when (r.reason) {
                    // 检查之后权限又被撤销：交给下次点击重新申请
                    LocFailure.NO_PERMISSION -> Toast.makeText(ctx, "需要定位权限", Toast.LENGTH_SHORT).show()
                    LocFailure.LOCATION_OFF -> prompt = LocPrompt.LOCATION_SETTINGS
                    LocFailure.UNAVAILABLE -> Toast.makeText(ctx, tip, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        busy = false
        val rationale = ctx.findActivity()?.let { a -> LOCATION_PERMS.any { a.shouldShowRequestPermissionRationale(it) } } ?: true
        when (LocCheck.afterRequest(
            fineGranted = res[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION),
            coarseGranted = res[Manifest.permission.ACCESS_COARSE_LOCATION] == true || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION),
            shouldShowRationale = rationale,
        )) {
            LocPermOutcome.GRANTED -> locate()
            LocPermOutcome.DENIED -> Toast.makeText(ctx, "未获得定位权限", Toast.LENGTH_SHORT).show()
            LocPermOutcome.DENIED_PERMANENTLY -> prompt = LocPrompt.PERMISSION_SETTINGS
        }
    }

    prompt?.let { p ->
        val dark = isSystemInDarkTheme()
        val ink = Fuse.ink(dark)
        AlertDialog(
            onDismissRequest = { prompt = null },
            shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
            title = { Text(if (p == LocPrompt.PERMISSION_SETTINGS) "定位权限已关闭" else "系统定位已关闭") },
            text = {
                Text(if (p == LocPrompt.PERMISSION_SETTINGS) "请在应用设置的权限里允许定位，然后回来再点一次。" else "请打开系统定位开关，然后回来再点一次。")
            },
            confirmButton = {
                DialogButton("去设置", primary = true, onClick = {
                    prompt = null
                    if (p == LocPrompt.PERMISSION_SETTINGS) {
                        openSettings(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    } else {
                        openSettings(ctx, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                })
            },
            dismissButton = { DialogButton("取消", onClick = { prompt = null }) },
        )
    }

    return remember {
        LocateAction(
            startFn = {
                if (!busy) {
                    val has = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (has) locate() else { busy = true; launcher.launch(LOCATION_PERMS) }
                }
            },
            busyFn = { busy },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
