package com.busnotice.commute.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.busnotice.commute.MigrationException
import com.busnotice.commute.MigrationStore
import com.busnotice.commute.Migrator
import com.busnotice.commute.RecoveryCode
import com.busnotice.commute.RecoveryQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun stamp(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(t))

@Composable
private fun dialogColors(): Triple<Color, Color, Boolean> {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return Triple(Fuse.surface(dark), Fuse.ink(dark), dark)
}

@Composable
private fun Hint(text: String, color: Color = Color.Unspecified) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = if (color == Color.Unspecified) Fuse.dim(dark) else color)
}

/** 恢复码的二维码：白底黑块，深色模式下同样可识别 */
@Composable
private fun QrImage(text: String, modifier: Modifier = Modifier) {
    val m = remember(text) { RecoveryQr.matrix(text) }
    Canvas(modifier.background(Color.White)) {
        val cell = size.width / m.size
        for (y in m.indices) for (x in m[y].indices) {
            if (m[y][x]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

// ---------------- 导出 ----------------

/** 设置 → 迁移到新手机：选择内容 → 生成加密迁移文件 → 显示恢复码（文字与二维码）→ 分享或保存文件 */
@Composable
fun ExportMigrationDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (surface, ink, _) = dialogColors()
    var includeRecords by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<MigrationStore.Exported?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = result?.file ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(f.readBytes()) } != null }.getOrDefault(false)
            }
            Toast.makeText(ctx, if (ok) "迁移文件已保存" else "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    val done = result
    if (done == null) {
        AlertDialog(
            onDismissRequest = { if (!working) onDismiss() },
            shape = DialogShape, containerColor = surface, titleContentColor = ink, textContentColor = ink,
            title = { Text("迁移到新手机") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Hint("导出一个加密的迁移文件，包含行程、设置、学习结果、家和公司、出门时的 Wi-Fi 判断等。在新手机上安装通勤胶囊后，用「从迁移文件恢复」导入。")
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !working) { includeRecords = !includeRecords },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = includeRecords, onCheckedChange = { includeRecords = it }, enabled = !working)
                        Text("包含行程轨迹和原始定位", fontSize = 15.sp)
                    }
                    Hint(if (includeRecords) "这些记录会先在本机解密，再放进迁移文件；迁移文件整体加密，没有恢复码无法打开。"
                        else "不勾选时只迁移行程、设置和学习结果，行程轨迹和原始定位留在这台手机上。")
                    Hint("行程轨迹只在本机加密保存，系统清空密钥后无法找回；自己保留一份迁移文件，也可以作为备份。")
                    if (working) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Hint("正在生成迁移文件…")
                    }
                    error?.let { Hint(it, Fuse.Amber) }
                }
            },
            confirmButton = {
                DialogButton("导出", primary = true, enabled = !working, onClick = {
                    working = true; error = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { MigrationStore.export(ctx, includeRecords) } }
                        working = false
                        r.onSuccess { result = it }.onFailure { error = (it as? IllegalStateException)?.message ?: "导出失败，请稍后再试" }
                    }
                })
            },
            dismissButton = { DialogButton("取消", enabled = !working, onClick = onDismiss) },
        )
        return
    }

    // 恢复码只显示这一次：点外部或返回键不关闭，须点「我已记下」
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        shape = DialogShape, containerColor = surface, titleContentColor = ink, textContentColor = ink,
        title = { Text("恢复码") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val groups = RecoveryCode.format(done.code)
                Text(groups.substring(0, 14) + "\n" + groups.substring(15), fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                QrImage(RecoveryCode.qrPayload(done.code), Modifier.size(180.dp))
                Hint("请截图或抄下恢复码。迁移文件里不含恢复码，导入时需要输入或扫描它；恢复码丢失后只能重新导出。")
                val m = done.meta
                Hint("${m.trips} 条行程 · " + (if (m.includesRecords) "含行程轨迹和原始定位（${m.traceDays} 天）" else "不含行程轨迹和原始定位") +
                    (if (m.skippedRecords > 0) " · ${m.skippedRecords} 条暂时无法读取的记录未放入" else ""))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(onClick = { shareFile(ctx, done.file) }) { Text("分享文件") }
                    PillButton(secondary = true, onClick = { saveLauncher.launch(done.file.name) }) { Text("保存到文件") }
                }
            }
        },
        confirmButton = { DialogButton("我已记下", primary = true, onClick = onDismiss) },
    )
}

private fun shareFile(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    ctx.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "发送迁移文件")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------------- 导入 ----------------

/** 从图片中识别恢复码二维码（相册、截图或拍照）；识别不到时返回 null */
private fun scanImage(ctx: Context, uri: Uri): String? {
    for (maxDim in intArrayOf(1600, 2600)) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val w = bmp.width; val h = bmp.height
        bmp.recycle()
        RecoveryQr.decode(px, w, h)?.let { return RecoveryCode.fromQr(it) }
        if (sample == 1) break
    }
    return null
}

private sealed interface ImportStep {
    object Pick : ImportStep
    class Code(val file: File) : ImportStep
    class Preview(val opened: MigrationStore.Opened) : ImportStep
    object Applying : ImportStep
}

/**
 * 从迁移文件恢复：选文件 → 输入或识别恢复码 → 预览 → 确认替换。
 * 导入成功后重启 App；[onClose] 在用户取消或导入失败时调用。
 */
@Composable
fun ImportMigrationFlow(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (surface, ink, _) = dialogColors()
    var step by remember { mutableStateOf<ImportStep>(ImportStep.Pick) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { onClose(); return@rememberLauncherForActivityResult }
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { MigrationStore.readFile(ctx, uri) } }
            r.onSuccess { step = ImportStep.Code(it) }.onFailure {
                Toast.makeText(ctx, (it as? MigrationException)?.userMessage ?: "无法读取这个文件", Toast.LENGTH_LONG).show()
                onClose()
            }
        }
    }
    fun onScanned(found: String?) {
        if (found != null) { code = RecoveryCode.format(found); error = null } else error = "没有识别到恢复码二维码，请换一张更清晰的图片，或手动输入"
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { onScanned(withContext(Dispatchers.IO) { runCatching { scanImage(ctx, uri) }.getOrNull() }) }
    }
    val photoFile = remember { File(File(ctx.cacheDir, "scan").apply { mkdirs() }, "recovery-code.jpg") }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (!ok) return@rememberLauncherForActivityResult
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { scanImage(ctx, Uri.fromFile(photoFile)) }.getOrNull() }
            photoFile.delete()
            onScanned(found)
        }
    }

    LaunchedEffect(Unit) {
        val busyReason = MigrationStore.busyReason()
        if (busyReason != null) { Toast.makeText(ctx, busyReason, Toast.LENGTH_LONG).show(); onClose(); return@LaunchedEffect }
        pickFile.launch(arrayOf("*/*"))
    }

    when (val s = step) {
        ImportStep.Pick -> Unit
        is ImportStep.Code -> AlertDialog(
            onDismissRequest = { if (!busy) onClose() },
            shape = DialogShape, containerColor = surface, titleContentColor = ink, textContentColor = ink,
            title = { Text("输入恢复码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Hint("导出迁移文件时显示的 24 位恢复码，可以手动输入，也可以识别恢复码的截图或照片。")
                    OutlinedTextField(
                        value = code, onValueChange = { code = it.uppercase(); error = null },
                        singleLine = false, minLines = 2, placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX") },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 17.sp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(secondary = true, enabled = !busy, onClick = {
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", photoFile)
                            try { takePhoto.launch(uri) } catch (e: ActivityNotFoundException) {
                                Toast.makeText(ctx, "没有可用的相机应用，请从相册识别或手动输入", Toast.LENGTH_LONG).show()
                            }
                        }) { Text("拍照识别") }
                        PillButton(secondary = true, enabled = !busy, onClick = {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text("从相册识别") }
                    }
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Hint("正在解密…")
                    }
                    error?.let { Hint(it, Fuse.Amber) }
                }
            },
            confirmButton = {
                DialogButton("下一步", primary = true, enabled = !busy && RecoveryCode.normalize(code) != null, onClick = {
                    busy = true; error = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { MigrationStore.open(s.file, code) } }
                        busy = false
                        r.onSuccess { step = ImportStep.Preview(it) }
                            .onFailure { error = (it as? MigrationException)?.userMessage ?: "恢复码不对或文件已损坏" }
                    }
                })
            },
            dismissButton = { DialogButton("取消", enabled = !busy, onClick = onClose) },
        )
        is ImportStep.Preview -> {
            val m = s.opened.preview
            AlertDialog(
                onDismissRequest = onClose,
                shape = DialogShape, containerColor = surface, titleContentColor = ink, textContentColor = ink,
                title = { Text("导入迁移文件？") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("导出时间：${stamp(m.createdAt)}", fontSize = 14.sp)
                        Text("行程：${m.trips} 条", fontSize = 14.sp)
                        Text("设置、学习结果、家和公司：包含", fontSize = 14.sp)
                        Text("行程轨迹和原始定位：" + if (m.includesRecords) "包含（${m.traceDays} 天）" else "不包含", fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Hint("导入会替换这台手机上当前的行程、设置和学习结果" +
                            (if (m.includesRecords) "，以及行程轨迹和原始定位" else "；这台手机已有的行程轨迹保持不变") +
                            "。导入前会自动保存一份本机快照，7 天内可在「⋯ → 设置 → 迁移」中撤销一次。导入完成后 App 会自动重新打开。")
                    }
                },
                confirmButton = {
                    DialogButton("替换并导入", primary = true, onClick = {
                        val opened = s.opened
                        step = ImportStep.Applying
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { MigrationStore.apply(ctx, opened) }.getOrNull() }
                            if (r == Migrator.Outcome.OK) {
                                Toast.makeText(ctx, "导入完成，正在重新打开", Toast.LENGTH_SHORT).show()
                                MigrationStore.restart(ctx)
                            } else {
                                Toast.makeText(ctx, when (r) {
                                    Migrator.Outcome.REJECTED -> "导入失败：文件已损坏或恢复码不对，本机数据未改动"
                                    Migrator.Outcome.KEY_UNAVAILABLE -> "本机加密暂不可用，未做任何改动，请稍后再试"
                                    Migrator.Outcome.STAGING_FAILED -> "无法写入暂存数据（存储空间不足？），未做任何改动"
                                    Migrator.Outcome.UNDO_SAVE_FAILED -> "无法保存本机快照（存储空间不足？），未做任何改动"
                                    Migrator.Outcome.ROLLED_BACK -> "导入失败，已恢复导入前的数据"
                                    else -> "导入失败"
                                }, Toast.LENGTH_LONG).show()
                                onClose()
                            }
                        }
                    })
                },
                dismissButton = { DialogButton("取消", onClick = onClose) },
            )
        }
        ImportStep.Applying -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            shape = DialogShape, containerColor = surface, titleContentColor = ink, textContentColor = ink,
            title = { Text("正在导入") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Hint("正在替换数据并重新加密记录…")
                }
            },
            confirmButton = {},
        )
    }
}

// ---------------- 撤销 ----------------

/** 撤销上一次导入：确认后恢复导入前的数据并重启 App */
@Composable
fun UndoImportDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (surface, ink, _) = dialogColors()
    val at = remember { MigrationStore.undoAt(ctx) }
    var working by remember { mutableStateOf(false) }
    if (at == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        shape = DialogShape, containerColor = surface, titleContentColor = ink, textContentColor = ink,
        title = { Text("撤销导入？") },
        text = { Hint("恢复到 ${stamp(at)} 导入之前这台手机上的行程、设置和学习结果；导入的数据会被替换。只能撤销一次，完成后 App 会自动重新打开。") },
        confirmButton = {
            DialogButton("撤销导入", primary = true, enabled = !working, onClick = {
                val busyReason = MigrationStore.busyReason()
                if (busyReason != null) { Toast.makeText(ctx, busyReason, Toast.LENGTH_LONG).show(); return@DialogButton }
                working = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { MigrationStore.undo(ctx) }.getOrDefault(false) }
                    if (ok) MigrationStore.restart(ctx)
                    else { working = false; Toast.makeText(ctx, "撤销失败，数据保持导入后的状态", Toast.LENGTH_LONG).show(); onDismiss() }
                }
            })
        },
        dismissButton = { DialogButton("取消", enabled = !working, onClick = onDismiss) },
    )
}

/** 导入完成后首页显示一次：说明已导入，并可撤销 */
@Composable
fun ImportedNotice(dark: Boolean, trips: Int, onUndo: () -> Unit, onDismiss: () -> Unit) {
    val ink = Fuse.ink(dark)
    Column(Modifier.fillMaxWidth().glass(dark).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Fuse.BusGreen))
            Spacer(Modifier.width(8.dp))
            Text("已从迁移文件恢复", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink)
        }
        Spacer(Modifier.height(4.dp))
        Text("导入了 $trips 条行程，以及设置和学习结果。如果导错了，7 天内可以撤销一次，恢复导入前的数据。",
            fontSize = 13.sp, lineHeight = 18.sp, color = Fuse.dim(dark))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(secondary = true, onClick = onUndo) { Text("撤销导入") }
            PillButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}
