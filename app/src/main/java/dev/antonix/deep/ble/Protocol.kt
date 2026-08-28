package dev.antonix.deep.ble

import dev.antonix.deep.model.PowerLevel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BLE wire format for deep.n / deep.r fw 1.8.9, captured from
 * com.dnateam.deep_app 1.4.0 HCI and verified live on 34:5F:45:36:E3:8E.
 *
 * GATT
 *   control  98658f38-e4e3-4ac4-8e14-b1b7ff024353
 *     status   98658f39-…  12 B  read + notify
 *     command  98658f3a-…   8 B  write + notify (2-byte reaction)
 *   data     9b5bfb12-20d7-4730-aa0c-993ea99fd4cd
 *     char     9b5bfb13-…  ≤80 B write + notify
 *
 * Command 8-byte frames
 *   settings (byte0 0..3, from NVS keys + firmware dispatcher):
 *     00 [0|1] 00…     LED enable
 *     01 [0..100] 00…  LED dim percent
 *     02 [0|1] 00…     vibro enable
 *     03 [1|2|3] 00…   power min/mid/max
 *     reaction 00 00 = ACK
 *   start:
 *     1a [program] 7f [power 1..3] [duration_s LE u32]
 *     reaction 00 00 = started, 03 00 = unknown program
 *     min duration 60 min, max 24 h (official app strings)
 *   stop:
 *     3c 00 00 00 00 00 00 00
 *     reaction 00 00 if it was running, 04 00 if already idle
 *
 * Built-in programs on this deep.n 1.8.9:
 *   1  Just a sleep / «Просто сон»
 *   10 «Глубокий сон» (Sleeping Bear slot; 2–9 and 11–16 nack 03 00)
 *
 * Status 12-byte
 *   idle     00 00 7f 00 00 00 00 00 00 00 00 00
 *   running  aa [program] 7f 00 [total LE u32] [elapsed LE u32]
 *
 * Data
 *   GET 00 [reg] → 00 00 [reg] [value]   regs 1–3 value 0x1A
 *              → 00 01                   unknown reg
 *   SET 01 [reg] … → 01 01 error (no writable regs observed)
 *   10 xx → 10 00 ACK, no status change
 *
 * Pause/resume: official UI has them, but no separate opcode showed
 * a paused status. 3 s hold on pause is STOP 0x3C.
 */
object Protocol {
    const val COMMAND_LEN = 8
    const val STATUS_LEN = 12

    const val ID_LED = 0
    const val ID_DIM = 1
    const val ID_VIBRO = 2
    const val ID_POWER = 3

    const val DATA_GET = 0
    const val DATA_SET = 1
    const val DATA_ACK = 0x10

    const val CMD_PROGRAM = 0x1A
    const val CMD_STOP = 0x3C
    const val FREQ_CURVE = 0x7F

    const val RX_OK = 0x00
    const val RX_ERROR = 0x01
    const val RX_UNKNOWN_PROGRAM = 0x03
    const val RX_NOT_RUNNING = 0x04

    const val MIN_DURATION_SEC = 60 * 60
    const val MAX_DURATION_SEC = 24 * 60 * 60

    fun pad8(bytes: ByteArray): ByteArray =
        if (bytes.size == COMMAND_LEN) bytes else bytes.copyOf(COMMAND_LEN)

    fun setValue(id: Int, value: Int): ByteArray =
        pad8(byteArrayOf(id.toByte(), value.toByte()))

    fun setLed(on: Boolean): ByteArray = setValue(ID_LED, if (on) 1 else 0)
    fun setDim(percent: Int): ByteArray = setValue(ID_DIM, percent.coerceIn(0, 100))
    fun setVibro(on: Boolean): ByteArray = setValue(ID_VIBRO, if (on) 1 else 0)
    fun setPower(level: PowerLevel): ByteArray = setValue(ID_POWER, level.wire)

    fun startCommand(program: Int, durationSec: Int, power: Int = 1): ByteArray {
        val buf = ByteBuffer.allocate(COMMAND_LEN).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(CMD_PROGRAM.toByte())
        buf.put(program.toByte())
        buf.put(FREQ_CURVE.toByte())
        buf.put(power.coerceIn(1, 3).toByte())
        buf.putInt(durationSec.coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC))
        return buf.array()
    }

    fun stopCommand(): ByteArray =
        byteArrayOf(CMD_STOP.toByte(), 0, 0, 0, 0, 0, 0, 0)

    fun getRegister(reg: Int): ByteArray = byteArrayOf(DATA_GET.toByte(), reg.toByte())

    fun reactionCode(notify: ByteArray?): Int? =
        notify?.getOrNull(0)?.toInt()?.and(0xFF)

    fun reactionMessage(notify: ByteArray?): String? {
        val code = reactionCode(notify) ?: return null
        return when (code) {
            RX_OK -> null
            RX_ERROR -> "куб ответил ошибкой"
            RX_UNKNOWN_PROGRAM -> "этой программы нет в прошивке"
            RX_NOT_RUNNING -> "уже тихо"
            else -> "ответ ${notify?.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }}"
        }
    }
}
