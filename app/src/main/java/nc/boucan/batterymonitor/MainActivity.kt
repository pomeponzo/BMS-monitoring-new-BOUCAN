package nc.boucan.batterymonitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter

/**
 * A standalone, offline monitor. No cloud account, analytics SDK or mock telemetry.
 * Requires the vendor app to disconnect before taking ownership of the BLE link.
 */
class MainActivity : Activity(), BmsBleClient.Listener {
    private lateinit var ui: DashboardUi
    private lateinit var ble: BmsBleClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = DashboardUi(this)
        setContentView(ui.root)
        ble = BmsBleClient(this, this)
        ui.connectButton.setOnClickListener {
            if (ui.isConnected) ble.disconnect() else ensurePermissionsAndScan()
        }
        ui.showState("HORS LIGNE", false)
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensurePermissionsAndScan() {
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 201)
            return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            onError("Bluetooth LE non disponible sur cet appareil")
            return
        }
        if (!adapter.isEnabled) {
            @Suppress("DEPRECATION")
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        openDevicePicker()
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 201) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                ensurePermissionsAndScan()
            } else onError("Autorise « Appareils à proximité » pour détecter le BMS")
        }
    }

    private fun openDevicePicker() {
        val devices = mutableListOf<BmsBleClient.FoundDevice>()
        val display = mutableListOf<String>()
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, display)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Recherche des batteries BLE…")
            .setAdapter(listAdapter) { _, index ->
                ble.stopScan()
                ble.connect(devices[index].device)
            }
            .setNegativeButton("ANNULER") { dlg, _ -> dlg.dismiss() }
            .create()
        dialog.setOnDismissListener { ble.stopScan() }
        dialog.show()
        ble.startScan(
            onDevice = { found ->
                if (isFinishing || !dialog.isShowing) return@startScan
                devices.add(found)
                display.add("${found.label}\n${found.address}     ${found.rssi} dBm")
                listAdapter.notifyDataSetChanged()
                if (devices.size == 1) dialog.setTitle("Sélectionne ton BMS")
            },
            onFinished = {
                if (dialog.isShowing && devices.isEmpty()) {
                    dialog.setTitle("Aucun appareil détecté")
                }
            }
        )
    }

    override fun onState(label: String, connected: Boolean) {
        runOnUiThread { ui.showState(label, connected) }
    }

    override fun onSnapshot(snapshot: BatterySnapshot) {
        runOnUiThread { ui.showSnapshot(snapshot) }
    }

    override fun onCapacity(capacityAh: Double) {
        runOnUiThread { ui.showCapacity(capacityAh) }
    }

    override fun onError(message: String) {
        runOnUiThread { ui.showError(message) }
    }

    override fun onDestroy() {
        ble.close()
        super.onDestroy()
    }
}
