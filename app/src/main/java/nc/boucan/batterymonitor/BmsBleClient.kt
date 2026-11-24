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
 * BLE client for DL-BOUCAN battery. Only sends confirmed Modbus READ (0x03)
 * frames to FFF2. Never writes BMS configuration or MOSFET state.
 * Permissions are requested by MainActivity before invoking this class.
 */
@SuppressLint("MissingPermission")
class BmsBleClient(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onState(label: String, connected: Boolean)
        fun onSnapshot(snapshot: BatterySnapshot)
        fun onCapacity(capacityAh: Double)
        fun onError(message: String)
    }

    data class FoundDevice(val device: BluetoothDevice, val label: String, val address: String, val rssi: Int)

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter? get() = manager.adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var polling = false
    private var tick = 0
    private val found = mutableSetOf<String>()
    private var onFound: ((FoundDevice) -> Unit)? = null
    private var onScanFinished: (() -> Unit)? = null

    private val notifyUuid = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val writeUuid = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    private val serviceUuid = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val assembler = ModbusFrameAssembler { payload ->
        if (payload.size == 124) {
            try {
                val sample = BmsProtocol.decodeStatus(payload)
                main.post { listener.onSnapshot(sample) }
            } catch (ex: IllegalArgumentException) {
                main.post { listener.onError("Trame rejetée : ${ex.message}") }
            }
        } else {
            val capacity = BmsProtocol.decodeCapacity(payload)
            if (capacity != null) main.post { listener.onCapacity(capacity) }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            if (found.add(address)) {
                val name = result.scanRecord?.deviceName
                    ?: runCatching { result.device.name }.getOrNull()
                    ?: "Appareil sans nom"
                main.post { onFound?.invoke(FoundDevice(result.device, name, address, result.rssi)) }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { listener.onError("Recherche BLE impossible (code $errorCode)") }
            stopScan()
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
                return
            }
            scanner?.startScan(scanCallback)
            main.postDelayed(stopScanTask, 15_000L)
        } catch (ex: Exception) {
            listener.onError("Impossible de scanner : ${ex.localizedMessage}")
        }
    }

    fun stopScan() {
        main.removeCallbacks(stopScanTask)
        runCatching { scanner?.stopScan(scanCallback) }
        scanner = null
        main.post { onScanFinished?.invoke() }
        onFound = null
        onScanFinished = null
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        disconnect()
        assembler.clear()
        listener.onState("Connexion au BMS…", false)
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (ex: Exception) {
            listener.onError("Connexion impossible : ${ex.localizedMessage}")
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            val activeGatt = gatt
            val characteristic = tx
            if (activeGatt != null && characteristic != null) {
                val command = if (tick > 0 && tick % 8 == 0) {
                    BmsProtocol.capacityRequest
                } else BmsProtocol.statusRequest
                tick++
                try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        val result = activeGatt.writeCharacteristic(
                            characteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        )
                        if (result != BluetoothGatt.GATT_SUCCESS) {
                            listener.onError("Écriture BLE refusée ($result)")
                        }
                    } else {
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        @Suppress("DEPRECATION")
                        characteristic.value = command
                        @Suppress("DEPRECATION")
                        val ok = activeGatt.writeCharacteristic(characteristic)
                        if (!ok) listener.onError("Commande de lecture non transmise")
                    }
                } catch (ex: Exception) {
                    main.post { listener.onError("Lecture interrompue : ${ex.localizedMessage}") }
                }
                main.postDelayed(this, 5_000L)
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(active: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                main.post { listener.onState("Découverte des services…", false) }
                active.discoverServices()
            } else {
                polling = false
                main.removeCallbacks(pollRunnable)
                active.close()
                if (gatt === active) gatt = null
                tx = null
                main.post { listener.onState("Déconnecté", false) }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    main.post { listener.onError("Liaison perdue (GATT $status)") }
                }
            }
        }

        override fun onServicesDiscovered(active: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                main.post { listener.onError("Services indisponibles ($status)") }
                return
            }
            val service = active.getService(serviceUuid)
            val rx = service?.getCharacteristic(notifyUuid)
            tx = service?.getCharacteristic(writeUuid)
            if (rx == null || tx == null) {
                main.post { listener.onError("Services FFF1 / FFF2 introuvables") }
                return
            }
            if (!active.setCharacteristicNotification(rx, true)) {
                main.post { listener.onError("Activation des notifications impossible") }
                return
            }
            val cccd = rx.getDescriptor(cccdUuid)
            if (cccd == null) {
                main.post { listener.onError("Descripteur de notifications introuvable") }
                return
            }
            val success = if (Build.VERSION.SDK_INT >= 33) {
                active.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                active.writeDescriptor(cccd)
            }
            if (!success) main.post { listener.onError("Abonnement BLE refusé") }
        }

        override fun onDescriptorWrite(active: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != cccdUuid) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                main.post { listener.onError("Notifications BLE refusées ($status)") }
                return
            }
            polling = true
            tick = 0
            main.removeCallbacks(pollRunnable)
            main.post {
                listener.onState("Mesures en direct", true)
                pollRunnable.run()
            }
        }

        @Deprecated("Compatibility callback for Android < 13")
        override fun onCharacteristicChanged(active: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == notifyUuid) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                assembler.offer(value)
            }
        }

        override fun onCharacteristicChanged(
            active: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (characteristic.uuid == notifyUuid) assembler.offer(value)
        }
    }

    fun disconnect() {
        polling = false
        main.removeCallbacks(pollRunnable)
        tx = null
        gatt?.let { active ->
            gatt = null
            runCatching { active.disconnect() }
            runCatching { active.close() }
        }
        listener.onState("Déconnecté", false)
    }

    fun close() {
        stopScan()
        disconnect()
    }
}
