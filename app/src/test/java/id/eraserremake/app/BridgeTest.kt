package id.eraserremake.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.eraser_core.EngineException
import uniffi.eraser_core.ProgressCallback
import uniffi.eraser_core.failDemo
import uniffi.eraser_core.ping
import uniffi.eraser_core.rgbaChecksum
import uniffi.eraser_core.runDemoJob

/**
 * Menjalankan binding UniFFI yang sama seperti di aplikasi, melawan build host
 * dari eraser-core (jna.library.path diatur di app/build.gradle.kts).
 */
class BridgeTest {

    @Test
    fun pingReturnsRustString() {
        val out = ping("unit-test")
        assertTrue(out.contains("unit-test"))
        assertTrue(out.contains("eraser-core"))
    }

    @Test
    fun demoJobReportsProgressAndSum() {
        val seen = mutableListOf<UInt>()
        val sum = runDemoJob(5u, object : ProgressCallback {
            override fun onProgress(percent: UInt) {
                seen.add(percent)
            }
        })
        assertEquals(15uL, sum)
        assertEquals(listOf(20u, 40u, 60u, 80u, 100u), seen)
    }

    @Test
    fun demoJobRejectsZeroSteps() {
        try {
            runDemoJob(0u, object : ProgressCallback {
                override fun onProgress(percent: UInt) = Unit
            })
            fail("expected EngineException.InvalidInput")
        } catch (e: EngineException.InvalidInput) {
            assertTrue(e.message!!.contains("at least 1"))
        }
    }

    @Test
    fun failDemoThrowsInternal() {
        try {
            failDemo()
            fail("expected EngineException.Internal")
        } catch (e: EngineException.Internal) {
            // sesuai harapan
        }
    }

    @Test
    fun checksumValidatesBuffer() {
        assertEquals(10uL + 255uL * 4uL, rgbaChecksum(byteArrayOf(10, 0, 0, 255.toByte())))
        try {
            rgbaChecksum(byteArrayOf(1, 2, 3))
            fail("expected EngineException.InvalidInput")
        } catch (e: EngineException.InvalidInput) {
            // sesuai harapan
        }
    }
}
