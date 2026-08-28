package dev.antonix.deep.ble

import dev.antonix.deep.model.PowerLevel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format reverse-engineered from deep.n fw 1.8.9 and com.dnateam.deep_app.
 *
 * Command characteristic (8 bytes, write + notify 2-byte reaction):
 *   DeviceSettingsEnum as byte0: 0 led-enable, 1 led-dim, 2 vibro-enable, 3 power.
 *   Program control uses the same characteristic with a non-overlapping opcode
 *   in byte0; the working start/stop frames are discovered on device and cached.
 *
 * Data characteristic (≤80 bytes, write + notify):
 *   GET  `00 [reg]`  →  `00 00 [reg] [value]`
 *   SET  `01 [reg] …` → `01 00` ok / `01 01` error
 *
 * Status (12 bytes, read + notify):
 *   idle     `00 00 7f 00 00 00 00 00 00 00 00 00`
 *   running  `aa [program] 7f [power] [total_s LE u32] [elapsed_s LE u32]`
 */
object Protocol {
    const val COMMAND_LEN = 8
    const val STATUS_LEN = 12

    const val ID_LED = 0
    const val ID_DIM = 1
    const val ID_VIBRO = 2
    const val ID_POWER = 3

    /** ProgramCommandEnum-style opcodes; 0–3 are device settings. */
    const val OPC_START = 4
    const val OPC_STOP = 5
    const val OPC_PAUSE = 6
    const val OPC_RESUME = 7

    const val DATA_GET = 0
    const val DATA_SET = 1

    fun pad8(bytes: ByteArray): ByteArray =
        if (bytes.size == COMMAND_LEN) bytes else bytes.copyOf(COMMAND_LEN)

    fun setValue(id: Int, value: Int): ByteArray =
        pad8(byteArrayOf(id.toByte(), value.toByte()))

    fun setLed(on: Boolean): ByteArray = setValue(ID_LED, if (on) 1 else 0)
    fun setDim(percent: Int): ByteArray = setValue(ID_DIM, percent.coerceIn(0, 100))
    fun setVibro(on: Boolean): ByteArray = setValue(ID_VIBRO, if (on) 1 else 0)
    fun setPower(level: PowerLevel): ByteArray = setValue(ID_POWER, level.wire)

    fun u32le(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    /**
     * Captured from official app 1.4.0 writing the command characteristic:
     *   `1a 01 7f 01 32 40 01 00`
     *   [0x1A][program][0x7F curve][power][duration_s little-endian u32]
     * 0x1A is also the GET value of data registers 1–3 (protocol tag).
     */
    const val CMD_PROGRAM = 0x1A
    const val CMD_STOP = 0x3C
    const val FREQ_CURVE = 0x7F

    fun startCommand(program: Int, durationSec: Int, power: Int = 1): ByteArray {
        val buf = ByteBuffer.allocate(COMMAND_LEN).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(CMD_PROGRAM.toByte())
        buf.put(program.toByte())
        buf.put(FREQ_CURVE.toByte())
        buf.put(power.coerceIn(1, 3).toByte())
        buf.putInt(durationSec.coerceAtLeast(0))
        return buf.array()
    }

    fun stopCommand(program: Int = 1): ByteArray =
        byteArrayOf(CMD_STOP.toByte(), 0, 0, 0, 0, 0, 0, 0)

    fun getRegister(reg: Int): ByteArray = byteArrayOf(DATA_GET.toByte(), reg.toByte())

    fun startCommandCandidates(program: Int, durationSec: Int, power: Int): List<ByteArray> {
        val dur = u32le(durationSec)
        val hours = (durationSec / 3600).coerceAtLeast(1)
        val p = program.toByte()
        return listOf(
            startCommand(program, durationSec, power),
            pad8(byteArrayOf(OPC_START.toByte(), p) + dur),
            pad8(byteArrayOf(0xAA.toByte(), p) + dur),
            pad8(byteArrayOf(0xAA.toByte(), p, power.toByte()) + dur),
            pad8(byteArrayOf(1, p) + dur),
            pad8(byteArrayOf(p) + dur),
            pad8(byteArrayOf(OPC_START.toByte(), p, hours.toByte())),
            pad8(byteArrayOf(OPC_START.toByte(), p) + u32le(durationSec / 60)),
            pad8(byteArrayOf(8, p) + dur),
            pad8(byteArrayOf(9, p) + dur),
            pad8(byteArrayOf(0x10, p) + dur),
            pad8(byteArrayOf(0x20, p) + dur),
        ).distinctBy { it.toList() }
    }

    fun stopCommandCandidates(): List<ByteArray> = listOf(
        stopCommand(),
        pad8(byteArrayOf(OPC_STOP.toByte())),
        pad8(byteArrayOf(OPC_PAUSE.toByte())),
        pad8(byteArrayOf(2)),
        pad8(byteArrayOf(3)),
        pad8(byteArrayOf(0xAA.toByte(), 0)),
        pad8(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
        pad8(byteArrayOf(0x10, 0)),
        pad8(byteArrayOf(0x20, 0)),
        pad8(byteArrayOf(8)),
        pad8(byteArrayOf(9)),
    ).distinctBy { it.toList() }

    fun stopDataCandidates(): List<ByteArray> = listOf(
        byteArrayOf(0x10, 0x03),
        byteArrayOf(0x10, 0x03, 0x01),
        byteArrayOf(0x10, 0x02),
        byteArrayOf(0x10, 0x00),
        byteArrayOf(0x10, 0x01, 0x00),
        byteArrayOf(0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00),
        byteArrayOf(0x02, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00),
        byteArrayOf(0x03, 0x00, 0x00),
        byteArrayOf(0x02, 0x00, 0x00),
        byteArrayOf(0x01, 0x00, 0x01, 0x00),
        byteArrayOf(0x01, 0x00, 0x01, 0x1A),
        byteArrayOf(0x03, 0x01),
        byteArrayOf(0x02, 0x01),
        byteArrayOf(0x03, 0xAA.toByte()),
        byteArrayOf(0x02, 0xAA.toByte()),
        byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00, 0x00),
        byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00),
        byteArrayOf(0x03, 0x00, 0x01),
        byteArrayOf(0x02, 0x00, 0x01),
        byteArrayOf(0x03, 0x01, 0x01),
        byteArrayOf(0x02, 0x01, 0x01),
        byteArrayOf(DATA_SET.toByte(), 0x00, 0x00),
        byteArrayOf(DATA_SET.toByte(), 0x01, 0x00),
        byteArrayOf(DATA_SET.toByte(), 0x02, 0x00),
        byteArrayOf(DATA_SET.toByte(), 0x03, 0x00),
        byteArrayOf(0x03, 0x00),
        byteArrayOf(0x02, 0x00),
        byteArrayOf(0x04, 0x01),
        byteArrayOf(0x05, 0x01),
    )

    fun startDataCandidates(program: Int, durationSec: Int): List<ByteArray> {
        val dur = u32le(durationSec)
        val p = program.toByte()
        return listOf(
            byteArrayOf(0x02, 0x00, p) + dur,
            byteArrayOf(0x03, 0x00, p) + dur,
            byteArrayOf(0x02, p) + dur,
            byteArrayOf(0x03, p) + dur,
            byteArrayOf(0x02, p, 0x01) + dur,
            byteArrayOf(0x03, p, 0x01) + dur,
            byteArrayOf(0x02, 0x00, p) + dur,
            byteArrayOf(0x03, 0x00, p) + dur,
            byteArrayOf(0x02, p),
            byteArrayOf(0x03, p),
            byteArrayOf(DATA_SET.toByte(), p) + dur,
            byteArrayOf(DATA_SET.toByte(), 0x00, p) + dur,
            byteArrayOf(0x02, p) + u32le(durationSec / 60),
            byteArrayOf(0xAA.toByte(), p) + dur,
            byteArrayOf(0x04, p) + dur,
            byteArrayOf(0x05, p) + dur,
            byteArrayOf(0x10, p) + dur,
        )
    }
}
