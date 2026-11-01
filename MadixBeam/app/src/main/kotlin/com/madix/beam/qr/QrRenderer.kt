package com.madix.beam.qr

import android.graphics.Bitmap
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.madix.beam.protocol.session.ErrorCorrection

/**
 * Renders QR codes at one pixel per module. The bitmap is scaled up on screen with
 * nearest-neighbour filtering, which keeps module edges perfectly sharp for the camera.
 */
object QrRenderer {
    private const val DARK = 0xFF000000.toInt()
    private const val LIGHT = 0xFFFFFFFF.toInt()

    fun render(text: String, errorCorrection: ErrorCorrection, quietZone: Int = 2): Bitmap {
        val qr = Encoder.encode(text, errorCorrection.toZxing(), null)
        val matrix = qr.matrix
        val width = matrix.width
        val size = width + quietZone * 2
        val pixels = IntArray(size * size) { LIGHT }
        for (y in 0 until matrix.height) {
            val row = (y + quietZone) * size + quietZone
            for (x in 0 until width) {
                if (matrix.get(x, y).toInt() == 1) pixels[row + x] = DARK
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun ErrorCorrection.toZxing(): ErrorCorrectionLevel = when (this) {
        ErrorCorrection.L -> ErrorCorrectionLevel.L
        ErrorCorrection.M -> ErrorCorrectionLevel.M
        ErrorCorrection.Q -> ErrorCorrectionLevel.Q
        ErrorCorrection.H -> ErrorCorrectionLevel.H
    }
}
