package com.hereliesaz.sphereslam.overlay

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.hereliesaz.sphereslam.CoverageGlowProjection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the "map more here" coverage glow: a soft, translucent, monochrome bloom over each still-
 * unscanned direction, on a transparent surface meant to sit above the camera preview.
 *
 * It renders [CoverageGlowProjection.GlowMark]s as additive point sprites with a radial falloff, so
 * overlapping gaps pool into a brighter haze and a fully-mapped scene draws nothing. Marks the
 * projection flagged off-screen are clamped to the frustum edge and drawn fainter, reading as a
 * directional nudge toward unmapped space rather than an in-view blob.
 *
 * Feed it with [setMarks] from any thread; it publishes one immutable snapshot per update and the GL
 * thread consumes the latest. Pair it with [CoverageGlowView] for a configured translucent surface,
 * or host it on your own [GLSurfaceView] (request an alpha channel and `setZOrderMediaOverlay(true)`).
 *
 * @property glowColor RGB of the glow, premultiplied at draw time by per-mark intensity and the
 *   radial falloff. Defaults to white; set a monochrome tint to taste.
 * @property pointSizePx on-screen diameter of each glow sprite, in pixels.
 * @property baseAlpha peak alpha at a sprite's center before intensity/falloff, in `[0, 1]`.
 */
class CoverageGlowRenderer(
    var glowColor: FloatArray = floatArrayOf(1f, 1f, 1f),
    var pointSizePx: Float = 220f,
    var baseAlpha: Float = 0.35f,
) : GLSurfaceView.Renderer {

    private val latest = AtomicReference(FloatArray(0))
    private var program = 0
    private var aPosition = 0
    private var aIntensity = 0
    private var uPointSize = 0
    private var uColor = 0
    private var uBaseAlpha = 0
    private var vertexBuffer: FloatBuffer = allocate(0)

    /**
     * Publish the current unscanned-direction marks to draw. Safe from any thread.
     *
     * @param marks projected marks from [CoverageGlowProjection.project]; pass an empty list (the
     *   default when coverage is complete) to clear the glow.
     */
    fun setMarks(marks: List<CoverageGlowProjection.GlowMark>) {
        latest.set(CoverageGlowGeometry.buildVertexData(marks))
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f) // transparent — the camera preview shows through.
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aIntensity = GLES20.glGetAttribLocation(program, "aIntensity")
        uPointSize = GLES20.glGetUniformLocation(program, "uPointSize")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uBaseAlpha = GLES20.glGetUniformLocation(program, "uBaseAlpha")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val data = latest.get()
        if (data.isEmpty() || program == 0) return

        // Additive, over a transparent surface: gaps accumulate into a brighter haze.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)

        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uPointSize, pointSizePx)
        GLES20.glUniform3f(uColor, glowColor[0], glowColor[1], glowColor[2])
        GLES20.glUniform1f(uBaseAlpha, baseAlpha)

        val buffer = ensureCapacity(data.size).apply {
            clear()
            put(data)
            position(0)
        }
        val stride = CoverageGlowGeometry.FLOATS_PER_VERTEX * BYTES_PER_FLOAT
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, stride, buffer)
        buffer.position(2)
        GLES20.glEnableVertexAttribArray(aIntensity)
        GLES20.glVertexAttribPointer(aIntensity, 1, GLES20.GL_FLOAT, false, stride, buffer)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, data.size / CoverageGlowGeometry.FLOATS_PER_VERTEX)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aIntensity)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun ensureCapacity(floats: Int): FloatBuffer {
        if (vertexBuffer.capacity() < floats) vertexBuffer = allocate(floats)
        return vertexBuffer
    }

    private fun allocate(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(maxOf(floats, 1) * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private fun buildProgram(vertex: String, fragment: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        return GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
        }
    }

    private fun compile(type: Int, src: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }

    private companion object {
        const val BYTES_PER_FLOAT = 4

        // Point-sprite glow. The fragment shader uses gl_PointCoord for a smooth radial falloff so
        // each gap reads as a soft bloom rather than a hard dot.
        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute float aIntensity;
            uniform float uPointSize;
            varying float vIntensity;
            void main() {
                vIntensity = aIntensity;
                gl_Position = vec4(aPosition, 0.0, 1.0);
                gl_PointSize = uPointSize;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uBaseAlpha;
            varying float vIntensity;
            void main() {
                // Distance from the sprite center in [0, ~0.707]; fade to 0 at the edge.
                float d = distance(gl_PointCoord, vec2(0.5));
                float falloff = clamp(1.0 - d * 2.0, 0.0, 1.0);
                float a = uBaseAlpha * vIntensity * falloff * falloff;
                gl_FragColor = vec4(uColor * a, a);
            }
        """
    }
}
