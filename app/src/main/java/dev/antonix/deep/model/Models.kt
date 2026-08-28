package dev.antonix.deep.model

enum class CubeModel(val advName: String, val title: String) {
    DeepN("deep.n", "deep.n"),
    DeepR("deep.r", "deep.r"),
    DeepNHotel("deep.n.hotel", "deep.n hotel"),
    DeepRHotel("deep.r.hotel", "deep.r hotel"),
    Unknown("", "куб");

    val isR: Boolean get() = this == DeepR || this == DeepRHotel

    companion object {
        fun fromName(name: String?): CubeModel {
            val n = name?.lowercase()?.trim().orEmpty()
            return entries.firstOrNull { it.advName == n } ?: Unknown
        }

        fun isCubeName(name: String?): Boolean {
            val n = name?.lowercase().orEmpty()
            return n.startsWith("deep.n") || n.startsWith("deep.r") || n == "deep"
        }
    }
}

enum class PowerLevel(val wire: Int, val label: String) {
    Min(1, "мин"),
    Mid(2, "сред"),
    Max(3, "макс"),
}

enum class ProgramKind(
    val number: Int,
    val title: String,
    val subtitle: String,
    val rOnly: Boolean,
) {
    JustSleep(
        1,
        "Просто сон",
        "Засыпание, глубокий сон, плавный подъём",
        rOnly = false,
    ),
    DeepSleep(
        10,
        "Глубокий сон",
        "Сон медведя · максимум глубокого сна, тяжёлое пробуждение",
        rOnly = false,
    ),
    DreamKit(
        3,
        "Дрим кит",
        "Яркие сны и комфортное пробуждение",
        rOnly = true,
    ),
    DreamCatcher(
        4,
        "Ловец снов",
        "Яркие сны, без утреннего подбуживания",
        rOnly = true,
    );

    fun availableOn(model: CubeModel): Boolean = !rOnly || model.isR
}

data class CubeInfo(
    val address: String,
    val name: String,
    val model: CubeModel,
    val firmware: String = "",
    val hardware: String = "",
    val serial: String = "",
    val manufacturer: String = "",
)

data class CubeStatus(
    val raw: ByteArray,
    val programState: Int,
    val programNumber: Int,
    val frequency: Int?,
    val power: Int,
    val elapsedSec: Int,
    val totalSec: Int,
) {
    val running: Boolean get() = programState != 0
    val frequencyHz: Int? get() = frequency?.takeIf { it in 1..80 }
    val remainingSec: Int
        get() = (totalSec - elapsedSec).coerceAtLeast(0)

    companion object {
        /**
         * Idle:     `00 00 7f 00 00 00 00 00 00 00 00 00`
         * Running:  `aa [program] 7f 00 [total LE u32] [elapsed LE u32]`
         *   program 1 = Просто сон, 10 = Глубокий сон on fw 1.8.9
         *   b3 is 0 while running (not the start-frame power)
         */
        fun parse(raw: ByteArray): CubeStatus {
            val b = raw.copyOf(12)
            fun u8(i: Int) = b[i].toInt() and 0xFF
            fun u32le(i: Int): Int {
                if (i + 3 >= b.size) return 0
                return (u8(i)) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)
            }
            val freqRaw = u8(2)
            return CubeStatus(
                raw = b,
                programState = u8(0),
                programNumber = u8(1),
                frequency = freqRaw.takeUnless { it == 0x7F || it == 0 },
                power = u8(3),
                totalSec = u32le(4),
                elapsedSec = u32le(8),
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is CubeStatus && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()
}

enum class ConnectionPhase {
    NoPermission,
    Idle,
    Scanning,
    Connecting,
    Connected,
    Failed,
}
