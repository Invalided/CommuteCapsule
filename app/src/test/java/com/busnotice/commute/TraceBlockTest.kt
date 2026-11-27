package com.busnotice.commute

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import javax.crypto.spec.SecretKeySpec

/** 行程记录压缩块：逐行无损往返、格式边界与密文封装（数据全部为随机虚构） */
class TraceBlockTest {
    private val key = RecordCipher(SecretKeySpec(ByteArray(32) { 5 }, "AES"), "k1")
    private val other = RecordCipher(SecretKeySpec(ByteArray(32) { 6 }, "AES"), "k2")

    private fun r(v: Double, f: Double) = Math.round(v * f) / f

    /** 按记录写入端的字段结构随机生成一行（org.json 序列化，与落盘前的明文同形） */
    private fun line(rnd: Random, t: Long): String {
        val o = JSONObject()
        when (rnd.nextInt(6)) {
            0 -> {
                o.put("m", r(rnd.nextDouble() * 3, 1000.0)).put("sd", r(rnd.nextDouble(), 1000.0)).put("n", rnd.nextInt(60))
                    .put("ax", r(rnd.nextGaussian(), 1000.0)).put("ay", r(rnd.nextGaussian(), 1000.0)).put("az", r(rnd.nextGaussian(), 1000.0))
                    .put("ts", t - rnd.nextInt(500))
                if (rnd.nextBoolean()) o.put("gx", r(rnd.nextGaussian() * 9, 1000.0)).put("gy", r(rnd.nextGaussian(), 1000.0)).put("gz", r(rnd.nextGaussian(), 1000.0))
                o.put("t", t).put("k", "acc")
            }
            1 -> {
                o.put("ts", t).put("mm", r(20 + rnd.nextDouble() * 60, 10.0)).put("msd", r(rnd.nextDouble() * 15, 100.0))
                if (rnd.nextBoolean()) o.put("p", r(900 + rnd.nextDouble() * 120, 100.0))
                o.put("t", t).put("k", "env")
            }
            2 -> o.put("lat", r(rnd.nextDouble() * 10 - 5, 1e6)).put("lng", r(rnd.nextDouble() * 10 - 5, 1e6))
                .put("acc", rnd.nextInt(200)).put("p", if (rnd.nextBoolean()) "gps" else "network")
                .put("spd", if (rnd.nextBoolean()) r(rnd.nextDouble() * 20, 10.0) else JSONObject.NULL)
                .put("ft", t - rnd.nextInt(3000)).put("t", t).put("k", "loc")
            3 -> {
                val cells = JSONArray()
                repeat(1 + rnd.nextInt(2)) {
                    cells.put(JSONObject().put("type", if (rnd.nextBoolean()) "nr" else "lte").put("id", rnd.nextLong() and 0xFFFFFFFFFL)
                        .put("tac", rnd.nextInt(1 shl 24)).put("pci", rnd.nextInt(1008)).put("dbm", -40 - rnd.nextInt(80)))
                }
                o.put("cells", cells)
                if (rnd.nextBoolean()) o.put("nb", JSONArray().put(JSONObject().put("type", "lte").put("pci", rnd.nextInt(504)).put("dbm", -90)))
                o.put("t", t).put("k", "cell")
            }
            4 -> o.put("e", listOf("start", "boarded", "metro_stop", "测试事件").random(rnd.asKotlin()))
                .put("leg", rnd.nextInt(4)).put("note", "示例 ${rnd.nextInt(1000)}").put("ok", rnd.nextBoolean()).put("t", t).put("k", "ev")
            else -> o.put("n", 10_000L + rnd.nextInt(5000)).put("ts", t).put("t", t).put("k", "step")
        }
        return o.toString()
    }

    private fun Random.asKotlin() = kotlin.random.Random(nextLong())

    private fun trace(seed: Long, n: Int): List<String> {
        val rnd = Random(seed)
        var t = 1_000_000_000_000L + rnd.nextInt(1_000_000)
        return List(n) { t += rnd.nextInt(2500); line(rnd, t) }
    }

    private fun roundTrip(lines: List<String>) {
        val b = TraceBlock.encode(lines)
        assertEquals(lines, TraceBlock.decode(b))
        val rec = key.encryptBlock(b)
        assertTrue(rec.startsWith("e3:k1:"))
        assertEquals(Decoded(lines, 0), RecordCodec.decodeLines(listOf(rec), key))
    }

    @Test fun randomTracesRoundTripExactly() {
        for (seed in 1L..20L) roundTrip(trace(seed, 50 + seed.toInt() * 97))
    }

    @Test fun columnarCodecIsUsedAndCompresses() {
        val lines = trace(99, 3000)
        val b = TraceBlock.encode(lines)
        assertEquals(TraceBlock.CODEC_COLUMNAR, b[0])
        val plain = lines.sumOf { it.toByteArray().size + 1 }
        assertTrue("compressed ${b.size} of $plain", b.size * 4 < plain)
    }

    @Test fun emptyAndSingleLine() {
        roundTrip(emptyList())
        roundTrip(listOf("""{"e":"start","t":1,"k":"ev"}"""))
        roundTrip(listOf(""))
        roundTrip(listOf("", "x", ""))
    }

    @Test fun numbersThatAreNotPlainFixedPointKeepTheirText() {
        val odd = listOf(
            """{"a":-0,"b":-0.0,"c":007,"d":1.50,"e":1e5,"f":-2.5E-3,"g":1.,"h":.5,"t":1,"k":"x"}""",
            """{"big":12345678901234567890123,"neg":-9223372036854775808,"max":999999999999999999,"t":2,"k":"x"}""",
            """{"a":[1,2,-3,4.25],"s":"x:12,y:[3]","u":"字段:5}","t":3,"k":"x"}""",
            """{"a":-0,"b":0,"c":-0.0,"d":0.0,"t":4,"k":"x"}""",
            """[1,-1,0.001,-0.001,1.0E10,NaN,Infinity]""",
        )
        roundTrip(odd)
        roundTrip(odd.reversed() + odd)
    }

    @Test fun malformedAndUnusualLinesRoundTrip() {
        val lines = listOf(
            "{bad json", "}", ":", ":1", "[,", ",,1,", "e2:k1:not-really-base64", "plain text 123,456",
            "a\u0000b:1,", "\u0000", "tab\there:3}", "emoji 🚇 :42}", "  leading and trailing  ",
            """{"k":"acc","m":0.1}""" + " ".repeat(5),
        )
        roundTrip(lines)
    }

    @Test fun veryLongLineRoundTrips() {
        val rnd = Random(3)
        val long = buildString { append("{\"a\":["); repeat(40_000) { if (it > 0) append(','); append(rnd.nextInt(100000) - 50000).append('.').append(rnd.nextInt(10)) }; append("],\"k\":\"x\"}") }
        assertTrue(long.length > 200_000)
        roundTrip(listOf("start", long, "end"))
    }

    @Test fun lineBreaksInsideRecordsAreRejected() {
        assertTrue(runCatching { TraceBlock.encode(listOf("a\nb")) }.isFailure)
        assertTrue(runCatching { TraceBlock.encode(listOf("a\rb")) }.isFailure)
    }

    @Test fun textCodecRoundTrips() {
        for (lines in listOf(emptyList(), listOf(""), listOf("a", "", "b"), trace(5, 200))) {
            val b = TraceBlock.encodeText(lines)
            assertEquals(TraceBlock.CODEC_TEXT, b[0])
            assertEquals(lines, TraceBlock.decode(b))
        }
    }

    @Test fun corruptOrUnknownBlocksDecodeToNull() {
        val b = TraceBlock.encode(trace(7, 300))
        assertNull(TraceBlock.decode(ByteArray(0)))
        assertNull(TraceBlock.decode(byteArrayOf(9) + b.copyOfRange(1, b.size)))
        assertNull(TraceBlock.decode(b.copyOf(b.size / 2)))
        val flipped = b.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x5A).toByte() }
        val d = TraceBlock.decode(flipped)
        // Deflate 的校验和可发现绝大多数改动；整块另有 GCM 认证
        if (d != null) assertFalse(d == trace(7, 300))
    }

    @Test fun blockRecordFormatAndKeyHandling() {
        val lines = trace(11, 400)
        val rec = key.encryptBlock(TraceBlock.encode(lines))
        assertTrue(RecordFormat.isEncrypted(rec))
        assertTrue(RecordFormat.isBlock(rec))
        assertEquals("k1", RecordFormat.keyIdOf(rec))
        assertEquals(setOf("k1"), RecordCodec.keyIds(sequenceOf(rec)))
        // 逐条解密接口不接受压缩块
        assertNull(key.decrypt(rec))
        // 密钥不符：跳过并计数
        assertEquals(Decoded(emptyList(), 1), RecordCodec.decodeLines(listOf(rec)) { other })
        assertEquals(Decoded(emptyList(), 1), RecordCodec.decodeLines(listOf(rec), null))
        // 改动标识（附加认证数据）或密文均无法通过认证
        val renamed = "e3:k2:" + rec.removePrefix("e3:k1:")
        assertNull(other.decryptBlock(renamed))
        val forged = RecordCipher(SecretKeySpec(ByteArray(32) { 5 }, "AES"), "k2")
        assertNull(forged.decryptBlock(renamed))
        val body = rec.removePrefix("e3:k1:")
        val tampered = "e3:k1:" + body.substring(0, 40) + (if (body[40] == 'A') 'B' else 'A') + body.substring(41)
        assertEquals(1, RecordCodec.decodeLines(listOf(tampered), key).unreadable)
    }

    @Test fun mixedLegacyLinesAndBlocksDecodeInOrder() {
        val a = trace(21, 30); val b = trace(22, 40); val c = trace(23, 5)
        val stored = listOf("""{"legacy":1}""") + key.encryptBlock(TraceBlock.encode(a)) +
            b.map { key.encrypt(it) } + other.encryptBlock(TraceBlock.encode(c)) + "e1:" + "" + """{"legacy":2}"""
        val ring = mapOf("k1" to key, "k2" to other)
        val d = RecordCodec.decodeLines(stored) { ring[it] }
        assertEquals(listOf("""{"legacy":1}""") + a + b + c + """{"legacy":2}""", d.text)
        assertEquals(1, d.unreadable)   // "e1:" 无内容
    }

    @Test fun blockEncryptionUsesFreshIv() {
        val p = TraceBlock.encode(trace(31, 10))
        val x = key.encryptBlock(p); val y = key.encryptBlock(p)
        assertFalse(x == y)
        assertArrayEquals(key.decryptBlock(x), key.decryptBlock(y))
    }
}
