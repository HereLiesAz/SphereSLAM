package com.hereliesaz.sphereslam.common.util

import android.util.Log
import com.hereliesaz.sphereslam.common.InternalSphereSlamApi

/**
 * Loads SphereSLAM's native engine (`libsphereslam.so`).
 *
 * Loading never throws. Any failure — most commonly an `UnsatisfiedLinkError` on an ABI the AAR does
 * not ship (the native library is built for `arm64-v8a` and `armeabi-v7a` only, so x86/x86_64
 * emulators have no `libsphereslam.so`) — is logged and reported as `false`, so callers can degrade
 * to "unavailable" instead of crashing in a class initializer.
 *
 * libsphereslam has no OpenCV dependency (the embedded artoolkitX subset is built with
 * `HAVE_OPENCV 0`), so OpenCV is not loaded here.
 */
@InternalSphereSlamApi
object NativeLibLoader {
    private const val TAG = "NativeLibLoader"

    @Volatile private var loaded = false

    /**
     * Load libsphereslam once. Returns true when it is loaded (now or previously); false when the
     * load failed. A failed load may be retried by calling again.
     */
    @Synchronized
    fun loadAll(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("sphereslam")
            Log.i(TAG, "libsphereslam.so loaded successfully.")
            loaded = true
            true
        } catch (t: Throwable) {
            // UnsatisfiedLinkError (missing ABI / library), SecurityException, or anything else:
            // never propagate out of a class initializer.
            Log.e(TAG, "libsphereslam.so could not be loaded; SphereSLAM native is unavailable.", t)
            false
        }
    }
}
