package com.hereliesaz.sphereslam

import android.opengl.Matrix
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reconstructed LightCycleTargetManager (The "Blue Dot" System).
 * Manages the augmented reality targets (Blue Dots) for spherical capture.
 * Uses a Fibonacci Lattice to distribute points evenly on a sphere.
 * 
 * Logic strictly follows the reconstruction of Google's LightCycle engine.
 */
class LightCycleTargetManager(numTargets: Int) {

    companion object {
        // The Golden Ratio
        private const val PHI = 1.618033988749895 
        
        // Threshold to trigger auto-capture (approx 3 degrees)
        private const val CAPTURE_THRESHOLD_RAD = 0.052f 
    }

    private val mTargetVectors: MutableList<FloatArray> = ArrayList()
    private val mTargetCaptured: BooleanArray = BooleanArray(numTargets)
    private var mCurrentTargetIndex = -1

    init {
        generateFibonacciSphere(numTargets)
    }

    /**
     * Generates target points using the Fibonacci Sphere algorithm.
     * Aligns the vertical axis of the Fibonacci spiral with the World Z axis (Up).
     */
    private fun generateFibonacciSphere(n: Int) {
        for (i in 0 until n) {
            // Fibonacci vertical component (range -1 to 1)
            val z = 1.0f - (i / (n - 1).toFloat()) * 2.0f
            val radius = sqrt(1.0f - z * z)
            val theta = (2.0 * Math.PI * i / PHI).toFloat()

            val x = (cos(theta.toDouble()) * radius).toFloat()
            val y = (sin(theta.toDouble()) * radius).toFloat()

            // World coordinates: Z is up.
            mTargetVectors.add(floatArrayOf(x, y, z))
            mTargetCaptured[i] = false
        }
    }

    /**
     * Checks if the camera is aligned with the current target.
     * @param rotationMatrix 4x4 rotation matrix mapping device to world.
     * @return true if capture should trigger
     */
    fun checkAlignment(rotationMatrix: FloatArray): Boolean {
        // Camera's forward vector is typically -Z in the device local frame
        val lookAt = floatArrayOf(0f, 0f, -1f, 1f)
        val worldForward = FloatArray(4)
          
        // Transform the local look vector (-Z) into the world frame
        Matrix.multiplyMV(worldForward, 0, rotationMatrix, 0, lookAt, 0)

        // Find closest uncaptured target
        var closestIdx = -1
        var maxDotProduct = -1.0f

        for (i in mTargetVectors.indices) {
            if (mTargetCaptured[i]) continue

            val target = mTargetVectors[i]
            // Standard 3D dot product (ignoring W component of worldForward)
            val dot = worldForward[0] * target[0] + worldForward[1] * target[1] + worldForward[2] * target[2]
            
            if (dot > maxDotProduct) {
                maxDotProduct = dot
                closestIdx = i
            }
        }

        mCurrentTargetIndex = closestIdx

        // Threshold check: acos(dot) < threshold
        if (maxDotProduct > -1.0f) {
            val angle = acos(maxDotProduct.toDouble().coerceIn(-1.0, 1.0))
            if (angle < CAPTURE_THRESHOLD_RAD) {
                return true
            }
        }
        return false
    }

    fun markCurrentTargetCaptured() {
        if (mCurrentTargetIndex != -1) {
            mTargetCaptured[mCurrentTargetIndex] = true
        }
    }

    fun getCurrentTargetIndex(): Int = mCurrentTargetIndex
    fun getTargetVectors(): List<FloatArray> = mTargetVectors
    fun getCapturedFlags(): BooleanArray = mTargetCaptured
    fun getTargetCount(): Int = mTargetVectors.size
    fun getCapturedCount(): Int = mTargetCaptured.count { it }
    
    fun reset() {
        for (i in mTargetCaptured.indices) mTargetCaptured[i] = false
        mCurrentTargetIndex = -1
    }
}
