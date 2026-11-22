package com.tumblecam.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity(), Camera10BitManager.Listener, CameraActions {

    private lateinit var manager: Camera10BitManager
    private lateinit var renderer: LutShaderRenderer
    private lateinit var glView: GLSurfaceView
    private lateinit var tracker: FlipKalmanTracker
    private lateinit var ble: Smooth5sBleController
    private val ui = CameraUiState()
    private val poseSink: (Bitmap) -> Unit = { bmp -> tracker.process(bmp) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { openActiveLens() }

    private val blePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (ble.hasPermissions()) ble.startScan() else ui.status = "Bluetooth permission denied"
        }

    private val lutPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            try {
                val lut = contentResolver.openInputStream(uri)!!.use { CubeLutParser.parse(it) }
                renderer.setLut(lut)
                runOnUiThread { ui.lutName = lut.title; ui.status = "Loaded LUT ${lut.title} (${lut.size}³)" }
            } catch (e: Exception) {
                runOnUiThread { ui.status = "LUT error: ${e.message}" }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        manager = Camera10BitManager(applicationContext, this)
        ble = Smooth5sBleController(applicationContext)
        tracker = FlipKalmanTracker(
            onState = { s ->
                if (ui.skeletonOn) { ui.track = s; ui.subjectLocked = s?.subjectLocked == true }
            },
            requestFrame = { renderer.requestPoseFrame() }
        )
        renderer = LutShaderRenderer(
            requestRender = { glView.requestRender() },
            onSurfaceTextureReady = { runOnUiThread { openActiveLens() } }
        )
        renderer.poseSink = poseSink
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        ui.lenses = manager.discoverLenses()
        ui.activeLens = ui.lenses.firstOrNull { it.supportsHlg10 && it.physicalId == null }
            ?: ui.lenses.firstOrNull { it.supportsHlg10 }
        ui.status = ui.activeLens?.summary ?: "No rear camera reports HLG10 10-bit capability"

        val actions: CameraActions = this
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { IosCameraScreen(ui, ble, glView, actions) }
        }
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        openActiveLens()
        if (ui.skeletonOn) renderer.requestPoseFrame()
    }

    override fun onPause() {
        manager.close()
        ui.recording = false
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        manager.shutdown()
        tracker.close()
        ble.close()
        super.onDestroy()
    }

    private fun openActiveLens() {
        val lens = ui.activeLens ?: return
        val texture = renderer.surfaceTexture ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        manager.open(lens, texture)
    }

    // ---- Camera10BitManager.Listener ----
    override fun onPreviewGeometry(width: Int, height: Int, rotationDegrees: Int) =
        renderer.setPreviewGeometry(width, height, rotationDegrees)

    override fun onSessionReady(lens: LensInfo) { ui.status = lens.summary }

    override fun onError(message: String) { ui.status = message; ui.recording = false }

    // ---- CameraActions ----
    override fun onRecordToggle() {
        if (ui.recording) {
            val f = manager.stopRecording()
            ui.recording = false
            ui.status = f?.let { "Saved ${it.name}" } ?: "Recording failed"
        } else {
            val f = manager.startRecording()
            ui.recording = f != null
            if (f == null) ui.status = "Cannot start recording"
        }
    }

    override fun onSelectLens(lens: LensInfo) { ui.activeLens = lens; openActiveLens() }

    override fun onToggleGrading() { ui.graded = !ui.graded; renderer.setGrading(ui.graded) }

    override fun onPickLut() { lutPicker.launch(arrayOf("*/*")) }

    override fun onToggleSkeleton() {
        ui.skeletonOn = !ui.skeletonOn
        if (ui.skeletonOn) {
            renderer.poseSink = poseSink
            renderer.requestPoseFrame()
        } else {
            renderer.poseSink = null
            tracker.reset()
            ui.track = null
            ui.subjectLocked = false
        }
    }

    override fun onControls(c: ManualControls) = manager.updateControls(c)

    override fun onLockSubject(nx: Float?, ny: Float?) {
        if (nx == null || ny == null) {
            tracker.unlock()
            ui.subjectLocked = false
        } else if (ui.skeletonOn) {
            tracker.lockTo(PointF(nx, ny))
            ui.subjectLocked = true
        } else {
            ui.status = "Turn on SKELETON to lock a subject"
        }
    }

    override fun onBleScan() {
        if (ble.hasPermissions()) ble.startScan() else blePermissionLauncher.launch(ble.requiredPermissions())
    }

    override fun onBleDisconnect() = ble.disconnect()
}
