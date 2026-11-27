package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.spec.SecretKeySpec

/** 行程记录整理为压缩块：原子替换、中断恢复、密钥异常时不丢原文件（数据全部为虚构） */
class TraceCompactionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val k1 = RecordCipher(SecretKeySpec(ByteArray(32) { 1 }, "AES"), "k1")
    private val k2 = RecordCipher(SecretKeySpec(ByteArray(32) { 2 }, "AES"), "k2")
    private val ring = mapOf("k1" to k1, "k2" to k2)
    private val keys = { id: String -> ring[id] }

    private fun plain(n: Int, from: Int = 0) = List(n) { j ->
        val i = from + j
        val t = 1_000_000_000_000L + i * 2000L
        """{"m":0.${i % 997},"sd":0.0${i % 9},"n":${i % 40},"ts":$t,"t":${t + 3},"k":"acc"}"""
    }

    private fun file(name: String, lines: List<String>) =
        File(tmp.root, name).apply { writeText(lines.joinToString("") { it + "\n" }) }

    private fun read(f: File) = RecordCodec.decodeLines(f.readLines(), keys)

    private fun tmpFiles() = tmp.root.listFiles()!!.filter { it.name.endsWith(RecordMigration.TMP_SUFFIX) }

    @Test fun mixedFileBecomesOneBlockWithIdenticalContent() {
        val legacy = plain(3)
        val enc1 = plain(500, 3)
        val enc2 = plain(200, 503)
        val f = file("2030-01-01_trip.jsonl", legacy + enc1.map { k1.encrypt(it) } + enc2.map { k2.encrypt(it) })
        val before = read(f).text
        assertEquals(legacy + enc1 + enc2, before)

        assertEquals(TraceCompaction.Result.COMPACTED, TraceCompaction.compactFile(f, keys, k2))
        val stored = f.readLines()
        assertEquals(1, stored.size)
        assertTrue(stored[0].startsWith("e3:k2:"))
        assertEquals(Decoded(before, 0), read(f))
        assertTrue(tmpFiles().isEmpty())

        // 再次整理：已是压缩块，不改动
        val mod = f.lastModified(); val text = f.readText()
        assertEquals(TraceCompaction.Result.ALREADY, TraceCompaction.compactFile(f, keys, k2))
        assertEquals(text, f.readText()); assertEquals(mod, f.lastModified())
    }

    @Test fun appendAfterCompactionStaysReadableAndRecompacts() {
        val f = file("2030-01-02_trip.jsonl", plain(100).map { k1.encrypt(it) })
        assertEquals(TraceCompaction.Result.COMPACTED, TraceCompaction.compactFile(f, keys, k1))
        f.appendText(plain(5, 100).joinToString("") { k1.encrypt(it) + "\n" })
        assertEquals(plain(105), read(f).text)
        assertEquals(TraceCompaction.Result.COMPACTED, TraceCompaction.compactFile(f, keys, k1))
        assertEquals(1, f.readLines().size)
        assertEquals(plain(105), read(f).text)
    }

    @Test fun emptyFileIsLeftAlone() {
        val f = file("2030-01-03_trip.jsonl", emptyList())
        assertEquals(TraceCompaction.Result.ALREADY, TraceCompaction.compactFile(f, keys, k1))
        assertEquals("", f.readText())
    }

    @Test fun singleLineAndCorruptLinesAreKeptVerbatim() {
        val lines = listOf("{not json", """{"k":"ev","e":"stop","t":5}""", "1,2,3", "e2:zz:")
        val f = file("2030-01-04_trip.jsonl", lines.take(1))
        assertEquals(TraceCompaction.Result.COMPACTED, TraceCompaction.compactFile(f, keys, k1))
        assertEquals(lines.take(1), read(f).text)
        // 无法读取的密文行（未知密钥）使整个文件不参与整理
        val g = file("2030-01-05_trip.jsonl", lines)
        val before = g.readText()
        assertEquals(TraceCompaction.Result.LOCKED, TraceCompaction.compactFile(g, keys, k1))
        assertEquals(before, g.readText())
        // 去掉无法读取的行后可整理，其余异常行原样保留
        val h = file("2030-01-06_trip.jsonl", lines.dropLast(1))
        assertEquals(TraceCompaction.Result.COMPACTED, TraceCompaction.compactFile(h, keys, k1))
        assertEquals(lines.dropLast(1), read(h).text)
    }

    @Test fun recordsUnderAnUnavailableKeyAreNotCompacted() {
        val lost = RecordCipher(SecretKeySpec(ByteArray(32) { 9 }, "AES"), "k9")
        val f = file("2030-01-07_trip.jsonl", plain(10).map { k1.encrypt(it) } + plain(3, 10).map { lost.encrypt(it) })
        val before = f.readText()
        assertEquals(TraceCompaction.Result.LOCKED, TraceCompaction.compactFile(f, keys, k1))
        assertEquals(before, f.readText())
        assertTrue(tmpFiles().isEmpty())
    }

    @Test fun wrongCurrentKeyFailsVerificationAndKeepsOriginal() {
        // 用于加密压缩块的密钥不在可读密钥中：核对失败，原文件不变，临时文件删除
        val stray = RecordCipher(SecretKeySpec(ByteArray(32) { 7 }, "AES"), "k7")
        val f = file("2030-01-08_trip.jsonl", plain(50).map { k1.encrypt(it) })
        val before = f.readText()
        assertEquals(TraceCompaction.Result.FAILED, TraceCompaction.compactFile(f, keys, stray))
        assertEquals(before, f.readText())
        assertTrue(tmpFiles().isEmpty())
        // 标识相同但密钥内容不同（例如密钥文件被替换）：同样失败且不丢数据
        val impostor = RecordCipher(SecretKeySpec(ByteArray(32) { 8 }, "AES"), "k1")
        assertEquals(TraceCompaction.Result.FAILED, TraceCompaction.compactFile(f, keys, impostor))
        assertEquals(before, f.readText())
        assertEquals(plain(50), read(f).text)
    }

    @Test fun interruptedRunLeavesOriginalIntactAndLeftoverIsCleaned() {
        val f = file("2030-01-09_trip.jsonl", plain(80).map { k1.encrypt(it) })
        val before = f.readText()
        // 模拟上次整理在替换前被中断：残留一个写了一半的临时文件
        val leftover = File(tmp.root, f.name + TraceCompaction.TMP_SUFFIX).apply { writeText("e3:k1:AAAA") }
        assertEquals(before, f.readText())
        assertEquals(plain(80), read(f).text)
        TraceCompaction.cleanup(tmp.root)
        assertFalse(leftover.exists())
        assertEquals(TraceCompaction.Result.COMPACTED, TraceCompaction.compactFile(f, keys, k1))
        assertEquals(plain(80), read(f).text)
    }

    @Test fun replaceStepFailureKeepsOriginal() {
        val f = file("2030-01-10_trip.jsonl", plain(30).map { k1.encrypt(it) })
        val before = f.readText()
        // 替换时被拒绝（例如文件此时正被写入）
        assertEquals(TraceCompaction.Result.FAILED, TraceCompaction.compactFile(f, keys, k1) { false })
        assertEquals(before, f.readText())
        assertTrue(tmpFiles().isEmpty())
        // 替换动作抛出异常
        assertEquals(TraceCompaction.Result.FAILED, TraceCompaction.compactFile(f, keys, k1) { throw IllegalStateException("killed") })
        assertEquals(before, f.readText())
        assertTrue(tmpFiles().isEmpty())
    }

    @Test fun fileChangedDuringCompactionIsNotReplaced() {
        val f = file("2030-01-11_trip.jsonl", plain(30).map { k1.encrypt(it) })
        val extra = k1.encrypt(plain(1, 30)[0])
        val r = TraceCompaction.compactFile(f, keys, k1) { replace ->
            f.appendText(extra + "\n"); f.setLastModified(f.lastModified() + 5_000)
            replace()
        }
        assertEquals(TraceCompaction.Result.CHANGED, r)
        assertEquals(plain(31), read(f).text)
        assertTrue(tmpFiles().isEmpty())
    }

    @Test fun legacyPlainFileCompactsAndMigrationHelpersStillWork() {
        val f = file("2030-01-12_bus.jsonl", plain(20))
        assertEquals(TraceCompaction.Result.COMPACTED, TraceCompaction.compactFile(f, keys, k1))
        // 压缩块视为密文：明文迁移不再改写；按密钥删除不可恢复记录时整行处理
        assertFalse(RecordMigration.hasPlain(f))
        assertEquals(0, RecordMigration.migrateFile(f) { error("should not encrypt") })
        assertEquals(setOf("k1"), RecordCodec.keyIds(f.readLines().asSequence()))
        RecordMigration.rewrite(f) { l -> if (RecordFormat.keyIdOf(l) == "k1") null else l }
        assertFalse(f.exists())
    }
}
