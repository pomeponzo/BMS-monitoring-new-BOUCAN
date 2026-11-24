package nc.boucan.batterymonitor

import java.util.Locale

/** Captured from the owner's Android HCI trace. Read-only Modbus function 0x03. */
object BmsProtocol {
    val statusRequest = fromHex("D2030000003ED7B9")
    val capacityRequest = fromHex("D20300800029965F")

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

/** BLE notifications may split one Modbus frame or concatenate multiple frames. */
class ModbusFrameAssembler(private val onPayload: (ByteArray) -> Unit) {
    private val bytes = ArrayList<Byte>(256)

    fun clear() = bytes.clear()

    fun offer(fragment: ByteArray) {
        bytes.addAll(fragment.toList())
        // Cap memory use if malformed notifications keep arriving.
        if (bytes.size > 4096) bytes.subList(0, bytes.size - 512).clear()
        while (bytes.isNotEmpty()) {
            val start = bytes.indexOfFirst { (it.toInt() and 255) == 0xD2 }
            if (start < 0) { bytes.clear(); return }
            if (start > 0) bytes.subList(0, start).clear()
            if (bytes.size < 2) return
            if ((bytes[1].toInt() and 255) != 0x03) {
                bytes.removeAt(0)
                continue
            }
            if (bytes.size < 3) return
            val count = bytes[2].toInt() and 255
            if (count > 250) { bytes.removeAt(0); continue }
            val expectedLength = count + 5
            if (bytes.size < expectedLength) return
            val packet = ByteArray(expectedLength) { bytes[it] }
            val actualCrc = (packet[expectedLength - 2].toInt() and 255) or
                ((packet[expectedLength - 1].toInt() and 255) shl 8)
            if (BmsProtocol.crc16(packet, expectedLength - 2) != actualCrc) {
                bytes.removeAt(0)
                continue
            }
            bytes.subList(0, expectedLength).clear()
            onPayload(packet.copyOfRange(3, expectedLength - 2))
        }
    }
}
