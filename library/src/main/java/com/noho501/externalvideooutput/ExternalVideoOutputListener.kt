package com.noho501.externalvideooutput

import android.view.Surface

/**
 * Callback interface for [ExternalVideoOutput] events.
 */
interface ExternalVideoOutputListener {

    /**
     * Called on the main thread when an external display is connected and its [Surface] is ready.
     *
     * @param surface The [Surface] backed by the external display.  Attach this surface as a
     *   capture target of Camera2, MediaProjection, or RootEncoder.
     */
    fun onExternalDisplayConnected(surface: Surface, width: Int, height: Int)

    /**
     * Called on the main thread when the external display is disconnected or the [Surface] is
     * destroyed.  Stop writing frames to the surface immediately after this call.
     */
    fun onExternalDisplayDisconnected()
}
