package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

/** 记录级加密格式：每条独立 IV、带版本前缀、兼容旧版明文 */
class RecordCipherTest {
    private fun key(b: Int) = SecretKeySpec(ByteArray(RecordFormat.KEY_BYTES) { b.toByte() }, "AES")
    private val line = """{"lat":1.0,"lng":2.0,"t":1,"k":"loc"}"""

    @Test fun roundTrip() {
        val c = RecordCipher(key(1))
        val e = c.encrypt(line)
        assertTrue(e.startsWith("e2:0:"))
        assertTrue(!e.contains("lat"))
        assertEquals(line, c.decrypt(e))
    }

    @Test fun eachRecordHasItsOwnIv() {
        val c = RecordCipher(key(1))
        val a = c.encrypt(line); val b = c.encrypt(line)
        assertNotEquals(a, b)
        val ivA = Base64.getDecoder().decode(RecordFormat.payloadOf(a)!!).copyOf(RecordFormat.IV_LEN)
        val ivB = Base64.getDecoder().decode(RecordFormat.payloadOf(b)!!).copyOf(RecordFormat.IV_LEN)
        assertTrue(!ivA.contentEquals(ivB))
        // IV + 明文 + 16 字节标签
        assertEquals(RecordFormat.IV_LEN + line.toByteArray().size + 16, Base64.getDecoder().decode(RecordFormat.payloadOf(a)!!).size)
    }

    @Test fun wrongKeyOrTamperedRecordIsRejected() {
        val e = RecordCipher(key(1)).encrypt(line)
        assertNull(RecordCipher(key(2)).decrypt(e))
        val raw = Base64.getDecoder().decode(RecordFormat.payloadOf(e)!!); raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        assertNull(RecordCipher(key(1)).decrypt("e1:" + Base64.getEncoder().encodeToString(raw)))
        assertNull(RecordCipher(key(1)).decrypt("e1:not-base64!"))
        assertNull(RecordCipher(key(1)).decrypt("e1:AAAA"))
    }

    @Test fun decodeLinesKeepsLegacyPlainAndSkipsUnreadable() {
        val c = RecordCipher(key(1))
        val other = RecordCipher(key(2))
        val lines = listOf(line, "", c.encrypt("""{"k":"ev"}"""), other.encrypt("""{"k":"cell"}"""), c.encrypt("""{"k":"acc"}"""))
        val d = RecordCodec.decodeLines(lines, c)
        assertEquals(listOf(line, """{"k":"ev"}""", """{"k":"acc"}"""), d.text)
        assertEquals(1, d.unreadable)
        // 没有可用密钥：密文全部跳过，明文照常读取
        val none = RecordCodec.decodeLines(lines, null as RecordCipher?)
        assertEquals(listOf(line), none.text)
        assertEquals(3, none.unreadable)
    }

    @Test fun unicodeContent() {
        val c = RecordCipher(key(3))
        val s = """{"e":"diag","msg":"已自动启动"}"""
        assertEquals(s, c.decrypt(c.encrypt(s)))
    }

    @Test fun keyIdFormatAndLegacyPrefix() {
        val k = key(4)
        val v2 = RecordCipher(k, "a1b2c3d4").encrypt(line)
        assertTrue(v2.startsWith("e2:a1b2c3d4:"))
        assertEquals("a1b2c3d4", RecordFormat.keyIdOf(v2))
        // e1 旧格式视为初始密钥
        val legacy = "e1:" + RecordFormat.payloadOf(RecordCipher(k).encrypt(line))
        assertEquals(RecordFormat.LEGACY_ID, RecordFormat.keyIdOf(legacy))
        assertEquals(line, RecordCipher(k).decrypt(legacy))
        assertNull(RecordFormat.keyIdOf(line))
        assertNull(RecordFormat.keyIdOf("e2:BAD ID:xx"))
        assertNull(RecordFormat.keyIdOf("e2:noterminator"))
    }

    @Test fun linesAreDecodedWithTheKeyNamedByTheirId() {
        val a = RecordCipher(key(5), "0"); val b = RecordCipher(key(6), "beef0001")
        val legacy = "e1:" + RecordFormat.payloadOf(a.encrypt("""{"k":"old"}"""))
        val lines = listOf(legacy, b.encrypt("""{"k":"new"}"""), a.encrypt("""{"k":"mid"}"""))
        val both = RecordCodec.decodeLines(lines) { mapOf("0" to a, "beef0001" to b)[it] }
        assertEquals(listOf("""{"k":"old"}""", """{"k":"new"}""", """{"k":"mid"}"""), both.text)
        assertEquals(0, both.unreadable)
        // 只有新密钥：旧记录跳过，不影响新记录
        val onlyNew = RecordCodec.decodeLines(lines) { mapOf("beef0001" to b)[it] }
        assertEquals(listOf("""{"k":"new"}"""), onlyNew.text)
        assertEquals(2, onlyNew.unreadable)
        assertEquals(setOf("0", "beef0001"), RecordCodec.keyIds(lines.asSequence()))
    }
}
