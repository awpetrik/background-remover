package id.eraserremake.app.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.eraser_core.ModelKind
import uniffi.eraser_core.ModelRuntime

/**
 * Pemegang tunggal ModelRuntime Rust. Library ONNX Runtime dimuat dari
 * nativeLibraryDir aplikasi (libonnxruntime.so dibundel di APK).
 */
object ModelRuntimeHolder {

    @Volatile
    private var runtime: ModelRuntime? = null

    fun get(context: Context): ModelRuntime = runtime ?: synchronized(this) {
        runtime ?: ModelRuntime(
            File(context.applicationInfo.nativeLibraryDir, "libonnxruntime.so").absolutePath,
        ).also { created ->
            // Terapkan preferensi akselerasi tersimpan ke runtime baru.
            runCatching { created.setUseNnapi(SettingsStore(context).useNnapi) }
            runtime = created
        }
    }

    fun kindOf(name: String): ModelKind = when (name) {
        "InstanceSeg" -> ModelKind.INSTANCE_SEG
        "PointSam" -> ModelKind.POINT_SAM
        "Selfie" -> ModelKind.SELFIE
        else -> ModelKind.SALIENT_MASK
    }

    /** Daftarkan semua model terpasang ke runtime (idempoten). Untuk PointSam
     * path menunjuk direktori berisi encoder.onnx dan decoder.onnx. */
    suspend fun registerInstalled(context: Context, store: ModelStore) {
        val rt = get(context)
        val dao = AppDb.get(context).modelDao()
        withContext(Dispatchers.IO) {
            for (model in dao.all()) {
                val file = store.fileOf(model)
                if (file.exists() && !rt.isRegistered(model.id)) {
                    runCatching {
                        rt.registerModel(
                            model.id,
                            file.absolutePath,
                            kindOf(model.kind),
                            model.inputWidth.toUInt(),
                            model.inputHeight.toUInt(),
                        )
                    }
                }
            }
        }
    }

    fun unregister(id: String) {
        runtime?.unloadModel(id)
    }
}
