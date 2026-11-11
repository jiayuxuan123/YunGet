package com.yunget.app.ui.scanner

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    @Test
    fun decodesQuarkShareLinkFromLuminanceFrame() {
        val expected = "https://pan.quark.cn/s/abc12345"
        val matrix = QRCodeWriter().encode(
            expected,
            BarcodeFormat.QR_CODE,
            240,
            240,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val width = matrix.width
        val height = matrix.height
        val luminance = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (matrix[x, y]) 0.toByte() else 255.toByte()
        }

        assertEquals(
            expected,
            QrDecoder.decodeLuminance(luminance, width, height, 0, 0, width, height),
        )
    }

    @Test
    fun rejectsTruncatedFrameWithoutThrowing() {
        assertNull(QrDecoder.decodeLuminance(ByteArray(4), 10, 10, 0, 0, 10, 10))
    }
}
