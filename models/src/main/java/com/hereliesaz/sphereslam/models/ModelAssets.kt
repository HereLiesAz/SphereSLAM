package com.hereliesaz.sphereslam.models

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import java.io.File

/**
 * Shared asset extraction for the ONNX wrappers. ORT opens a model from a file path, so a bundled
 * asset is copied to `filesDir` once — and **re-copied when its size differs from the bundled asset**
 * (an app update shipping a new model under the same name must not keep running the stale copy).
 * The copy is written to a temp file and renamed into place, so a crash mid-copy never leaves a
 * truncated model at the final path.
 */
internal object ModelAssets {

    /**
     * Ensure [dest] holds the asset [name]. Returns false (and logs under [tag]) when the asset is
     * missing or the copy fails.
     */
    fun copyAssetIfNeeded(context: Context, name: String, dest: File, tag: String): Boolean {
        return try {
            val expected = assetLength(context, name)
            if (isUpToDate(dest.exists(), dest.length(), expected)) return true
            val tmp = File(dest.parentFile, "${dest.name}.tmp")
            context.assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (expected >= 0L && tmp.length() != expected) {
                tmp.delete()
                Log.w(tag, "asset $name copy truncated (${tmp.length()} != $expected)")
                return false
            }
            // renameTo does not replace an existing file on every filesystem; clear the stale copy first.
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return false
            }
            dest.exists() && dest.length() > 0L
        } catch (t: Throwable) {
            Log.w(tag, "asset $name unavailable", t)
            false
        }
    }

    /**
     * Whether an extracted copy can be reused: it must exist, be non-empty, and — when the bundled
     * asset's length is known ([expectedLength] `>= 0`) — match it exactly. Pure; unit-tested.
     */
    fun isUpToDate(exists: Boolean, length: Long, expectedLength: Long): Boolean {
        if (!exists || length <= 0L) return false
        return expectedLength < 0L || length == expectedLength
    }

    /**
     * The bundled asset's byte length: from its file descriptor when stored uncompressed, otherwise by
     * reading the stream through (compressed assets have no descriptor). -1 if it cannot be determined.
     */
    private fun assetLength(context: Context, name: String): Long {
        try {
            context.assets.openFd(name).use { fd ->
                if (fd.length != AssetFileDescriptor.UNKNOWN_LENGTH) return fd.length
            }
        } catch (_: Throwable) {
            // Compressed asset: fall through to counting the stream.
        }
        return try {
            context.assets.open(name).use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
                total
            }
        } catch (_: Throwable) {
            -1L
        }
    }
}
