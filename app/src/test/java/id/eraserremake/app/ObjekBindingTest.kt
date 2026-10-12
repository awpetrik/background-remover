package id.eraserremake.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import uniffi.eraser_core.EditorEngine
import uniffi.eraser_core.ModelKind
import uniffi.eraser_core.ModelRuntime
import uniffi.eraser_core.ProgressCallback

/** Tes alat Objek Fase 3 lewat binding: prompt SAM dan segmentasi instans. */
class ObjekBindingTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "models/sam/decoder.onnx").isFile) return dir
            dir = dir.parentFile ?: error("repo root tidak ditemukan")
        }
    }

    private fun runtime(root: File): ModelRuntime {
        val dylib = File(root, "rust/eraser-core/third_party/onnxruntime/linux-x64/libonnxruntime.so")
        assumeTrue("lib ORT host belum ada", dylib.isFile)
        return ModelRuntime(dylib.absolutePath)
    }

    private val noopProgress = object : ProgressCallback {
        override fun onProgress(percent: UInt) = Unit
    }

    @Test
    fun samPromptSelectsSquare() {
        val root = repoRoot()
        val rt = runtime(root)
        rt.registerModel(
            "mobilesam",
            File(root, "models/sam").absolutePath,
            ModelKind.POINT_SAM,
            1024u,
            1024u,
        )
        val w = 256
        val h = 256
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                if (x in 64 until 192 && y in 64 until 192) {
                    rgba[i] = 255.toByte()
                    rgba[i + 1] = 255.toByte()
                    rgba[i + 2] = 255.toByte()
                }
                rgba[i + 3] = 255.toByte()
            }
        }
        val e = EditorEngine.open(w.toUInt(), h.toUInt(), rgba)
        e.samStart(rt, "mobilesam", noopProgress)
        assertTrue(e.samIsActive())
        val preview = e.samAddPoint(rt, 128f, 128f, true)
        val pw = preview.width.toInt()
        val centerAlpha = preview.rgba[((128 * pw + 128) * 4 + 3)].toInt() and 0xFF
        assertTrue("pratinjau tengah harus berwarna", centerAlpha > 0)
        e.samApply(rt, true)
        val after = e.preview(100_000u)
        fun alpha(x: Int, y: Int) = after.rgba[((y * w + x) * 4 + 3)].toInt() and 0xFF
        assertEquals(255, alpha(128, 128))
        assertEquals(0, alpha(4, 4))
        assertTrue(e.undo())
        val restored = e.preview(100_000u)
        assertEquals(255, restored.rgba[(4 * w + 4) * 4 + 3].toInt() and 0xFF)
        e.close()
        rt.close()
    }

    @Test
    fun detectBusAndKeepOnly() {
        val root = repoRoot()
        val fixture = File(root, "tools/work/test_bus.rgb")
        assumeTrue("fixture bus belum dibuat", fixture.isFile)
        val sizeParts = File(root, "tools/work/test_bus.size").readText().trim().split(" ")
        val w = sizeParts[0].toInt()
        val h = sizeParts[1].toInt()
        val rgb = fixture.readBytes()
        val rgba = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            rgba[i * 4] = rgb[i * 3]
            rgba[i * 4 + 1] = rgb[i * 3 + 1]
            rgba[i * 4 + 2] = rgb[i * 3 + 2]
            rgba[i * 4 + 3] = 255.toByte()
        }
        val rt = runtime(root)
        val model = File(root, "models/instance/yolo11n-seg.onnx")
        assertTrue("model instans harus ada", model.isFile)
        rt.registerModel("yolo", model.absolutePath, ModelKind.INSTANCE_SEG, 640u, 640u)
        val e = EditorEngine.open(w.toUInt(), h.toUInt(), rgba)
        val found = e.detectObjects(rt, "yolo", noopProgress)
        val labels = found.map { it.label }
        assertTrue("deteksi terlalu sedikit: $labels", found.size >= 3)
        assertTrue("bus tidak ada: $labels", labels.contains("bus"))
        assertTrue("person tidak ada: $labels", labels.contains("person"))
        val bus = found.first { it.label == "bus" }
        e.applyObjectSelection(listOf(bus.index), true)
        val after = e.preview(1_000_000u)
        fun alpha(x: Int, y: Int) = after.rgba[((y * w + x) * 4 + 3)].toInt() and 0xFF
        val cx = (bus.x + bus.width / 2u).toInt()
        val cy = (bus.y + bus.height / 2u).toInt()
        assertTrue("tengah bus tetap terlihat", alpha(cx, cy) > 0)
        assertEquals("di atas bus harus transparan", 0, alpha(w / 2, 50))
        assertTrue(e.undo())
        e.close()
        rt.close()
    }
}
