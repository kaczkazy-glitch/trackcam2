package com.tumblecam.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.UUID

/**
 * Safe BLE wrapper: every call is a no-op (with a log line) if the gimbal is off, disconnected,
 * or permissions are missing. The Smooth 5S wire protocol is not public, so [velocityEncoder] and
 * [writeCharUuid] are supplied once you have discovered them from the log (see sendHex).
 */
@SuppressLint("MissingPermission")
class Smooth5sBleController(private val context: Context) {

    enum class State { IDLE, NEEDS_PERMISSION, BLUETOOTH_OFF, SCANNING, CONNECTING, CONNECTED, ERROR }

    var state by mutableStateOf(State.IDLE)
        private set
    var deviceName by mutableStateOf<String?>(null)
        private set
    val log = mutableStateListOf<String>()

    var nameFilters: List<String> = listOf("SMOOTH", "ZHIYUN")
    var extraServiceUuids: List<UUID> = emptyList()
    var writeCharUuid: UUID? = null
    /** Maps (pan, tilt) in -1..1 to a raw frame; return null to skip sending. */
    var velocityEncoder: ((Float, Float) -> ByteArray?)? = null

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var lastCmdMs = 0L
    private var warnedNoEncoder = false
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()

    fun requiredPermissions() = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    fun hasPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun setState(s: State) { main.post { state = s } }
    private fun logLine(msg: String) = main.post { log.add(msg); while (log.size > 200) log.removeAt(0) }
    private fun fail(msg: String) { logLine(msg); setState(State.ERROR) }
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    // ---------- Scan / connect ----------

    fun startScan() {
        if (!hasPermissions()) { setState(State.NEEDS_PERMISSION); return }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { setState(State.BLUETOOTH_OFF); logLine("Bluetooth is off"); return }
        if (state == State.CONNECTED || state == State.CONNECTING || scanning) return
        try {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            adapter.bluetoothLeScanner?.startScan(null, settings, scanCb) ?: run { fail("No BLE scanner"); return }
            scanning = true
            setState(State.SCANNING)
            logLine("Scanning for Smooth/Zhiyun…")
            main.postDelayed({
                if (scanning) { stopScan(); if (state == State.SCANNING) setState(State.IDLE); logLine("Scan timed out") }
            }, 15_000)
        } catch (e: Exception) { fail("Scan failed: ${e.message}") }
    }

    fun stopScan() {
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (e: Exception) { }
        scanning = false
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            if (nameFilters.any { name.contains(it, ignoreCase = true) } || uuids.any { it in extraServiceUuids }) {
                logLine("Found $name ${result.device.address} rssi ${result.rssi}")
                stopScan()
                connect(result.device)
            }
        }
        override fun onScanFailed(errorCode: Int) { scanning = false; fail("Scan failed ($errorCode)") }
    }

    fun connect(device: BluetoothDevice) {
        if (!hasPermissions()) { setState(State.NEEDS_PERMISSION); return }
        try {
            setState(State.CONNECTING)
            main.post { deviceName = device.name ?: device.address }
            gatt = device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) { fail("Connect failed: ${e.message}") }
    }

    fun disconnect() {
        stopScan()
        val g = gatt
        if (g == null) { setState(State.IDLE); return }
        try { g.disconnect() } catch (e: Exception) { logLine("Disconnect error: ${e.message}") }
    }

    fun close() {
        stopScan()
        try { gatt?.close() } catch (e: Exception) { }
        gatt = null
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                logLine("Connected, discovering services")
                g.discoverServices()
            } else {
                logLine("Disconnected (status $status)")
                g.close()
                if (gatt === g) gatt = null
                setState(if (status == BluetoothGatt.GATT_SUCCESS) State.IDLE else State.ERROR)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Service discovery failed ($status)"); return }
            notifyQueue.clear()
            for (s in g.services) {
                logLine("SVC ${s.uuid}")
                for (c in s.characteristics) {
                    val p = c.properties
                    val flags = buildString {
                        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("R ")
                        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("W ")
                        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("WNR ")
                        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("N ")
                        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("I ")
                    }
                    logLine("  CHR ${c.uuid} $flags")
                    if (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        notifyQueue.add(c)
                    }
                }
            }
            setState(State.CONNECTED)
            nextNotify(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = nextNotify(g)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            logLine("RX ${c.uuid}: ${hex(value)}")
        }
    }

    private fun nextNotify(g: BluetoothGatt) {
        val c = notifyQueue.poll() ?: return
        try {
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(CCCD)
            if (d == null) { nextNotify(g); return }
            val v = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            g.writeDescriptor(d, v)
        } catch (e: Exception) { logLine("Notify error: ${e.message}") }
    }

    // ---------- Commands ----------

    fun setWriteCharacteristic(text: String): Boolean {
        val u = parseUuid(text) ?: run { logLine("Bad UUID: $text"); return false }
        writeCharUuid = u
        return true
    }

    fun setPanTiltSpeed(panVel: Float, tiltVel: Float): Boolean {
        val enc = velocityEncoder
        if (enc == null) {
            if (!warnedNoEncoder) { warnedNoEncoder = true; logLine("setPanTiltSpeed: no velocityEncoder configured") }
            return false
        }
        val now = SystemClock.uptimeMillis()
        val stop = panVel == 0f && tiltVel == 0f
        if (!stop && now - lastCmdMs < 50) return false
        lastCmdMs = now
        val frame = enc(panVel.coerceIn(-1f, 1f), tiltVel.coerceIn(-1f, 1f)) ?: return false
        return sendBytes(frame)
    }

    fun sendHex(text: String): Boolean {
        val clean = text.replace("0x", "", ignoreCase = true).replace(Regex("[^0-9A-Fa-f]"), "")
        if (clean.isEmpty() || clean.length % 2 != 0) { logLine("Hex must be whole bytes"); return false }
        return sendBytes(ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    }

    fun sendBytes(data: ByteArray): Boolean {
        val g = gatt
        if (g == null || state != State.CONNECTED) { logLine("TX skipped: gimbal not connected"); return false }
        val u = writeCharUuid ?: run { logLine("TX skipped: no write characteristic set"); return false }
        val c = g.services.firstNotNullOfOrNull { it.getCharacteristic(u) } ?: run { logLine("TX skipped: $u not found"); return false }
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return try {
            val rc = g.writeCharacteristic(c, data, type)
            logLine("TX ${hex(data)} -> $rc")
            rc == BluetoothStatusCodes.SUCCESS
        } catch (e: Exception) { logLine("TX error: ${e.message}"); false }
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun parseUuid(s: String): UUID? = try {
            val t = s.trim()
            if (t.length <= 8) UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(t.toLong(16))) else UUID.fromString(t)
        } catch (e: Exception) { null }
    }
}
