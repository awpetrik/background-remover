package id.eraserremake.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.eraser_core.BrushMode
import uniffi.eraser_core.CropRect
import uniffi.eraser_core.EditorEngine
import uniffi.eraser_core.EngineException
import uniffi.eraser_core.ImagePoint

/** Menguji EditorEngine lewat binding yang sama seperti aplikasi. */
class EditorEngineTest {

    private fun engine(w: Int, h: Int, alpha: Byte = 255.toByte()): EditorEngine {
        val rgba = ByteArray(w * h * 4)
        var i = 0
        while (i < rgba.size) {
            rgba[i] = 200.toByte()
            rgba[i + 1] = 100
            rgba[i + 2] = 50
            rgba[i + 3] = alpha
            i += 4
        }
        return EditorEngine.open(w.toUInt(), h.toUInt(), rgba)
    }

    private fun alphaAt(e: EditorEngine, x: Int, y: Int): Int {
        val w = e.width().toInt()
        val preview = e.preview(100_000u)
        return preview.rgba[((y * w) + x) * 4 + 3].toInt() and 0xFF
    }

    @Test
    fun openRejectsWrongBufferSize() {
        try {
            EditorEngine.open(4u, 4u, ByteArray(10))
            fail("expected EngineException.InvalidInput")
        } catch (e: EngineException.InvalidInput) {
            // sesuai harapan
        }
    }

    @Test
    fun brushEraseAndRestore() {
        val e = engine(40, 40)
        e.brush(listOf(ImagePoint(20f, 20f)), 6f, BrushMode.ERASE)
        assertEquals(0, alphaAt(e, 20, 20))
        assertEquals(255, alphaAt(e, 0, 0))
        e.brush(listOf(ImagePoint(20f, 20f)), 6f, BrushMode.RESTORE)
        assertEquals(255, alphaAt(e, 20, 20))
        e.close()
    }

    @Test
    fun strokeTransactionIsSingleUndoStep() {
        val e = engine(80, 30)
        e.beginStroke()
        e.brush(listOf(ImagePoint(10f, 15f)), 4f, BrushMode.ERASE)
        e.brush(listOf(ImagePoint(60f, 15f)), 4f, BrushMode.ERASE)
        e.endStroke()
        assertEquals(0, alphaAt(e, 10, 15))
        assertEquals(0, alphaAt(e, 60, 15))
        assertTrue(e.undo())
        assertEquals(255, alphaAt(e, 10, 15))
        assertEquals(255, alphaAt(e, 60, 15))
        assertFalse(e.canUndo())
        e.close()
    }

    @Test
    fun cropAndUndoRestoreDimensions() {
        val e = engine(100, 80)
        e.crop(CropRect(10u, 5u, 40u, 30u))
        assertEquals(40, e.width().toInt())
        assertEquals(30, e.height().toInt())
        assertTrue(e.undo())
        assertEquals(100, e.width().toInt())
        assertEquals(80, e.height().toInt())
        e.close()
    }

    @Test
    fun previewScalesDown() {
        val e = engine(400, 200)
        val p = e.preview(100u)
        assertEquals(100, p.width.toInt())
        assertEquals(50, p.height.toInt())
        e.close()
    }
}
