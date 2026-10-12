package id.eraserremake.app.data

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 untuk verifikasi unduhan model (design doc 8). Pure JVM, bisa dites. */
object FileHash {
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun verify(file: File, expectedHex: String): Boolean =
        file.isFile && sha256Hex(file).equals(expectedHex, ignoreCase = true)
}
