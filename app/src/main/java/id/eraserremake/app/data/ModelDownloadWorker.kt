package id.eraserremake.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Unduh satu model dari katalog: tulis ke file .part, laporkan progres,
 * verifikasi SHA-256, lalu daftarkan ke database. Hash tidak cocok = gagal
 * dan file dibuang (design doc 8).
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_ID) ?: return@withContext failure("id kosong")
        val url = inputData.getString(KEY_URL) ?: return@withContext failure("url kosong")
        val expectedHash = inputData.getString(KEY_SHA256).orEmpty()
        val fileName = inputData.getString(KEY_FILE) ?: "$id.onnx"
        val total = inputData.getLong(KEY_SIZE, 0L)

        val modelsDir = File(applicationContext.filesDir, "models").apply { mkdirs() }
        val part = File(modelsDir, "$fileName.part")
        val dest = File(modelsDir, fileName)

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = (downloaded * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                setProgress(
                                    Data.Builder()
                                        .putInt(KEY_PROGRESS, percent)
                                        .build(),
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            part.delete()
            return@withContext Result.retry()
        } catch (e: Exception) {
            part.delete()
            return@withContext failure("unduhan gagal: ${e.message}")
        }

        val downloadedSize = part.length()

        if (expectedHash.isNotEmpty() && !FileHash.verify(part, expectedHash)) {
            part.delete()
            return@withContext failure("verifikasi SHA-256 gagal, file ditolak")
        }

        val kind = inputData.getString(KEY_KIND) ?: ModelStore.KIND_SALIENT
        if (kind == ModelStore.KIND_SAM) {
            // Paket SAM adalah zip berisi encoder.onnx dan decoder.onnx.
            val dir = File(modelsDir, fileName)
            val ok = runCatching {
                dir.mkdirs()
                java.util.zip.ZipInputStream(part.inputStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == "encoder.onnx" || entry.name == "decoder.onnx") {
                            File(dir, entry.name).outputStream().use { zip.copyTo(it) }
                        }
                        entry = zip.nextEntry
                    }
                }
                File(dir, "encoder.onnx").isFile && File(dir, "decoder.onnx").isFile
            }.getOrDefault(false)
            part.delete()
            if (!ok) {
                dir.deleteRecursively()
                return@withContext failure("paket SAM tidak berisi encoder.onnx dan decoder.onnx")
            }
        } else if (!part.renameTo(dest)) {
            part.delete()
            return@withContext failure("gagal menyimpan file model")
        }

        val dao = AppDb.get(applicationContext).modelDao()
        val hasDefault = dao.allByKind(kind).any { it.isDefault }
        dao.upsert(
            InstalledModel(
                id = id,
                name = inputData.getString(KEY_NAME) ?: id,
                kind = kind,
                version = inputData.getString(KEY_VERSION) ?: "1",
                sizeBytes = downloadedSize,
                inputWidth = inputData.getInt(KEY_IW, 320),
                inputHeight = inputData.getInt(KEY_IH, 320),
                fileName = fileName,
                bundled = false,
                isDefault = !hasDefault,
                sha256 = expectedHash,
            ),
        )
        Result.success()
    }

    private fun failure(message: String): Result = Result.failure(
        Data.Builder().putString(KEY_ERROR, message).build(),
    )

    companion object {
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_KIND = "kind"
        const val KEY_VERSION = "version"
        const val KEY_SIZE = "size"
        const val KEY_SHA256 = "sha256"
        const val KEY_IW = "iw"
        const val KEY_IH = "ih"
        const val KEY_URL = "url"
        const val KEY_FILE = "file"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
    }
}
