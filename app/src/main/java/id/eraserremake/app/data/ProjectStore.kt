package id.eraserremake.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.eraser_core.EditorEngine
import java.io.File
import java.io.FileOutputStream

/**
 * Penyimpanan file proyek di penyimpanan internal:
 * files/projects/<id>/original.bin (salinan foto sumber apa adanya),
 * work.png (hasil edit terakhir), thumb.jpg (thumbnail di atas checkerboard).
 */
class ProjectStore(private val context: Context) {

    private val dao get() = AppDb.get(context).projectDao()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun projectsRoot(): File =
        File(context.filesDir, "projects").apply { mkdirs() }

    private fun dirFor(id: Long): File =
        File(projectsRoot(), id.toString()).apply { mkdirs() }

    /** Buat proyek dari foto yang dipilih lewat Photo Picker. */
    suspend fun createFromUri(uri: Uri): Project = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Foto tidak bisa dibaca" }

        val now = System.currentTimeMillis()
        val id = dao.insert(
            Project(
                createdAt = now,
                updatedAt = now,
                originalPath = "",
                workPath = null,
                thumbPath = "",
                width = bounds.outWidth,
                height = bounds.outHeight,
            ),
        )
        val dir = dirFor(id)
        val original = File(dir, "original.bin")
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(original).use { input.copyTo(it) }
        } ?: error("Foto tidak bisa disalin")

        val thumb = File(dir, "thumb.jpg")
        writeThumb(original, thumb)

        val project = Project(
            id = id,
            createdAt = now,
            updatedAt = now,
            originalPath = original.absolutePath,
            workPath = null,
            thumbPath = thumb.absolutePath,
            width = bounds.outWidth,
            height = bounds.outHeight,
        )
        dao.update(project)
        project
    }

    /** Bitmap awal untuk sesi edit: work.png bila ada, kalau tidak foto asli. */
    suspend fun loadEditBitmap(project: Project): Bitmap = withContext(Dispatchers.IO) {
        val file = project.workPath?.let { File(it) }?.takeIf { it.exists() }
            ?: File(project.originalPath)
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
            ?: error("Gambar proyek tidak bisa dibaca")
        decoded.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** Bitmap foto asli proyek (untuk Atur Ulang Edit). */
    suspend fun loadOriginalBitmap(project: Project): Bitmap = withContext(Dispatchers.IO) {
        val decoded = BitmapFactory.decodeFile(project.originalPath)
            ?: error("Gambar asli tidak bisa dibaca")
        decoded.copy(Bitmap.Config.ARGB_8888, false)
    }

    /**
     * Simpan keadaan engine sebagai work.png + thumbnail, dan perbarui baris
     * proyek. Sengaja tidak melempar: autosave tidak boleh mengganggu UI.
     */
    suspend fun saveWork(project: Project, engine: EditorEngine) {
        runCatching {
            withContext(Dispatchers.IO) {
                val dir = dirFor(project.id)
                val rgba = engine.currentRgba()
                val w = engine.width().toInt()
                val h = engine.height().toInt()
                val bitmap = Bitmaps.fromRgba(rgba, w, h)
                val work = File(dir, "work.png")
                FileOutputStream(work).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val thumb = File(dir, "thumb.jpg")
                writeThumbBitmap(bitmap, thumb)
                dao.update(
                    project.copy(
                        updatedAt = System.currentTimeMillis(),
                        workPath = work.absolutePath,
                        thumbPath = thumb.absolutePath,
                        width = w,
                        height = h,
                    ),
                )
            }
        }
    }

    /** saveWork tanpa menunggu selesai, untuk dipanggil dari titik keluar editor. */
    fun saveWorkAsync(project: Project, engine: EditorEngine) {
        ioScope.launch { saveWork(project, engine) }
    }

    /** Buang hasil edit (Atur Ulang Edit): hapus work.png dan kembalikan baris proyek. */
    suspend fun clearWork(project: Project): Project = withContext(Dispatchers.IO) {
        val dir = dirFor(project.id)
        File(dir, "work.png").delete()
        writeThumb(File(project.originalPath), File(dir, "thumb.jpg"))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(project.originalPath, bounds)
        val updated = project.copy(
            updatedAt = System.currentTimeMillis(),
            workPath = null,
            thumbPath = File(dir, "thumb.jpg").absolutePath,
            width = bounds.outWidth,
            height = bounds.outHeight,
        )
        dao.update(updated)
        updated
    }

    suspend fun delete(project: Project) = withContext(Dispatchers.IO) {
        dao.delete(project)
        dirFor(project.id).deleteRecursively()
        Unit
    }

    private fun writeThumb(originalFile: File, thumbFile: File) {
        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        BitmapFactory.decodeFile(originalFile.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > 512 || opts.outHeight / sample > 512) sample *= 2
        opts.inSampleSize = sample
        opts.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeFile(originalFile.absolutePath, opts) ?: return
        writeThumbBitmap(bitmap, thumbFile)
    }

    private fun writeThumbBitmap(source: Bitmap, thumbFile: File) {
        val scaled = Bitmaps.scaledToMax(source, 512)
        val flat = Bitmaps.overCheckerboard(scaled)
        FileOutputStream(thumbFile).use { flat.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }
}
