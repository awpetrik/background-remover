package id.eraserremake.app

import id.eraserremake.app.data.FileHash
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import uniffi.eraser_core.EditorEngine
import uniffi.eraser_core.ModelKind
import uniffi.eraser_core.ModelRuntime
import uniffi.eraser_core.ProgressCallback

/** Tes operasi Fase 2 lewat binding yang sama seperti aplikasi. */
class AiEngineTest {

    private fun twoColorEngine(): EditorEngine {
        val w = 40
        val h = 20
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                if (x < 20) rgba[i] = 255.toByte() else rgba[i + 2] = 255.toByte()
                rgba[i + 3] = 255.toByte()
            }
        }
        return EditorEngine.open(w.toUInt(), h.toUInt(), rgba)
    }

    private fun alphaAt(e: EditorEngine, x: Int, y: Int): Int {
        val w = e.width().toInt()
        val preview = e.preview(100_000u)
        return preview.rgba[((y * w) + x) * 4 + 3].toInt() and 0xFF
    }

    @Test
    fun fileHashMatchesKnownVector() {
        // SHA-256("abc") adalah vektor uji yang dikenal publik.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FileHash.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun autoColorRemovesMatchingColor() {
        val e = twoColorEngine()
        e.autoColor(5u, 10u, 10u)
        assertEquals(0, alphaAt(e, 15, 10))
        assertEquals(255, alphaAt(e, 30, 10))
        assertTrue(e.undo())
        assertEquals(255, alphaAt(e, 15, 10))
        e.close()
    }

    @Test
    fun magicRespectsRadius() {
        val e = twoColorEngine()
        e.magic(2u, 10u, 10u, 5u)
        assertEquals(0, alphaAt(e, 4, 10))
        assertEquals(255, alphaAt(e, 15, 10))
        e.close()
    }

    @Test
    fun invertAndRefineWork() {
        val e = twoColorEngine()
        e.invertMask()
        assertEquals(0, alphaAt(e, 5, 10), )
        assertTrue(e.undo())
        assertEquals(255, alphaAt(e, 5, 10))
        e.refineEdges(0u)
        assertFalse(e.canUndo())
        e.refineEdges(2u)
        assertTrue(e.canUndo())
        e.close()
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "models/bundled/u2netp.onnx").isFile) return dir
            dir = dir.parentFile ?: error("repo root tidak ditemukan dari user.dir")
        }
    }

    /**
     * Ujung ke ujung: ModelRuntime + model terbundel asli + applyAi pada
     * gambar sintetis. Dilewati bila library ORT host belum diunduh
     * (tools/fetch_onnxruntime.sh).
     */
    @Test
    fun applyAiWithBundledModel() {
        val root = repoRoot()
        val dylib = File(root, "rust/eraser-core/third_party/onnxruntime/linux-x64/libonnxruntime.so")
        assumeTrue("lib ORT host belum ada", dylib.isFile)
        val model = File(root, "models/bundled/u2netp.onnx")

        val runtime = ModelRuntime(dylib.absolutePath)
        runtime.registerModel(
            "u2netp",
            model.absolutePath,
            ModelKind.SALIENT_MASK,
            320u,
            320u,
        )
        assertTrue(runtime.isRegistered("u2netp"))

        // Gambar 64x64: bujur sangkar putih di tengah, latar hitam.
        val w = 64
        val h = 64
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                if (x in 16 until 48 && y in 16 until 48) {
                    rgba[i] = 255.toByte()
                    rgba[i + 1] = 255.toByte()
                    rgba[i + 2] = 255.toByte()
                }
                rgba[i + 3] = 255.toByte()
            }
        }
        val engine = EditorEngine.open(w.toUInt(), h.toUInt(), rgba)
        val progress = mutableListOf<UInt>()
        engine.applyAi(
            runtime,
            "u2netp",
            object : ProgressCallback {
                override fun onProgress(percent: UInt) {
                    synchronized(progress) { progress.add(percent) }
                }
            },
        )
        assertEquals(255, alphaAt(engine, 32, 32))
        assertEquals(0, alphaAt(engine, 2, 2))
        assertTrue("progres harus mencapai 100", progress.contains(100u))
        assertTrue(engine.undo())
        assertEquals(255, alphaAt(engine, 2, 2))
        engine.close()
        runtime.close()
    }
}
