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
 */
class LightCycleTargetManager(numTargets: Int) {

    companion object {
        // The Golden Ratio
        private const val PHI = 1.618033988749895 // (1.0 + Math.sqrt(5.0)) / 2.0
        
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
     * Math: lat = arccos(2i/N), lon = 2*pi*i / PHI
     */
    private fun generateFibonacciSphere(n: Int) {
        for (i in 0 until n) {
            // Map i to range [-1, 1]
            val y = 1.0f - (i / (n - 1).toFloat()) * 2.0f
            val radius = sqrt(1.0f - y * y)
            val theta = (2.0 * Math.PI * i / PHI).toFloat()

            val x = (cos(theta.toDouble()) * radius).toFloat()
            val z = (sin(theta.toDouble()) * radius).toFloat()

            // Store vector (x, y, z)
            mTargetVectors.add(floatArrayOf(x, y, z))
            mTargetCaptured[i] = false
        }
    }

    /**
     * Checks if the camera is aligned with the current target.
     * @param rotationMatrix 4x4 rotation matrix from SensorManager
     * @return true if capture should trigger
     */
    fun checkAlignment(rotationMatrix: FloatArray): Boolean {
        // Camera's forward vector is typically -Z in OpenGL
        val lookAt = floatArrayOf(0f, 0f, -1f, 1f)
        val currentVector = FloatArray(4)
          
        // Transform the look vector by the device rotation
        Matrix.multiplyMV(currentVector, 0, rotationMatrix, 0, lookAt, 0)

        // Find closest uncaptured target
        var closestIdx = -1
        var maxDotProduct = -1.0f

        for (i in mTargetVectors.indices) {
            if (mTargetCaptured[i]) continue

            val target = mTargetVectors[i]
            val dot = dotProduct(currentVector, target)
            if (dot > maxDotProduct) {
                maxDotProduct = dot
                closestIdx = i
            }
        }

        mCurrentTargetIndex = closestIdx

        // Dot product of 1.0 means perfect alignment.   
        // Threshold check: acos(dot) < threshold
        if (maxDotProduct > -1.0f && acos(maxDotProduct.toDouble().coerceIn(-1.0, 1.0)) < CAPTURE_THRESHOLD_RAD) {
            return true   
        }
        return false
    }

    fun markCurrentTargetCaptured() {
        if (mCurrentTargetIndex != -1) {
            mTargetCaptured[mCurrentTargetIndex] = true
        }
    }
      
    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        // Dot product implementation: a[0]*b[0] + a[1]*b[1] + a[2]*b[2]
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    }

    fun getTargetVectors(): List<FloatArray> = mTargetVectors
    fun getCapturedFlags(): BooleanArray = mTargetCaptured
    fun getTargetCount(): Int = mTargetVectors.size
    fun getCapturedCount(): Int = mTargetCaptured.count { it }
}
