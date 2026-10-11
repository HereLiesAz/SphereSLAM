package com.hereliesaz.sphereslam.models

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared asset extraction for the model wrappers. ONNX Runtime and LiteRT open a model from a file
 * path (LiteRT memory-maps it, which a compressed APK asset would not allow), so a bundled asset is
 * copied to `filesDir` once — and **re-copied whenever its content changes**: the SHA-256 of the
 * bundled asset is compared with the one recorded next to the extracted copy (`<file>.sha256`), so an
 * app update shipping a different model under the same name (even one of the same size) never keeps
 * running the stale copy. The asset's hash is computed once per process. The copy is hashed while it
 * is written to a temp file, verified, and renamed into place, so a crash mid-copy never leaves a
 * truncated or mismatched model at the final path.
 */
internal object ModelAssets {

    private val assetHashes = ConcurrentHashMap<String, String>()

    /**
     * Ensure [dest] holds the asset [name]. Returns false (and logs under [tag]) when the asset is
     * missing or the copy fails.
     */
    fun copyAssetIfNeeded(context: Context, name: String, dest: File, tag: String): Boolean {
        return try {
            val expected = assetHashes[name] ?: context.assets.open(name).use(::sha256Hex).also { assetHashes[name] = it }
            val stamp = stampFile(dest)
            val recorded = if (stamp.exists()) stamp.readText().trim() else null
            if (isUpToDate(dest.exists(), dest.length(), recorded, expected)) return true
            val tmp = File(dest.parentFile, "${dest.name}.tmp")
            val written = context.assets.open(name).use { input -> tmp.outputStream().use { copyHashing(input, it) } }
            if (written != expected) {
                tmp.delete()
                Log.w(tag, "asset $name copy does not match the bundled asset ($written != $expected)")
                return false
            }
            // renameTo does not replace an existing file on every filesystem; clear the stale copy first.
            if (stamp.exists()) stamp.delete()
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return false
            }
            stamp.writeText(expected) // last: a crash before this just re-copies next time
            dest.exists() && dest.length() > 0L
        } catch (t: Throwable) {
            Log.w(tag, "asset $name unavailable", t)
            false
        }
    }

    /** The content stamp written next to an extracted [dest]. */
    fun stampFile(dest: File): File = File(dest.parentFile, "${dest.name}.sha256")

    /**
     * Whether an extracted copy can be reused: it must exist, be non-empty, and carry a recorded
     * content hash equal to the bundled asset's. Pure; unit-tested.
     */
    fun isUpToDate(exists: Boolean, length: Long, recordedSha256: String?, assetSha256: String): Boolean =
        exists && length > 0L && recordedSha256 != null && recordedSha256.equals(assetSha256, ignoreCase = true)

    /** Lower-case hex SHA-256 of [input] (read to the end, not closed). */
    fun sha256Hex(input: InputStream): String = copyHashing(input, null)

    /** SHA-256 of [file]. */
    fun sha256Hex(file: File): String = file.inputStream().use(::sha256Hex)

    private fun copyHashing(input: InputStream, output: OutputStream?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
            output?.write(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
