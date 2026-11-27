package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.crypto.spec.SecretKeySpec

/** 导入（替换）、失败回滚与撤销 */
class MigratorTest {
    private class MemLocal(val cipher: RecordCipher?) : LocalData {
        val prefs = HashMap<String, Map<String, Any?>>()
        val traces = sortedMapOf<String, List<String>>()
        var failPrefs: String? = null
        override fun readPrefs(name: String) = prefs[name].orEmpty()
        override fun writePrefs(name: String, entries: Map<String, Any?>): Boolean {
            if (name == failPrefs) return false
            prefs[name] = entries.toMap(); return true
        }
        override fun traceNames() = traces.keys.toList()
        override fun readTraceRaw(name: String) = traces[name].orEmpty()
        override fun writeTraceRaw(name: String, lines: List<String>): Boolean { traces[name] = lines; return true }
        override fun deleteTrace(name: String): Boolean { traces.remove(name); return true }
        override fun encrypt(plain: String) = cipher?.encrypt(plain)
        val staged = sortedMapOf<String, List<String>>()
        override fun stageTrace(name: String, lines: List<String>): Boolean { staged[name] = lines; return true }
        override fun commitStaged(name: String): Boolean { traces[name] = staged.remove(name) ?: return false; return true }
        override fun clearStaged() = staged.clear()
    }

    /** 内存中的撤销快照；[fail] 为 true 时保存失败 */
    private class MemUndo(val fail: Boolean = false) : UndoStore {
        var bytes: ByteArray? = null
        var saved = false
        override fun save(write: (OutputStream) -> Unit): Boolean {
            saved = true
            if (fail) return false
            bytes = ByteArrayOutputStream().also(write).toByteArray(); return true
        }
        override fun open() = ByteArrayInputStream(bytes!!)
    }

    private fun src(c: MigrationContent) = PackageSource { ByteArrayInputStream(MigrationPackage.write(c)) }

    private fun importReplace(c: MigrationContent, l: MemLocal, now: Long, undo: MemUndo = MemUndo()) =
        Migrator.importReplace(src(c), l, now, undo)

    private val localKey = RecordCipher(SecretKeySpec(ByteArray(32) { 3 }, "AES"), "beef0001")

    private fun local(): MemLocal = MemLocal(localKey).apply {
        prefs["trips"] = mapOf("list" to """[{"id":"local"}]""")
        prefs["places"] = mapOf("manual_HOME" to "1.0,2.0")
        prefs[MigrationPackage.SAMPLE_PREFS] = mapOf("samples" to localKey.encrypt("[[1.0,2.0,5.0,1]]"))
        traces["2030-02-01_local000.jsonl"] = listOf(localKey.encrypt("""{"k":"local"}"""))
    }

    private fun pkg(withRecords: Boolean) = MigrationContent(
        PackageMeta(MigrationPackage.FORMAT, 10L, "x", 1, withRecords, 1, 0),
        mapOf("trips" to mapOf("list" to """[{"id":"imported"}]"""), "learn" to mapOf("k" to 1)),
        if (withRecords) mapOf("2030-01-01_imported.jsonl" to listOf("""{"k":"loc","lat":1.0}""")) else null,
        if (withRecords) mapOf("samples" to "[[3.0,4.0,5.0,2]]") else null,
    )

    @Test fun importReplacesSettingsAndReEncryptsRecords() {
        val l = local()
        val undo = MemUndo()
        assertEquals(Migrator.Outcome.OK, importReplace(pkg(true), l, 99L, undo))
        assertEquals("""[{"id":"imported"}]""", l.prefs["trips"]!!["list"])
        assertEquals(mapOf<String, Any?>("k" to 1), l.prefs["learn"])
        // 迁移包没有的白名单文件被清空（替换语义）
        assertTrue(l.prefs["places"]!!.isEmpty())
        // 记录用本机密钥重新加密，旧文件被替换
        assertEquals(setOf("2030-01-01_imported.jsonl"), l.traces.keys)
        val line = l.traces.values.single().single()
        assertTrue(line.startsWith("e2:beef0001:"))
        assertEquals("""{"k":"loc","lat":1.0}""", localKey.decrypt(line))
        assertEquals("[[3.0,4.0,5.0,2]]", localKey.decrypt(l.prefs[MigrationPackage.SAMPLE_PREFS]!!["samples"] as String))
        // 撤销快照按落盘原样保存（记录不解密）
        val snap = MigrationPackage.read(undo.bytes!!)
        assertTrue(snap.meta.raw)
        assertTrue(snap.traces!!.values.single().single().startsWith("e2:beef0001:"))
    }

    @Test fun undoRestoresThePreviousData() {
        val l = local()
        val before = l.prefs.toMap() to l.traces.toMap()
        val undo = MemUndo()
        importReplace(pkg(true), l, 99L, undo)
        assertTrue(Migrator.restore(PackageSource { undo.open() }, l))
        assertEquals(before.second, l.traces.toMap())
        assertEquals(before.first["trips"], l.prefs["trips"])
        assertEquals(before.first["places"], l.prefs["places"])
        assertEquals(before.first[MigrationPackage.SAMPLE_PREFS], l.prefs[MigrationPackage.SAMPLE_PREFS])
    }

    @Test fun importWithoutRecordsKeepsLocalRecords() {
        val l = local()
        val traces = l.traces.toMap(); val samples = l.prefs[MigrationPackage.SAMPLE_PREFS]
        val undo = MemUndo()
        assertEquals(Migrator.Outcome.OK, importReplace(pkg(false), l, 99L, undo))
        assertEquals(traces, l.traces.toMap())
        assertEquals(samples, l.prefs[MigrationPackage.SAMPLE_PREFS])
        assertNull(MigrationPackage.read(undo.bytes!!).traces)
    }

    @Test fun nothingChangesWhenKeyOrUndoSnapshotIsUnavailable() {
        val noKey = MemLocal(null).apply { prefs["trips"] = mapOf("list" to "[]") }
        val u = MemUndo()
        assertEquals(Migrator.Outcome.KEY_UNAVAILABLE, importReplace(pkg(true), noKey, 1L, u))
        assertFalse(u.saved)
        assertEquals("[]", noKey.prefs["trips"]!!["list"])
        assertTrue(noKey.staged.isEmpty())
        val l = local(); val before = l.prefs.toMap(); val traces = l.traces.toMap()
        assertEquals(Migrator.Outcome.UNDO_SAVE_FAILED, importReplace(pkg(true), l, 1L, MemUndo(fail = true)))
        assertEquals(before, l.prefs.toMap())
        assertEquals(traces, l.traces.toMap())
        assertTrue(l.staged.isEmpty())
    }

    @Test fun failedWriteRollsBack() {
        val l = local(); val before = l.prefs.toMap(); val traces = l.traces.toMap()
        l.failPrefs = "walk_learn"
        assertEquals(Migrator.Outcome.FAILED, importReplace(pkg(true), l, 1L))
        l.failPrefs = null
        assertEquals(before["trips"], l.prefs["trips"])
        assertEquals(traces, l.traces.toMap())
    }

    @Test fun encryptedLinesInAPackageAreRejected() {
        val bad = pkg(true).copy(traces = mapOf("2030-01-01_x.jsonl" to listOf("e2:beef0001:AAAA")))
        val l = local(); val before = l.traces.toMap()
        assertEquals(Migrator.Outcome.FAILED, importReplace(bad, l, 1L))
        assertEquals(before, l.traces.toMap())
    }
    @Test fun compactedRecordsSurviveSnapshotAndRestoreVerbatim() {
        val l = local()
        val lines = listOf("""{"k":"ev","e":"start","t":1}""", """{"k":"acc","m":0.12,"t":2}""")
        val block = localKey.encryptBlock(TraceBlock.encode(lines))
        l.traces["2030-02-02_local000.jsonl"] = listOf(block, localKey.encrypt("""{"k":"ev","e":"stop","t":3}"""))
        val undo = MemUndo()
        assertEquals(Migrator.Outcome.OK, importReplace(pkg(true), l, 99L, undo))
        // 快照按落盘原样保存压缩块，撤销后逐字节恢复且仍可读
        assertTrue(Migrator.restore(PackageSource { undo.open() }, l))
        val stored = l.traces["2030-02-02_local000.jsonl"]!!
        assertEquals(block, stored[0])
        assertEquals(lines + """{"k":"ev","e":"stop","t":3}""", RecordCodec.decodeLines(stored, localKey).text)
        // 迁移包中的记录为明文：压缩块不可作为迁移包内容导入
        val bad = pkg(true).copy(traces = mapOf("2030-01-01_imported.jsonl" to listOf(block)))
        assertEquals(Migrator.Outcome.FAILED, importReplace(bad, l, 100L))
    }
}
