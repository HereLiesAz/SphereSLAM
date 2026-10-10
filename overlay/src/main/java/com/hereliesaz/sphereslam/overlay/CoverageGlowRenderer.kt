package com.hereliesaz.sphereslam.overlay

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.hereliesaz.sphereslam.CoverageGlowProjection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the coverage haze: a flat, uniform, low-opacity fill over every unscanned tile. Accepted
 * tiles get nothing, so the haze's border is the edge of what has been scanned. No falloff, no
 * glow — every hazed pixel is exactly [hazeColor] at [hazeAlpha] over the camera preview.
 *
 * Feed it triangles from [CoverageGlowProjection.projectRegions] via [setTriangles]. Those never
 * overlap, so each pixel is drawn at most once and the opacity stays constant across tile seams.
 * The shader emits premultiplied color and blends `ONE, ONE_MINUS_SRC_ALPHA`, leaving a correctly
 * premultiplied framebuffer for the translucent surface to composite. If the shaders fail to
 * compile or link, the failure is logged and the renderer draws nothing (it never throws on the GL
 * thread).
 */
class CoverageGlowRenderer(
    hazeColor: FloatArray = HOT_PINK,
    hazeAlpha: Float = DEFAULT_HAZE_ALPHA,
) : GLSurfaceView.Renderer {

    /** Haze RGB, each in `[0, 1]`. Defaults to hot pink (#FF69B4). */
    var hazeColor: FloatArray = hazeColor.copyOf()
        get() = field.copyOf()
        set(value) {
            require(validColor(value)) { "hazeColor must contain three RGB values in [0, 1]" }
            field = value.copyOf()
        }

    /** Haze opacity over the preview, `[0, 1]`; constant across every unscanned tile. */
    var hazeAlpha: Float = hazeAlpha
        set(value) {
            require(value.isFinite() && value in 0f..1f) { "hazeAlpha must be in [0, 1]" }
            field = value
        }

    init {
        require(validColor(this.hazeColor)) { "hazeColor must contain three RGB values in [0, 1]" }
        require(this.hazeAlpha.isFinite() && this.hazeAlpha in 0f..1f) { "hazeAlpha must be in [0, 1]" }
    }

    private val latest = AtomicReference(FloatArray(0))
    private var program = 0
    private var aPosition = 0
    private var uColor = 0
    private var uAlpha = 0
    private var vertexBuffer: FloatBuffer = allocate(0)

    /**
     * Replace the haze geometry: `GL_TRIANGLES` NDC vertex data,
     * [CoverageGlowProjection.FLOATS_PER_REGION_VERTEX] floats per vertex, as returned by
     * [CoverageGlowProjection.projectRegions]. An empty array clears the haze.
     */
    fun setTriangles(vertices: FloatArray) {
        require(vertices.size % (3 * CoverageGlowProjection.FLOATS_PER_REGION_VERTEX) == 0) {
            "vertices must hold whole triangles, size was ${vertices.size}"
        }
        latest.set(vertices.copyOf())
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        // Re-creation on the same context: release the previous program. After an EGL context loss
        // the old name is already gone, which glIsProgram reports, so nothing unrelated is deleted.
        if (program != 0 && GLES20.glIsProgram(program)) GLES20.glDeleteProgram(program)
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val data = latest.get()
        if (data.isEmpty() || program == 0) return

        GLES20.glEnable(GLES20.GL_BLEND)
        // Premultiplied source over a cleared (0,0,0,0) target: each pixel is drawn once, so the
        // framebuffer holds exactly (rgb * alpha, alpha) — what the translucent surface composites.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        val color = hazeColor
        GLES20.glUniform3f(uColor, color[0], color[1], color[2])
        GLES20.glUniform1f(uAlpha, hazeAlpha)

        val buffer = ensureCapacity(data.size).apply {
            clear()
            put(data)
            position(0)
        }
        val perVertex = CoverageGlowProjection.FLOATS_PER_REGION_VERTEX
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, perVertex, GLES20.GL_FLOAT, false, perVertex * BYTES_PER_FLOAT, buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, data.size / perVertex)
        GLES20.glDisableVertexAttribArray(aPosition)
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

    /** Returns the linked program, or 0 (logged) when compilation or linking failed. */
    private fun buildProgram(vertex: String, fragment: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        if (vs == 0) return 0
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        if (fs == 0) {
            GLES20.glDeleteShader(vs)
            return 0
        }
        val p = GLES20.glCreateProgram()
        if (p == 0) {
            Log.e(TAG, "glCreateProgram failed")
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return 0
        }
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        // Flagged for deletion; freed once detached from (or deleted with) the program.
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Glow program link failed: ${GLES20.glGetProgramInfoLog(p)}")
            GLES20.glDeleteProgram(p)
            return 0
        }
        return p
    }

    /** Returns the compiled shader, or 0 (logged and deleted) on failure. */
    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            Log.e(TAG, "glCreateShader($type) failed")
            return 0
        }
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Glow shader ($type) compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        /** Hot pink, #FF69B4. */
        val HOT_PINK: FloatArray get() = floatArrayOf(1f, 105f / 255f, 180f / 255f)

        /** Default haze opacity: low enough to see the wall through, high enough to read the edge. */
        const val DEFAULT_HAZE_ALPHA = 0.2f

        private fun validColor(c: FloatArray) = c.size == 3 && c.all { it.isFinite() && it in 0f..1f }

        private const val BYTES_PER_FLOAT = 4
        private const val TAG = "CoverageGlowRenderer"
        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uAlpha;
            void main() {
                gl_FragColor = vec4(uColor * uAlpha, uAlpha);
            }
        """
    }
}
