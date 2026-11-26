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
        assertTrue(e.startsWith("e1:"))
        assertTrue(!e.contains("lat"))
        assertEquals(line, c.decrypt(e))
    }

    @Test fun eachRecordHasItsOwnIv() {
        val c = RecordCipher(key(1))
        val a = c.encrypt(line); val b = c.encrypt(line)
        assertNotEquals(a, b)
        val ivA = Base64.getDecoder().decode(a.substring(3)).copyOf(RecordFormat.IV_LEN)
        val ivB = Base64.getDecoder().decode(b.substring(3)).copyOf(RecordFormat.IV_LEN)
        assertTrue(!ivA.contentEquals(ivB))
        // IV + 明文 + 16 字节标签
        assertEquals(RecordFormat.IV_LEN + line.toByteArray().size + 16, Base64.getDecoder().decode(a.substring(3)).size)
    }

    @Test fun wrongKeyOrTamperedRecordIsRejected() {
        val e = RecordCipher(key(1)).encrypt(line)
        assertNull(RecordCipher(key(2)).decrypt(e))
        val raw = Base64.getDecoder().decode(e.substring(3)); raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
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
        val none = RecordCodec.decodeLines(lines, null)
        assertEquals(listOf(line), none.text)
        assertEquals(3, none.unreadable)
    }

    @Test fun unicodeContent() {
        val c = RecordCipher(key(3))
        val s = """{"e":"diag","msg":"已自动启动"}"""
        assertEquals(s, c.decrypt(c.encrypt(s)))
    }
}
