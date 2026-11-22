package com.tumblecam.app

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES11Ext

import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Viewfinder-only renderer. The recorder surface never touches this pipeline,
 * so recorded HLG10 video stays ungraded. Modes: 0 = raw HLG, 1 = .cube LUT, 2 = filmic Rec.709 fallback.
 * Also produces small upright tone-mapped frames for pose detection.
 */
class LutShaderRenderer(
    private val requestRender: () -> Unit,
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile var surfaceTexture: SurfaceTexture? = null
        private set
    @Volatile private var gradingEnabled = true
    @Volatile private var lut: CubeLut? = null
    @Volatile private var lutDirty = false
    @Volatile private var bufW = 1920
    @Volatile private var bufH = 1080
    @Volatile private var rotation = 90

    private var viewW = 1
    private var viewH = 1
    private var lutLoaded = false
    private var program = 0
    private var oesTex = 0
    private var lutTex = 0
    private val texMatrix = FloatArray(16)
    private val locations = HashMap<String, Int>()

    @Volatile var poseSink: ((Bitmap) -> Unit)? = null
    @Volatile private var wantPose = false
    private val readback = PoseReadback(288, 512)

    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().put(floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        ))

    fun requestPoseFrame() { wantPose = true; requestRender() }

    fun setGrading(enabled: Boolean) {
        gradingEnabled = enabled
        requestRender()
    }

    fun setLut(newLut: CubeLut?) {
        lut = newLut
        lutDirty = true
        requestRender()
    }

    fun setPreviewGeometry(width: Int, height: Int, rotationDegrees: Int) {
        bufW = width
        bufH = height
        rotation = rotationDegrees
        requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        locations.clear()
        for (name in listOf("uMvp", "uTexMatrix", "uTex", "uLut", "uMode", "uLutSize", "uDomMin", "uDomMax")) {
            locations[name] = GLES20.glGetUniformLocation(program, name)
        }
        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        oesTex = ids[0]
        lutTex = ids[1]
        readback.invalidate()
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        setTexParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        lutLoaded = false
        lutDirty = true
        val st = SurfaceTexture(oesTex)
        st.setOnFrameAvailableListener { requestRender() }
        surfaceTexture = st
        onSurfaceTextureReady(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
        } catch (e: RuntimeException) {
            return
        }
        st.getTransformMatrix(texMatrix)
        if (lutDirty) uploadLut()

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(loc("uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES20.glUniform1i(loc("uLut"), 1)

        val mode = if (!gradingEnabled) 0 else if (lutLoaded) 1 else 2
        GLES20.glUniform1i(loc("uMode"), mode)
        val current = lut
        if (mode == 1 && current != null) {
            GLES20.glUniform1f(loc("uLutSize"), current.size.toFloat())
            GLES20.glUniform3fv(loc("uDomMin"), 1, current.domainMin, 0)
            GLES20.glUniform3fv(loc("uDomMax"), 1, current.domainMax, 0)
        }
        GLES20.glUniformMatrix4fv(loc("uMvp"), 1, false, computeMvp(), 0)
        GLES20.glUniformMatrix4fv(loc("uTexMatrix"), 1, false, texMatrix, 0)

        drawQuad()
        val sink = poseSink
        if (wantPose && sink != null) {
            wantPose = false
            val bmp = readback.capture {
                GLES20.glUniformMatrix4fv(loc("uMvp"), 1, false, computeMvp(readback.width, readback.height, true), 0)
                GLES20.glUniform1i(loc("uMode"), 2)
                drawQuad()
            }
            sink(bmp)
        }
    }

    private fun drawQuad() {
        vertices.position(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(0)
        vertices.position(2)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
    }

    private fun loc(name: String): Int = locations[name] ?: -1

    private fun uploadLut() {
        lutDirty = false
        val l = lut
        if (l == null) {
            lutLoaded = false
            return
        }
        val buffer = ByteBuffer.allocateDirect(l.data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(l.data)
        buffer.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, l.size, l.size, l.size, 0,
            GLES20.GL_RGB, GLES20.GL_FLOAT, buffer
        )
        setTexParams(GLES30.GL_TEXTURE_3D)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE)
        lutLoaded = true
    }

    private fun setTexParams(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /** Rotates the landscape sensor buffer upright and letterboxes it into the target. */
    private fun computeMvp(vw: Int = viewW, vh: Int = viewH, flipY: Boolean = false): FloatArray {
        val aspect = bufW.toFloat() / bufH
        val rot = rotation
        val swap = rot % 180 != 0
        val hx = if (swap) 1f else aspect
        val hy = if (swap) aspect else 1f
        val viewAspect = vw.toFloat() / vh
        val fit = minOf(viewAspect / hx, 1f / hy)
        val proj = FloatArray(16)
        Matrix.orthoM(proj, 0, -viewAspect, viewAspect, if (flipY) 1f else -1f, if (flipY) -1f else 1f, -1f, 1f)
        val model = FloatArray(16)
        Matrix.setIdentityM(model, 0)
        Matrix.scaleM(model, 0, fit, fit, 1f)
        Matrix.rotateM(model, 0, -rot.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(model, 0, aspect, 1f, 1f)
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, proj, 0, model, 0)
        return mvp
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("Link failed: " + GLES20.glGetProgramInfoLog(prog))
        return prog
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("Shader failed: " + GLES20.glGetShaderInfoLog(shader))
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """#version 300 es
uniform mat4 uMvp;
uniform mat4 uTexMatrix;
layout(location = 0) in vec4 aPos;
layout(location = 1) in vec2 aTex;
out vec2 vTex;
void main() {
    gl_Position = uMvp * aPos;
    vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
}"""

        private const val FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp sampler3D;
precision highp samplerExternalOES;
uniform samplerExternalOES uTex;
uniform sampler3D uLut;
uniform int uMode;
uniform float uLutSize;
uniform vec3 uDomMin;
uniform vec3 uDomMax;
in vec2 vTex;
out vec4 outColor;

vec3 hlgToLinear(vec3 e) {
    const float a = 0.17883277;
    const float b = 0.28466892;
    const float c = 0.55991073;
    vec3 lo = e * e / 3.0;
    vec3 hi = (exp((e - c) / a) + b) / 12.0;
    return mix(lo, hi, step(vec3(0.5), e));
}

vec3 filmic(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec3 src = texture(uTex, vTex).rgb;
    if (uMode == 1) {
        vec3 c = clamp((src - uDomMin) / (uDomMax - uDomMin), 0.0, 1.0);
        c = c * ((uLutSize - 1.0) / uLutSize) + 0.5 / uLutSize;
        outColor = vec4(texture(uLut, c).rgb, 1.0);
    } else if (uMode == 2) {
        const mat3 bt2020To709 = mat3(
             1.6605, -0.1246, -0.0182,
            -0.5876,  1.1329, -0.1006,
            -0.0728, -0.0083,  1.1187);
        vec3 lin = max(bt2020To709 * hlgToLinear(src), vec3(0.0)) * 3.2;
        outColor = vec4(pow(filmic(lin), vec3(1.0 / 2.2)), 1.0);
    } else {
        outColor = vec4(src, 1.0);
    }
}"""
    }
}
