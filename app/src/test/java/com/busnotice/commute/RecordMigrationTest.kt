package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import javax.crypto.spec.SecretKeySpec

/** 旧版明文记录就地转为密文：可中断、可重复、不丢数据 */
class RecordMigrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val c = RecordCipher(SecretKeySpec(ByteArray(32) { 7 }, "AES"))
    private val plain = listOf("""{"k":"ev","e":"start"}""", """{"k":"loc","lat":1.0,"lng":2.0}""", """{"k":"cell"}""")

    private fun file(lines: List<String>) = tmp.newFile("2030-01-01_trip.jsonl").apply { writeText(lines.joinToString("\n") + "\n") }

    @Test fun convertsPlainLinesInPlace() {
        val f = file(plain)
        assertEquals(3, RecordMigration.migrateFile(f) { c.encrypt(it) })
        val stored = f.readLines()
        assertTrue(stored.all { it.startsWith("e2:0:") })
        assertEquals(plain, RecordCodec.decodeLines(stored, c).text)
        assertFalse(File(f.parentFile, f.name + ".tmp").exists())
    }

    @Test fun mixedFileKeepsEncryptedLinesAndOrder() {
        val e = c.encrypt("""{"k":"acc"}""")
        val f = file(listOf(plain[0], e, plain[1]))
        assertEquals(2, RecordMigration.migrateFile(f) { c.encrypt(it) })
        val stored = f.readLines()
        assertEquals(e, stored[1])
        assertEquals(listOf(plain[0], """{"k":"acc"}""", plain[1]), RecordCodec.decodeLines(stored, c).text)
    }

    @Test fun alreadyEncryptedIsUntouched() {
        val f = file(plain.map { c.encrypt(it) })
        val before = f.readText(); val mod = f.lastModified()
        assertEquals(0, RecordMigration.migrateFile(f) { fail("should not encrypt"); null })
        assertEquals(before, f.readText())
        assertEquals(mod, f.lastModified())
    }

    @Test fun interruptedMigrationKeepsOriginalAndCanResume() {
        val f = file(plain)
        val before = f.readText()
        var n = 0
        try {
            RecordMigration.migrateFile(f) { if (++n == 2) throw IllegalStateException("interrupted") else c.encrypt(it) }
            fail("expected failure")
        } catch (e: IOException) { }
        assertEquals(before, f.readText())
        assertFalse(File(f.parentFile, f.name + ".tmp").exists())
        // 密钥不可用（返回 null）同样不改原文件
        try { RecordMigration.migrateFile(f) { null }; fail("expected failure") } catch (e: IOException) { }
        assertEquals(before, f.readText())
        // 下次启动重新执行
        assertEquals(3, RecordMigration.migrateFile(f) { c.encrypt(it) })
        assertEquals(plain, RecordCodec.decodeLines(f.readLines(), c).text)
    }

    @Test fun leftoverTempFileFromCrashIsRemoved() {
        val f = file(plain)
        val stale = File(f.parentFile, f.name + ".tmp").apply { writeText("e1:partial\n") }
        RecordMigration.cleanup(tmp.root)
        assertFalse(stale.exists())
        assertEquals(plain, f.readLines())
        assertEquals(3, RecordMigration.migrateFile(f) { c.encrypt(it) })
    }

    @Test fun keyFilesWriteAtomicallyAndRetireWithoutDeleting() {
        val d = File(tmp.root, "key")
        var t = java.time.LocalDateTime.of(2030, 1, 2, 3, 4, 5)
        val s = FileKeyFiles(d) { t }
        assertEquals(null, s.readCurrent())
        assertTrue(s.writeCurrent(byteArrayOf(1, 2, 3)))
        assertTrue(s.readCurrent()!!.contentEquals(byteArrayOf(1, 2, 3)))
        // 已有当前密钥时不允许覆盖
        assertFalse(s.writeCurrent(byteArrayOf(4)))
        assertTrue(s.readCurrent()!!.contentEquals(byteArrayOf(1, 2, 3)))
        assertFalse(File(d, "local_key.bin.tmp").exists())
        // 停用：改名保留，内容不变
        assertEquals("local_key.20300102-030405.bin", s.retireCurrent())
        assertEquals(null, s.readCurrent())
        assertTrue(s.writeCurrent(byteArrayOf(4)))
        assertEquals("local_key.20300102-030405-1.bin", s.retireCurrent())
        assertEquals(listOf("local_key.20300102-030405-1.bin", "local_key.20300102-030405.bin"), s.retired().keys.sorted())
        assertTrue(s.retired()["local_key.20300102-030405.bin"]!!.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals("", s.retireCurrent())
    }

    @Test fun rewriteDropsOnlySelectedLinesAndDeletesEmptyFile() {
        val f = file(plain)
        RecordMigration.rewrite(f) { if (it.contains("loc")) null else it }
        assertEquals(listOf(plain[0], plain[2]), f.readLines())
        RecordMigration.rewrite(f) { null }
        assertFalse(f.exists())
    }
}
