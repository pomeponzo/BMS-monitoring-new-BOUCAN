package nc.boucan.batterymonitor

import org.junit.Assert.*
import org.junit.Test

class BmsProtocolTest {
    private fun setU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun statusPayload() = ByteArray(124).also { raw ->
        listOf(3362, 3363, 3363, 3363).forEachIndexed { index, mv -> setU16(raw, index * 2, mv) }
        setU16(raw, 64, 66)   // 26 °C
        setU16(raw, 66, 66)
        setU16(raw, 80, 134)  // 13.4 V
        setU16(raw, 82, 30046) // +4.6 A
        setU16(raw, 84, 857)  // 85.7 %
        setU16(raw, 96, 2399) // 239.9 Ah
        setU16(raw, 98, 4)
        setU16(raw, 100, 2)
        setU16(raw, 102, 2)
        setU16(raw, 106, 1)
        setU16(raw, 108, 1)
    }

    private fun modbusFrame(payload: ByteArray): ByteArray {
        val header = byteArrayOf(0xD2.toByte(), 0x03, payload.size.toByte()) + payload
        val crc = BmsProtocol.crc16(header)
        return header + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
    }

    @Test fun observedStatusCommandCrcIsValid() {
        val cmd = BmsProtocol.statusRequest
        assertEquals(0xB9D7, BmsProtocol.crc16(cmd, cmd.size - 2))
    }

    @Test fun decodeFieldsFromKnownLayout() {
        val sample = BmsProtocol.decodeStatus(statusPayload(), 42L)
        assertEquals(13.4, sample.voltageV, 0.001)
        assertEquals(4.6, sample.currentA, 0.001)
        assertEquals(85.7, sample.socPercent, 0.001)
        assertEquals(239.9, sample.remainingAh, 0.001)
        assertEquals(3362, sample.cellsMv[0])
        assertEquals(listOf(26, 26), sample.temperaturesC)
        assertEquals(2, sample.cycles)
        assertTrue(sample.chargingAllowed)
        assertEquals(0L, sample.alarmBits)
    }

    @Test fun fragmentedAndCoalescedNotifications() {
        val results = mutableListOf<ByteArray>()
        val parser = ModbusFrameAssembler { results += it }
        val packet = modbusFrame(statusPayload())
        parser.offer(packet.copyOfRange(0, 7))
        parser.offer(packet.copyOfRange(7, 32))
        assertTrue(results.isEmpty())
        parser.offer(packet.copyOfRange(32, packet.size) + packet)
        assertEquals(2, results.size)
        assertArrayEquals(statusPayload(), results[0])
    }

    @Test fun corruptCrcIsRejected() {
        val results = mutableListOf<ByteArray>()
        val parser = ModbusFrameAssembler { results += it }
        val invalid = modbusFrame(statusPayload())
        invalid[15] = (invalid[15].toInt() xor 1).toByte()
        parser.offer(invalid + modbusFrame(statusPayload()))
        assertEquals(1, results.size)
    }

    @Test fun configurationCapacity() {
        val payload = ByteArray(82)
        setU16(payload, 0, 2800)
        assertEquals(280.0, BmsProtocol.decodeCapacity(payload)!!, 0.0001)
    }

    @Test fun verifiedDaly2MosFramesAreCanonical() {
        assertArrayEquals(
            BmsProtocol.fromHex("D20600A500008A4A"),
            BmsProtocol.mosWriteRequest(MosSwitch.CHARGE, false)
        )
        assertArrayEquals(
            BmsProtocol.fromHex("D20600A500014B8A"),
            BmsProtocol.mosWriteRequest(MosSwitch.CHARGE, true)
        )
        assertArrayEquals(
            BmsProtocol.fromHex("D20600A600007A4A"),
            BmsProtocol.mosWriteRequest(MosSwitch.DISCHARGE, false)
        )
        assertArrayEquals(
            BmsProtocol.fromHex("D20600A60001BB8A"),
            BmsProtocol.mosWriteRequest(MosSwitch.DISCHARGE, true)
        )
    }

    @Test fun segmentedMosEchoWithReadInSameNotification() {
        val echo = BmsProtocol.mosWriteRequest(MosSwitch.CHARGE, false)
        val writes = mutableListOf<ByteArray>()
        val reads = mutableListOf<ByteArray>()
        val parser = ModbusFrameAssembler({ reads += it }, { writes += it })
        parser.offer(echo.copyOfRange(0, 3))
        parser.offer(echo.copyOfRange(3, echo.size) + modbusFrame(statusPayload()))
        assertEquals(1, writes.size)
        assertArrayEquals(echo, writes[0])
        assertEquals(1, reads.size)
        assertArrayEquals(statusPayload(), reads[0])
    }

    @Test fun corruptMosEchoDoesNotAcknowledge() {
        val writes = mutableListOf<ByteArray>()
        val parser = ModbusFrameAssembler({}, { writes += it })
        val invalid = BmsProtocol.mosWriteRequest(MosSwitch.DISCHARGE, false)
        invalid[3] = (invalid[3].toInt() xor 0x01).toByte()
        parser.offer(invalid)
        assertTrue(writes.isEmpty())
    }

    @Test fun modbusWriteExceptionIsDecodedAfterCrcVerification() {
        val errors = mutableListOf<Int>()
        val parser = ModbusFrameAssembler({}, {}, { errors += it })
        val bytes = BmsProtocol.fromHex("D28602")
        val crc = BmsProtocol.crc16(bytes)
        val message = bytes + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
        parser.offer(message.copyOfRange(0, 2))
        parser.offer(message.copyOfRange(2, message.size))
        assertEquals(listOf(2), errors)
    }

}
