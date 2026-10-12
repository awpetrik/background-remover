package id.eraserremake.app.data

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Pengelola model AI: model terbundel, katalog (cache file atau aset bawaan),
 * unduhan via WorkManager, dan daftar terpasang di Room (design doc 5.3, 8).
 */
class ModelStore(private val context: Context) {

    private val db = AppDb.get(context)
    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }
    private val catalogCache = File(context.filesDir, "catalog.json")

    fun observeInstalled(): Flow<List<InstalledModel>> = db.modelDao().observeAll()

    fun observeSalient(): Flow<List<InstalledModel>> =
        db.modelDao().observeByKind(KIND_SALIENT)

    fun observeInstance(): Flow<List<InstalledModel>> =
        db.modelDao().observeByKind(KIND_INSTANCE)

    fun observeSam(): Flow<List<InstalledModel>> =
        db.modelDao().observeByKind(KIND_SAM)

    fun fileOf(model: InstalledModel): File = File(modelsDir, model.fileName)

    /** Salin model terbundel dari aset dan daftarkan di database (idempoten). */
    suspend fun ensureBundled() = withContext(Dispatchers.IO) {
        val dest = File(modelsDir, BUNDLED_FILE)
        val ok = dest.isFile && FileHash.verify(dest, BUNDLED_SHA256)
        if (!ok) {
            context.assets.open("models/$BUNDLED_FILE").use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }
        if (db.modelDao().getById(BUNDLED_ID) == null) {
            val hasDefault = db.modelDao().allByKind(KIND_SALIENT).any { it.isDefault }
            db.modelDao().upsert(
                InstalledModel(
                    id = BUNDLED_ID,
                    name = "U2NetP (bawaan)",
                    kind = KIND_SALIENT,
                    version = "1",
                    sizeBytes = dest.length(),
                    inputWidth = 320,
                    inputHeight = 320,
                    fileName = BUNDLED_FILE,
                    bundled = true,
                    isDefault = !hasDefault,
                    sha256 = BUNDLED_SHA256,
                ),
            )
        }
    }

    /** Katalog aktif: cache hasil refresh bila ada, kalau tidak katalog aset bawaan. */
    suspend fun loadCatalog(): List<CatalogModel> = withContext(Dispatchers.IO) {
        val json = if (catalogCache.isFile) {
            catalogCache.readText()
        } else {
            context.assets.open("models/catalog.json").bufferedReader().readText()
        }
        parseCatalog(json)
    }

    fun catalogIsCached(): Boolean = catalogCache.isFile

    /** Unduh katalog JSON dari URL hosting statis dan jadikan cache aktif. */
    suspend fun refreshCatalog(url: String): Result<List<CatalogModel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                val text = conn.inputStream.bufferedReader().readText()
                val parsed = parseCatalog(text)
                require(parsed.isNotEmpty()) { "katalog kosong atau format salah" }
                catalogCache.writeText(text)
                parsed
            }
        }

    suspend fun setDefault(model: InstalledModel) {
        db.modelDao().allByKind(model.kind).forEach { other ->
            if (other.isDefault && other.id != model.id) {
                db.modelDao().upsert(other.copy(isDefault = false))
            }
        }
        db.modelDao().upsert(model.copy(isDefault = true))
    }

    /** Hapus model hasil unduhan (model terbundel tidak bisa dihapus).
     * Untuk PointSam yang terhapus adalah direktori modelnya. */
    suspend fun deleteInstalled(model: InstalledModel) {
        if (model.bundled) return
        withContext(Dispatchers.IO) { fileOf(model).deleteRecursively() }
        db.modelDao().deleteById(model.id)
        ModelRuntimeHolder.unregister(model.id)
    }

    /** Antrekan unduhan satu entri katalog. Pekerjaan unik per id model. */
    fun enqueueDownload(entry: CatalogModel) {
        val data = Data.Builder()
            .putString(ModelDownloadWorker.KEY_ID, entry.id)
            .putString(ModelDownloadWorker.KEY_NAME, entry.name)
            .putString(ModelDownloadWorker.KEY_KIND, entry.kind)
            .putString(ModelDownloadWorker.KEY_VERSION, entry.version)
            .putLong(ModelDownloadWorker.KEY_SIZE, entry.sizeBytes)
            .putString(ModelDownloadWorker.KEY_SHA256, entry.sha256)
            .putInt(ModelDownloadWorker.KEY_IW, entry.inputWidth)
            .putInt(ModelDownloadWorker.KEY_IH, entry.inputHeight)
            .putString(ModelDownloadWorker.KEY_URL, entry.fileUrl)
            // PointSam diunduh sebagai zip dan disimpan sebagai direktori.
            .putString(
                ModelDownloadWorker.KEY_FILE,
                if (entry.kind == KIND_SAM) entry.id else entry.id + ".onnx",
            )
            .build()
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(data)
            .addTag(TAG_DOWNLOAD)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(entry.id),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelDownload(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    companion object {
        const val KIND_SALIENT = "SalientMask"
        const val KIND_INSTANCE = "InstanceSeg"
        const val KIND_SAM = "PointSam"
        const val TAG_DOWNLOAD = "model-download"
        const val BUNDLED_ID = "u2netp"
        const val BUNDLED_FILE = "u2netp.onnx"
        const val BUNDLED_SHA256 =
            "87d7f434f1e9c09f130e342815f1cc795e5d19129d41111e3873255f14378cfa"

        fun workName(id: String): String = "download-$id"
    }
}
