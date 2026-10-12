package com.hereliesaz.sphereslam.nativebridge

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hereliesaz.sphereslam.common.InternalSphereSlamApi
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A session created as the very first use of [KpmBridge], with nothing having loaded libsphereslam:
 * the path a host takes when it goes straight to `SphereSlam.create`. Before [KpmBridge] loaded the
 * library itself this threw UnsatisfiedLinkError on device while every other test passed, because
 * they all call [KpmBridge.isAvailable] (which loads it) first.
 *
 * The ABI gate reads [Build.SUPPORTED_ABIS] rather than [KpmBridge.isAvailable] so the bridge stays
 * untouched until the create call. The test is only cold when its class runs first in the process
 * (alone, or under Android Test Orchestrator):
 * `./gradlew :core:nativebridge:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hereliesaz.sphereslam.nativebridge.KpmBridgeColdStartTest`.
 */
@OptIn(InternalSphereSlamApi::class)
@RunWith(AndroidJUnit4::class)
class KpmBridgeColdStartTest {

    @Test
    fun aSessionIsTheFirstThingCreated() {
        assumeTrue(
            "libsphereslam has no build for ${Build.SUPPORTED_ABIS.toList()}",
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" },
        )
        val s = KpmBridge.createCalibratedSession(320, 240, 300f, 300f, 160f, 120f)
        try {
            assertNotEquals(0L, s)
        } finally {
            KpmBridge.destroySession(s)
        }
    }
}
