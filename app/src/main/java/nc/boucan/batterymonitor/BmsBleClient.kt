package nc.boucan.batterymonitor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * BLE transport. Callers handle permissions and confirmation dialogs.
 *
 * Reads: Modbus 0x03, documented in the user's own Android capture.
 * MOS: Modbus 0x06 on 0xA5/0xA6, documented on the same Daly2 family but
 * NOT yet corroborated on this exact unit. Never silently retry writes.
 * A valid write echo is NOT success: only a subsequent status READ verifies state.
 */
@SuppressLint("MissingPermission")
class BmsBleClient(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onState(label: String, connected: Boolean)
        fun onSnapshot(snapshot: BatterySnapshot)
        fun onCapacity(capacityAh: Double)
        fun onJournalEntry(entry: JournalEntry)
        fun onJournalState(message: String)
        fun onError(message: String)
        fun onMosResult(switch: MosSwitch, desiredOn: Boolean, ok: Boolean, message: String)
    }

    data class FoundDevice(val device: BluetoothDevice, val label: String, val address: String, val rssi: Int)

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter? get() = manager.adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var connected = false
    private var polling = false
    private var tick = 0
    private val found = mutableMapOf<String, String>()
    private var onFound: ((FoundDevice) -> Unit)? = null
    private var onScanFinished: (() -> Unit)? = null
    private var lastSnapshot: BatterySnapshot? = null

    private data class PendingMos(
        val switch: MosSwitch,
        val desiredOn: Boolean,
        val bytes: ByteArray,
        var acknowledged: Boolean = false,
        var readAfterAck: Boolean = false
    )
    private var pendingMos: PendingMos? = null
    private var journalAddress = 0x81
    private var journalIndex = 1
    private var journalTotal = 0
    private var journalWaiting = false
    private var journalFailures = 0
    private var journalDone = false
    private val journalTimeout = Runnable {
        if (journalWaiting) {
            journalWaiting = false
            journalFailures++
            if (journalFailures >= 2) {
                if (journalIndex == 1 && journalAddress == 0x81) {
                    journalAddress = 0xD2
                    journalFailures = 0
                } else {
                    journalDone = true
                    listener.onJournalState(if (journalIndex == 1)
                        "Historique non détecté" else "Synchronisation interrompue")
                }
            }
        }
    }
    private val journalRead = Runnable {
        if (connected && polling && pendingMos == null && !journalDone && !journalWaiting) {
            journalWaiting = true
            if (writeCommand(JournalProtocol.request(journalIndex, journalAddress))) {
                main.postDelayed(journalTimeout, 2200L)
            } else journalTimeout.run()
        }
    }
    private fun onJournalFrame(address: Int, payload: ByteArray) {
        if (!connected || !journalWaiting || pendingMos != null) return
        if (journalAddress == 0x81 && address !in listOf(0x51, 0x81)) return
        if (journalAddress == 0xD2 && address != 0xD2) return
        val entry = JournalProtocol.decode(payload) ?: return
        main.removeCallbacks(journalTimeout)
        journalWaiting = false
        journalFailures = 0
        if (journalIndex == 1) journalTotal = entry.count
        listener.onJournalEntry(entry)
        listener.onJournalState("Synchronisation : $journalIndex/$journalTotal")
        if (journalIndex >= journalTotal) {
            journalDone = true
            listener.onJournalState("Historique synchronisé")
        } else journalIndex++
    }
    private fun resetJournal() {
        main.removeCallbacks(journalRead)
        main.removeCallbacks(journalTimeout)
        journalAddress = 0x81
        journalIndex = 1
        journalTotal = 0
        journalWaiting = false
        journalFailures = 0
        journalDone = false
    }

    private val notifyUuid = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val writeUuid = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    private val serviceUuid = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val assembler = ModbusFrameAssembler(
        onPayload = { payload -> onFrame(payload) },
        onWriteEcho = { echo -> onWriteEcho(echo) },
        onWriteException = { code -> main.post { failMos("BMS : commande rejetée (Modbus $code)") } },
        onJournalPayload = { address, payload -> onJournalFrame(address, payload) }
    )

    private fun onFrame(payload: ByteArray) {
        if (!connected) return
        if (payload.size == 124) {
            val sample = try { BmsProtocol.decodeStatus(payload) }
            catch (e: IllegalArgumentException) {
                listener.onError("Trame rejetée : ${e.message}")
                return
            }
            lastSnapshot = sample
            listener.onSnapshot(sample)
            val request = pendingMos
            if (request != null && request.acknowledged && request.readAfterAck) {
                val observed = if (request.switch == MosSwitch.CHARGE)
                    sample.chargingAllowed else sample.dischargingAllowed
                if (observed == request.desiredOn) {
                    completeMos(true, "État confirmé par une nouvelle lecture du BMS")
                }
            }
        } else if (payload.size == 82) {
            BmsProtocol.decodeCapacity(payload)?.let(listener::onCapacity)
        }
    }

    private fun onWriteEcho(echo: ByteArray) {
        val request = pendingMos ?: return
        if (!echo.contentEquals(request.bytes)) return
        request.acknowledged = true
        main.removeCallbacks(pollRunnable)
        main.postDelayed(verifyRunnable, 350L)
    }

    private val verifyRunnable = object : Runnable {
        override fun run() {
            val request = pendingMos ?: return
            if (!request.acknowledged || !connected) return
            if (writeCommand(BmsProtocol.statusRequest)) {
                request.readAfterAck = true
                main.postDelayed(this, 1_800L)
            } else failMos("Impossible de relire l'état du BMS")
        }
    }

    private val mosTimeout = Runnable {
        if (pendingMos != null) failMos("Pas de confirmation fiable du BMS après 12 s")
    }

    private fun completeMos(ok: Boolean, message: String) {
        val request = pendingMos ?: return
        pendingMos = null
        if (!ok) lastSnapshot = null // stale or uncertain state must be read anew
        main.removeCallbacks(verifyRunnable)
        main.removeCallbacks(mosTimeout)
        listener.onMosResult(request.switch, request.desiredOn, ok, message)
        if (connected && polling) main.postDelayed(pollRunnable, 500L)
    }

    private fun failMos(message: String) = completeMos(false, message)

    /**
     * Execute only following an explicit user dialog confirmation. One write max;
     * any timeout or disconnect is reported as unknown state, never retried.
     */
    fun setMos(switch: MosSwitch, enabled: Boolean): Boolean {
        if (!connected || !polling || tx == null || pendingMos != null || journalWaiting) return false
        val fresh = lastSnapshot?.takeIf { System.currentTimeMillis() - it.timestampMs < 15_000L }
            ?: return false
        val observed = if (switch == MosSwitch.CHARGE) fresh.chargingAllowed else fresh.dischargingAllowed
        if (observed == enabled) {
            listener.onMosResult(switch, enabled, true, "Déjà dans l'état demandé")
            return true
        }
        val bytes = BmsProtocol.mosWriteRequest(switch, enabled)
        main.removeCallbacks(pollRunnable)
        main.removeCallbacks(journalRead)
        pendingMos = PendingMos(switch, enabled, bytes)
        if (!writeCommand(bytes)) {
            failMos("Écriture Bluetooth impossible")
            return false
        }
        main.postDelayed(mosTimeout, 12_000L)
        return true
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val name = result.scanRecord?.deviceName
                ?: runCatching { result.device.name }.getOrNull()
                ?: "Appareil sans nom"
            val previous = found[address]
            // Some devices broadcast their name only in scan responses: do not
            // permanently discard a battery after an unnamed first advertisement.
            if (previous == null || (previous == "Appareil sans nom" && name != previous)) {
                found[address] = name
                val entry = FoundDevice(result.device, name, address, result.rssi)
                main.post { onFound?.invoke(entry) }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                listener.onError("Recherche BLE impossible (code $errorCode)")
                stopScan()
            }
        }
    }

    private val stopScanTask = Runnable { stopScan() }

    fun startScan(onDevice: (FoundDevice) -> Unit, onFinished: () -> Unit) {
        stopScan()
        found.clear()
        onFound = onDevice
        onScanFinished = onFinished
        try {
            scanner = adapter?.bluetoothLeScanner
            if (scanner == null) {
                listener.onError("Bluetooth indisponible ou désactivé")
                stopScan()
                return
            }
            scanner?.startScan(scanCallback)
            main.postDelayed(stopScanTask, 15_000L)
        } catch (ex: Exception) {
            listener.onError("Impossible de scanner : ${ex.localizedMessage}")
            stopScan()
        }
    }

    fun stopScan() {
        main.removeCallbacks(stopScanTask)
        runCatching { scanner?.stopScan(scanCallback) }
        scanner = null
        val completed = onScanFinished
        onFound = null
        onScanFinished = null
        completed?.invoke()
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        // Release previous link without generating a false disconnection event.
        dropGatt()
        assembler.clear()
        listener.onState("Connexion au BMS…", false)
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (ex: Exception) {
            listener.onError("Connexion impossible : ${ex.localizedMessage}")
            listener.onState("Déconnecté", false)
        }
    }

    private fun writeCommand(command: ByteArray): Boolean {
        val activeGatt = gatt ?: return false
        val characteristic = tx ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                activeGatt.writeCharacteristic(
                    characteristic, command,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                characteristic.value = command
                @Suppress("DEPRECATION")
                activeGatt.writeCharacteristic(characteristic)
            }
        } catch (ex: Exception) {
            listener.onError("Bluetooth : ${ex.localizedMessage}")
            false
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling || !connected || pendingMos != null) return
            val command = if (tick > 0 && tick % 8 == 0)
                BmsProtocol.capacityRequest else BmsProtocol.statusRequest
            tick++
            if (!writeCommand(command)) listener.onError("Échec de lecture BLE")
            if (!journalDone) main.postDelayed(journalRead, 1500L)
            main.postDelayed(this, 5_000L)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(active: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (gatt !== active) { runCatching { active.close() }; return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED &&
                    status == BluetoothGatt.GATT_SUCCESS) {
                    listener.onState("Découverte des services…", false)
                    if (!active.discoverServices()) {
                        listener.onError("Découverte BLE impossible")
                        disconnect()
                    }
                } else {
                    if (pendingMos != null) failMos("Liaison coupée : état MOS non confirmé")
                    dropGatt()
                    listener.onState("Déconnecté", false)
                    if (status != BluetoothGatt.GATT_SUCCESS)
                        listener.onError("Liaison perdue (GATT $status)")
                }
            }
        }

        override fun onServicesDiscovered(active: BluetoothGatt, status: Int) {
            main.post {
                if (gatt !== active) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onError("Services indisponibles ($status)")
                    disconnect()
                    return@post
                }
                val service = active.getService(serviceUuid)
                val rx = service?.getCharacteristic(notifyUuid)
                tx = service?.getCharacteristic(writeUuid)
                if (rx == null || tx == null) {
                    listener.onError("Services FFF1 / FFF2 introuvables")
                    disconnect()
                    return@post
                }
                if (!active.setCharacteristicNotification(rx, true)) {
                    listener.onError("Activation des notifications impossible")
                    disconnect()
                    return@post
                }
                val cccd = rx.getDescriptor(cccdUuid)
                if (cccd == null) {
                    listener.onError("Descripteur de notifications introuvable")
                    disconnect()
                    return@post
                }
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    active.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    active.writeDescriptor(cccd)
                }
                if (!accepted) {
                    listener.onError("Abonnement BLE refusé")
                    disconnect()
                }
            }
        }

        override fun onDescriptorWrite(active: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (gatt !== active || descriptor.uuid != cccdUuid) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onError("Notifications BLE refusées ($status)")
                    disconnect()
                    return@post
                }
                connected = true
                polling = true
                tick = 0
                resetJournal()
                listener.onJournalState("Recherche de l’historique…")
                lastSnapshot = null
                listener.onState("Mesures en direct", true)
                main.removeCallbacks(pollRunnable)
                pollRunnable.run()
            }
        }

        @Deprecated("Compatibility callback for Android < 13")
        override fun onCharacteristicChanged(active: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == notifyUuid) {
                @Suppress("DEPRECATION")
                val bytes = characteristic.value?.clone() ?: return
                main.post { if (gatt === active) assembler.offer(bytes) }
            }
        }

        override fun onCharacteristicChanged(
            active: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (characteristic.uuid == notifyUuid) {
                val bytes = value.clone()
                main.post { if (gatt === active) assembler.offer(bytes) }
            }
        }
    }

    private fun dropGatt() {
        resetJournal()
        assembler.clear()
        main.removeCallbacks(pollRunnable)
        main.removeCallbacks(verifyRunnable)
        main.removeCallbacks(mosTimeout)
        polling = false
        connected = false
        lastSnapshot = null
        pendingMos = null
        tx = null
        val old = gatt
        gatt = null
        old?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
    }

    fun disconnect() {
        if (pendingMos != null) failMos("Déconnexion : état MOS non confirmé")
        dropGatt()
        listener.onState("Déconnecté", false)
    }

    fun close() {
        stopScan()
        disconnect()
    }
}
