package com.busnotice.commute

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 迁移文件的分块加密、流式导出与导入、旧格式兼容、截断与篡改检测、内存上界。测试数据均为虚构。 */
class MigrationStreamTest {
    @get:Rule val tmp = TemporaryFolder()

    private val code = "0123456789ABCDEFGHJKMNPQ"
    private val localKey = RecordCipher(SecretKeySpec(ByteArray(32) { 7 }, "AES"), "cafe0002")

    private fun reason(block: () -> Unit): MigrationException.Reason? =
        try { block(); null } catch (e: MigrationException) { e.reason }

    private class MemLocal(val cipher: RecordCipher?) : LocalData {
        val prefs = HashMap<String, Map<String, Any?>>()
        val traces = sortedMapOf<String, List<String>>()
        val staged = sortedMapOf<String, List<String>>()
        var maxStagedLines = 0
        override fun readPrefs(name: String) = prefs[name].orEmpty()
        override fun writePrefs(name: String, entries: Map<String, Any?>): Boolean { prefs[name] = entries.toMap(); return true }
        override fun traceNames() = traces.keys.toList()
        override fun readTraceRaw(name: String) = traces[name].orEmpty()
        override fun writeTraceRaw(name: String, lines: List<String>): Boolean { traces[name] = lines; return true }
        override fun deleteTrace(name: String): Boolean { traces.remove(name); return true }
        override fun encrypt(plain: String) = cipher?.encrypt(plain)
        override fun stageTrace(name: String, lines: List<String>): Boolean {
            maxStagedLines = maxOf(maxStagedLines, lines.size); staged[name] = lines; return true
        }
        override fun commitStaged(name: String): Boolean { traces[name] = staged.remove(name) ?: return false; return true }
        override fun clearStaged() = staged.clear()
    }

    private class MemUndo : UndoStore {
        var bytes: ByteArray? = null
        override fun save(write: (OutputStream) -> Unit): Boolean { bytes = ByteArrayOutputStream().also(write).toByteArray(); return true }
        override fun open() = ByteArrayInputStream(bytes!!)
    }

    /** 统计写入量、不保存内容的输出流 */
    private class CountingSink : OutputStream() {
        var total = 0L
        var maxWrite = 0
        override fun write(b: Int) { total++ }
        override fun write(b: ByteArray, off: Int, len: Int) { total += len; maxWrite = maxOf(maxWrite, len) }
    }

    /** 虚构的行程记录：数值由固定种子随机生成 */
    private fun fakeLines(seed: Long, n: Int): List<String> {
        val r = Random(seed)
        return List(n) { i ->
            """{"k":"acc","t":${1_000_000L + seed * 100_000 + i},"m":${"%.3f".format(java.util.Locale.ROOT, r.nextDouble())},"n":${r.nextInt(100000)}}"""
        }
    }

    private fun fakePrefs() = mapOf(
        "trips" to mapOf<String, Any>("list" to """[{"id":"demo-a"},{"id":"demo-b"}]"""),
        "learn" to mapOf<String, Any>("b" to true, "i" to 3, "l" to 2_000_000_000_000L, "f" to 0.25f, "ss" to setOf("x", "y"), "s" to "示例"),
    )

    private fun local(): MemLocal = MemLocal(localKey).apply {
        prefs["trips"] = mapOf("list" to """[{"id":"old"}]""")
        prefs["places"] = mapOf("manual_HOME" to "0.5,0.5")
        prefs[MigrationPackage.SAMPLE_PREFS] = mapOf("samples" to localKey.encrypt("[[0.1,0.2,5.0,1]]"))
        traces["2031-03-01_old00000.jsonl"] = listOf(localKey.encrypt("""{"k":"old"}"""))
    }

    private fun export(
        traces: Map<String, List<String>>?, samples: Map<String, Any>? = traces?.let { mapOf("samples" to "[[0.3,0.4,9.0,2]]") },
        extra: List<MigrationExport.Trace> = emptyList(), chunkSize: Int = MigrationCrypto.CHUNK_SIZE,
    ): Pair<ByteArray, MigrationExport.Result> {
        val list = traces?.map { (n, l) -> MigrationExport.Trace(n, l.isNotEmpty()) { Decoded(l, 0) } }?.plus(extra)
        val bos = ByteArrayOutputStream()
        val r = MigrationExport.write(bos, code, 42L, "0.0.0-test", fakePrefs(), list, samples, chunkSize = chunkSize)
        return bos.toByteArray() to r
    }

    private fun source(file: ByteArray, c: String = code) = PackageSource { MigrationCrypto.openStream(ByteArrayInputStream(file), c) }

    private fun head(file: ByteArray) = MigrationCrypto.openStream(ByteArrayInputStream(file), code).use { MigrationPackage.scan(it, headOnly = true) }

    private fun decryptAll(l: MemLocal) = l.traces.mapValues { (_, v) -> v.map { localKey.decrypt(it)!! } }

    /** 各块的起止位置（文件头之后） */
    private fun frames(file: ByteArray): List<IntRange> {
        val out = ArrayList<IntRange>()
        var p = 36
        while (p < file.size) {
            val len = ((file[p + 1].toInt() and 0xff) shl 24) or ((file[p + 2].toInt() and 0xff) shl 16) or
                ((file[p + 3].toInt() and 0xff) shl 8) or (file[p + 4].toInt() and 0xff)
            out += p until p + 5 + len
            p += 5 + len
        }
        return out
    }

    @Test fun manyRecordsRoundTripThroughStreamingExportAndImport() {
        val traces = (0 until 40).associate { i -> "2031-01-%02d_demo%04d.jsonl".format(i % 28 + 1, i) to fakeLines(i.toLong(), 300 + i * 7) }
        val unreadable = MigrationExport.Trace("2031-02-01_locked00.jsonl", true) { Decoded(emptyList(), 3) }
        val badName = MigrationExport.Trace("../outside.jsonl", true) { Decoded(listOf("""{"k":"x"}"""), 1) }
        val (file, r) = export(traces, extra = listOf(unreadable, badName), chunkSize = 4096)
        assertEquals(2, file[5].toInt())
        assertTrue(r.chunks > 10)
        assertEquals(28, r.meta.traceDays)
        assertEquals(4, r.meta.skippedRecords)
        // 预览只读开头：清单中的天数按非空文件名预先统计（含随后发现无法读取的那一天）
        val h = head(file)
        assertNull(h.traceNames)
        val p = Migrator.preview(h)
        assertTrue(p.includesRecords); assertEquals(2, p.trips); assertEquals(29, p.traceDays); assertEquals(42L, p.createdAt)
        assertEquals(fakePrefs(), h.prefs)
        // 完整读取时以包末统计为准
        val full = MigrationCrypto.openStream(ByteArrayInputStream(file), code).use { MigrationPackage.scan(it) }
        assertEquals(28, full.meta.traceDays); assertEquals(4, full.meta.skippedRecords)
        assertEquals(traces.keys.sorted(), full.traceNames)

        val l = local()
        val undo = MemUndo()
        assertEquals(Migrator.Outcome.OK, Migrator.importReplace(source(file), l, 99L, undo))
        assertEquals(traces, decryptAll(l))
        assertTrue(l.traces.values.flatten().all { it.startsWith("e2:cafe0002:") })
        assertEquals(fakePrefs()["learn"], l.prefs["learn"])
        assertTrue(l.prefs["places"]!!.isEmpty())
        assertEquals("[[0.3,0.4,9.0,2]]", localKey.decrypt(l.prefs[MigrationPackage.SAMPLE_PREFS]!!["samples"] as String))
        assertTrue(l.staged.isEmpty())
        // 撤销恢复导入前的数据
        assertTrue(Migrator.restore(PackageSource { undo.open() }, l))
        assertEquals(setOf("2031-03-01_old00000.jsonl"), l.traces.keys)
        assertEquals("""[{"id":"old"}]""", l.prefs["trips"]!!["list"])
        assertEquals(mapOf<String, Any?>("manual_HOME" to "0.5,0.5"), l.prefs["places"])
    }

    @Test fun includingRecordsWithoutAnyFilesReplacesLocalRecords() {
        val (file, r) = export(emptyMap(), samples = emptyMap())
        assertTrue(r.meta.includesRecords); assertEquals(0, r.meta.traceDays)
        val p = Migrator.preview(head(file))
        assertTrue(p.includesRecords); assertEquals(0, p.traceDays)
        val l = local()
        assertEquals(Migrator.Outcome.OK, Migrator.importReplace(source(file), l, 1L, MemUndo()))
        assertTrue(l.traces.isEmpty())
        assertEquals(emptyMap<String, Any?>(), l.prefs[MigrationPackage.SAMPLE_PREFS])
        assertEquals("""[{"id":"demo-a"},{"id":"demo-b"}]""", l.prefs["trips"]!!["list"])
    }

    @Test fun settingsOnlyKeepsLocalRecords() {
        val (file, r) = export(null)
        assertFalse(r.meta.includesRecords)
        assertFalse(Migrator.preview(head(file)).includesRecords)
        val l = local(); val traces = l.traces.toMap(); val samples = l.prefs[MigrationPackage.SAMPLE_PREFS]
        val undo = MemUndo()
        assertEquals(Migrator.Outcome.OK, Migrator.importReplace(source(file), l, 1L, undo))
        assertEquals(traces, l.traces.toMap())
        assertEquals(samples, l.prefs[MigrationPackage.SAMPLE_PREFS])
        assertEquals(fakePrefs()["learn"], l.prefs["learn"])
        assertNull(MigrationPackage.read(undo.bytes!!).traces)
    }

    /** 按格式版本 1 的写法生成迁移文件（整体一次 AES-GCM） */
    private fun sealV1(plain: ByteArray, c: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val header = "CCMIG".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 1, 16) + salt + iv
        val key = MigrationCrypto.hkdf(RecoveryCode.normalize(c)!!.toByteArray(Charsets.US_ASCII), salt,
            "CommuteCapsule migration v1".toByteArray(Charsets.US_ASCII), 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    @Test fun legacySingleBlockFilesStillImport() {
        val traces = mapOf("2031-01-05_demo0001.jsonl" to fakeLines(5, 50), "2031-01-06_demo0002.jsonl" to fakeLines(6, 20))
        val content = MigrationContent(PackageMeta(1, 7L, "0.3.0", 2, true, 2, 0), fakePrefs(), traces, mapOf("samples" to "[[0.3,0.4,9.0,2]]"))
        val zip = MigrationPackage.write(content)
        val v1 = sealV1(zip, code)
        assertEquals(MigrationCrypto.VERSION_SINGLE, MigrationCrypto.checkHeader(v1.copyOf(MigrationCrypto.PREFIX_LEN)))
        assertEquals(MigrationCrypto.MAX_BYTES, MigrationCrypto.maxBytes(MigrationCrypto.VERSION_SINGLE))
        assertArrayEquals(zip, MigrationCrypto.open(v1, code))
        val p = Migrator.preview(head(v1))
        assertEquals(2, p.traceDays); assertTrue(p.includesRecords); assertEquals(7L, p.createdAt)
        val l = local()
        assertEquals(Migrator.Outcome.OK, Migrator.importReplace(source(v1), l, 1L, MemUndo()))
        assertEquals(traces, decryptAll(l))
        // 旧格式同样检测篡改与错误恢复码
        val bad = v1.copyOf(); bad[bad.size - 20] = (bad[bad.size - 20].toInt() xor 4).toByte()
        assertEquals(MigrationException.Reason.WRONG_CODE_OR_CORRUPT, reason { MigrationCrypto.open(bad, code) })
        assertEquals(MigrationException.Reason.WRONG_CODE_OR_CORRUPT, reason { MigrationCrypto.open(v1, RecoveryCode.generate()) })
    }

    @Test fun truncationIsDetectedAtEveryCut() {
        val (file, _) = export(mapOf("2031-01-07_demo0003.jsonl" to fakeLines(9, 2000)), chunkSize = 1024)
        val fr = frames(file)
        assertTrue(fr.size > 5)
        assertEquals(file.size, fr.last().last + 1)
        val cuts = fr.map { it.first } + fr.map { it.first + 3 } + fr.map { it.last } + listOf(36, file.size - 1)
        for (cut in cuts.distinct()) {
            val t = file.copyOf(cut)
            assertEquals("cut at $cut", MigrationException.Reason.WRONG_CODE_OR_CORRUPT, reason { MigrationCrypto.open(t, code) })
            val l = local(); val before = l.traces.toMap(); val prefs = l.prefs.toMap()
            assertEquals(Migrator.Outcome.REJECTED, Migrator.importReplace(source(t), l, 1L, MemUndo()))
            assertEquals(before, l.traces.toMap()); assertEquals(prefs, l.prefs.toMap()); assertTrue(l.staged.isEmpty())
        }
    }

    @Test fun tamperingReorderingAndExtraDataAreDetected() {
        val (file, _) = export(mapOf("2031-01-08_demo0004.jsonl" to fakeLines(11, 400)), chunkSize = 1024)
        val fr = frames(file)
        fun check(label: String, bad: ByteArray) {
            assertEquals(label, MigrationException.Reason.WRONG_CODE_OR_CORRUPT, reason { MigrationCrypto.open(bad, code) })
            val l = local(); val before = l.traces.toMap()
            assertEquals(label, Migrator.Outcome.REJECTED, Migrator.importReplace(source(bad), l, 1L, MemUndo()))
            assertEquals(before, l.traces.toMap())
        }
        fun flip(at: Int, bit: Int = 1) = file.copyOf().also { it[at] = (it[at].toInt() xor bit).toByte() }
        fun join(parts: List<IntRange>) = file.copyOfRange(0, 36) + parts.map { file.copyOfRange(it.first, it.last + 1) }.reduce { a, b -> a + b }
        // 文件头（随机数前缀、分块长度不参与密钥，但参与认证）
        check("nonce prefix", flip(30))
        check("chunk size field", flip(35, 0x08))
        // 中间块的密文与标签
        check("middle ciphertext", flip(fr[2].first + 20))
        check("middle tag", flip(fr[2].last))
        // 末块标志：非末块标为末块、末块标为非末块
        check("mark middle as last", flip(fr[1].first))
        check("mark last as middle", flip(fr.last().first))
        // 调换两个中间块、重复一块、删掉一块
        val swapped = fr.toMutableList().also { val x = it[1]; it[1] = it[2]; it[2] = x }
        check("swap", join(swapped))
        check("duplicate", join(fr.take(3) + listOf(fr[2]) + fr.drop(3)))
        check("drop", join(fr.take(2) + fr.drop(3)))
        // 末块之后追加内容
        check("trailing byte", file + byteArrayOf(0))
        check("trailing frame", file + file.copyOfRange(fr[1].first, fr[1].last + 1))
        // 块长度字段超出范围
        check("length", flip(fr[0].first + 2, 0x40))
    }

    @Test fun wrongRecoveryCodeChangesNothing() {
        val (file, _) = export(mapOf("2031-01-09_demo0005.jsonl" to fakeLines(13, 30)))
        val other = RecoveryCode.generate()
        assertEquals(MigrationException.Reason.WRONG_CODE_OR_CORRUPT, reason { MigrationCrypto.open(file, other) })
        assertEquals(MigrationException.Reason.WRONG_CODE_OR_CORRUPT, reason {
            MigrationCrypto.openStream(ByteArrayInputStream(file), other).use { MigrationPackage.scan(it, headOnly = true) }
        })
        assertEquals(MigrationException.Reason.WRONG_CODE_OR_CORRUPT, reason { MigrationCrypto.openStream(ByteArrayInputStream(file), "not a code") })
        val l = local(); val before = l.prefs.toMap()
        assertEquals(Migrator.Outcome.REJECTED, Migrator.importReplace(source(file, other), l, 1L, MemUndo()))
        assertEquals(before, l.prefs.toMap())
        // 不是迁移文件、文件过短
        assertEquals(MigrationException.Reason.NOT_MIGRATION_FILE, reason { MigrationCrypto.open(ByteArray(0), code) })
        assertEquals(MigrationException.Reason.NOT_MIGRATION_FILE, reason { MigrationCrypto.checkHeader("PK\u0003\u0004".toByteArray()) })
        assertEquals(MigrationException.Reason.WRONG_CODE_OR_CORRUPT, reason { MigrationCrypto.open(file.copyOf(20), code) })
    }

    @Test fun packagesWithEncryptedLinesOrSnapshotsAreRejectedWithoutChanges() {
        val l0 = local()
        val enc = mapOf("2031-01-10_demo0006.jsonl" to listOf(localKey.encrypt("""{"k":"x"}""")))
        val (file, _) = export(enc)
        val l = local(); val before = l.traces.toMap()
        assertEquals(Migrator.Outcome.REJECTED, Migrator.importReplace(source(file), l, 1L, MemUndo()))
        assertEquals(before, l.traces.toMap()); assertTrue(l.staged.isEmpty())
        // 本机快照（落盘原样）不能当作迁移包导入
        val snap = ByteArrayOutputStream().also { Migrator.writeSnapshot(l0, true, 5L, it) }.toByteArray()
        assertEquals(Migrator.Outcome.REJECTED, Migrator.importReplace(PackageSource { ByteArrayInputStream(snap) }, l, 1L, MemUndo()))
        assertEquals(before, l.traces.toMap())
    }

    @Test fun exportMemoryDoesNotGrowWithTotalRecords() {
        fun run(files: Int): Pair<MigrationExport.Result, Long> {
            val sink = CountingSink()
            var bytesBeforeLastRead = 0L
            val list = (0 until files).map { i ->
                MigrationExport.Trace("2031-%02d-%02d_demo%04d.jsonl".format(i / 28 % 12 + 1, i % 28 + 1, i), true) {
                    if (i == files - 1) bytesBeforeLastRead = sink.total
                    Decoded(fakeLines(i.toLong(), 1000), 0)
                }
            }
            val r = MigrationExport.write(sink, code, 1L, "", fakePrefs(), list, emptyMap())
            assertTrue(sink.maxWrite <= MigrationCrypto.CHUNK_SIZE + 16)
            // 读到最后一个文件时，此前的内容已经写出，尚未写出的不超过一个缓冲区加最后一个文件：没有在内存中累积整个迁移包
            assertTrue(sink.total - bytesBeforeLastRead < 3L * MigrationCrypto.CHUNK_SIZE)
            return r to sink.total
        }
        val (small, smallBytes) = run(20)
        val (large, largeBytes) = run(120)
        assertTrue(largeBytes > smallBytes * 5)
        assertTrue(large.plainBytes > small.plainBytes * 5)
        assertTrue(large.chunks >= 10)
        assertTrue(large.chunks > small.chunks * 3)
        // 缓冲区大小、单次写出量与同时持有的记录行数都与总量无关
        assertEquals(MigrationCrypto.CHUNK_SIZE, small.bufferBytes)
        assertEquals(small.bufferBytes, large.bufferBytes)
        assertTrue(large.maxWrite <= MigrationCrypto.CHUNK_SIZE + 16)
        assertEquals(1000, small.maxTraceLines)
        assertEquals(1000, large.maxTraceLines)
    }

    @Test fun importMemoryDoesNotGrowWithTotalRecords() {
        val traces = (0 until 60).associate { i -> "2031-04-%02d_demo%04d.jsonl".format(i % 28 + 1, i) to fakeLines(i + 100L, 400) }
        val (file, _) = export(traces)
        var maxLines = 0
        var calls = 0
        val s = MigrationCrypto.openStream(ByteArrayInputStream(file), code).use {
            MigrationPackage.scan(it) { _, lines -> calls++; maxLines = maxOf(maxLines, lines.size) }
        }
        assertEquals(60, calls); assertEquals(400, maxLines)
        assertEquals(60, s.traceNames!!.size)
        val l = local()
        assertEquals(Migrator.Outcome.OK, Migrator.importReplace(source(file), l, 1L, MemUndo()))
        assertEquals(400, l.maxStagedLines)
    }

    @Test fun atomicStreamWriteLeavesNoPartialFile() {
        val f = File(tmp.root, "undo.zip")
        assertTrue(atomicWriteStream(f) { it.write("first".toByteArray()) })
        assertEquals("first", f.readText())
        assertFalse(atomicWriteStream(f) { it.write("half".toByteArray()); throw IllegalStateException("interrupted") })
        assertEquals("first", f.readText())
        assertFalse(File(tmp.root, "undo.zip.tmp").exists())
        assertNotNull(tmp.root.listFiles()?.singleOrNull())
    }
}
