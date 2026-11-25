package nc.boucan.batterymonitor

import java.time.LocalDateTime
import java.util.Locale

// Experimental DALY event journal, documented on R24TK/R24TM boards.
// No settings or clock writes. Keep event-specific fields raw: layouts differ.
object JournalProtocol {
    fun request(index: Int, address: Int): ByteArray {
        require(index in 1..4095 && address in listOf(0x81, 0xD2))
        val register = 0x3000 + index
        val prefix = byteArrayOf(address.toByte(), 3, (register shr 8).toByte(),
            register.toByte(), 0, 1)
        val crc = BmsProtocol.crc16(prefix)
        return prefix + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
    }

    fun decode(data: ByteArray): JournalEntry? {
        if (data.size != 133) return null
        fun b(i: Int) = data[i].toInt() and 255
        fun u(i: Int) = b(i) * 256 + b(i + 1)
        val count = u(2)
        if (count !in 1..4095 || u(0) !in 0..count) return null
        val date = runCatching {
            LocalDateTime.of(2000 + b(5), b(6), b(7), b(8), b(9), b(10))
        }.getOrNull() ?: return null
        if (b(5) > 99 || u(13) !in 1..10000 || u(17) !in 0..1000) return null
        val cells = (0 until 16).map { u(31 + 2 * it) }.filter { it in 500..5000 }
        if (cells.isEmpty()) return null
        val raw = data.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 255) }
        return JournalEntry(count, date.toString(), u(13) / 10.0, (u(15) - 30000) / 10.0,
            u(17) / 10.0, cells, (95..98).map { b(it) - 40 }.filter { it in -40..125 },
            raw)
    }
}

data class JournalEntry(val count: Int, val date: String, val voltage: Double,
    val current: Double, val soc: Double, val cells: List<Int>, val temperatures: List<Int>,
    val raw: String) {
    // Ring position and fill count change independently of the event.
    val key: String get() = raw.drop(8)
}
