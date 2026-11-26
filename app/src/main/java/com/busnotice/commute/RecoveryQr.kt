package com.busnotice.commute

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 恢复码二维码的生成与识别（ZXing 核心库，与 Android 无关） */
object RecoveryQr {
    /** 生成二维码模块矩阵：[y][x] 为 true 表示深色，含 2 个模块的留白 */
    fun matrix(text: String): Array<BooleanArray> {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
    }

    /** 从图片像素（ARGB）中识别二维码文字；识别不到时返回 null */
    fun decode(argb: IntArray, width: Int, height: Int): String? {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
        val src = RGBLuminanceSource(width, height, argb)
        for (bin in listOf(HybridBinarizer(src), GlobalHistogramBinarizer(src), HybridBinarizer(src.invert()))) {
            val r = runCatching { MultiFormatReader().decode(BinaryBitmap(bin), hints) }.getOrNull()
            if (r != null) return r.text
        }
        return null
    }
}
