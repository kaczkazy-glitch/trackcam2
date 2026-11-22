package com.tumblecam.app

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLES30

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Offscreen FBO that renders an upright, tone-mapped copy of the preview for ML Kit. */
class PoseReadback(val width: Int, val height: Int) {
    private var fbo = 0
    private val buf: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

    fun invalidate() { fbo = 0 }

    private fun init() {
        val t = IntArray(1)
        val f = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glGenFramebuffers(1, f, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, t[0], 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) throw RuntimeException("Pose FBO incomplete: $status")
        fbo = f[0]
    }

    fun capture(draw: () -> Unit): Bitmap {
        if (fbo == 0) init()
        val vp = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vp, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        draw()
        buf.rewind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(vp[0], vp[1], vp[2], vp[3])
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buf.rewind()
        bmp.copyPixelsFromBuffer(buf)
        return bmp
    }
}
