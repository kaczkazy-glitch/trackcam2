package com.tumblecam.app

import android.util.Range
import android.util.Size

data class LensInfo(
    val label: String,
    val logicalId: String,
    val physicalId: String?,
    val focalLengthMm: Float,
    val equiv35mm: Float,
    val sensorOrientation: Int,
    val supportsHlg10: Boolean,
    val videoSize: Size,
    val videoFps: Int,
    val fpsRange: Range<Int>,
    val exposureTimeRangeNs: Range<Long>,
    val isoRange: Range<Int>,
    val minFocusDiopters: Float,
    val aeCompRange: Range<Int>,
    val aeCompStepEv: Float
) {
    val summary: String
        get() = "$label  ${videoSize.width}x${videoSize.height}@$videoFps  " +
            if (supportsHlg10) "HLG10" else "SDR only"
}

data class ManualControls(
    val autoExposure: Boolean = true,
    val aeCompensationIndex: Int = 0,
    val iso: Int = 100,
    val exposureTimeNs: Long = 8_333_333L,
    val autoFocus: Boolean = true,
    val focusDistanceDiopters: Float = 0f,
    val focusPoint: Pair<Float, Float>? = null
)
