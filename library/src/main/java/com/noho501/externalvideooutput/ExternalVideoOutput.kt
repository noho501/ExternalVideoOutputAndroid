package com.noho501.externalvideooutput

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ExternalVideoOutput routes a [Surface] to an external display connected via USB-C → HDMI.
 *
 * Usage:
 * ```
 * ExternalVideoOutput.shared.start(context)
 * val surface = ExternalVideoOutput.shared.surface
 * // Attach `surface` to Camera2 / MediaProjection / RootEncoder
 * ExternalVideoOutput.shared.stop()
 * ```
 *
 * The library:
 * - Uses [DisplayManager] to detect external displays automatically.
 * - Creates a [ExternalDisplayPresentation] on the external display to host a [SurfaceView].
 * - Exposes the [Surface] from that [SurfaceView] so video frames flow directly without any
 *   CPU-based conversion or bitmap copies.
 * - Handles display connect/disconnect lifecycle.
 * - Does NOT modify or mirror the main app UI.
 */
class ExternalVideoOutput private constructor() {

    companion object {
        /** Singleton instance. */
        @JvmStatic
        val shared: ExternalVideoOutput by lazy { ExternalVideoOutput() }

        private const val TAG = "ExternalVideoOutput"
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    @Volatile private var context: Context? = null
    @Volatile private var displayManager: DisplayManager? = null
    @Volatile private var presentation: ExternalDisplayPresentation? = null

    /** Whether an external display is currently connected. */
    @Volatile var isConnected: Boolean = false
        private set

    /**
     * The [Surface] backed by the external display's [android.view.SurfaceView].
     *
     * This value is non-null only while [isConnected] is true and the presentation has been
     * successfully shown on the external display.  Connect this surface as a target output of
     * Camera2, MediaProjection, or RootEncoder.
     */
    @Volatile var surface: Surface? = null
        private set

    /** Optional listener for connection/surface changes. */
    var listener: ExternalVideoOutputListener? = null

    // ------------------------------------------------------------------
    // Display listener
    // ------------------------------------------------------------------

    @Volatile var width: Int = 0
        private set
    @Volatile var height: Int = 0
        private set

    @Volatile private var targetVideoWidth: Int = 1920
    @Volatile private var targetVideoHeight: Int = 1080

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            Log.d(TAG, "Display added: $displayId")
            mainHandler.post { handleDisplayAdded(displayId) }
        }

        override fun onDisplayRemoved(displayId: Int) {
            Log.d(TAG, "Display removed: $displayId")
            mainHandler.post { handleDisplayRemoved(displayId) }
        }

        override fun onDisplayChanged(displayId: Int) {
            // Not needed for basic connect/disconnect handling.
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Start monitoring for external displays and present on one if already connected.
     *
     * Must be called from the main thread (or any thread; the internal work is dispatched to the
     * main thread automatically).
     *
     * @param context An application or activity [Context].
     */
    fun start(context: Context, videoWidth: Int, videoHeight: Int) {
        this.targetVideoWidth = videoWidth
        this.targetVideoHeight = videoHeight

        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "start() called but already running")
            return
        }
        this.context = context.applicationContext
        mainHandler.post { initialize() }
    }

    /**
     * Stop monitoring and release all resources.  The [surface] becomes null after this call.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) {
            Log.w(TAG, "stop() called but not running")
            return
        }
        mainHandler.post { release() }
    }

    // ------------------------------------------------------------------
    // Internal – must be called on the main thread
    // ------------------------------------------------------------------

    private fun initialize() {
        val ctx = context ?: return
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager = dm
        dm.registerDisplayListener(displayListener, mainHandler)

        // Connect to any already-attached external display.
        val externalDisplays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        if (externalDisplays.isNotEmpty()) {
            showPresentation(externalDisplays[0])
        }
    }

    private fun release() {
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        dismissPresentation()
        context = null
    }

    private fun handleDisplayAdded(displayId: Int) {
        if (!running.get()) return
        val dm = displayManager ?: return
        val display = dm.getDisplay(displayId) ?: return
        if (display.flags and Display.FLAG_PRESENTATION != 0) {
            showPresentation(display)
        }
    }

    private fun handleDisplayRemoved(displayId: Int) {
        val current = presentation ?: return
        if (current.display.displayId == displayId) {
            dismissPresentation()
        }
    }

    private fun showPresentation(display: Display) {
        val ctx = context ?: return
        dismissPresentation()
        Log.d(TAG, "Showing presentation on display ${display.displayId}: ${display.name}")
        val p = ExternalDisplayPresentation(ctx, display, targetVideoWidth, targetVideoHeight) { newSurface, w, h ->
            onSurfaceAvailable(newSurface, w, h)
        }
        p.setOnDismissListener {
            if (presentation == p) {
                Log.d(TAG, "Presentation dismissed")
                surface = null
                width = 0
                height = 0
                isConnected = false
                presentation = null
                listener?.onExternalDisplayDisconnected()
            }
        }
        p.show()
        presentation = p
    }

    private fun dismissPresentation() {
        presentation?.dismiss()
        presentation = null
        surface = null
        width = 0
        height = 0
        isConnected = false
    }

    private fun onSurfaceAvailable(newSurface: Surface?, w: Int, h: Int) {
        surface = newSurface
        width = w
        height = h
        isConnected = newSurface != null
        if (newSurface != null) {
            Log.d(TAG, "External surface ready: $newSurface, size: $w x $h")
            listener?.onExternalDisplayConnected(newSurface, w, h)
        }
    }
}
