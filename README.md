# ExternalVideoOutput for Android

An Android library that routes a [`Surface`](https://developer.android.com/reference/android/view/Surface)
to an external display connected via USB-C → HDMI — with zero CPU-based frame conversion.

```
Camera2 / MediaProjection / RootEncoder
              ↓
           Surface
              ↓
     ExternalVideoOutput
              ↓
       External Display
              ↓
        USB-C → HDMI
              ↓
         Capture Card
```

---

## Features

- Zero-copy pipeline: video flows directly through `Surface` — no `Bitmap`, no `Canvas`, no JPEG.
- Automatic connect/disconnect via `DisplayManager`.
- Thread-safe singleton API.
- Optimised for 30/60 FPS livestreaming (low latency, low memory).
- Works with Camera2, MediaProjection, and RootEncoder.
- Does **not** mirror the main screen.

---

## Installation

Add the `:library` module to your project (local source dependency) or publish it to your local
Maven repository and reference it from `build.gradle.kts`:

```kotlin
// settings.gradle.kts
include(":library")

// app/build.gradle.kts
dependencies {
    implementation(project(":library"))
}
```

---

## Usage

### 1. Start monitoring

```kotlin
// In Activity.onCreate()
ExternalVideoOutput.shared.start(context)
```

### 2. Observe connect/disconnect

```kotlin
ExternalVideoOutput.shared.listener = object : ExternalVideoOutputListener {
    override fun onExternalDisplayConnected(surface: Surface) {
        // Attach `surface` to your Camera2 / MediaProjection / RootEncoder output
    }
    override fun onExternalDisplayDisconnected() {
        // Stop writing frames to the surface
    }
}
```

### 3. Use the surface

```kotlin
val surface: Surface? = ExternalVideoOutput.shared.surface
// Pass this Surface as a capture target
```

### 4. Stop

```kotlin
// In Activity.onDestroy()
ExternalVideoOutput.shared.stop()
```

---

## Public API

```kotlin
object ExternalVideoOutput {
    val shared: ExternalVideoOutput

    var listener: ExternalVideoOutputListener?

    /** true when an external display is connected and its Surface is ready */
    val isConnected: Boolean

    /** The Surface backed by the external display's SurfaceView; null when disconnected */
    val surface: Surface?

    fun start(context: Context)
    fun stop()
}

interface ExternalVideoOutputListener {
    fun onExternalDisplayConnected(surface: Surface)
    fun onExternalDisplayDisconnected()
}
```

---

## Camera2 Integration

```kotlin
private fun createCaptureSession(camera: CameraDevice) {
    val previewSurface  = surfaceView.holder.surface
    val externalSurface = ExternalVideoOutput.shared.surface

    val targets = listOfNotNull(previewSurface, externalSurface)

    camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                targets.forEach { addTarget(it) }
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 60))
            }
            session.setRepeatingRequest(request.build(), null, handler)
        }
        override fun onConfigureFailed(session: CameraCaptureSession) = Unit
    }, handler)
}
```

## MediaProjection Integration

```kotlin
ExternalVideoOutput.shared.listener = object : ExternalVideoOutputListener {
    override fun onExternalDisplayConnected(surface: Surface) {
        val surface = ExternalVideoOutput.shared.surface ?: return
        val displayMetrics = resources.displayMetrics
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ExternalOutput",
            displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )
    }
    override fun onExternalDisplayDisconnected() {
        virtualDisplay?.release()
        virtualDisplay = null
    }
}
```

## RootEncoder Integration

```kotlin
ExternalVideoOutput.shared.listener = object : ExternalVideoOutputListener {
    override fun onExternalDisplayConnected(surface: Surface) {
        // RootEncoder exposes a method to set the output surface
        rootEncoder.setOutputSurface(ExternalVideoOutput.shared.surface)
        rootEncoder.startStream(endpoint)
    }
    override fun onExternalDisplayDisconnected() {
        rootEncoder.stopStream()
    }
}
```

---

## Architecture

```
ExternalVideoOutput (singleton)
    │
    ├── DisplayManager.DisplayListener  ← monitors display connect/disconnect
    │
    └── ExternalDisplayPresentation (android.app.Presentation)
            │
            └── SurfaceView
                    │
                    └── Surface  ← exposed to callers; Camera2/MediaProjection writes frames here
```

`Presentation` renders on the external display only — it is completely invisible on the phone
screen.  The main app UI is untouched.

---

## Limitations

- Requires an external display that the system exposes via `DisplayManager.DISPLAY_CATEGORY_PRESENTATION`.
- USB-C → HDMI adapters vary; some do not expose a Presentation display.  Use a capture card or
  a display confirmed to work with Android Presentation.
- Minimum SDK 21 (Android 5.0).
- Multi-display behaviour beyond the first detected external display is not implemented.

---

## Example App

The `example/` module shows a complete Camera2 integration:

- Requests camera permission at runtime.
- Shows normal camera preview on the phone.
- Detects external display automatically.
- Routes the same Camera2 session to both the phone preview and the external display.
- Shows connection status and FPS counter.

```
Camera2 CameraCaptureSession
    ├── SurfaceView (phone preview)
    └── ExternalVideoOutput.surface (USB-C → HDMI)
```