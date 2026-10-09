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
 * Draws the transparent "map more here" coverage glow.
 *
 * The fragment shader emits premultiplied color and the sprites blend additively
 * (`GL_ONE, GL_ONE`), so overlapping glows brighten rather than occlude. [pointSizePx] is clamped
 * at draw time to the driver's `GL_ALIASED_POINT_SIZE_RANGE`. If the shaders fail to compile or
 * link, the failure is logged and the renderer draws nothing (it never throws on the GL thread).
 */
class CoverageGlowRenderer(
    glowColor: FloatArray = floatArrayOf(1f, 1f, 1f),
    pointSizePx: Float = 220f,
    baseAlpha: Float = 0.35f,
) : GLSurfaceView.Renderer {

    var glowColor: FloatArray = glowColor.copyOf()
        get() = field.copyOf()
        set(value) {
            require(value.size == 3 && value.all { it.isFinite() }) {
                "glowColor must contain three finite RGB values"
            }
            field = value.copyOf()
        }

    var pointSizePx: Float = pointSizePx
        set(value) {
            require(value.isFinite() && value > 0f) { "pointSizePx must be finite and positive" }
            field = value
        }

    var baseAlpha: Float = baseAlpha
        set(value) {
            require(value.isFinite() && value in 0f..1f) { "baseAlpha must be in [0, 1]" }
            field = value
        }

    init {
        require(this.glowColor.size == 3 && this.glowColor.all { it.isFinite() })
        require(this.pointSizePx.isFinite() && this.pointSizePx > 0f)
        require(this.baseAlpha.isFinite() && this.baseAlpha in 0f..1f)
    }

    private val latest = AtomicReference(FloatArray(0))
    private var program = 0
    private var aPosition = 0
    private var aIntensity = 0
    private var uPointSize = 0
    private var uColor = 0
    private var uBaseAlpha = 0
    private var vertexBuffer: FloatBuffer = allocate(0)
    private var pointSizeRangeMin = 0f
    private var pointSizeRangeMax = 0f

    fun setMarks(marks: List<CoverageGlowProjection.GlowMark>) {
        latest.set(CoverageGlowGeometry.buildVertexData(marks))
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        // Re-creation on the same context: release the previous program. After an EGL context loss
        // the old name is already gone, which glIsProgram reports, so nothing unrelated is deleted.
        if (program != 0 && GLES20.glIsProgram(program)) GLES20.glDeleteProgram(program)
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        val range = FloatArray(2)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_POINT_SIZE_RANGE, range, 0)
        pointSizeRangeMin = range[0]
        pointSizeRangeMax = range[1]
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

        GLES20.glEnable(GLES20.GL_BLEND)
        // The shader output is already premultiplied (rgb * a), so the source factor is ONE;
        // destination ONE keeps the glow additive.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glUseProgram(program)
        GLES20.glUniform1f(
            uPointSize,
            CoverageGlowGeometry.clampPointSize(pointSizePx, pointSizeRangeMin, pointSizeRangeMax),
        )
        val color = glowColor
        GLES20.glUniform3f(uColor, color[0], color[1], color[2])
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

    private companion object {
        const val BYTES_PER_FLOAT = 4
        const val TAG = "CoverageGlowRenderer"
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
                float d = distance(gl_PointCoord, vec2(0.5));
                float falloff = clamp(1.0 - d * 2.0, 0.0, 1.0);
                float a = uBaseAlpha * vIntensity * falloff * falloff;
                gl_FragColor = vec4(uColor * a, a);
            }
        """
    }
}
