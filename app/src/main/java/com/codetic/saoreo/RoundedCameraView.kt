package com.codetic.saoreo

import android.content.Context
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/** A texture is composited with the surrounding view, including the preview's rounded outline. */
internal class RoundedCameraView(context: Context, eglContext: EglBase.Context) :
    TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val renderer = EglRenderer("Together rounded preview")
    private var fill = false
    private var released = false
    @Volatile private var sourceAspect = 0f

    init {
        renderer.init(eglContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        surfaceTextureListener = this
        clipToOutline = true
        val radius = 20 * resources.displayMetrics.density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    fun configure(fill: Boolean, mirror: Boolean) {
        renderer.setMirror(mirror)
        if (this.fill != fill) {
            this.fill = fill
            updateLayout()
        }
    }

    override fun onFrame(frame: VideoFrame) {
        val aspect = frame.rotatedWidth.toFloat() / frame.rotatedHeight.coerceAtLeast(1)
        if (aspect != sourceAspect) {
            sourceAspect = aspect
            post(::updateLayout)
        }
        renderer.onFrame(frame)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (!released) {
            updateLayout()
            renderer.createEglSurface(texture)
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = updateLayout()
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        if (released) return true
        renderer.releaseEglSurface { texture.release() }
        return false
    }

    private fun updateLayout() {
        if (released || width == 0 || height == 0) return
        val viewportAspect = width.toFloat() / height
        val frameAspect = sourceAspect.takeIf { it > 0f } ?: viewportAspect
        renderer.setLayoutAspectRatio(if (fill) viewportAspect else frameAspect)
        val scaleX = if (fill) 1f else minOf(1f, frameAspect / viewportAspect)
        val scaleY = if (fill) 1f else minOf(1f, viewportAspect / frameAspect)
        setTransform(Matrix().apply { setScale(scaleX, scaleY, width / 2f, height / 2f) })
    }

    fun release() {
        released = true
        renderer.release()
    }
}
