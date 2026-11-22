package com.tumblecam.app

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

const val CONTENT_ASPECT = 9f / 16f

class CameraUiState {
    var status by mutableStateOf("Starting…")
    var lenses by mutableStateOf(emptyList<LensInfo>())
    var activeLens by mutableStateOf<LensInfo?>(null)
    var recording by mutableStateOf(false)
    var graded by mutableStateOf(true)
    var skeletonOn by mutableStateOf(true)
    var lutName by mutableStateOf("Filmic Rec.709 (built-in)")
    var track by mutableStateOf<TrackState?>(null)
    var subjectLocked by mutableStateOf(false)
    var controls by mutableStateOf(ManualControls())
}

interface CameraActions {
    fun onRecordToggle()
    fun onSelectLens(lens: LensInfo)
    fun onToggleGrading()
    fun onPickLut()
    fun onToggleSkeleton()
    fun onControls(c: ManualControls)
    fun onLockSubject(nx: Float?, ny: Float?)
    fun onBleScan()
    fun onBleDisconnect()
}

private fun normalize(o: Offset, size: IntSize): Pair<Float, Float> {
    val r = fitRect(size.width.toFloat(), size.height.toFloat(), CONTENT_ASPECT)
    return ((o.x - r.left) / r.width).coerceIn(0f, 1f) to ((o.y - r.top) / r.height).coerceIn(0f, 1f)
}

private fun zeroFrac(lens: LensInfo?): Float {
    val lo = lens?.aeCompRange?.lower ?: 0
    val hi = lens?.aeCompRange?.upper ?: 0
    return if (hi == lo) 0.5f else (0 - lo).toFloat() / (hi - lo)
}

@Composable
fun IosCameraScreen(ui: CameraUiState, ble: Smooth5sBleController, glView: GLSurfaceView, actions: CameraActions) {
    var focusPx by remember { mutableStateOf<Offset?>(null) }
    var focusTick by remember { mutableIntStateOf(0) }
    var evFrac by remember { mutableFloatStateOf(0.5f) }
    var manual by remember { mutableStateOf(false) }
    var showBle by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    val lens = ui.activeLens
    val density = LocalDensity.current

    LaunchedEffect(ui.controls) { actions.onControls(ui.controls) }
    LaunchedEffect(ui.recording) { elapsed = 0; while (ui.recording) { delay(1000); elapsed++ } }
    LaunchedEffect(focusTick) { if (focusTick > 0) { delay(3500); focusPx = null } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
        if (ui.skeletonOn) NeonSkeletonOverlay(ui.track, Modifier.fillMaxSize(), CONTENT_ASPECT)

        // tap = focus/exposure, long-press = lock / unlock subject
        Box(
            Modifier.fillMaxSize().pointerInput(lens) {
                detectTapGestures(
                    onTap = { o ->
                        val (nx, ny) = normalize(o, size)
                        focusPx = o; focusTick++
                        evFrac = zeroFrac(lens)
                        ui.controls = ui.controls.copy(focusPoint = nx to ny, aeCompensationIndex = 0)
                    },
                    onLongPress = { o ->
                        if (ui.subjectLocked) actions.onLockSubject(null, null)
                        else { val (nx, ny) = normalize(o, size); actions.onLockSubject(nx, ny) }
                    }
                )
            }
        )

        focusPx?.let { p ->
            val half = with(density) { 40.dp.toPx() }
            Box(Modifier.offset { IntOffset((p.x - half).roundToInt(), (p.y - half).roundToInt()) }) {
                FocusReticle(evFrac, evEnabled = lens != null && lens.aeCompRange.upper > lens.aeCompRange.lower) { d ->
                    evFrac = (evFrac - d).coerceIn(0f, 1f)
                    lens?.let {
                        val lo = it.aeCompRange.lower
                        val hi = it.aeCompRange.upper
                        ui.controls = ui.controls.copy(aeCompensationIndex = (lo + (hi - lo) * evFrac).roundToInt())
                    }
                    focusTick++
                }
            }
        }

        // top status bar
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val hlg = lens?.supportsHlg10 == true
                GlassPill(if (hlg) "10-BIT HLG10" else "8-BIT", active = hlg)
                GlassPill(lens?.let { "${if (it.videoSize.width >= 3840) "4K" else "1080"} ${it.videoFps}" } ?: "--")
                GlassPill(if (ui.graded) "LUT ON" else "LOG RAW", ui.graded, onClick = actions::onToggleGrading)
                GlassPill("SKELETON", ui.skeletonOn, NeonCyan, actions::onToggleSkeleton)
                GlassPill("GIMBAL ${ble.state}", ble.state == Smooth5sBleController.State.CONNECTED, NeonCyan) { showBle = true }
            }
            if (ui.recording) {
                GlassPill("● REC %02d:%02d".format(elapsed / 60, elapsed % 60), true, Color(0xFFFF3B30))
            }
            val t = ui.track
            if (ui.skeletonOn && t != null) {
                val label = (if (t.subjectLocked) "LOCKED · " else "") +
                    if (t.coasting) "PREDICTING ${t.coastFrames}/${FlipKalmanTracker.MAX_COAST}" else "TRACKING"
                GlassPill(label, true, if (t.coasting) NeonAmber else NeonCyan)
            }
            Text(ui.status, color = Color(0xCCFFFFFF), fontSize = 10.sp)
        }

        // bottom controls
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(8.dp)
                .glass(26.dp).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (manual && lens != null) ManualPanel(lens, ui.controls) { ui.controls = it }
            LensBar(ui.lenses.filter { it.supportsHlg10 }, lens, actions::onSelectLens)
            ModeBar(manual, { manual = it; ui.controls = ui.controls.copy(autoExposure = !it, autoFocus = !it) }, actions::onPickLut)
            Text(ui.lutName, color = Color(0xFFB0BEC5), fontSize = 10.sp)
            RecordButton(ui.recording, actions::onRecordToggle)
        }

        if (showBle) {
            Box(Modifier.align(Alignment.Center)) { BleSheet(ble, actions) { showBle = false } }
        }
    }
}
