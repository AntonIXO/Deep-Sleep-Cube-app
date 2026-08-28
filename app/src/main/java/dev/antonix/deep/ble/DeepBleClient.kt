package dev.antonix.deep.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dev.antonix.deep.model.CubeInfo
import dev.antonix.deep.model.CubeModel
import dev.antonix.deep.model.CubeStatus
import dev.antonix.deep.model.PowerLevel
import dev.antonix.deep.model.ProgramKind
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("MissingPermission")
class DeepBleClient(context: Context) {

    interface Listener {
        fun onScanResult(info: CubeInfo, rssi: Int)
        fun onConnected(info: CubeInfo)
        fun onDisconnected(reason: String)
        fun onStatus(status: CubeStatus)
        fun onLog(line: String)
        fun onReaction(from: UUID, data: ByteArray)
    }

    private val app = context.applicationContext
    var listener: Listener? = null

    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val thread = HandlerThread("deep-gatt").also { it.start() }
    private val handler = Handler(thread.looper)
    private val mutex = Mutex()

    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var ready = CompletableDeferred<Boolean>()

    private var writeWait: CompletableDeferred<Boolean>? = null
    private var readWait: CompletableDeferred<ByteArray?>? = null
    private var descWait: CompletableDeferred<Boolean>? = null

    @Volatile var lastStartFrame: String? = null
    @Volatile var lastStopFrame: String? = null
    @Volatile private var lastDataReaction: ByteArray? = null

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            log("Bluetooth недоступен")
            return
        }
        if (scanning) return
        scanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
            log("Сканирование… коснись куба, если он молчит")
        } catch (t: Throwable) {
            scanning = false
            log("Скан: ${t.message}")
        }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Throwable) {
        }
    }

    fun connect(address: String) {
        val a = adapter ?: return
        stopScan()
        handler.post {
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (_: Throwable) {
            }
            gatt = null
            ready = CompletableDeferred()
            val device = try {
                a.getRemoteDevice(address)
            } catch (t: Throwable) {
                log("Адрес $address: ${t.message}")
                ready.complete(false)
                return@post
            }
            log("Подключение к $address")
            gatt = if (Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(app, false, callback)
            }
        }
    }

    fun disconnect() {
        handler.post {
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (_: Throwable) {
            }
            gatt = null
        }
    }

    fun close() {
        stopScan()
        disconnect()
        thread.quitSafely()
    }

    suspend fun awaitReady(timeoutMs: Long = 12_000): Boolean =
        withTimeoutOrNull(timeoutMs) { ready.await() } == true

    suspend fun readAscii(uuid: UUID): String {
        val raw = readChar(uuid) ?: return ""
        return raw.toString(Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
    }

    suspend fun readStatus(): CubeStatus? {
        val raw = readChar(Uuids.statusChar) ?: return null
        return CubeStatus.parse(raw).also { listener?.onStatus(it) }
    }

    suspend fun writeCommand(bytes: ByteArray): Boolean {
        val payload = Protocol.pad8(bytes)
        log("cmd ${payload.toHex()}")
        return writeChar(Uuids.commandChar, payload)
    }

    suspend fun writeData(bytes: ByteArray): Boolean {
        log("dat ${bytes.toHex()}")
        return writeChar(Uuids.dataChar, bytes)
    }

    suspend fun setLed(on: Boolean) = writeCommand(Protocol.setLed(on))
    suspend fun setDim(percent: Int) = writeCommand(Protocol.setDim(percent))
    suspend fun setVibro(on: Boolean) = writeCommand(Protocol.setVibro(on))
    suspend fun setPower(level: PowerLevel) = writeCommand(Protocol.setPower(level))

    suspend fun startProgram(
        kind: ProgramKind,
        durationSec: Int,
        power: PowerLevel = PowerLevel.Max,
        preferred: String? = null,
    ): Boolean {
        log("Старт «${kind.title}», ${durationSec / 60} мин")
        val before = readStatus() ?: return false
        if (before.running) {
            log("Уже идёт ${before.raw.toHex()}")
            return true
        }
        val frame = Protocol.startCommand(kind.number, durationSec, power.wire)
        if (tryCmd(frame, true, before)) {
            lastStartFrame = "cmd ${frame.toHex()}"
            return true
        }
        log("Не запустилось")
        return false
    }

    suspend fun stopProgram(preferred: String? = null): Boolean {
        log("Стоп")
        val before = readStatus() ?: return false
        if (!before.running) {
            log("Уже тихо ${before.raw.toHex()}")
            return true
        }
        val frame = Protocol.stopCommand()
        if (tryCmd(frame, false, before)) {
            lastStopFrame = "cmd ${frame.toHex()}"
            return true
        }
        log("Не остановилось")
        return false
    }

    private suspend fun tryCmd(frame: ByteArray, wantRunning: Boolean, before: CubeStatus): Boolean {
        writeCommand(frame)
        delayOnGatt(400)
        val after = readStatus() ?: return false
        val changed = after.running == wantRunning && before.running != wantRunning
        if (changed) {
            log("ок cmd ${frame.toHex()} → ${after.raw.toHex()}")
            return true
        }
        return false
    }

    private suspend fun tryDat(frame: ByteArray, wantRunning: Boolean, before: CubeStatus): Boolean {
        lastDataReaction = null
        writeData(frame)
        delayOnGatt(400)
        val nack = lastDataReaction?.getOrNull(1)?.toInt()?.and(0xFF) == 1
        val after = readStatus() ?: return false
        if (nack) return false
        val changed = after.running == wantRunning && before.running != wantRunning
        if (changed) {
            log("ок dat ${frame.toHex()} → ${after.raw.toHex()}")
            return true
        }
        return false
    }

    private suspend fun tryTagged(tag: String?, wantRunning: Boolean, before: CubeStatus): Boolean {
        if (tag.isNullOrBlank()) return false
        val bytes = parseHexFrame(tag) ?: return false
        return if (tag.startsWith("dat")) tryDat(bytes, wantRunning, before)
        else tryCmd(bytes, wantRunning, before)
    }

    private fun parseHexFrame(tag: String): ByteArray? {
        val hex = tag.removePrefix("cmd ").removePrefix("dat ").trim()
        return try {
            hex.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun dumpRegisters() {
        for (r in 0..8) {
            writeData(Protocol.getRegister(r))
            delayOnGatt(220)
        }
    }

    private suspend fun delayOnGatt(ms: Long) {
        suspendCancellableCoroutine { cont ->
            handler.postDelayed({ if (cont.isActive) cont.resume(Unit) }, ms)
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        listener?.onLog(line)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val raw = record?.bytes
            val parsedName = record?.deviceName ?: result.device.name
            val rawName = raw?.let { asciiName(it) }
            val name = parsedName ?: rawName
            // Do NOT match advertised 0x00FF: the laptop and other BLE
            // devices advertise it, and we previously connected to them.
            if (!CubeModel.isCubeName(name) && rawName == null) return
            val addr = result.device.address ?: return
            val resolved = name ?: rawName ?: addr
            val info = CubeInfo(
                address = addr,
                name = resolved,
                model = CubeModel.fromName(resolved),
            )
            listener?.onScanResult(info, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("Скан ошибка $errorCode")
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Соединение есть ($status)")
                handler.post {
                    try {
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    } catch (_: Throwable) {
                    }
                    g.requestMtu(185)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!ready.isCompleted) ready.complete(false)
                listener?.onDisconnected("разрыв $status")
                try {
                    g.close()
                } catch (_: Throwable) {
                }
                if (gatt === g) gatt = null
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU $mtu")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("GATT сервисы ($status)")
            handler.post { enableNotify(g, Uuids.statusChar) }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val uuid = descriptor.characteristic?.uuid
            descWait?.complete(status == BluetoothGatt.GATT_SUCCESS)
            descWait = null
            when (uuid) {
                Uuids.statusChar -> handler.post { enableNotify(g, Uuids.commandChar) }
                Uuids.commandChar -> handler.post { enableNotify(g, Uuids.dataChar) }
                Uuids.dataChar -> handler.post { finishConnect(g) }
                else -> handler.post { finishConnect(g) }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == Uuids.dataChar) lastDataReaction = value
            listener?.onReaction(characteristic.uuid, value)
            if (characteristic.uuid == Uuids.statusChar) {
                listener?.onStatus(CubeStatus.parse(value))
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val v = characteristic.value ?: return
            onCharacteristicChanged(g, characteristic, v)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeWait?.complete(status == BluetoothGatt.GATT_SUCCESS)
            writeWait = null
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            readWait?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
            readWait = null
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val v = characteristic.value
            readWait?.complete(if (status == BluetoothGatt.GATT_SUCCESS) v else null)
            readWait = null
        }
    }

    private fun enableNotify(g: BluetoothGatt, uuid: UUID) {
        val ch = findChar(g, uuid)
        if (ch == null) {
            log("Нет $uuid")
            finishConnect(g)
            return
        }
        g.setCharacteristicNotification(ch, true)
        val ccc = ch.getDescriptor(Uuids.ccc)
        if (ccc == null) {
            if (uuid == Uuids.dataChar) finishConnect(g)
            else {
                val next = when (uuid) {
                    Uuids.statusChar -> Uuids.commandChar
                    else -> Uuids.dataChar
                }
                enableNotify(g, next)
            }
            return
        }
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(ccc, value)
        } else {
            @Suppress("DEPRECATION")
            ccc.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(ccc)
        }
    }

    private fun finishConnect(g: BluetoothGatt) {
        val device = g.device
        val name = device.name ?: device.address
        handler.post {
            val info = CubeInfo(
                address = device.address,
                name = name,
                model = CubeModel.fromName(name),
            )
            listener?.onConnected(info)
            if (!ready.isCompleted) ready.complete(true)
            findChar(g, Uuids.statusChar)?.let { g.readCharacteristic(it) }
        }
    }

    private fun findChar(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (s in g.services.orEmpty()) {
            s.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    private suspend fun writeChar(uuid: UUID, bytes: ByteArray): Boolean = mutex.withLock {
        val result = CompletableDeferred<Boolean>()
        handler.post {
            val g = gatt
            val ch = g?.let { findChar(it, uuid) }
            if (g == null || ch == null) {
                result.complete(false)
                return@post
            }
            writeWait = result
            val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, bytes, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                ch.writeType = type
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
            if (!ok) {
                writeWait = null
                result.complete(false)
            }
        }
        withTimeoutOrNull(3_000) { result.await() } ?: run {
            writeWait = null
            false
        }
    }

    private suspend fun readChar(uuid: UUID): ByteArray? = mutex.withLock {
        val result = CompletableDeferred<ByteArray?>()
        handler.post {
            val g = gatt
            val ch = g?.let { findChar(it, uuid) }
            if (g == null || ch == null) {
                result.complete(null)
                return@post
            }
            readWait = result
            if (!g.readCharacteristic(ch)) {
                readWait = null
                result.complete(null)
            }
        }
        withTimeoutOrNull(3_000) { result.await() }.also { readWait = null }
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        private const val TAG = "DeepBLE"

        private val cubeNames = listOf(
            "deep.n.hotel", "deep.r.hotel", "deep.n", "deep.r",
        ).map { it.encodeToByteArray() }

        fun asciiName(raw: ByteArray): String? {
            for (needle in cubeNames) {
                val i = indexOf(raw, needle)
                if (i >= 0) return needle.decodeToString()
            }
            return null
        }

        private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || needle.size > hay.size) return -1
            outer@ for (i in 0..hay.size - needle.size) {
                for (j in needle.indices) {
                    if (hay[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }
    }
}
