package id.eraserremake.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class ExportFormat { PNG, JPG }

/** Render hasil akhir dari buffer Rust dan kirim ke galeri atau aplikasi lain. */
object Exporter {

    const val GALLERY_DIR = "Pictures/EraserRemake"

    /** Bitmap siap ekspor. JPG diratakan ke warna latar; ukuran kecil dibatasi 1024px. */
    fun render(source: Bitmap, format: ExportFormat, bgColor: Int, small: Boolean): Bitmap {
        val sized = if (small) Bitmaps.scaledToMax(source, 1024) else source
        if (format == ExportFormat.PNG) return sized
        val flat = Bitmap.createBitmap(sized.width, sized.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawPaint(Paint().apply { color = bgColor })
        canvas.drawBitmap(sized, 0f, 0f, null)
        return flat
    }

    suspend fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        format: ExportFormat,
    ): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = "eraser_${System.currentTimeMillis()}"
        val mime = if (format == ExportFormat.PNG) "image/png" else "image/jpeg"
        val ext = if (format == ExportFormat.PNG) "png" else "jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                val compressFormat =
                    if (format == ExportFormat.PNG) Bitmap.CompressFormat.PNG
                    else Bitmap.CompressFormat.JPEG
                bitmap.compress(compressFormat, 95, out)
            } ?: false
        }.getOrDefault(false)
        if (!ok) {
            resolver.delete(uri, null, null)
            return@withContext null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }

    /** Tulis ke cache dan buat intent bagikan. Mengembalikan intent chooser. */
    suspend fun shareIntent(
        context: Context,
        bitmap: Bitmap,
        format: ExportFormat,
    ): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val ext = if (format == ExportFormat.PNG) "png" else "jpg"
        val file = File(dir, "eraser_${System.currentTimeMillis()}.$ext")
        FileOutputStream(file).use { out ->
            val compressFormat =
                if (format == ExportFormat.PNG) Bitmap.CompressFormat.PNG
                else Bitmap.CompressFormat.JPEG
            bitmap.compress(compressFormat, 95, out)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (format == ExportFormat.PNG) "image/png" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, "Bagikan hasil").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
