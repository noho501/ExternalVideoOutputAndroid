package com.noho501.externalvideooutput.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.noho501.externalvideooutput.ExternalVideoOutput
import com.noho501.externalvideooutput.ExternalVideoOutputListener
import com.noho501.externalvideooutput.example.databinding.ActivityMainBinding
import java.util.concurrent.Executors

/**
 * Example activity demonstrating ExternalVideoOutput with Camera2.
 *
 * Architecture:
 * ```
 * Camera2
 *    ↓
 * CameraCaptureSession
 *    ├── cameraPreview (SurfaceView on phone screen)
 *    └── ExternalVideoOutput.surface → USB-C HDMI external display
 * ```
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ExampleActivity"
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
    }

    private lateinit var binding: ActivityMainBinding

    // Camera2
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraId: String? = null

    // FPS counter
    private var frameCount = 0L
    private var lastFpsTimestamp = System.currentTimeMillis()
    private val fpsHandler = Handler(android.os.Looper.getMainLooper())
    private val fpsRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val elapsed = (now - lastFpsTimestamp) / 1000f
            if (elapsed > 0) {
                val fps = frameCount / elapsed
                binding.tvFps.text = "%.0f FPS".format(fps)
                frameCount = 0
                lastFpsTimestamp = now
            }
            fpsHandler.postDelayed(this, 1000)
        }
    }

    // Camera permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupExternalVideoOutput()

        binding.cameraPreview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                checkAndRequestCameraPermission()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = stopCamera()
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        fpsHandler.removeCallbacks(fpsRunnable)
        stopCamera()
        ExternalVideoOutput.shared.stop()
    }

    // ------------------------------------------------------------------
    // ExternalVideoOutput
    // ------------------------------------------------------------------

    private fun setupExternalVideoOutput() {
        ExternalVideoOutput.shared.listener = object : ExternalVideoOutputListener {
            override fun onExternalDisplayConnected(surface: Surface) {
                Log.d(TAG, "External display connected, surface=$surface")
                binding.tvStatus.text = "External display: connected"
                // Re-open the camera session with the new external surface as an extra target.
                reopenCaptureSession()
            }

            override fun onExternalDisplayDisconnected() {
                Log.d(TAG, "External display disconnected")
                binding.tvStatus.text = "External display: disconnected"
                // Remove the external surface from the camera session.
                reopenCaptureSession()
            }
        }
        ExternalVideoOutput.shared.start(this)
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    private fun checkAndRequestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(CAMERA_PERMISSION)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        val thread = HandlerThread("CameraThread").also { it.start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)

        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        cameraId = selectBackCamera(cameraManager) ?: run {
            Toast.makeText(this, "No back camera found", Toast.LENGTH_SHORT).show()
            return
        }

        cameraManager.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession(camera)
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "CameraDevice error: $error")
                camera.close()
                cameraDevice = null
            }
        }, cameraHandler)

        fpsHandler.post(fpsRunnable)
    }

    private fun stopCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    private fun reopenCaptureSession() {
        val camera = cameraDevice ?: return
        captureSession?.close()
        captureSession = null
        createCaptureSession(camera)
    }

    private fun createCaptureSession(camera: CameraDevice) {
        val previewSurface = binding.cameraPreview.holder.surface
        val externalSurface = ExternalVideoOutput.shared.surface

        val targets = buildList {
            add(previewSurface)
            externalSurface?.let { add(it) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val outputConfigs = targets.map { OutputConfiguration(it) }
            val executor = Executors.newSingleThreadExecutor()
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startRepeatingRequest(session, targets)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session configuration failed")
                    }
                }
            )
            camera.createCaptureSession(config)
        } else {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startRepeatingRequest(session, targets)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session configuration failed")
                    }
                },
                cameraHandler
            )
        }
    }

    private fun startRepeatingRequest(session: CameraCaptureSession, targets: List<Surface>) {
        val requestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        targets.forEach { requestBuilder.addTarget(it) }
        requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            android.util.Range(30, 60))
        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: android.hardware.camera2.TotalCaptureResult
            ) {
                frameCount++
            }
        }
        session.setRepeatingRequest(requestBuilder.build(), captureCallback, cameraHandler)
    }

    private fun selectBackCamera(manager: CameraManager): String? {
        return manager.cameraIdList.firstOrNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }
}
