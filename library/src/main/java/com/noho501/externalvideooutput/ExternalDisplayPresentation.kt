package com.noho501.externalvideooutput

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * A [Presentation] that fills the entire external display with a [SurfaceView].
 *
 * When the [Surface] backing the [SurfaceView] becomes available (or is destroyed), the
 * [onSurfaceReady] callback is invoked so [ExternalVideoOutput] can expose the surface to callers.
 *
 * Layout: the [SurfaceView] matches the display dimensions and is the only view.  Nothing from the
 * host application UI is shown on the external display.
 */
internal class ExternalDisplayPresentation(
    context: Context,
    display: Display,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val onSurfaceReady: (Surface?, Int, Int) -> Unit
) : Presentation(context, display) {

    init {
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(context)
        root.setBackgroundColor(Color.BLACK)

        val surfaceView = SurfaceView(context)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        lp.gravity = Gravity.CENTER
        surfaceView.layoutParams = lp

        root.addView(surfaceView)
        setContentView(root)

        root.post {
            val screenW = root.width.toFloat()
            val screenH = root.height.toFloat()

            if (screenW > 0 && screenH > 0 && videoWidth > 0 && videoHeight > 0) {
                val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
                val screenAspect = screenW / screenH

                val targetW: Int
                val targetH: Int

                if (videoAspect > screenAspect) {
                    targetW = screenW.toInt()
                    targetH = (screenW / videoAspect).toInt()
                } else {
                    targetH = screenH.toInt()
                    targetW = (screenH * videoAspect).toInt()
                }

                val slp = surfaceView.layoutParams as FrameLayout.LayoutParams
                slp.width = targetW
                slp.height = targetH
                surfaceView.layoutParams = slp
            }
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                onSurfaceReady(holder.surface, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurfaceReady(null, 0, 0)
            }
        })
    }
}