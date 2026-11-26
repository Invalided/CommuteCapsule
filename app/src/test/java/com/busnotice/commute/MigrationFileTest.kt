package com.busnotice.commute

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** 迁移文件：恢复码、加解密、文件头与包内容的编解码 */
class MigrationFileTest {
    private val code = "0123456789ABCDEFGHJKMNPQ"

    private fun reason(block: () -> Unit): MigrationException.Reason? =
        try { block(); null } catch (e: MigrationException) { e.reason }

    @Test fun recoveryCodeHas120BitsAndNormalizesInput() {
        val a = RecoveryCode.generate(); val b = RecoveryCode.generate()
        assertEquals(24, a.length)
        assertTrue(a.all { it in RecoveryCode.ALPHABET })
        assertTrue(a != b)
        assertEquals("0123-4567-89AB-CDEF-GHJK-MNPQ", RecoveryCode.format(code))
        // 忽略大小写、空格和连字符；O/I/L 视为 0/1/1
        assertEquals(code, RecoveryCode.normalize(" 0123-4567 89ab-cdef ghjk-mnpq "))
        assertEquals("0111" + code.substring(4), RecoveryCode.normalize("OIlI" + code.substring(4)))
        assertNull(RecoveryCode.normalize(code.dropLast(1)))
        assertNull(RecoveryCode.normalize(code.dropLast(1) + "U"))
        // 二维码内容带前缀；其他二维码不接受
        assertEquals(code, RecoveryCode.fromQr(RecoveryCode.qrPayload(code)))
        assertNull(RecoveryCode.fromQr("https://example.org/$code"))
    }

    @Test fun sealAndOpenRoundTrip() {
        val plain = "迁移内容".toByteArray() + ByteArray(1000) { it.toByte() }
        val sealed = MigrationCrypto.seal(plain, code)
        assertTrue(String(sealed, 0, 5, Charsets.US_ASCII) == "CCMIG")
        assertEquals(MigrationCrypto.VERSION, sealed[5].toInt())
        assertArrayEquals(plain, MigrationCrypto.open(sealed, code))
        // 输入时的分组与大小写不影响
        assertArrayEquals(plain, MigrationCrypto.open(sealed, RecoveryCode.format(code).lowercase()))
        // 同样内容每次加密结果不同（随机盐与 IV）
        assertFalse(sealed.contentEquals(MigrationCrypto.seal(plain, code)))
    }

    @Test fun wrongCodeOrTamperingIsReportedClearly() {
        val sealed = MigrationCrypto.seal("x".repeat(200).toByteArray(), code)
        assertEquals(MigrationException.Reason.WRONG_CODE_OR_CORRUPT, reason { MigrationCrypto.open(sealed, RecoveryCode.generate()) })
        for (pos in listOf(10, 30, sealed.size / 2, sealed.size - 1)) {   // 盐、IV、密文、标签
            val bad = sealed.copyOf(); bad[pos] = (bad[pos].toInt() xor 1).toByte()
            assertEquals(MigrationException.Reason.WRONG_CODE_OR_CORRUPT, reason { MigrationCrypto.open(bad, code) })
        }
        assertEquals(MigrationException.Reason.WRONG_CODE_OR_CORRUPT, reason { MigrationCrypto.open(sealed.copyOf(40), code) })
        assertEquals(MigrationException.Reason.NOT_MIGRATION_FILE, reason { MigrationCrypto.open("PK\u0003\u0004zip".toByteArray(), code) })
        assertEquals("恢复码不对或文件已损坏", MigrationException(MigrationException.Reason.WRONG_CODE_OR_CORRUPT).userMessage)
    }

    @Test fun newerFormatAsksToUpgrade() {
        val sealed = MigrationCrypto.seal(ByteArray(10), code)
        val newer = sealed.copyOf(); newer[5] = (MigrationCrypto.VERSION + 1).toByte()
        assertEquals(MigrationException.Reason.NEWER_VERSION, reason { MigrationCrypto.checkHeader(newer) })
        assertTrue(MigrationException(MigrationException.Reason.NEWER_VERSION).userMessage.contains("升级"))
        // 包内清单的格式版本更高时同样提示升级
        val zip = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("manifest.json")); z.write("""{"format":${MigrationPackage.FORMAT + 1}}""".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        assertEquals(MigrationException.Reason.NEWER_VERSION, reason { MigrationPackage.read(zip) })
    }

    @Test fun hkdfMatchesRfc5869TestCase1() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val okm = MigrationCrypto.hkdf(hex("0b".repeat(22)), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    @Test fun packageRoundTripKeepsTypesAndRecords() {
        val prefs = mapOf(
            "trips" to mapOf<String, Any>("list" to """[{"id":"t1"},{"id":"t2"}]"""),
            "learn" to mapOf<String, Any>("b" to true, "i" to 7, "l" to 1_900_000_000_000L, "f" to 1.5f, "ss" to setOf("a", "b"), "s" to "文字"),
        )
        val traces = mapOf("2030-01-01_aaaaaaaa.jsonl" to listOf("""{"k":"loc","lat":1.0}""", """{"k":"ev"}"""), "2030-01-02_bus.jsonl" to listOf("""{"k":"bus"}"""))
        val meta = PackageMeta(MigrationPackage.FORMAT, 1L, "0.0.0", 2, true, 2, 0)
        val c = MigrationPackage.read(MigrationPackage.write(MigrationContent(meta, prefs, traces, mapOf("samples" to "[[1.0,2.0,10.0,1]]"))))
        assertEquals(prefs, c.prefs)
        assertEquals(traces, c.traces)
        assertEquals("[[1.0,2.0,10.0,1]]", c.samplePrefs!!["samples"])
        val p = Migrator.preview(c)
        assertEquals(2, p.trips); assertTrue(p.includesRecords); assertEquals(2, p.traceDays)
        // 不含行程记录
        val noRec = MigrationPackage.read(MigrationPackage.write(MigrationContent(meta, prefs, null, null)))
        assertNull(noRec.traces); assertNull(noRec.samplePrefs)
        assertFalse(Migrator.preview(noRec).includesRecords)
        // 有记录标记但没有文件：视为包含（空）
        val empty = MigrationPackage.read(MigrationPackage.write(MigrationContent(meta, prefs, emptyMap(), emptyMap())))
        assertNotNull(empty.traces)
    }

    @Test fun unknownEntriesAndUnsafeNamesAreIgnored() {
        val zip = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
                put("manifest.json", """{"format":1,"createdAt":5}""")
                put("prefs/trips.json", """{"list":{"t":"s","v":"[]"}}""")
                put("prefs/run_state.json", """{"x":{"t":"s","v":"y"}}""")
                put("traces/../../shared_prefs/x.jsonl", "a")
                put("traces/sub/x.jsonl", "a")
                put("traces/2030-01-01_ok.jsonl", "line\n")
            }
        }.toByteArray()
        val c = MigrationPackage.read(zip)
        assertEquals(setOf("trips"), c.prefs.keys)
        assertEquals(setOf("2030-01-01_ok.jsonl"), c.traces!!.keys)
        assertEquals(MigrationException.Reason.WRONG_CODE_OR_CORRUPT, reason { MigrationPackage.read("not a zip".toByteArray()) })
    }

    @Test fun deviceOnlyKeysAreNotExported() {
        val p = MigrationPackage.portable("places", mapOf("nextSample" to 5L, "clusters" to "[]", "n" to null))
        assertEquals(mapOf<String, Any>("clusters" to "[]"), p)
    }

    @Test fun exportedPrefsMatchTheBackupWhitelist() {
        val f = listOf(File("src/main/res/xml/data_extraction_rules.xml"), File("app/src/main/res/xml/data_extraction_rules.xml")).first { it.exists() }
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement
        val nodes = (root.getElementsByTagName("cloud-backup").item(0) as Element).getElementsByTagName("include")
        val names = (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("path").removeSuffix(".xml") }.toSet()
        assertEquals(names, MigrationPackage.PREFS.toSet())
        assertFalse(MigrationPackage.SAMPLE_PREFS in MigrationPackage.PREFS)
    }

    @Test fun recoveryCodeQrRoundTrip() {
        val c = RecoveryCode.generate(SecureRandom())
        val m = RecoveryQr.matrix(RecoveryCode.qrPayload(c))
        // 每个模块放大为 6×6 像素，模拟截图
        val scale = 6; val w = m.size * scale
        val px = IntArray(w * w) { i -> if (m[(i / w) / scale][(i % w) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertEquals(c, RecoveryQr.decode(px, w, w)?.let(RecoveryCode::fromQr))
        assertNull(RecoveryQr.decode(IntArray(100 * 100) { 0xFFFFFFFF.toInt() }, 100, 100))
        if (m.size > 41) fail("恢复码二维码应保持低版本，便于识别")
    }
}
