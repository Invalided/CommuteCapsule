package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
    }

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
        var undo: ByteArray? = null
        assertEquals(Migrator.Outcome.OK, Migrator.importReplace(pkg(true), l, 99L) { undo = it; true })
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
        val snap = MigrationPackage.read(undo!!)
        assertTrue(snap.meta.raw)
        assertTrue(snap.traces!!.values.single().single().startsWith("e2:beef0001:"))
    }

    @Test fun undoRestoresThePreviousData() {
        val l = local()
        val before = l.prefs.toMap() to l.traces.toMap()
        var undo: ByteArray? = null
        Migrator.importReplace(pkg(true), l, 99L) { undo = it; true }
        assertTrue(Migrator.restore(MigrationPackage.read(undo!!), l))
        assertEquals(before.second, l.traces.toMap())
        assertEquals(before.first["trips"], l.prefs["trips"])
        assertEquals(before.first["places"], l.prefs["places"])
        assertEquals(before.first[MigrationPackage.SAMPLE_PREFS], l.prefs[MigrationPackage.SAMPLE_PREFS])
    }

    @Test fun importWithoutRecordsKeepsLocalRecords() {
        val l = local()
        val traces = l.traces.toMap(); val samples = l.prefs[MigrationPackage.SAMPLE_PREFS]
        var undo: ByteArray? = null
        assertEquals(Migrator.Outcome.OK, Migrator.importReplace(pkg(false), l, 99L) { undo = it; true })
        assertEquals(traces, l.traces.toMap())
        assertEquals(samples, l.prefs[MigrationPackage.SAMPLE_PREFS])
        assertNull(MigrationPackage.read(undo!!).traces)
    }

    @Test fun nothingChangesWhenKeyOrUndoSnapshotIsUnavailable() {
        val noKey = MemLocal(null).apply { prefs["trips"] = mapOf("list" to "[]") }
        var saved = false
        assertEquals(Migrator.Outcome.KEY_UNAVAILABLE, Migrator.importReplace(pkg(true), noKey, 1L) { saved = true; true })
        assertFalse(saved)
        assertEquals("[]", noKey.prefs["trips"]!!["list"])
        val l = local(); val before = l.prefs.toMap()
        assertEquals(Migrator.Outcome.UNDO_SAVE_FAILED, Migrator.importReplace(pkg(true), l, 1L) { false })
        assertEquals(before, l.prefs.toMap())
    }

    @Test fun failedWriteRollsBack() {
        val l = local(); val before = l.prefs.toMap(); val traces = l.traces.toMap()
        l.failPrefs = "walk_learn"
        assertEquals(Migrator.Outcome.FAILED, Migrator.importReplace(pkg(true), l, 1L) { true })
        l.failPrefs = null
        assertEquals(before["trips"], l.prefs["trips"])
        assertEquals(traces, l.traces.toMap())
    }

    @Test fun encryptedLinesInAPackageAreRejected() {
        val bad = pkg(true).copy(traces = mapOf("2030-01-01_x.jsonl" to listOf("e2:beef0001:AAAA")))
        val l = local(); val before = l.traces.toMap()
        assertEquals(Migrator.Outcome.FAILED, Migrator.importReplace(bad, l, 1L) { true })
        assertEquals(before, l.traces.toMap())
    }
    @Test fun compactedRecordsSurviveSnapshotAndRestoreVerbatim() {
        val l = local()
        val lines = listOf("""{"k":"ev","e":"start","t":1}""", """{"k":"acc","m":0.12,"t":2}""")
        val block = localKey.encryptBlock(TraceBlock.encode(lines))
        l.traces["2030-02-02_local000.jsonl"] = listOf(block, localKey.encrypt("""{"k":"ev","e":"stop","t":3}"""))
        var undo: ByteArray? = null
        assertEquals(Migrator.Outcome.OK, Migrator.importReplace(pkg(true), l, 99L) { undo = it; true })
        // 快照按落盘原样保存压缩块，撤销后逐字节恢复且仍可读
        assertTrue(Migrator.restore(MigrationPackage.read(undo!!), l))
        val stored = l.traces["2030-02-02_local000.jsonl"]!!
        assertEquals(block, stored[0])
        assertEquals(lines + """{"k":"ev","e":"stop","t":3}""", RecordCodec.decodeLines(stored, localKey).text)
        // 迁移包中的记录为明文：压缩块不可作为迁移包内容导入
        val bad = pkg(true).copy(traces = mapOf("2030-01-01_imported.jsonl" to listOf(block)))
        assertEquals(Migrator.Outcome.FAILED, Migrator.importReplace(bad, l, 100L) { true })
    }
}
