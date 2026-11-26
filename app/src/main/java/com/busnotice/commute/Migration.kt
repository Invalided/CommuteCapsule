package com.busnotice.commute

import android.app.backup.BackupManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

/** [LocalData] 的 Android 实现：SharedPreferences 与 traces 目录 */
class AndroidLocalData(private val ctx: Context) : LocalData {
    private fun dir() = TraceStore.dir(ctx)

    override fun readPrefs(name: String): Map<String, Any?> = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all

    override fun writePrefs(name: String, entries: Map<String, Any?>): Boolean {
        val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        entries.forEach { (k, v) ->
            when (v) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
        }
        return e.commit()
    }

    override fun traceNames(): List<String> = TraceStore.files(ctx).map { it.name }

    override fun readTraceRaw(name: String): List<String> = synchronized(TraceStore.lock) {
        File(dir(), name).takeIf { it.exists() }?.readLines()?.filter { it.isNotEmpty() } ?: emptyList()
    }

    override fun writeTraceRaw(name: String, lines: List<String>): Boolean = synchronized(TraceStore.lock) {
        val sb = StringBuilder(); lines.forEach { sb.append(it).append('\n') }
        FileKeyFiles.atomicWrite(File(dir(), name), sb.toString().toByteArray(Charsets.UTF_8))
    }

    override fun deleteTrace(name: String): Boolean = synchronized(TraceStore.lock) {
        val f = File(dir(), name); !f.exists() || f.delete()
    }

    override fun encrypt(plain: String): String? = LocalCrypto.encrypt(ctx, plain)
}

/**
 * 迁移到新手机：导出加密迁移文件、从迁移文件恢复（替换）与撤销一次导入。
 * 撤销快照与状态存放在 noBackupFilesDir/migration，不参与系统备份，也不进迁移文件。
 */
object MigrationStore {
    private const val UNDO_FILE = "undo.zip"
    private const val STATE_FILE = "state.json"
    /** 撤销快照保留天数 */
    private const val UNDO_KEEP_DAYS = 7L
    /** 导入时清空的运行缓存（与行程编号相关） */
    private val RUNTIME_PREFS = listOf("run_state", "live_snapshot", "depart_advice")

    class Exported(val file: File, val code: String, val meta: PackageMeta)

    private fun dir(ctx: Context) = File(ctx.noBackupFilesDir, "migration").apply { mkdirs() }

    private fun appVersion(ctx: Context) = runCatching { Updater.currentName(ctx) }.getOrDefault("")

    /** 现在不能导入或撤销的原因；可以时返回 null */
    fun busyReason(): String? = when {
        CommuteService.instance != null -> "行程进行中，结束后再操作"
        DemoPlayer.snapshot.value != null || DemoPlayer.countdown.value != null -> "演示回放进行中，结束后再操作"
        else -> null
    }

    /**
     * 导出迁移文件（后台线程调用）。[includeRecords] 为 true 时把行程记录与原始定位采样在本机解密后放入包内，
     * 迁移包整体以恢复码派生的密钥加密。文件写到 cacheDir/export，由用户分享或保存。
     */
    fun export(ctx: Context, includeRecords: Boolean): Exported {
        val c = ctx.applicationContext
        runCatching { Places.migrateSamples(c) }
        if (includeRecords && !LocalCrypto.ready(c)) throw IllegalStateException("本机加密暂不可用，暂时无法导出行程轨迹，请稍后再试")
        val prefs = MigrationPackage.PREFS.associateWith { n -> MigrationPackage.portable(n, c.getSharedPreferences(n, Context.MODE_PRIVATE).all) }
        var skipped = 0
        val traces = if (!includeRecords) null else TraceStore.files(c).mapNotNull { f ->
            val raw = synchronized(TraceStore.lock) { f.readLines() }
            val d = LocalCrypto.decodeCounted(c, raw)
            skipped += d.unreadable
            if (MigrationPackage.validTraceName(f.name) && d.text.isNotEmpty()) f.name to d.text else null
        }.toMap()
        val samples: Map<String, Any>? = if (!includeRecords) null else Places.samplesJson(c)?.let { mapOf("samples" to it) } ?: emptyMap()
        val meta = PackageMeta(
            format = MigrationPackage.FORMAT, createdAt = System.currentTimeMillis(), appVersion = appVersion(c),
            trips = MigrationPackage.tripCount(prefs), includesRecords = includeRecords,
            traceDays = traces?.keys?.let(MigrationPackage::traceDays) ?: 0, skippedRecords = skipped,
        )
        val zip = MigrationPackage.write(MigrationContent(meta, prefs, traces, samples))
        val code = RecoveryCode.generate()
        val sealed = MigrationCrypto.seal(zip, code)
        val out = File(c.cacheDir, "export").apply { mkdirs() }
        out.listFiles { f -> f.name.endsWith(".${MigrationCrypto.EXTENSION}") }?.forEach { it.delete() }
        val f = File(out, "commute-migration-${LocalDate.now()}.${MigrationCrypto.EXTENSION}")
        f.writeBytes(sealed)
        return Exported(f, code, meta)
    }

    /** 读取用户选的文件（有大小上限），并检查文件头；不需要恢复码 */
    fun readFile(ctx: Context, uri: Uri): ByteArray {
        val cr = ctx.contentResolver
        val size = runCatching { cr.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
        if (size > MigrationCrypto.MAX_BYTES) throw MigrationException(MigrationException.Reason.TOO_LARGE)
        val bytes = cr.openInputStream(uri)?.use { input ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf); if (n < 0) break
                total += n
                if (total > MigrationCrypto.MAX_BYTES) throw MigrationException(MigrationException.Reason.TOO_LARGE)
                bos.write(buf, 0, n)
            }
            bos.toByteArray()
        } ?: throw MigrationException(MigrationException.Reason.NOT_MIGRATION_FILE)
        MigrationCrypto.checkHeader(bytes)
        return bytes
    }

    /** 用恢复码解密并解析（后台线程调用） */
    fun open(bytes: ByteArray, code: String): MigrationContent {
        val c = MigrationPackage.read(MigrationCrypto.open(bytes, code))
        if (c.meta.raw) throw MigrationException(MigrationException.Reason.NOT_MIGRATION_FILE)
        return c
    }

    /** 当前行程的闹钟与地理围栏全部取消（替换数据前调用） */
    private fun cancelAll(c: Context) = TripStore.all(c).forEach { runCatching { Scheduler.cancel(c, it.id) } }

    private fun clearRuntime(c: Context) = RUNTIME_PREFS.forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }

    /**
     * 导入（替换）：先保存本机快照，再替换数据；失败时自动回滚。之后重新安排闹钟与围栏。
     * 调用方随后重启进程，使各模块的内存缓存按新数据重新加载。
     */
    fun apply(ctx: Context, content: MigrationContent): Migrator.Outcome {
        val c = ctx.applicationContext
        cancelAll(c)
        val now = System.currentTimeMillis()
        val undo = File(dir(c), UNDO_FILE)
        val r = Migrator.importReplace(content, AndroidLocalData(c), now) { FileKeyFiles.atomicWrite(undo, it) }
        if (r == Migrator.Outcome.OK) {
            writeState(c, JSONObject().put("undoAt", now).put("notice", true).put("trips", MigrationPackage.tripCount(content.prefs)))
            clearRuntime(c)
            runCatching { BackupManager(c).dataChanged() }
        }
        runCatching { Scheduler.rescheduleAll(c) }
        return r
    }

    /** 撤销快照的创建时间；没有可撤销的导入时返回 null */
    fun undoAt(ctx: Context): Long? {
        val c = ctx.applicationContext
        if (!File(dir(c), UNDO_FILE).exists()) return null
        return readState(c)?.optLong("undoAt")?.takeIf { it > 0 }
    }

    /** 撤销上一次导入，恢复导入前的数据；成功后撤销快照删除（只能撤销一次） */
    fun undo(ctx: Context): Boolean {
        val c = ctx.applicationContext
        val f = File(dir(c), UNDO_FILE)
        val snap = runCatching { MigrationPackage.read(f.readBytes()) }.getOrNull() ?: return false
        if (!snap.meta.raw) return false
        cancelAll(c)
        val ok = Migrator.restore(snap, AndroidLocalData(c))
        if (ok) {
            f.delete(); File(dir(c), STATE_FILE).delete()
            clearRuntime(c)
            runCatching { BackupManager(c).dataChanged() }
        }
        runCatching { Scheduler.rescheduleAll(c) }
        return ok
    }

    /** 导入后首页显示一次「已导入」提示 */
    fun noticePending(ctx: Context): Boolean = readState(ctx.applicationContext)?.optBoolean("notice") == true && undoAt(ctx) != null

    fun importedTrips(ctx: Context): Int = readState(ctx.applicationContext)?.optInt("trips") ?: 0

    fun dismissNotice(ctx: Context) {
        val c = ctx.applicationContext
        readState(c)?.let { writeState(c, it.put("notice", false)) }
    }

    /** 撤销快照超过保留期后删除（后台线程调用） */
    fun expireUndo(ctx: Context) {
        val c = ctx.applicationContext
        val at = undoAt(c) ?: return
        if (System.currentTimeMillis() - at > UNDO_KEEP_DAYS * 86_400_000L) {
            File(dir(c), UNDO_FILE).delete(); File(dir(c), STATE_FILE).delete()
        }
    }

    private fun readState(c: Context): JSONObject? =
        runCatching { File(dir(c), STATE_FILE).takeIf { it.exists() }?.readText()?.let(::JSONObject) }.getOrNull()

    private fun writeState(c: Context, o: JSONObject) {
        FileKeyFiles.atomicWrite(File(dir(c), STATE_FILE), o.toString().toByteArray())
    }

    /** 重启应用进程，使所有内存缓存按新数据重新加载；启动时会重新安排闹钟与围栏 */
    fun restart(ctx: Context) {
        val c = ctx.applicationContext
        val launch = c.packageManager.getLaunchIntentForPackage(c.packageName) ?: return
        c.startActivity(Intent.makeRestartActivityTask(launch.component).setPackage(c.packageName))
        Runtime.getRuntime().exit(0)
    }
}
