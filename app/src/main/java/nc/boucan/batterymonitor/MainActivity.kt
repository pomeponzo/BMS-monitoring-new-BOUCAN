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
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter

/**
 * On launch: request BLE permissions, scan for this exact battery name or
 * previously connected MAC, connect automatically. Re-scan on link loss
 * while Activity is visible; never automatically operate MOSFETs.
 */
class MainActivity : Activity(), BmsBleClient.Listener {
    private lateinit var ui: DashboardUi
    private lateinit var ble: BmsBleClient
    private val main = Handler(Looper.getMainLooper())
    private var foreground = false
    private var autoEnabled = true
    private var scanning = false
    private var linking = false
    private var scanMatched = false
    private var pendingAddress: String? = null
    private var bluetoothRequested = false
    private var autoPermissionRequested = false
    private val prefs by lazy { getSharedPreferences("boucan_link", MODE_PRIVATE) }
    private val journalPrefs by lazy { getSharedPreferences("boucan_journal", MODE_PRIVATE) }
    private var journalDevice: String? = null
    private var journalMessage = "Hors ligne"
    private val journal = linkedMapOf<String, JournalEntry>()
    private val autoRetry = Runnable { if (foreground && autoEnabled) attemptAutoConnection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = DashboardUi(this)
        setContentView(ui.root)
        ble = BmsBleClient(this, this)
        ui.connectButton.setOnClickListener {
            if (ui.isConnected) {
                autoEnabled = false
                main.removeCallbacks(autoRetry)
                ble.disconnect()
            } else {
                autoEnabled = true
                main.removeCallbacks(autoRetry)
                attemptAutoConnection()
            }
        }
        // Manual discovery remains accessible by long-pressing the connect button.
        ui.connectButton.setOnLongClickListener {
            if (!ui.isConnected) {
                autoEnabled = false
                main.removeCallbacks(autoRetry)
                openDevicePicker()
            }
            true
        }
        journalDevice = prefs.getString("last_address", null)
        loadJournal()
        ui.journalButton.setOnClickListener { openJournal() }
        ui.onMosAction = { switch, enabled -> confirmMosAction(switch, enabled) }
        ui.showState("RECHERCHE AUTOMATIQUE", false)
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (autoEnabled && !ui.isConnected) main.post(autoRetry)
    }

    override fun onStop() {
        foreground = false
        main.removeCallbacks(autoRetry)
        if (scanning) { scanning = false; ble.stopScan() }
        super.onStop()
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensureBluetoothReady(): Boolean {
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            if (!autoPermissionRequested) {
                autoPermissionRequested = true
                requestPermissions(missing.toTypedArray(), 201)
            } else ui.showState("AUTORISATION BLUETOOTH REQUISE", false)
            return false
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            onError("Bluetooth LE indisponible")
            return false
        }
        if (!adapter.isEnabled) {
            ui.showState("ACTIVER LE BLUETOOTH", false)
            if (!bluetoothRequested) {
                bluetoothRequested = true
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 202)
            }
            return false
        }
        return true
    }

    private fun attemptAutoConnection() {
        if (!foreground || !autoEnabled || scanning || linking || ui.isConnected) return
        if (!ensureBluetoothReady()) return
        scanning = true
        scanMatched = false
        ui.showState("RECHERCHE DE DL-BOUCAN…", false)
        val preferred = prefs.getString("last_address", null)
        var fallbackNamed: BmsBleClient.FoundDevice? = null
        ble.startScan(
            onDevice = { found ->
                if (!foreground || !autoEnabled || scanMatched) return@startScan
                val remembered = preferred != null && found.address.equals(preferred, ignoreCase = true)
                val named = found.label.trim().equals("DL-BOUCAN battery", ignoreCase = true)
                if (preferred != null && named && !remembered) fallbackNamed = found
                // Prefer the last connected device. Only fall back to its name
                // after the full scan (BLE private addresses can rotate).
                if (remembered || (preferred == null && named)) {
                    scanMatched = true
                    scanning = false
                    linking = true
                    pendingAddress = found.address
                    ble.connect(found.device)
                }
            },
            onFinished = {
                scanning = false
                if (foreground && autoEnabled && !scanMatched && !linking && !ui.isConnected) {
                    val candidate = fallbackNamed
                    if (candidate != null) {
                        scanMatched = true
                        linking = true
                        pendingAddress = candidate.address
                        ble.connect(candidate.device)
                    } else {
                        ui.showState("BATTERIE INTROUVABLE — NOUVEL ESSAI", false)
                        scheduleRetry(10_000L)
                    }
                }
            }
        )
    }

    private fun scheduleRetry(delay: Long) {
        main.removeCallbacks(autoRetry)
        if (foreground && autoEnabled) main.postDelayed(autoRetry, delay)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 203 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(journalCsv()) } }
                    .onFailure { onError("Export impossible : " + it.message) }
            }
        }
        if (requestCode == 202) {
            bluetoothRequested = false
            if (resultCode == RESULT_OK && autoEnabled) scheduleRetry(350L)
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 201) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (autoEnabled) scheduleRetry(250L) else openDevicePicker()
            } else {
                ui.showState("PERMISSION BLE NON ACCORDÉE", false)
                onError("Autorise « Appareils à proximité » dans les paramètres de l'app")
            }
        }
    }

    private fun openDevicePicker() {
        if (scanning || linking || ui.isConnected) return
        if (!ensureBluetoothReady()) return
        val devices = mutableListOf<BmsBleClient.FoundDevice>()
        val labels = mutableListOf<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Sélection manuelle du BMS")
            .setAdapter(adapter) { _, index ->
                ble.stopScan()
                pendingAddress = devices[index].address
                autoEnabled = true
                linking = true
                ble.connect(devices[index].device)
            }
            .setNegativeButton("ANNULER") { d, _ -> d.dismiss() }
            .create()
        dialog.setOnDismissListener { if (scanning) { scanning = false; ble.stopScan() } }
        dialog.show()
        scanning = true
        ble.startScan(
            onDevice = { found ->
                if (!dialog.isShowing) return@startScan
                devices.add(found)
                labels.add("${found.label}\n${found.address} · ${found.rssi} dBm")
                adapter.notifyDataSetChanged()
            },
            onFinished = { scanning = false }
        )
    }

    private fun confirmMosAction(switch: MosSwitch, enabled: Boolean) {
        if (!ui.isConnected) {
            onError("Reconnecter le BMS avant toute commande")
            return
        }
        val verb = if (enabled) "RÉACTIVER" else "COUPER"
        val name = if (switch == MosSwitch.CHARGE) "LA CHARGE" else "LA DÉCHARGE"
        val impact = if (enabled)
            "Cette action demande la réactivation du MOSFET. Le BMS peut la refuser si une protection est active."
        else if (switch == MosSwitch.CHARGE)
            "Cette action peut interrompre immédiatement la recharge de la batterie."
        else "Cette action peut couper immédiatement l'alimentation des appareils reliés à la batterie."
        AlertDialog.Builder(this)
            .setTitle("$verb $name ?")
            .setMessage(
                "$impact\n\n" +
                "Le protocole MOS a été documenté sur des BMS Daly compatibles, " +
                "mais n'a pas encore été vérifié sur ce firmware précis. " +
                "Le changement sera confirmé uniquement après relecture de l'état du BMS.\n\n" +
                "Exécuter cette commande ?"
            )
            .setNegativeButton("ANNULER", null)
            .setPositiveButton("CONFIRMER") { _, _ ->
                ui.showMosPending(switch, enabled)
                if (!ble.setMos(switch, enabled)) {
                    ui.showMosResult(switch, enabled, false, "Commande indisponible : mesures récentes requises")
                }
            }
            .show()
    }

    override fun onState(label: String, connected: Boolean) {
        runOnUiThread {
            ui.showState(label, connected)
            if (connected) {
                linking = false
                pendingAddress?.let {
                    prefs.edit().putString("last_address", it).apply()
                    if (journalDevice != it) { journalDevice = it; loadJournal() }
                }
                pendingAddress = null
                main.removeCallbacks(autoRetry)
            } else if (label == "Déconnecté") {
                onJournalState("Hors ligne")
                linking = false
                pendingAddress = null
                if (autoEnabled) scheduleRetry(4_000L)
            }
        }
    }

    private fun loadJournal() {
        journal.clear()
        journalDevice?.let { device ->
            journalPrefs.getStringSet(device, emptySet()).orEmpty().forEach { raw ->
                runCatching { JournalProtocol.decode(BmsProtocol.fromHex(raw)) }.getOrNull()?.let {
                    journal[it.key] = it
                }
            }
        }
        ui.showJournal(journalMessage, journal.size)
    }

    override fun onJournalEntry(entry: JournalEntry) {
        if (journal.put(entry.key, entry) == null) {
            journalDevice?.let { device ->
                journalPrefs.edit().putStringSet(device, journal.values.map { it.raw }.toSet()).apply()
            }
        }
        ui.showJournal(journalMessage, journal.size)
    }

    override fun onJournalState(message: String) {
        journalMessage = message
        ui.showJournal(message, journal.size)
    }

    private fun journalCsv(): String = buildString {
        appendLine("date_bms;tension_V;courant_A;soc_pct;cellules_mV;temperatures_C;trame")
        journal.values.sortedByDescending { it.date }.forEach {
            appendLine(listOf(it.date, it.voltage, it.current, it.soc,
                it.cells.joinToString("|"), it.temperatures.joinToString("|"), it.raw).joinToString(";"))
        }
    }

    private fun openJournal() {
        val entries = journal.values.sortedByDescending { it.date }
        val rows = entries.map {
            String.format(java.util.Locale.FRANCE, "%s\n%.1f %%   ·   %.1f V   ·   %.1f A",
                it.date.replace("T", " "), it.soc, it.voltage, it.current)
        }.toTypedArray()
        val builder = AlertDialog.Builder(this).setTitle("Historique")
            .setNegativeButton("Fermer", null)
        if (entries.isEmpty()) builder.setMessage(journalMessage)
        else {
            builder.setItems(rows) { _, index ->
                val e = entries[index]
                AlertDialog.Builder(this).setTitle(e.date.replace("T", " "))
                    .setMessage("Cellules : " + e.cells.joinToString(" / ") + " mV\nTempératures : " +
                        e.temperatures.joinToString(" / ") + " °C")
                    .setPositiveButton("Fermer", null).show()
            }
            builder.setNeutralButton("Exporter CSV") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/csv"
                    putExtra(Intent.EXTRA_TITLE, "Boucan-historique.csv")
                }, 203)
            }
        }
        builder.show()
    }

    override fun onSnapshot(snapshot: BatterySnapshot) {
        runOnUiThread { ui.showSnapshot(snapshot) }
    }

    override fun onCapacity(capacityAh: Double) {
        runOnUiThread { ui.showCapacity(capacityAh) }
    }

    override fun onMosResult(switch: MosSwitch, desiredOn: Boolean, ok: Boolean, message: String) {
        runOnUiThread { ui.showMosResult(switch, desiredOn, ok, message) }
    }

    override fun onError(message: String) {
        runOnUiThread { ui.showError(message) }
    }

    override fun onDestroy() {
        main.removeCallbacks(autoRetry)
        ble.close()
        super.onDestroy()
    }
}
