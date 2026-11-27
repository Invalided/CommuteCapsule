package com.busnotice.commute

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** 恢复码二维码的确定性回归测试：固定随机种子，覆盖足量随机码 */
class RecoveryQrTest {
    /**
     * 在编码器默认掩模下无法被常规识别流程定位的样例码（随机生成，不含任何真实数据），
     * 用于确认生成端的掩模自检与识别端的回退均能覆盖此类内容。
     */
    private val hardCodes = listOf(
        "N7EC8EJN4AR3FSZEATAV66J4", "X50MEC1FX9DAG7JR2P3ACKJ8", "ENAW85BGHWWKXJ0ATPT8Q9JB",
        "EJCXYWJRBNQ03T7Z53YR4SQN", "GAM8AFMHH0FWNCXR0Y2KKB4V",
    )

    private fun randomCode(r: Random): String =
        String(CharArray(RecoveryCode.LENGTH) { RecoveryCode.ALPHABET[r.nextInt(32)] })

    /** 将模块矩阵按 scale 放大为白底黑块的像素，并在四周加 pad 像素的 bg 色背景 */
    private fun render(m: Array<BooleanArray>, scale: Int, pad: Int = 0, bg: Int = WHITE): Triple<IntArray, Int, Int> {
        val q = m.size * scale
        val w = q + 2 * pad
        val px = IntArray(w * w) { bg }
        for (y in 0 until q) for (x in 0 until q) px[(y + pad) * w + x + pad] = if (m[y / scale][x / scale]) BLACK else WHITE
        return Triple(px, w, w)
    }

    private fun roundTrip(code: String, scale: Int, pad: Int = 0, bg: Int = WHITE): Boolean {
        val (px, w, h) = render(RecoveryQr.matrix(RecoveryCode.qrPayload(code)), scale, pad, bg)
        return RecoveryQr.decode(px, w, h)?.let(RecoveryCode::fromQr) == code
    }

    @Test fun matrixKeepsStandardQuietZone() {
        val m = RecoveryQr.matrix(RecoveryCode.qrPayload(hardCodes[0]))
        val n = m.size
        for (i in 0 until n) for (j in 0 until RecoveryQr.MARGIN) {
            assertTrue(!m[j][i] && !m[n - 1 - j][i] && !m[i][j] && !m[i][n - 1 - j])
        }
        assertTrue("恢复码二维码应保持低版本，便于识别", n - 2 * RecoveryQr.MARGIN <= 33)
    }

    @Test fun knownHardCodesDecodeOnPlainAndDarkBackgrounds() {
        for (c in hardCodes) {
            assertTrue(c, roundTrip(c, scale = 6))
            assertTrue(c, roundTrip(c, scale = 6, pad = 120, bg = DARK))
            assertTrue(c, roundTrip(c, scale = 3, pad = 40, bg = WHITE))
        }
    }

    @Test fun manyRandomCodesAllDecode() {
        var total = 0
        val failed = ArrayList<String>()
        for (seed in longArrayOf(1L, 7L, 42L)) {
            val r = Random(seed)
            repeat(1100) {
                val c = randomCode(r)
                total++
                if (!roundTrip(c, scale = 4)) failed += c
            }
        }
        assertTrue(total >= 3000)
        assertEquals("无法识别的码：$failed", 0, failed.size)
    }

    /** 旧版生成的二维码（2 模块留白、默认掩模）仍可由当前识别流程读取 */
    @Test fun legacyQrImagesStillDecode() {
        for (c in hardCodes) {
            val payload = RecoveryCode.qrPayload(c)
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
            val bm = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
            val m = Array(bm.height) { y -> BooleanArray(bm.width) { x -> bm.get(x, y) } }
            val (px, w, h) = render(m, scale = 6)
            assertEquals(c, RecoveryQr.decode(px, w, h)?.let(RecoveryCode::fromQr))
        }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val DARK = 0xFF202020.toInt()
    }
}
