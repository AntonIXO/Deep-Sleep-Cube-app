package dev.antonix.deep.ui

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.antonix.deep.ble.DeepBleClient
import dev.antonix.deep.ble.Uuids
import dev.antonix.deep.model.ConnectionPhase
import dev.antonix.deep.model.CubeInfo
import dev.antonix.deep.model.CubeStatus
import dev.antonix.deep.model.PowerLevel
import dev.antonix.deep.model.ProgramKind
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val cubes: List<CubeInfo> = emptyList(),
    val connected: CubeInfo? = null,
    val status: CubeStatus? = null,
    val selected: ProgramKind = ProgramKind.JustSleep,
    val durationHours: Int = 9,
    val power: PowerLevel = PowerLevel.Max,
    val led: Boolean = true,
    val vibro: Boolean = true,
    val log: List<String> = emptyList(),
    val showLog: Boolean = true,
    val lastReaction: String = "",
    val busy: Boolean = false,
    val message: String = "",
)

class DeepViewModel(app: Application) : AndroidViewModel(app), DeepBleClient.Listener {
    private val prefs: SharedPreferences =
        app.getSharedPreferences("deep", 0)
    private val ble = DeepBleClient(app).also { it.listener = this }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var poll: Job? = null
    private var connecting = false

    fun onPermissionsGranted() {
        val last = prefs.getString("last_addr", DEFAULT_ADDR) ?: DEFAULT_ADDR
        _state.update { it.copy(phase = ConnectionPhase.Scanning, message = "") }
        ble.startScan()
        viewModelScope.launch {
            delay(400)
            if (_state.value.connected == null && !connecting) {
                log("Прямое подключение $last")
                connectAddress(last)
            }
        }
    }

    fun onPermissionsDenied() {
        _state.update { it.copy(phase = ConnectionPhase.NoPermission) }
    }

    fun connect(info: CubeInfo) {
        connectAddress(info.address)
    }

    private fun connectAddress(address: String) {
        if (connecting) return
        connecting = true
        _state.update { it.copy(phase = ConnectionPhase.Connecting, message = "Соединяюсь…") }
        ble.connect(address)
        viewModelScope.launch {
            val ok = ble.awaitReady(15_000)
            connecting = false
            if (!ok && _state.value.connected == null) {
                _state.update {
                    it.copy(
                        phase = ConnectionPhase.Scanning,
                        message = "Нет связи. Дотронься до куба.",
                    )
                }
                ble.startScan()
            }
        }
    }

    fun disconnect() {
        poll?.cancel()
        connecting = false
        ble.disconnect()
        _state.update {
            it.copy(
                phase = ConnectionPhase.Scanning,
                connected = null,
                status = null,
                message = "",
            )
        }
        ble.startScan()
    }

    fun select(program: ProgramKind) {
        _state.update { it.copy(selected = program) }
    }

    fun setDurationHours(h: Int) {
        _state.update { it.copy(durationHours = h.coerceIn(4, 12)) }
    }

    fun togglePlay() {
        val s = _state.value
        if (s.busy || s.phase != ConnectionPhase.Connected || s.status == null) return
        val running = s.status.running == true
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = if (running) "Останавливаю…" else "Запускаю…") }
            val ok = if (running) {
                ble.stopProgram(prefs.getString("stop_frame", null))
            } else {
                val sec = s.durationHours * 3600
                ble.startProgram(s.selected, sec, s.power, prefs.getString("start_frame", null))
            }
            ble.lastStartFrame?.let { prefs.edit().putString("start_frame", it).apply() }
            ble.lastStopFrame?.let { prefs.edit().putString("stop_frame", it).apply() }
            ble.readStatus()
            _state.update {
                it.copy(
                    busy = false,
                    message = if (ok) "" else "Команда не принята. Открой лог.",
                    showLog = it.showLog || !ok,
                )
            }
        }
    }

    fun setPower(level: PowerLevel) {
        _state.update { it.copy(power = level) }
        viewModelScope.launch { ble.setPower(level) }
    }

    fun setLed(on: Boolean) {
        _state.update { it.copy(led = on) }
        viewModelScope.launch { ble.setLed(on) }
    }

    fun setVibro(on: Boolean) {
        _state.update { it.copy(vibro = on) }
        viewModelScope.launch { ble.setVibro(on) }
    }

    fun toggleLog() {
        _state.update { it.copy(showLog = !it.showLog) }
    }

    fun release() {
        poll?.cancel()
        ble.close()
    }

    override fun onScanResult(info: CubeInfo, rssi: Int) {
        _state.update { st ->
            val exists = st.cubes.any { it.address == info.address }
            val cubes = if (exists) {
                st.cubes.map { if (it.address == info.address) info else it }
            } else {
                st.cubes + info
            }
            st.copy(cubes = cubes)
        }
        val last = prefs.getString("last_addr", DEFAULT_ADDR)
        val st = _state.value
        if (st.phase == ConnectionPhase.Scanning && st.connected == null && !connecting) {
            if (info.address.equals(last, ignoreCase = true)) {
                connect(info)
            }
        }
    }

    override fun onConnected(info: CubeInfo) {
        prefs.edit().putString("last_addr", info.address).apply()
        connecting = false
        _state.update {
            it.copy(
                phase = ConnectionPhase.Connected,
                connected = info,
                message = "",
            )
        }
        viewModelScope.launch {
            val fw = ble.readAscii(Uuids.firmwareRev)
            val hw = ble.readAscii(Uuids.hardwareRev)
            val model = ble.readAscii(Uuids.modelNumber)
            val man = ble.readAscii(Uuids.manufacturer)
            val serial = ble.readAscii(Uuids.serialNumber)
            _state.update { st ->
                val base = st.connected ?: info
                st.copy(
                    connected = base.copy(
                        firmware = fw.ifBlank { base.firmware },
                        hardware = hw.ifBlank { base.hardware },
                        serial = serial.ifBlank { base.serial },
                        manufacturer = man.ifBlank { base.manufacturer },
                        name = model.ifBlank { base.name },
                        model = dev.antonix.deep.model.CubeModel.fromName(
                            model.ifBlank { base.name },
                        ),
                    ),
                )
            }
            ble.readStatus()
            startPoll()
        }
    }

    override fun onDisconnected(reason: String) {
        poll?.cancel()
        connecting = false
        log("Отключено: $reason")
        _state.update {
            it.copy(
                phase = ConnectionPhase.Scanning,
                connected = null,
                message = "Связь потеряна",
            )
        }
        ble.startScan()
    }

    override fun onStatus(status: CubeStatus) {
        val kind = ProgramKind.entries.firstOrNull { it.number == status.programNumber }
        _state.update {
            it.copy(
                status = status,
                selected = kind ?: it.selected,
            )
        }
    }

    override fun onLog(line: String) {
        log(line)
    }

    override fun onReaction(from: UUID, data: ByteArray) {
        val hex = data.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        val short = from.toString().substring(0, 8)
        _state.update { it.copy(lastReaction = "$short $hex") }
        log("notify $short $hex")
        Log.i("DeepBLE", "notify $short $hex")
    }

    private fun startPoll() {
        poll?.cancel()
        poll = viewModelScope.launch {
            while (true) {
                delay(4_000)
                if (!_state.value.busy) ble.readStatus()
            }
        }
    }

    private fun log(line: String) {
        _state.update {
            val next = (it.log + line).takeLast(100)
            it.copy(log = next)
        }
    }

    companion object {
        const val DEFAULT_ADDR = "34:5F:45:36:E3:8E"
    }
}
