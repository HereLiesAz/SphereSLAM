package com.hereliesaz.sphereslam.overlay

import android.opengl.EGL14
import android.opengl.EGLContext
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
 *
 * **Fill modes.** [FillMode.TRIANGLES] (default) fills the supplied triangles — the haze over
 * `PhotosphereMap.regionsNeedingUpdate()`. [FillMode.COMPLEMENT] fills the whole viewport *except*
 * the supplied triangles — a wash with transparent holes over `PhotosphereMap.currentRegions()`.
 *
 * **Embedding.** Besides acting as its own `GLSurfaceView.Renderer`, it can draw into a host
 * renderer's framebuffer: call [createGlResources] from the host's `onSurfaceCreated`, then
 * [drawEmbedded] from its `onDrawFrame` (for example before drawing the artwork, so the wash sits
 * above the camera preview and beneath the design). [drawEmbedded] never clears the host's buffers,
 * blends premultiplied (`ONE, ONE_MINUS_SRC_ALPHA`) into the currently bound framebuffer and
 * viewport, and restores the GL state it touches. [FillMode.COMPLEMENT] renders the hole mask into a
 * private offscreen texture sized to the viewport, so it needs no stencil or depth buffer. Call
 * [releaseGlResources] on the GL thread when done.
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

    /** How [setTriangles] geometry is filled. Safe to change from any thread. */
    enum class FillMode {
        /** Fill the supplied triangles (the haze over tiles needing an update). */
        TRIANGLES,

        /** Fill the whole viewport except the supplied triangles (a wash with holes). */
        COMPLEMENT,
    }

    /** The current [FillMode]; defaults to [FillMode.TRIANGLES]. */
    @Volatile
    var fillMode: FillMode = FillMode.TRIANGLES

    private val latest = AtomicReference(FloatArray(0))
    private var program = 0
    private var aPosition = 0
    private var uColor = 0
    private var uAlpha = 0
    private var maskProgram = 0
    private var maskPosition = 0
    private var washProgram = 0
    private var washPosition = 0
    private var washColor = 0
    private var washAlpha = 0
    private var washMask = 0
    private var maskFbo = 0
    private var maskTexture = 0
    private var maskWidth = 0
    private var maskHeight = 0
    private var vertexBuffer: FloatBuffer = allocate(0)
    private var glContext: EGLContext? = null
    private val quadBuffer: FloatBuffer = allocate(FULL_SCREEN_QUAD.size).apply { put(FULL_SCREEN_QUAD); position(0) }

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
        createGlResources()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawEmbedded()
    }

    /**
     * Compile the shader programs on the **current** GL context. Call from the GL thread — the
     * host's `onSurfaceCreated` when embedding (this renderer's own [onSurfaceCreated] calls it).
     * Re-creation on the same EGL context releases the previous objects; on a new context (after a
     * context loss) the stale names are forgotten, never deleted, so a host object that reuses a name
     * is never touched.
     */
    fun createGlResources() {
        // Same context: free the previous objects. A new context (after EGL context loss) never owned
        // them, and its names may already belong to the host, so just forget them.
        if (glContext == EGL14.eglGetCurrentContext()) releaseGlResources() else forgetGlResources()
        glContext = EGL14.eglGetCurrentContext()
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
        maskProgram = buildProgram(VERTEX_SHADER, MASK_FRAGMENT_SHADER)
        maskPosition = GLES20.glGetAttribLocation(maskProgram, "aPosition")
        washProgram = buildProgram(WASH_VERTEX_SHADER, WASH_FRAGMENT_SHADER)
        washPosition = GLES20.glGetAttribLocation(washProgram, "aPosition")
        washColor = GLES20.glGetUniformLocation(washProgram, "uColor")
        washAlpha = GLES20.glGetUniformLocation(washProgram, "uAlpha")
        washMask = GLES20.glGetUniformLocation(washProgram, "uMask")
    }

    /** Delete every GL object this renderer owns. Call on the GL thread with the context current. */
    fun releaseGlResources() {
        if (glContext != EGL14.eglGetCurrentContext()) {
            forgetGlResources()
            return
        }
        for (p in intArrayOf(program, maskProgram, washProgram)) {
            if (p != 0 && GLES20.glIsProgram(p)) GLES20.glDeleteProgram(p)
        }
        program = 0; maskProgram = 0; washProgram = 0
        if (maskFbo != 0 && GLES20.glIsFramebuffer(maskFbo)) GLES20.glDeleteFramebuffers(1, intArrayOf(maskFbo), 0)
        if (maskTexture != 0 && GLES20.glIsTexture(maskTexture)) GLES20.glDeleteTextures(1, intArrayOf(maskTexture), 0)
        forgetGlResources()
    }

    private fun forgetGlResources() {
        program = 0; maskProgram = 0; washProgram = 0
        maskFbo = 0; maskTexture = 0; maskWidth = 0; maskHeight = 0
    }

    /**
     * Draw the current geometry in the current [fillMode] into the **currently bound** framebuffer
     * and viewport, without clearing it, blending premultiplied color `ONE, ONE_MINUS_SRC_ALPHA`.
     * Restores the GL state it changes (program, framebuffer, viewport, blend, depth/stencil/scissor/
     * cull enables, array buffer, active texture and its 2D binding, clear color). Call on the GL
     * thread after [createGlResources]; a no-op when resources are missing.
     */
    fun drawEmbedded() {
        val data = latest.get()
        when (fillMode) {
            FillMode.TRIANGLES -> {
                if (data.isEmpty() || program == 0) return
                val saved = GlState.capture()
                try {
                    prepareBlend()
                    drawTriangles(program, aPosition, data) {
                        val color = hazeColor
                        GLES20.glUniform3f(uColor, color[0], color[1], color[2])
                        GLES20.glUniform1f(uAlpha, hazeAlpha)
                    }
                } finally {
                    saved.restore()
                }
            }
            FillMode.COMPLEMENT -> {
                if (maskProgram == 0 || washProgram == 0) return
                val saved = GlState.capture()
                try {
                    if (!renderMask(data, saved.viewport[2], saved.viewport[3], saved.framebuffer)) return
                    GLES20.glViewport(saved.viewport[0], saved.viewport[1], saved.viewport[2], saved.viewport[3])
                    prepareBlend()
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture)
                    GLES20.glUseProgram(washProgram)
                    val color = hazeColor
                    GLES20.glUniform3f(washColor, color[0], color[1], color[2])
                    GLES20.glUniform1f(washAlpha, hazeAlpha)
                    GLES20.glUniform1i(washMask, 0)
                    quadBuffer.position(0)
                    GLES20.glEnableVertexAttribArray(washPosition)
                    GLES20.glVertexAttribPointer(washPosition, 2, GLES20.GL_FLOAT, false, 2 * BYTES_PER_FLOAT, quadBuffer)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GLES20.glDisableVertexAttribArray(washPosition)
                } finally {
                    saved.restore()
                }
            }
        }
    }

    private fun prepareBlend() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0) // client-side vertex arrays
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glColorMask(true, true, true, true)
        GLES20.glEnable(GLES20.GL_BLEND)
        // Premultiplied source: over a cleared (0,0,0,0) target each pixel holds (rgb * a, a), and
        // over existing premultiplied content it composites correctly.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private inline fun drawTriangles(prog: Int, attr: Int, data: FloatArray, uniforms: () -> Unit) {
        GLES20.glUseProgram(prog)
        uniforms()
        val buffer = ensureCapacity(data.size).apply {
            clear()
            put(data)
            position(0)
        }
        val perVertex = CoverageGlowProjection.FLOATS_PER_REGION_VERTEX
        GLES20.glEnableVertexAttribArray(attr)
        GLES20.glVertexAttribPointer(attr, perVertex, GLES20.GL_FLOAT, false, perVertex * BYTES_PER_FLOAT, buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, data.size / perVertex)
        GLES20.glDisableVertexAttribArray(attr)
    }

    /** Render the hole triangles as 1.0 into the private mask texture; false when it cannot. */
    private fun renderMask(holes: FloatArray, width: Int, height: Int, hostFramebuffer: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (!ensureMask(width, height)) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, hostFramebuffer)
            return false
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFbo)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glColorMask(true, true, true, true)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT) // the private mask only; never the host buffer
        if (holes.isNotEmpty()) drawTriangles(maskProgram, maskPosition, holes) {}
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, hostFramebuffer)
        return true
    }

    private fun ensureMask(width: Int, height: Int): Boolean {
        if (maskFbo != 0 && maskWidth == width && maskHeight == height) return true
        if (maskTexture == 0) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            maskTexture = t[0]
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        if (maskFbo == 0) {
            val f = IntArray(1)
            GLES20.glGenFramebuffers(1, f, 0)
            maskFbo = f[0]
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, maskTexture, 0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Coverage mask framebuffer incomplete: 0x${Integer.toHexString(status)}")
            maskWidth = 0; maskHeight = 0
            return false
        }
        maskWidth = width
        maskHeight = height
        return true
    }

    /** The GL state [drawEmbedded] may change, captured so it can be put back exactly. */
    private class GlState(
        val program: Int,
        val framebuffer: Int,
        val arrayBuffer: Int,
        val viewport: IntArray,
        val blend: Boolean,
        val blendFunc: IntArray,
        val depthTest: Boolean,
        val stencilTest: Boolean,
        val scissorTest: Boolean,
        val cullFace: Boolean,
        val activeTexture: Int,
        val texture0: Int,
        val clearColor: FloatArray,
        val colorMask: BooleanArray,
    ) {
        fun restore() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            GLES20.glUseProgram(program)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, arrayBuffer)
            setEnabled(GLES20.GL_BLEND, blend)
            GLES20.glBlendFuncSeparate(blendFunc[0], blendFunc[1], blendFunc[2], blendFunc[3])
            setEnabled(GLES20.GL_DEPTH_TEST, depthTest)
            setEnabled(GLES20.GL_STENCIL_TEST, stencilTest)
            setEnabled(GLES20.GL_SCISSOR_TEST, scissorTest)
            setEnabled(GLES20.GL_CULL_FACE, cullFace)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture0)
            GLES20.glActiveTexture(activeTexture)
            GLES20.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
            GLES20.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3])
        }

        companion object {
            private fun int(name: Int): Int = IntArray(1).also { GLES20.glGetIntegerv(name, it, 0) }[0]

            private fun setEnabled(cap: Int, on: Boolean) {
                if (on) GLES20.glEnable(cap) else GLES20.glDisable(cap)
            }

            fun capture(): GlState {
                val viewport = IntArray(4).also { GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, it, 0) }
                val activeTexture = int(GLES20.GL_ACTIVE_TEXTURE)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                val texture0 = int(GLES20.GL_TEXTURE_BINDING_2D)
                GLES20.glActiveTexture(activeTexture)
                val clear = FloatArray(4).also { GLES20.glGetFloatv(GLES20.GL_COLOR_CLEAR_VALUE, it, 0) }
                val mask = BooleanArray(4).also { GLES20.glGetBooleanv(GLES20.GL_COLOR_WRITEMASK, it, 0) }
                return GlState(
                    program = int(GLES20.GL_CURRENT_PROGRAM),
                    framebuffer = int(GLES20.GL_FRAMEBUFFER_BINDING),
                    arrayBuffer = int(GLES20.GL_ARRAY_BUFFER_BINDING),
                    viewport = viewport,
                    blend = GLES20.glIsEnabled(GLES20.GL_BLEND),
                    blendFunc = intArrayOf(
                        int(GLES20.GL_BLEND_SRC_RGB), int(GLES20.GL_BLEND_DST_RGB),
                        int(GLES20.GL_BLEND_SRC_ALPHA), int(GLES20.GL_BLEND_DST_ALPHA),
                    ),
                    depthTest = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST),
                    stencilTest = GLES20.glIsEnabled(GLES20.GL_STENCIL_TEST),
                    scissorTest = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST),
                    cullFace = GLES20.glIsEnabled(GLES20.GL_CULL_FACE),
                    activeTexture = activeTexture,
                    texture0 = texture0,
                    clearColor = clear,
                    colorMask = mask,
                )
            }
        }
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

        /** White, the conventional "wash with holes" color for [FillMode.COMPLEMENT]. */
        val WHITE: FloatArray get() = floatArrayOf(1f, 1f, 1f)

        private fun validColor(c: FloatArray) = c.size == 3 && c.all { it.isFinite() && it in 0f..1f }

        /** Two NDC triangles covering the viewport, as a triangle strip. */
        private val FULL_SCREEN_QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

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
        private const val MASK_FRAGMENT_SHADER = """
            precision mediump float;
            void main() {
                gl_FragColor = vec4(1.0);
            }
        """
        private const val WASH_VERTEX_SHADER = """
            attribute vec2 aPosition;
            varying vec2 vUv;
            void main() {
                vUv = aPosition * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """
        private const val WASH_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uAlpha;
            uniform sampler2D uMask;
            varying vec2 vUv;
            void main() {
                float a = uAlpha * (1.0 - texture2D(uMask, vUv).r);
                gl_FragColor = vec4(uColor * a, a);
            }
        """
    }
}
