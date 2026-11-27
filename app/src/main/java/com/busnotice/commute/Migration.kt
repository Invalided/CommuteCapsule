package com.busnotice.commute

import android.app.backup.BackupManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate

/**
 * 先把内容写到同目录临时文件并同步落盘，再整体替换 [f]；[write] 抛出异常或替换失败时删除临时文件、[f] 不变。
 */
internal fun atomicWriteStream(f: File, write: (OutputStream) -> Unit): Boolean {
    f.parentFile?.mkdirs()
    val tmp = File(f.parentFile, f.name + ".tmp")
    return runCatching {
        FileOutputStream(tmp).use { fos ->
            val out = fos.buffered(64 * 1024)
            write(out)
            out.flush(); fos.fd.sync()
        }
        tmp.renameTo(f) || run { tmp.delete(); false }
    }.getOrElse { tmp.delete(); false }
}

/** [LocalData] 的 Android 实现：SharedPreferences 与 traces 目录 */
class AndroidLocalData(private val ctx: Context) : LocalData {
    private fun dir() = TraceStore.dir(ctx)
    /** 导入与撤销时的记录暂存区，与 traces 目录在同一存储分区，换入时直接改名 */
    private fun staging() = File(ctx.noBackupFilesDir, "migration/staging").apply { mkdirs() }

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

    override fun stageTrace(name: String, lines: List<String>): Boolean =
        atomicWriteStream(File(staging(), name)) { out -> lines.forEach { out.write(it.toByteArray(Charsets.UTF_8)); out.write('\n'.code) } }

    override fun commitStaged(name: String): Boolean = synchronized(TraceStore.lock) {
        val src = File(staging(), name)
        src.exists() && src.renameTo(File(dir(), name))
    }

    override fun clearStaged() {
        staging().listFiles()?.forEach { it.delete() }
    }
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
     * 迁移包以恢复码派生的密钥分块加密。记录文件逐个读取、解密并写出，内存占用与记录总量无关。
     * 先写临时文件，完成并同步落盘后再改名为正式文件；失败时删除临时文件。文件写到 cacheDir/export，由用户分享或保存。
     */
    fun export(ctx: Context, includeRecords: Boolean): Exported {
        val c = ctx.applicationContext
        runCatching { Places.migrateSamples(c) }
        if (includeRecords && !LocalCrypto.ready(c)) throw IllegalStateException("本机加密暂不可用，暂时无法导出行程轨迹，请稍后再试")
        val prefs = MigrationPackage.PREFS.associateWith { n -> MigrationPackage.portable(n, c.getSharedPreferences(n, Context.MODE_PRIVATE).all) }
        val traces = if (!includeRecords) null else TraceStore.files(c).map { f ->
            MigrationExport.Trace(f.name, f.length() > 0) {
                val raw = synchronized(TraceStore.lock) { if (f.exists()) f.readLines() else emptyList() }
                LocalCrypto.decodeCounted(c, raw)
            }
        }
        val samples: Map<String, Any>? = if (!includeRecords) null else Places.samplesJson(c)?.let { mapOf("samples" to it) } ?: emptyMap()
        val code = RecoveryCode.generate()
        val out = File(c.cacheDir, "export").apply { mkdirs() }
        out.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        val f = File(out, "commute-migration-${LocalDate.now()}.${MigrationCrypto.EXTENSION}")
        val tmp = File(out, f.name + ".tmp")
        val result = try {
            FileOutputStream(tmp).use { fos ->
                val buffered = fos.buffered(64 * 1024)
                val r = MigrationExport.write(buffered, code, System.currentTimeMillis(), appVersion(c), prefs, traces, samples)
                buffered.flush(); fos.fd.sync()
                r
            }
        } catch (e: Exception) {
            tmp.delete(); throw e
        }
        out.listFiles { x -> x.name.endsWith(".${MigrationCrypto.EXTENSION}") && x.name != f.name }?.forEach { it.delete() }
        if (!tmp.renameTo(f)) { tmp.delete(); throw IOException("rename failed") }
        return Exported(f, code, result.meta)
    }

    private fun importDir(c: Context) = File(c.cacheDir, "import").apply { mkdirs() }

    /** 已选的迁移文件与恢复码；[preview] 为只读开头得到的预览信息 */
    class Opened(val file: File, val code: String, val preview: PackageMeta)

    /**
     * 把用户选的文件复制到 App 的缓存目录（检查文件头与大小上限），返回副本；不需要恢复码。
     * 先写临时文件，完成后再改名；失败时不留副本。
     */
    fun readFile(ctx: Context, uri: Uri): File {
        val c = ctx.applicationContext
        val dir = importDir(c)
        dir.listFiles()?.forEach { it.delete() }
        val cr = c.contentResolver
        val size = runCatching { cr.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
        if (size > MigrationCrypto.MAX_STREAM_BYTES) throw MigrationException(MigrationException.Reason.TOO_LARGE)
        val f = File(dir, "pending.${MigrationCrypto.EXTENSION}")
        val tmp = File(dir, f.name + ".tmp")
        try {
            val opened = cr.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    val prefix = java.io.ByteArrayOutputStream()
                    var limit = -1L
                    var total = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        total += n
                        if (limit < 0) {
                            prefix.write(buf, 0, minOf(n, MigrationCrypto.PREFIX_LEN - prefix.size()))
                            if (prefix.size() >= MigrationCrypto.PREFIX_LEN) limit = MigrationCrypto.maxBytes(MigrationCrypto.checkHeader(prefix.toByteArray()))
                        }
                        if (limit >= 0 && total > limit) throw MigrationException(MigrationException.Reason.TOO_LARGE)
                        fos.write(buf, 0, n)
                    }
                    if (limit < 0) MigrationCrypto.checkHeader(prefix.toByteArray())
                    fos.flush(); fos.fd.sync()
                }
                true
            }
            if (opened != true) throw MigrationException(MigrationException.Reason.NOT_MIGRATION_FILE)
            if (!tmp.renameTo(f)) throw IOException("rename failed")
        } catch (e: Exception) {
            tmp.delete(); f.delete(); throw e
        }
        return f
    }

    private fun source(o: Opened) = PackageSource { MigrationCrypto.openStream(FileInputStream(o.file).buffered(64 * 1024), o.code) }

    /** 用恢复码解密并只读迁移包开头（清单与设置），用于预览（后台线程调用） */
    fun open(file: File, code: String): Opened {
        val s = MigrationCrypto.openStream(FileInputStream(file).buffered(64 * 1024), code).use { MigrationPackage.scan(it, headOnly = true) }
        if (s.meta.raw) throw MigrationException(MigrationException.Reason.NOT_MIGRATION_FILE)
        return Opened(file, code, Migrator.preview(s))
    }

    /** 当前行程的闹钟与地理围栏全部取消（替换数据前调用） */
    private fun cancelAll(c: Context) = TripStore.all(c).forEach { runCatching { Scheduler.cancel(c, it.id) } }

    private fun clearRuntime(c: Context) = RUNTIME_PREFS.forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }

    /**
     * 导入（替换）：先保存本机快照，再替换数据；失败时自动回滚。之后重新安排闹钟与围栏。
     * 调用方随后重启进程，使各模块的内存缓存按新数据重新加载。
     */
    fun apply(ctx: Context, opened: Opened): Migrator.Outcome {
        val c = ctx.applicationContext
        cancelAll(c)
        val now = System.currentTimeMillis()
        val r = Migrator.importReplace(source(opened), AndroidLocalData(c), now, undoStore(c))
        opened.file.delete()
        if (r == Migrator.Outcome.OK) {
            writeState(c, JSONObject().put("undoAt", now).put("notice", true).put("trips", opened.preview.trips))
            clearRuntime(c)
            runCatching { BackupManager(c).dataChanged() }
        }
        runCatching { Scheduler.rescheduleAll(c) }
        return r
    }

    /** 撤销快照：写临时文件后原子替换 */
    private fun undoStore(c: Context) = object : UndoStore {
        private val f = File(dir(c), UNDO_FILE)
        override fun save(write: (OutputStream) -> Unit) = atomicWriteStream(f, write)
        override fun open() = FileInputStream(f).buffered(64 * 1024)
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
        val head = runCatching { FileInputStream(f).buffered().use { MigrationPackage.scan(it, headOnly = true) } }.getOrNull() ?: return false
        if (!head.meta.raw) return false
        cancelAll(c)
        val ok = Migrator.restore(PackageSource { FileInputStream(f).buffered(64 * 1024) }, AndroidLocalData(c))
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
