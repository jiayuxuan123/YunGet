package com.yunget.app.ui.scanner

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Pure ZXing adapter so camera frames and tests use the identical decode path. */
internal object QrDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    fun decodeLuminance(
        data: ByteArray,
        dataWidth: Int,
        dataHeight: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): String? {
        if (data.isEmpty() || dataWidth <= 0 || dataHeight <= 0 || width <= 0 || height <= 0) return null
        if (data.size < dataWidth * dataHeight) return null
        if (left < 0 || top < 0 || left + width > dataWidth || top + height > dataHeight) return null
        return runCatching {
            val source = PlanarYUVLuminanceSource(
                data,
                dataWidth,
                dataHeight,
                left,
                top,
                width,
                height,
                false,
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().apply { setHints(hints) }
                .decodeWithState(bitmap)
                ?.text
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
