package nc.boucan.batterymonitor

import java.util.Locale

/** MOS output channel, distinct from protection thresholds (which are never altered). */
enum class MosSwitch(val register: Int, val label: String) {
    CHARGE(0x00A5, "charge"),
    DISCHARGE(0x00A6, "décharge")
}

/** Read requests from the user's HCI trace; MOS writes from independently documented Daly2 BLE. */
object BmsProtocol {
    val statusRequest = fromHex("D2030000003ED7B9")
    val capacityRequest = fromHex("D20300800029965F")

    /**
     * Modbus 0x06, register A5/A6 and value 0/1.
     * Observed in external Daly Smart BMS HCI capture, NOT yet on the owner's firmware.
     * UI must warn and require per-command consent; callers must verify write echo AND a fresh read.
     */
    fun mosWriteRequest(switch: MosSwitch, enabled: Boolean): ByteArray {
        val register = switch.register
        val value = if (enabled) 1 else 0
        val prefix = byteArrayOf(
            0xD2.toByte(), 0x06, (register shr 8).toByte(), register.toByte(),
            0x00, value.toByte()
        )
        val crc = crc16(prefix)
        return prefix + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun crc16(input: ByteArray, end: Int = input.size): Int {
        var crc = 0xFFFF
        for (index in 0 until end) {
            crc = crc xor (input[index].toInt() and 0xFF)
            repeat(8) { crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }

    fun decodeStatus(data: ByteArray, nowMs: Long = System.currentTimeMillis()): BatterySnapshot {
        require(data.size == 124) { "Réponse de statut inattendue : ${data.size} octets" }
        fun u16(offset: Int) =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

        // The cell array occupies 0..63, the temperature array 64..79.
        val cellCount = u16(98).coerceIn(0, 32)
        val tempCount = u16(100).coerceIn(0, 8)
        val voltage = u16(80) / 10.0
        val current = (u16(82) - 30000) / 10.0
        var alarms = 0L
        for (i in 116 until 124) alarms = (alarms shl 8) or (data[i].toLong() and 255L)
        return BatterySnapshot(
            timestampMs = nowMs,
            voltageV = voltage,
            currentA = current,
            powerW = voltage * current,
            socPercent = u16(84) / 10.0,
            remainingAh = u16(96) / 10.0,
            cellsMv = (0 until cellCount).map { u16(it * 2) },
            temperaturesC = (0 until tempCount).map { u16(64 + it * 2) - 40 },
            cycles = u16(102),
            chargingAllowed = u16(106) != 0,
            dischargingAllowed = u16(108) != 0,
            alarmBits = alarms
        )
    }

    fun decodeCapacity(data: ByteArray): Double? {
        if (data.size != 82) return null
        val capacityDeciAh = ((data[0].toInt() and 255) shl 8) or (data[1].toInt() and 255)
        return (capacityDeciAh / 10.0).takeIf { it > 0 && it < 5000 }
    }

    fun alarmsAsHex(alarms: Long): String = String.format(Locale.US, "0x%016X", alarms)
}

data class BatterySnapshot(
    val timestampMs: Long,
    val voltageV: Double,
    val currentA: Double,
    val powerW: Double,
    val socPercent: Double,
    val remainingAh: Double,
    val cellsMv: List<Int>,
    val temperaturesC: List<Int>,
    val cycles: Int,
    val chargingAllowed: Boolean,
    val dischargingAllowed: Boolean,
    val alarmBits: Long
)

/**
 * BLE notifications may fragment/concatenate Modbus reads, single-register
 * write echoes (0x06) and exceptions (0x86). No state is trusted without CRC.
 */
class ModbusFrameAssembler(
    private val onPayload: (ByteArray) -> Unit,
    private val onWriteEcho: (ByteArray) -> Unit = {},
    private val onWriteException: (Int) -> Unit = {}
) {
    private val bytes = ArrayList<Byte>(256)

    fun clear() = bytes.clear()

    fun offer(fragment: ByteArray) {
        bytes.addAll(fragment.toList())
        if (bytes.size > 4096) bytes.subList(0, bytes.size - 512).clear()
        while (bytes.isNotEmpty()) {
            val start = bytes.indexOfFirst { (it.toInt() and 0xFF) == 0xD2 }
            if (start < 0) { bytes.clear(); return }
            if (start > 0) bytes.subList(0, start).clear()
            if (bytes.size < 2) return
            val function = bytes[1].toInt() and 0xFF
            val expected = when (function) {
                0x03 -> {
                    if (bytes.size < 3) return
                    val count = bytes[2].toInt() and 0xFF
                    if (count > 250) { bytes.removeAt(0); continue }
                    count + 5
                }
                0x06 -> 8
                0x86 -> 5
                else -> { bytes.removeAt(0); continue }
            }
            if (bytes.size < expected) return
            val packet = ByteArray(expected) { bytes[it] }
            val wireCrc = (packet[expected - 2].toInt() and 0xFF) or
                ((packet[expected - 1].toInt() and 0xFF) shl 8)
            if (BmsProtocol.crc16(packet, expected - 2) != wireCrc) {
                bytes.removeAt(0)
                continue
            }
            bytes.subList(0, expected).clear()
            when (function) {
                0x03 -> onPayload(packet.copyOfRange(3, expected - 2))
                0x06 -> onWriteEcho(packet)
                0x86 -> onWriteException(packet[2].toInt() and 0xFF)
            }
        }
    }
}
