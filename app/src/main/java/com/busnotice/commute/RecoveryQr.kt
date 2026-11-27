package com.busnotice.commute

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 恢复码二维码的生成与识别（ZXing 核心库，与 Android 无关） */
object RecoveryQr {
    /** 留白宽度（模块数），取 QR 规范规定的最小静区 4 */
    const val MARGIN = 4

    /** 自检时每个模块放大的像素数 */
    private const val VERIFY_SCALE = 4

    /**
     * 生成二维码模块矩阵：[y][x] 为 true 表示深色，含 [MARGIN] 个模块的留白。
     *
     * 个别内容在编码器默认选择的掩模下，其数据区会形成与定位图形相似的图样，
     * 导致识别器定位失败。生成后先以本对象的识别流程自检，不通过时依次改用其他掩模，
     * 直至可识别为止；所有掩模均符合规范，扫描端无需任何改动。
     */
    fun matrix(text: String): Array<BooleanArray> {
        val first = encode(text, null)
        if (verifies(first, text)) return first.toArray()
        for (mask in 0 until 8) {
            val m = encode(text, mask)
            if (verifies(m, text)) return m.toArray()
        }
        return first.toArray()
    }

    /** 从图片像素（ARGB）中识别二维码文字；识别不到时返回 null */
    fun decode(argb: IntArray, width: Int, height: Int): String? {
        decodeOnce(argb, width, height, pure = false)?.let { return it }
        // 截图或图片仅含二维码本身时，跳过定位图形检测，直接按纯码图采样
        decodeOnce(argb, width, height, pure = true)?.let { return it }
        // 缩小后重试：改变模块的像素尺度，可绕开部分定位误判
        for (f in 2..3) {
            if (width / f < 21 || height / f < 21) break
            val (px, w, h) = downscale(argb, width, height, f)
            decodeOnce(px, w, h, pure = false)?.let { return it }
        }
        return null
    }

    private fun encode(text: String, mask: Int?): BitMatrix {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
        hints[EncodeHintType.MARGIN] = MARGIN
        if (mask != null) hints[EncodeHintType.QR_MASK_PATTERN] = mask
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    private fun BitMatrix.toArray() = Array(height) { y -> BooleanArray(width) { x -> get(x, y) } }

    /** 以常规识别流程（不使用纯码图提示）检查矩阵渲染后能否还原出原文字 */
    private fun verifies(m: BitMatrix, text: String): Boolean {
        val w = m.width * VERIFY_SCALE
        val h = m.height * VERIFY_SCALE
        val px = IntArray(w * h) { i ->
            if (m.get((i % w) / VERIFY_SCALE, (i / w) / VERIFY_SCALE)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return decodeOnce(px, w, h, pure = false) == text
    }

    private fun decodeOnce(argb: IntArray, width: Int, height: Int, pure: Boolean): String? {
        val hints = HashMap<DecodeHintType, Any>()
        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE)
        hints[DecodeHintType.TRY_HARDER] = true
        if (pure) hints[DecodeHintType.PURE_BARCODE] = true
        val src = RGBLuminanceSource(width, height, argb)
        for (bin in listOf(HybridBinarizer(src), GlobalHistogramBinarizer(src), HybridBinarizer(src.invert()))) {
            val r = runCatching { MultiFormatReader().decode(BinaryBitmap(bin), hints) }.getOrNull()
            if (r != null) return r.text
        }
        return null
    }

    /** 按 f×f 像素取平均亮度缩小图片，返回（像素、宽、高） */
    private fun downscale(argb: IntArray, width: Int, height: Int, f: Int): Triple<IntArray, Int, Int> {
        val w = width / f
        val h = height / f
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (dy in 0 until f) for (dx in 0 until f) {
                val p = argb[(y * f + dy) * width + x * f + dx]
                sum += (((p shr 16) and 0xff) * 299 + ((p shr 8) and 0xff) * 587 + (p and 0xff) * 114) / 1000
            }
            val v = sum / (f * f)
            out[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Triple(out, w, h)
    }
}
