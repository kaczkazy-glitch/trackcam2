package com.tumblecam.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

val IosYellow = Color(0xFFFFD60A)
private val GlassFill = Color(0x66101014)
private val GlassEdge = Color(0x33FFFFFF)

/** Translucent "glass" surface: tinted fill plus hairline edge (no backdrop blur API in Compose). */
fun Modifier.glass(radius: Dp = 20.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this.clip(shape).background(GlassFill).border(1.dp, GlassEdge, shape)
}

@Composable
fun GlassPill(text: String, active: Boolean = false, accent: Color = IosYellow, onClick: (() -> Unit)? = null) {
    var m = Modifier.glass(50.dp)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Text(text, color = if (active) accent else Color.White, fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold, modifier = m.padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
fun FocusReticle(evFrac: Float, evEnabled: Boolean, onEvDrag: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(80.dp)) {
            val sw = 1.5.dp.toPx()
            val t = 8.dp.toPx()
            drawRect(IosYellow, style = Stroke(sw))
            val cx = size.width / 2
            val cy = size.height / 2
            drawLine(IosYellow, Offset(cx, 0f), Offset(cx, t), sw)
            drawLine(IosYellow, Offset(cx, size.height), Offset(cx, size.height - t), sw)
            drawLine(IosYellow, Offset(0f, cy), Offset(t, cy), sw)
            drawLine(IosYellow, Offset(size.width, cy), Offset(size.width - t, cy), sw)
        }
        Spacer(Modifier.width(8.dp))
        if (evEnabled) {
            Canvas(
                Modifier.width(36.dp).height(120.dp).pointerInput(Unit) {
                    detectVerticalDragGestures { change, dy -> change.consume(); onEvDrag(dy / size.height) }
                }
            ) {
                val x = size.width / 2
                val y = (1f - evFrac) * size.height
                drawLine(IosYellow.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
                drawCircle(IosYellow, 9.dp.toPx(), Offset(x, y))
                drawCircle(Color.Black, 4.dp.toPx(), Offset(x, y))
            }
        }
    }
}

@Composable
fun LensBar(lenses: List<LensInfo>, active: LensInfo?, onSelect: (LensInfo) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        lenses.forEach { l ->
            val sel = l == active
            Box(
                Modifier.size(if (sel) 52.dp else 44.dp).clip(CircleShape)
                    .background(if (sel) Color(0x55FFD60A) else Color(0x55000000))
                    .border(1.dp, if (sel) IosYellow else GlassEdge, CircleShape)
                    .clickable { onSelect(l) },
                contentAlignment = Alignment.Center
            ) {
                Text(l.label, color = if (sel) IosYellow else Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun ModeBar(manual: Boolean, onMode: (Boolean) -> Unit, onLut: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("AUTO" to false, "MANUAL" to true).forEach { (name, isManual) ->
            Text(name, color = if (manual == isManual) IosYellow else Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onMode(isManual) })
        }
        GlassPill("LOAD .CUBE", onClick = onLut)
    }
}

@Composable
fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(74.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(if (recording) 28.dp else 58.dp)
                .clip(RoundedCornerShape(if (recording) 6.dp else 29.dp)).background(Color(0xFFFF3B30))
        )
    }
}

@Composable
fun ManualPanel(lens: LensInfo, c: ManualControls, onChange: (ManualControls) -> Unit) {
    val colors = SliderDefaults.colors(thumbColor = IosYellow, activeTrackColor = IosYellow)
    val denom = (1_000_000_000.0 / c.exposureTimeNs).toFloat().coerceIn(24f, 4000f)
    Column(Modifier.fillMaxWidth()) {
        Text("ISO ${c.iso}", color = Color.White, fontSize = 11.sp)
        Slider(c.iso.toFloat().coerceIn(lens.isoRange.lower.toFloat(), lens.isoRange.upper.toFloat()),
            { onChange(c.copy(iso = it.toInt())) },
            valueRange = lens.isoRange.lower.toFloat()..lens.isoRange.upper.toFloat(), colors = colors)
        Text("SHUTTER 1/${denom.toInt()}", color = Color.White, fontSize = 11.sp)
        Slider(denom, { onChange(c.copy(exposureTimeNs = (1_000_000_000.0 / it).toLong())) },
            valueRange = 24f..4000f, colors = colors)
        Text("FOCUS ${"%.2f".format(c.focusDistanceDiopters)} D (0 = infinity)", color = Color.White, fontSize = 11.sp)
        Slider(c.focusDistanceDiopters, { onChange(c.copy(focusDistanceDiopters = it)) },
            valueRange = 0f..max(lens.minFocusDiopters, 0.1f), colors = colors)
    }
}

@Composable
fun BleSheet(ble: Smooth5sBleController, actions: CameraActions, onClose: () -> Unit) {
    var uuidText by remember { mutableStateOf("") }
    var hexText by remember { mutableStateOf("") }
    val field = TextStyle(fontSize = 12.sp, color = Color.White)
    Column(Modifier.fillMaxWidth(0.94f).glass(22.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Zhiyun Smooth 5S: ${ble.state}  ${ble.deviceName ?: ""}", color = Color.White, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill("SCAN", onClick = actions::onBleScan)
            GlassPill("DISCONNECT", onClick = actions::onBleDisconnect)
            GlassPill("CLOSE", onClick = onClose)
        }
        OutlinedTextField(uuidText, { uuidText = it }, singleLine = true, textStyle = field,
            label = { Text("Write characteristic UUID (e.g. FFE1)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(hexText, { hexText = it }, singleLine = true, textStyle = field,
            label = { Text("Hex bytes (e.g. A5 01 00)") }, modifier = Modifier.fillMaxWidth())
        GlassPill("SEND", active = true) {
            if (uuidText.isNotBlank()) ble.setWriteCharacteristic(uuidText)
            ble.sendHex(hexText)
        }
        ble.log.takeLast(10).forEach { Text(it, color = Color(0xFFB0BEC5), fontSize = 9.sp) }
    }
}
