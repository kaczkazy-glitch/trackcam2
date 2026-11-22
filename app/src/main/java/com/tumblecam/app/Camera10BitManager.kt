package com.tumblecam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.hypot
import kotlin.math.roundToInt

class Camera10BitManager(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onPreviewGeometry(width: Int, height: Int, rotationDegrees: Int)
        fun onSessionReady(lens: LensInfo)
        fun onError(message: String)
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("Camera10Bit").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { command -> handler.post(command) }
    private val main = Handler(Looper.getMainLooper())

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var activeArray: Rect? = null
    @Volatile private var controls = ManualControls()
    @Volatile var currentLens: LensInfo? = null
        private set

    val isRecording: Boolean get() = recorder != null

    // ---------- Discovery ----------

    fun discoverLenses(): List<LensInfo> {
        val result = mutableListOf<LensInfo>()
        for (id in cameraManager.cameraIdList) {
            val logical = cameraManager.getCameraCharacteristics(id)
            if (logical.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            result += describe(id, null, logical, logical)
            for (physId in logical.physicalCameraIds) {
                result += describe(id, physId, logical, cameraManager.getCameraCharacteristics(physId))
            }
        }
        return result.sortedBy { it.equiv35mm }
    }

    private fun supportsHlg10(c: CameraCharacteristics): Boolean {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT)) return false
        val profiles = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES) ?: return false
        return profiles.supportedProfiles.contains(DynamicRangeProfiles.HLG10)
    }

    private fun <T : Any> pick(optics: CameraCharacteristics, logical: CameraCharacteristics,
                               key: CameraCharacteristics.Key<T>): T? = optics.get(key) ?: logical.get(key)

    private fun describe(logicalId: String, physicalId: String?, logical: CameraCharacteristics,
                         optics: CameraCharacteristics): LensInfo {
        val focal = pick(optics, logical, CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val sensor = pick(optics, logical, CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val equiv = if (sensor != null && focal > 0f) focal * 43.27f / hypot(sensor.width, sensor.height) else 0f
        val (size, fps, range) = pickVideoConfig(logical)
        val name = if (equiv > 0f) "${equiv.roundToInt()}mm" else "Cam $logicalId"
        return LensInfo(
            label = if (physicalId == null) "$name\u00B7L" else name,
            logicalId = logicalId,
            physicalId = physicalId,
            focalLengthMm = focal,
            equiv35mm = equiv,
            sensorOrientation = logical.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            supportsHlg10 = supportsHlg10(logical),
            videoSize = size,
            videoFps = fps,
            fpsRange = range,
            exposureTimeRangeNs = pick(optics, logical, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?: Range(1_000L, 100_000_000L),
            isoRange = pick(optics, logical, CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(50, 3200),
            minFocusDiopters = pick(optics, logical, CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            aeCompRange = pick(optics, logical, CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
            aeCompStepEv = pick(optics, logical, CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        )
    }

    private fun pickVideoConfig(c: CameraCharacteristics): Triple<Size, Int, Range<Int>> {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val sizes = map?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
        val candidates = listOf(
            Size(3840, 2160) to 60, Size(3840, 2160) to 30, Size(1920, 1080) to 60, Size(1920, 1080) to 30
        )
        for ((size, fps) in candidates) {
            if (map == null || sizes.none { it == size }) continue
            val minDuration = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
            if (minDuration > 1_000_000_000L / fps + 500_000L) continue
            val range = ranges.filter { it.upper == fps }.maxByOrNull { it.lower } ?: continue
            return Triple(size, fps, range)
        }
        return Triple(Size(1920, 1080), 30, Range(30, 30))
    }

    private fun choosePreviewSize(c: CameraCharacteristics): Size {
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1920, 1080)
        return sizes.filter { it.width * 9 == it.height * 16 && it.width <= 1920 }
            .maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
    }

    // ---------- Open / session ----------

    @SuppressLint("MissingPermission")
    fun open(lens: LensInfo, texture: SurfaceTexture) {
        close()
        if (!lens.supportsHlg10) {
            fail("${lens.label}: HLG10 (10-bit) not supported")
            return
        }
        try {
            val chars = cameraManager.getCameraCharacteristics(lens.logicalId)
            activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val size = choosePreviewSize(chars)
            texture.setDefaultBufferSize(size.width, size.height)
            previewSurface = Surface(texture)
            recorderSurface = MediaCodec.createPersistentInputSurface()
            currentLens = lens
            main.post { listener.onPreviewGeometry(size.width, size.height, lens.sensorOrientation) }
            cameraManager.openCamera(lens.logicalId, executor, stateCallback)
        } catch (e: Exception) {
            fail("Open failed: ${e.message}")
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            createSession(camera)
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            device = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            device = null
            fail("Camera error code $error")
        }
    }

    private fun createSession(dev: CameraDevice) {
        val lens = currentLens ?: return
        val outputs = listOfNotNull(previewSurface, recorderSurface).map { surface ->
            OutputConfiguration(surface).apply {
                setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
                lens.physicalId?.let { setPhysicalCameraId(it) }
            }
        }
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                applyRepeating()
                main.post { listener.onSessionReady(lens) }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                fail("HLG10 session failed for ${lens.videoSize.width}x${lens.videoSize.height}@${lens.videoFps}")
            }
        }
        try {
            dev.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)
            )
        } catch (e: Exception) {
            fail("Session error: ${e.message}")
        }
    }

    // ---------- Manual controls ----------

    fun updateControls(newControls: ManualControls) {
        controls = newControls
        applyRepeating()
    }

    private fun applyRepeating() {
        val s = session ?: return
        try {
            s.setRepeatingRequest(buildRequest(recorder != null), null, handler)
        } catch (e: Exception) {
            fail("Request error: ${e.message}")
        }
    }

    private fun buildRequest(recording: Boolean): CaptureRequest {
        val dev = checkNotNull(device) { "Camera closed" }
        val lens = checkNotNull(currentLens) { "No lens" }
        val template = if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val b = dev.createCaptureRequest(template)
        previewSurface?.let { b.addTarget(it) }
        if (recording) recorderSurface?.let { b.addTarget(it) }
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, lens.fpsRange)
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        val c = controls
        if (c.autoExposure) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                c.aeCompensationIndex.coerceIn(lens.aeCompRange.lower, lens.aeCompRange.upper))
        } else {
            val frameNs = 1_000_000_000L / lens.videoFps
            val minT = lens.exposureTimeRangeNs.lower
            val maxT = maxOf(minT, minOf(lens.exposureTimeRangeNs.upper, frameNs))
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, c.exposureTimeNs.coerceIn(minT, maxT))
            b.set(CaptureRequest.SENSOR_SENSITIVITY, c.iso.coerceIn(lens.isoRange.lower, lens.isoRange.upper))
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
        }
        if (c.autoFocus || lens.minFocusDiopters <= 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, c.focusDistanceDiopters.coerceIn(0f, lens.minFocusDiopters))
        }
        val fp = c.focusPoint
        val arr = activeArray
        if (fp != null && arr != null) {
            val (nx, ny) = fp
            val fx = if (lens.sensorOrientation == 270) 1f - ny else ny
            val fy = if (lens.sensorOrientation == 270) nx else 1f - nx
            val w = arr.width() / 8
            val x = (arr.left + fx * arr.width() - w / 2).toInt().coerceIn(arr.left, arr.right - w)
            val y = (arr.top + fy * arr.height() - w / 2).toInt().coerceIn(arr.top, arr.bottom - w)
            val region = arrayOf(MeteringRectangle(x, y, w, w, MeteringRectangle.METERING_WEIGHT_MAX))
            b.set(CaptureRequest.CONTROL_AF_REGIONS, region)
            b.set(CaptureRequest.CONTROL_AE_REGIONS, region)
        }
        return b.build()
    }

    // ---------- Recording (HEVC Main10, unbaked HLG10) ----------

    fun startRecording(): File? {
        val lens = currentLens ?: return null
        val surface = recorderSurface ?: return null
        if (session == null || recorder != null) return null
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            val file = File(dir, "TUMBLE_${System.currentTimeMillis()}_HLG10.mp4")
            val withAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val mr = MediaRecorder(context)
            if (withAudio) mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(file.absolutePath)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
            mr.setVideoEncodingProfileLevel(
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51
            )
            mr.setVideoSize(lens.videoSize.width, lens.videoSize.height)
            mr.setVideoFrameRate(lens.videoFps)
            mr.setVideoEncodingBitRate(if (lens.videoSize.width >= 3840) 80_000_000 else 30_000_000)
            if (withAudio) {
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioEncodingBitRate(256_000)
                mr.setAudioSamplingRate(48_000)
            }
            mr.setInputSurface(surface)
            mr.setOrientationHint(lens.sensorOrientation)
            mr.prepare()
            recorder = mr
            recordingFile = file
            applyRepeating()
            mr.start()
            file
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            fail("Record start failed: ${e.message}")
            null
        }
    }

    fun stopRecording(): File? {
        val mr = recorder ?: return null
        val file = recordingFile
        recorder = null
        recordingFile = null
        applyRepeating()
        var saved: File? = file
        try {
            mr.stop()
        } catch (e: RuntimeException) {
            file?.delete()
            saved = null
        }
        mr.reset()
        mr.release()
        return saved
    }

    // ---------- Teardown ----------

    fun close() {
        if (recorder != null) stopRecording()
        session?.close()
        session = null
        device?.close()
        device = null
        previewSurface?.release()
        previewSurface = null
        recorderSurface?.release()
        recorderSurface = null
    }

    fun shutdown() {
        close()
        thread.quitSafely()
    }

    private fun fail(message: String) {
        main.post { listener.onError(message) }
    }
}
