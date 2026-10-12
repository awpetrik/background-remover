package id.eraserremake.app.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.nio.ByteBuffer

/** Konversi Bitmap dan buffer RGBA mentah yang dipakai engine Rust. */
object Bitmaps {

    /**
     * Bitmap Android menyimpan RGB premultiplied terhadap alfa; engine Rust
     * bekerja dengan alfa lurus. Konversi dilakukan di sini, hanya pada batas
     * buka/simpan, bukan per operasi.
     */
    fun rgbaOf(bitmap: Bitmap): ByteArray {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val bytes = ByteArray(argb.byteCount)
        argb.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
        var i = 0
        while (i < bytes.size) {
            val a = bytes[i + 3].toInt() and 0xFF
            if (a in 1..254) {
                for (c in 0..2) {
                    val v = (bytes[i + c].toInt() and 0xFF) * 255 / a
                    bytes[i + c] = v.coerceAtMost(255).toByte()
                }
            } else if (a == 0) {
                bytes[i] = 0
                bytes[i + 1] = 0
                bytes[i + 2] = 0
            }
            i += 4
        }
        return bytes
    }

    fun fromRgba(rgba: ByteArray, width: Int, height: Int): Bitmap {
        val bytes = rgba.copyOf()
        var i = 0
        while (i < bytes.size) {
            val a = bytes[i + 3].toInt() and 0xFF
            if (a in 1..254) {
                for (c in 0..2) {
                    val v = (bytes[i + c].toInt() and 0xFF) * a / 255
                    bytes[i + c] = v.toByte()
                }
            } else if (a == 0) {
                bytes[i] = 0
                bytes[i + 1] = 0
                bytes[i + 2] = 0
            }
            i += 4
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        return bitmap
    }

    /** Gambar bitmap di atas pola checkerboard, untuk thumbnail dan pratinjau JPG. */
    fun overCheckerboard(source: Bitmap, cellPx: Int = 16): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val light = Paint().apply { color = 0xFFFFFFFF.toInt() }
        val dark = Paint().apply { color = 0xFFC9C9C9.toInt() }
        canvas.drawPaint(light)
        var row = 0
        var y = 0
        while (y < source.height) {
            var col = row % 2
            var x = 0
            while (x < source.width) {
                if (col % 2 == 1) {
                    canvas.drawRect(
                        x.toFloat(),
                        y.toFloat(),
                        minOf(x + cellPx, source.width).toFloat(),
                        minOf(y + cellPx, source.height).toFloat(),
                        dark,
                    )
                }
                x += cellPx
                col++
            }
            y += cellPx
            row++
        }
        canvas.drawBitmap(source, 0f, 0f, null)
        return out
    }

    fun scaledToMax(source: Bitmap, maxPx: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxPx) return source
        val scale = maxPx.toFloat() / longest
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }
}
