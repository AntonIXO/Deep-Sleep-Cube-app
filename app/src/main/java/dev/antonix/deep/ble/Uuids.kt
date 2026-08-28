package dev.antonix.deep.ble

import java.util.UUID

/**
 * GATT layout reverse-engineered from com.dnateam.deep_app 1.4.0
 * and a live deep.n (fw 1.8.9, ESP32, advertised as 0x00FF).
 *
 * Advertised names: deep.n, deep.r, deep.n.hotel, deep.r.hotel
 */
object Uuids {
    val advertisedService: UUID = uuid16(0x00FF)

    val gap: UUID = uuid16(0x1800)
    val gatt: UUID = uuid16(0x1801)
    val deviceInfo: UUID = uuid16(0x180A)
    val battery: UUID = uuid16(0x180F)

    val modelNumber: UUID = uuid16(0x2A24)
    val serialNumber: UUID = uuid16(0x2A25)
    val firmwareRev: UUID = uuid16(0x2A26)
    val hardwareRev: UUID = uuid16(0x2A27)
    val manufacturer: UUID = uuid16(0x2A29)
    val batteryLevel: UUID = uuid16(0x2A19)
    val batteryPowerState: UUID = uuid16(0x2A1A)
    val deviceName: UUID = uuid16(0x2A00)

    /** Control service: status + 8-byte command. */
    val controlService: UUID = UUID.fromString("98658f38-e4e3-4ac4-8e14-b1b7ff024353")
    /** 12-byte program/status, read + notify. */
    val statusChar: UUID = UUID.fromString("98658f39-e4e3-4ac4-8e14-b1b7ff024353")
    /** 8-byte command, write + write-without-response + notify (2-byte reaction). */
    val commandChar: UUID = UUID.fromString("98658f3a-e4e3-4ac4-8e14-b1b7ff024353")

    /** Data/program channel, max 80 bytes, write + notify. */
    val dataService: UUID = UUID.fromString("9b5bfb12-20d7-4730-aa0c-993ea99fd4cd")
    val dataChar: UUID = UUID.fromString("9b5bfb13-20d7-4730-aa0c-993ea99fd4cd")

    /** TI OAD leftovers — firmware update only, do not touch. */
    val oadService: UUID = UUID.fromString("f000ffc0-0451-4000-b000-000000000000")

    val ccc: UUID = uuid16(0x2902)

    private fun uuid16(short: Int): UUID =
        UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))
}
