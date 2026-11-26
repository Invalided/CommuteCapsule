package com.busnotice.commute

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 数据密钥的加载、重建与旧密文的处理（主密钥用软件实现模拟系统密钥库） */
class KeyRingTest {
    /** 模拟密钥库：[master] 为 null 表示密钥库被清空；[broken] 模拟解封失败 */
    private class FakeWrapper : KeyWrapper {
        var master: ByteArray? = null
        var broken = false
        var unavailable = false
        var generated = 0
        override fun hasMaster(): Boolean { if (unavailable) error("keystore unavailable"); return master != null }
        override fun wrap(raw: ByteArray): ByteArray {
            val m = master ?: ByteArray(32).also { SecureRandom().nextBytes(it); master = it; generated++ }
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(m, "AES"), GCMParameterSpec(128, iv))
            return iv + c.doFinal(raw)
        }
        override fun unwrap(blob: ByteArray): ByteArray {
            if (broken) error("cannot unwrap")
            val m = master ?: error("missing")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(m, "AES"), GCMParameterSpec(128, blob, 0, 12))
            return c.doFinal(blob, 12, blob.size - 12)
        }
        override fun deleteMaster() { master = null }
    }

    private class MemStore(var fail: Boolean = false) : BlobStore {
        var blob: ByteArray? = null
        override fun read() = blob
        override fun write(b: ByteArray): Boolean { if (fail) return false; blob = b; return true }
    }

    @Test fun firstUseGeneratesAndLaterLoadsSameKey() {
        val w = FakeWrapper(); val s = MemStore()
        val a = KeyRing(w, s).load()!!
        assertFalse(a.reset)
        assertNotNull(s.blob)
        val b = KeyRing(w, s).load()!!
        assertFalse(b.reset)
        assertArrayEquals(a.key.encoded, b.key.encoded)
        assertEquals(1, w.generated)
        // 落盘的只是封装结果，不是数据密钥本身
        assertFalse(s.blob!!.contentEquals(a.key.encoded))
    }

    @Test fun lostMasterRegeneratesAndOldRecordsAreSkipped() {
        val w = FakeWrapper(); val s = MemStore()
        val old = RecordCipher(KeyRing(w, s).load()!!.key)
        val oldLine = old.encrypt("""{"k":"loc"}""")
        w.master = null   // 系统清空了密钥库
        val again = KeyRing(w, s).load()!!
        assertTrue(again.reset)
        val c = RecordCipher(again.key)
        val d = RecordCodec.decodeLines(listOf(oldLine, c.encrypt("""{"k":"ev"}""")), c)
        assertEquals(listOf("""{"k":"ev"}"""), d.text)
        assertEquals(1, d.unreadable)
        // 之后用新密钥继续读写
        assertArrayEquals(again.key.encoded, KeyRing(w, s).load()!!.key.encoded)
    }

    @Test fun corruptedMasterIsReplaced() {
        val w = FakeWrapper(); val s = MemStore()
        val first = KeyRing(w, s).load()!!
        w.broken = true
        val second = KeyRing(w, s).load()!!
        assertTrue(second.reset)
        assertFalse(first.key.encoded.contentEquals(second.key.encoded))
        assertEquals(2, w.generated)
    }

    @Test fun unsavedKeyIsNotUsed() {
        assertNull(KeyRing(FakeWrapper(), MemStore(fail = true)).load())
    }

    @Test fun unavailableKeystoreDoesNotResetExistingKey() {
        val w = FakeWrapper(); val s = MemStore()
        val first = KeyRing(w, s).load()!!
        val saved = s.blob
        w.unavailable = true
        assertNull(KeyRing(w, s).load())
        assertArrayEquals(saved, s.blob)
        w.unavailable = false
        assertArrayEquals(first.key.encoded, KeyRing(w, s).load()!!.key.encoded)
    }
}
