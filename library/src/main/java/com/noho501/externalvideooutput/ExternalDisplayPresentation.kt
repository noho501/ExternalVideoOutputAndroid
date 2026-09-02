package com.noho501.externalvideooutput

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager

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
    private val onSurfaceReady: (Surface?) -> Unit
) : Presentation(context, display) {

    init {
        // Keep the presentation window on top and prevent the system from dimming it.
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val surfaceView = SurfaceView(context)
        setContentView(surfaceView)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                onSurfaceReady(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Surface dimensions have changed – the existing Surface handle remains valid;
                // callers using Camera2 / MediaProjection will adapt through their own resize logic.
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurfaceReady(null)
            }
        })
    }
}
