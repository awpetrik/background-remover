package id.eraserremake.app.ui

import android.graphics.Bitmap
import id.eraserremake.app.data.Bitmaps
import id.eraserremake.app.data.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.eraser_core.BrushMode
import uniffi.eraser_core.CropRect
import uniffi.eraser_core.EditorEngine
import uniffi.eraser_core.ImagePoint

/**
 * Satu sesi edit. Semua panggilan engine berjalan di Dispatchers.Default;
 * buffer piksel tetap di Rust, yang menyeberang hanya pratinjau dan hasil ekspor.
 */
class EditorSession(
    val project: Project,
    val engine: EditorEngine,
) {
    fun imageWidth(): Int = engine.width().toInt()
    fun imageHeight(): Int = engine.height().toInt()
    fun canUndo(): Boolean = engine.canUndo()
    fun canRedo(): Boolean = engine.canRedo()

    suspend fun previewBitmap(maxPx: Int): Bitmap = withContext(Dispatchers.Default) {
        val preview = engine.preview(maxPx.toUInt())
        Bitmaps.fromRgba(preview.rgba, preview.width.toInt(), preview.height.toInt())
    }

    suspend fun beginStroke() = withContext(Dispatchers.Default) { engine.beginStroke() }

    suspend fun stroke(points: List<ImagePoint>, radius: Float, mode: BrushMode) =
        withContext(Dispatchers.Default) { engine.brush(points, radius, mode) }

    suspend fun endStroke() = withContext(Dispatchers.Default) { engine.endStroke() }

    suspend fun undo(): Boolean = withContext(Dispatchers.Default) { engine.undo() }

    suspend fun redo(): Boolean = withContext(Dispatchers.Default) { engine.redo() }

    suspend fun crop(rect: CropRect) = withContext(Dispatchers.Default) { engine.crop(rect) }

    /** Terapkan model AI. Progress 0..100 dilaporkan lewat onProgress. */
    suspend fun applyAi(
        runtime: uniffi.eraser_core.ModelRuntime,
        modelId: String,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.Default) {
        engine.applyAi(
            runtime,
            modelId,
            object : uniffi.eraser_core.ProgressCallback {
                override fun onProgress(percent: UInt) = onProgress(percent.toInt())
            },
        )
    }

    suspend fun invertMask() = withContext(Dispatchers.Default) { engine.invertMask() }

    suspend fun refineEdges(level: Int) =
        withContext(Dispatchers.Default) { engine.refineEdges(level.toUByte()) }

    suspend fun autoColor(x: Int, y: Int, tolerance: Int) = withContext(Dispatchers.Default) {
        engine.autoColor(x.toUInt(), y.toUInt(), tolerance.toUByte())
    }

    suspend fun magic(x: Int, y: Int, tolerance: Int, radius: Int) =
        withContext(Dispatchers.Default) {
            engine.magic(x.toUInt(), y.toUInt(), tolerance.toUByte(), radius.toUInt())
        }

    suspend fun detectObjects(
        runtime: uniffi.eraser_core.ModelRuntime,
        modelId: String,
        onProgress: (Int) -> Unit,
    ): List<uniffi.eraser_core.DetectedObject> = withContext(Dispatchers.Default) {
        engine.detectObjects(
            runtime,
            modelId,
            object : uniffi.eraser_core.ProgressCallback {
                override fun onProgress(percent: UInt) = onProgress(percent.toInt())
            },
        )
    }

    suspend fun applyObjectSelection(indices: List<Int>, keepOnly: Boolean) =
        withContext(Dispatchers.Default) {
            engine.applyObjectSelection(indices.map { it.toUInt() }, keepOnly)
        }

    suspend fun clearDetections() = withContext(Dispatchers.Default) {
        engine.clearDetections()
    }

    suspend fun samStart(
        runtime: uniffi.eraser_core.ModelRuntime,
        modelId: String,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.Default) {
        engine.samStart(
            runtime,
            modelId,
            object : uniffi.eraser_core.ProgressCallback {
                override fun onProgress(percent: UInt) = onProgress(percent.toInt())
            },
        )
    }

    suspend fun samAddPoint(
        runtime: uniffi.eraser_core.ModelRuntime,
        x: Float,
        y: Float,
        positive: Boolean,
    ): Bitmap = withContext(Dispatchers.Default) {
        val preview = engine.samAddPoint(runtime, x, y, positive)
        Bitmaps.fromRgba(preview.rgba, preview.width.toInt(), preview.height.toInt())
    }

    suspend fun samUndoPoint(runtime: uniffi.eraser_core.ModelRuntime): Bitmap =
        withContext(Dispatchers.Default) {
            val preview = engine.samUndoPoint(runtime)
            Bitmaps.fromRgba(preview.rgba, preview.width.toInt(), preview.height.toInt())
        }

    suspend fun samApply(runtime: uniffi.eraser_core.ModelRuntime, keepOnly: Boolean) =
        withContext(Dispatchers.Default) {
            engine.samApply(runtime, keepOnly)
        }

    suspend fun samCancel(runtime: uniffi.eraser_core.ModelRuntime) =
        withContext(Dispatchers.Default) {
            engine.samCancel(runtime)
        }

    fun samIsActive(): Boolean = engine.samIsActive()

    suspend fun fullBitmap(): Bitmap = withContext(Dispatchers.Default) {
        Bitmaps.fromRgba(engine.currentRgba(), imageWidth(), imageHeight())
    }

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { engine.close() }
        }
    }

    companion object {
        suspend fun open(project: Project, bitmap: Bitmap): EditorSession =
            withContext(Dispatchers.Default) {
                val rgba = Bitmaps.rgbaOf(bitmap)
                val engine = EditorEngine.open(
                    bitmap.width.toUInt(),
                    bitmap.height.toUInt(),
                    rgba,
                )
                EditorSession(project, engine)
            }
    }
}
