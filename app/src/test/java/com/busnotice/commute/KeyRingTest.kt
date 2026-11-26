package com.busnotice.commute

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 数据密钥的加载、停用、保留与恢复（主密钥用软件实现模拟系统密钥库） */
class KeyRingTest {
    /** 模拟密钥库：按别名保存主密钥；可模拟密钥库不可用、暂时性解封失败与认证失败 */
    private class FakeWrapper : KeyWrapper {
        val masters = HashMap<String, ByteArray>()
        var unavailable = false
        var transientUnwrap = false
        val invalidAliases = HashSet<String>()
        var generated = 0
        override fun hasMaster(alias: String): Boolean { if (unavailable) error("keystore unavailable"); return alias in masters }
        override fun wrap(alias: String, raw: ByteArray): ByteArray {
            if (unavailable) error("keystore unavailable")
            val m = masters.getOrPut(alias) { generated++; ByteArray(32).also { SecureRandom().nextBytes(it) } }
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(m, "AES"), GCMParameterSpec(128, iv))
            return byteArrayOf(1) + iv + c.doFinal(raw)
        }
        override fun unwrap(alias: String, blob: ByteArray): ByteArray {
            if (unavailable || transientUnwrap) error("transient")
            if (alias in invalidAliases) throw KeyBlobInvalid("auth")
            val m = masters[alias] ?: error("missing")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(m, "AES"), GCMParameterSpec(128, blob, 1, 12))
            return c.doFinal(blob, 13, blob.size - 13)
        }
    }

    private class MemFiles : KeyFiles {
        var current: ByteArray? = null
        val retiredMap = LinkedHashMap<String, ByteArray>()
        var failWrite = false
        override fun readCurrent() = current
        override fun writeCurrent(b: ByteArray): Boolean { if (failWrite || current != null) return false; current = b; return true }
        override fun retireCurrent(): String? {
            val c = current ?: return ""
            val name = "local_key.20300101-00000${retiredMap.size}.bin"
            retiredMap[name] = c; current = null; return name
        }
        override fun retired() = retiredMap.toMap()
    }

    private val sample = """{"k":"loc","lat":1.0,"lng":2.0}"""

    @Test fun firstUseGeneratesAndLaterLoadsSameKey() {
        val w = FakeWrapper(); val f = MemFiles()
        val a = KeyRing(w, f).load()
        assertEquals(KeyRing.Rotation.CREATED, a.rotation)
        val b = KeyRing(w, f).load()
        assertNull(b.rotation)
        assertEquals(a.current!!.id, b.current!!.id)
        assertEquals(sample, b.current!!.decrypt(a.current!!.encrypt(sample)))
        assertEquals(1, w.generated)
        // 落盘的只是封装结果与标识，不含数据密钥本身
        val env = KeyEnvelope.decode(f.current!!)!!
        assertEquals(a.current!!.id, env.id)
        assertEquals(KeyEnvelope.aliasFor(env.id), env.alias)
    }

    @Test fun legacyKeyFileIsTheInitialKeyForE1Records() {
        val w = FakeWrapper(); val f = MemFiles()
        val raw = ByteArray(32) { 9 }
        f.current = w.wrap(KeyEnvelope.LEGACY_ALIAS, raw)   // 升级前的格式：首字节 1，无标识
        val old = RecordCipher(SecretKeySpec(raw, "AES"))
        val e1 = "e1:" + RecordFormat.payloadOf(old.encrypt(sample))
        val st = KeyRing(w, f).load()
        assertNull(st.rotation)
        assertEquals(RecordFormat.LEGACY_ID, st.current!!.id)
        assertEquals(listOf(sample), RecordCodec.decodeLines(listOf(e1)) { st.readable[it] }.text)
    }

    @Test fun transientFailuresNeverReplaceTheKey() {
        val w = FakeWrapper(); val f = MemFiles()
        val first = KeyRing(w, f).load().current!!
        val saved = f.current
        // 密钥库不可用
        w.unavailable = true
        assertTrue(KeyRing(w, f).load().deferred)
        // 解封抛出暂时性异常
        w.unavailable = false; w.transientUnwrap = true
        repeat(3) { assertTrue(KeyRing(w, f).load().deferred) }
        assertArrayEquals(saved, f.current)
        assertTrue(f.retiredMap.isEmpty())
        assertEquals(1, w.generated)
        // 恢复后仍是原来的密钥
        w.transientUnwrap = false
        val again = KeyRing(w, f).load()
        assertNull(again.rotation)
        assertEquals(sample, again.current!!.decrypt(first.encrypt(sample)))
    }

    @Test fun lostMasterKeepsOldKeyFileAndReportsLost() {
        val w = FakeWrapper(); val f = MemFiles()
        val old = KeyRing(w, f).load().current!!
        val oldBytes = f.current!!
        val oldLine = old.encrypt(sample)
        w.masters.clear()   // 系统清空了密钥库
        val st = KeyRing(w, f).load()
        assertEquals(KeyRing.Rotation.LOST, st.rotation)
        assertEquals(setOf(old.id), st.lost)
        assertNotEquals(old.id, st.current!!.id)
        // 旧密钥文件改名保留，内容不变
        assertEquals(1, f.retiredMap.size)
        assertArrayEquals(oldBytes, f.retiredMap.values.single())
        val newLine = st.current!!.encrypt("""{"k":"ev"}""")
        val d = RecordCodec.decodeLines(listOf(oldLine, newLine)) { st.readable[it] }
        assertEquals(listOf("""{"k":"ev"}"""), d.text)
        assertEquals(1, d.unreadable)
        // 之后的启动：继续用新密钥，旧密钥仍判定为不可恢复，不再换新
        val next = KeyRing(w, f).load()
        assertNull(next.rotation)
        assertEquals(st.current!!.id, next.current!!.id)
        assertEquals(setOf(old.id), next.lost)
    }

    @Test fun invalidKeyIsRetainedAndRecoveredOnLaterStart() {
        val w = FakeWrapper(); val f = MemFiles()
        val old = KeyRing(w, f).load().current!!
        val oldLine = old.encrypt(sample)
        w.invalidAliases += KeyEnvelope.aliasFor(old.id)
        val st = KeyRing(w, f).load()
        assertEquals(KeyRing.Rotation.INVALID, st.rotation)
        assertEquals(setOf(old.id), st.pending)
        assertTrue(st.lost.isEmpty())
        val newLine = st.current!!.encrypt("""{"k":"ev"}""")
        // 后来旧密钥又能解开：旧记录恢复可读，新记录继续用新密钥
        w.invalidAliases.clear()
        val later = KeyRing(w, f).load()
        assertNull(later.rotation)
        assertEquals(st.current!!.id, later.current!!.id)
        assertTrue(later.pending.isEmpty())
        val d = RecordCodec.decodeLines(listOf(oldLine, newLine)) { later.readable[it] }
        assertEquals(listOf(sample, """{"k":"ev"}"""), d.text)
        assertEquals(0, d.unreadable)
    }

    @Test fun pendingKeyIsRetriedWithinTheSameProcess() {
        val w = FakeWrapper(); val f = MemFiles()
        val old = KeyRing(w, f).load().current!!
        w.invalidAliases += KeyEnvelope.aliasFor(old.id)
        val ring = KeyRing(w, f)
        val st = ring.load()
        assertEquals(setOf(old.id), st.pending)
        assertTrue(ring.retryPending().isEmpty())
        w.invalidAliases.clear()
        val got = ring.retryPending()
        assertEquals(setOf(old.id), got.keys)
        assertEquals(sample, got[old.id]!!.decrypt(old.encrypt(sample)))
        // 重试时主密钥已不存在：归入不可恢复
        val w2 = FakeWrapper(); val f2 = MemFiles()
        val o2 = KeyRing(w2, f2).load().current!!
        w2.invalidAliases += KeyEnvelope.aliasFor(o2.id)
        val r2 = KeyRing(w2, f2); r2.load()
        w2.masters.remove(KeyEnvelope.aliasFor(o2.id))
        val lostNow = mutableSetOf<String>()
        assertTrue(r2.retryPending(lostNow).isEmpty())
        assertEquals(setOf(o2.id), lostNow)
    }

    @Test fun newKeyIsNotUsedUntilSaved() {
        val w = FakeWrapper(); val f = MemFiles()
        val old = KeyRing(w, f).load().current!!
        w.masters.clear()
        f.failWrite = true
        val st = KeyRing(w, f).load()
        assertTrue(st.deferred)
        // 旧密钥已保留，没有被覆盖
        assertEquals(1, f.retiredMap.size)
        f.failWrite = false
        val next = KeyRing(w, f).load()
        assertNotNull(next.current)
        assertEquals(KeyRing.Rotation.MISSING, next.rotation)
        assertEquals(setOf(old.id), next.lost)
    }

    @Test fun lockedSummaryCountsLostDaysOnly() {
        val ids = mapOf(
            "2030-01-01_aaaaaaaa.jsonl" to setOf("0"),            // 初始密钥已不可恢复
            "2030-01-02_bus.jsonl" to setOf("0", "beef0001"),     // 混合
            "2030-01-03_bbbbbbbb.jsonl" to setOf("beef0001"),     // 可读
            "2030-01-04_cccccccc.jsonl" to setOf("cafe0002"),     // 待重试
            "2030-01-05_dddddddd.jsonl" to setOf("dead0003"),     // 没有任何密钥文件
        )
        val s = LockedRecords.summarize(ids, readable = setOf("beef0001"), pending = setOf("cafe0002"), lost = setOf("0"))
        assertEquals(setOf("2030-01-01", "2030-01-02", "2030-01-05"), s.lostDays)
        assertEquals(setOf("2030-01-04"), s.pendingDays)
        assertFalse(LockedRecords.isLost("cafe0002", setOf("beef0001"), setOf("cafe0002"), setOf("0")))
        // 没有不可恢复记录时不提示
        assertTrue(LockedRecords.summarize(mapOf("2030-01-03_x.jsonl" to setOf("beef0001")), setOf("beef0001"), emptySet(), emptySet()).lostDays.isEmpty())
    }
}
