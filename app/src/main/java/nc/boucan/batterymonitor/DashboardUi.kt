package nc.boucan.batterymonitor

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Boucan visual system: marine field-instrument aesthetic.
 * Chalk base, pine ink, clay action accent, editorial condensed labels, mono values.
 * Flat rules and asymmetric rhythm instead of generic soft-card dashboards.
 */
class DashboardUi(private val activity: Activity) {
    private val chalk = Color.rgb(244, 242, 236)
    private val ink = Color.rgb(23, 42, 37)
    private val muted = Color.rgb(102, 117, 107)
    private val pine = Color.rgb(45, 111, 85)
    private val clay = Color.rgb(214, 136, 93)
    private val rule = Color.rgb(210, 216, 207)
    private val paper = Color.rgb(233, 235, 226)
    private val offWhite = Color.rgb(245, 246, 238)
    private val mono = Typeface.create("monospace", Typeface.NORMAL)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val condensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(chalk)
    }
    val connectButton: Button
    var onMosAction: ((MosSwitch, Boolean) -> Unit)? = null
    private var lastReading: BatterySnapshot? = null
    private var mosInProgress: MosSwitch? = null
    var isConnected: Boolean = false
        private set

    private val connectionStatus: TextView
    private val soc: TextView
    private val socCaption: TextView
    private val socBar: ChargeSegments
    private val remaining: TextView
    private val configured: TextView
    private val voltage: TextView
    private val current: TextView
    private val power: TextView
    private val temperature: TextView
    private val cellBars: CellRows
    private val cellDelta: TextView
    private val chargeMos: TextView
    private val dischargeMos: TextView
    private val chargeControl: Button
    private val dischargeControl: Button
    private val mosFeedback: TextView
    private val alarms: TextView
    private val updated: TextView
    private val trend: TrendLine
    private val errors: TextView
    private val history = ArrayList<Double>(90)

    init {
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
        }
        val symbol = MarkView(activity)
        header.addView(symbol, LinearLayout.LayoutParams(dp(31), dp(38)).apply { rightMargin = dp(13) })
        val titleBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(label("BOUCAN", 23f, ink, bold = true, spacing = 0.10f))
        titleBox.addView(label("BATTERY MONITOR    /    12 V", 10f, muted, spacing = 0.13f))
        header.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        connectButton = Button(activity).apply {
            text = "CONNECTER"
            textSize = 11f
            letterSpacing = 0.08f
            isAllCaps = false
            typeface = condensedBold
            setTextColor(offWhite)
            background = shape(ink, 6f)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(13), dp(10), dp(13), dp(10))
        }
        header.addView(connectButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        root.addView(header)
        root.addView(separator(), LinearLayout.LayoutParams(-1, dp(1)))

        val scroll = ScrollView(activity).apply { isFillViewport = true; clipToPadding = false }
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(36))
        }
        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val mast = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        mast.addView(label("TABLEAU DE BORD    /    01", 11f, muted, bold = true, spacing = .12f),
            LinearLayout.LayoutParams(0, -2, 1f))
        connectionStatus = label("HORS LIGNE", 10f, clay, bold = true, spacing = .08f)
        mast.addView(connectionStatus)
        page.addView(mast)
        gap(page, 15)

        // One intentional hero surface. Everything else uses a rule-based layout.
        val hero = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(ink, 9f)
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }
        hero.addView(label("RÉSERVE D'ÉNERGIE", 11f, Color.rgb(176, 197, 181), bold = true, spacing = .14f))
        gap(hero, 15)
        val socRow = LinearLayout(activity).apply { gravity = Gravity.BOTTOM }
        soc = label("—", 62f, offWhite, family = mono).apply { setSingleLine(true) }
        socRow.addView(soc, LinearLayout.LayoutParams(0, -2, 1f))
        socCaption = label("%", 33f, clay, family = condensedBold).apply { gravity = Gravity.BOTTOM }
        socRow.addView(socCaption, LinearLayout.LayoutParams(dp(35), dp(66)))
        hero.addView(socRow)
        gap(hero, 3)
        hero.addView(label("PRÉCISION TRANSMISE  /  0,1 %", 10f,
            Color.rgb(175, 193, 180), spacing = .08f))
        gap(hero, 11)
        socBar = ChargeSegments(activity)
        hero.addView(socBar, LinearLayout.LayoutParams(-1, dp(22)))
        gap(hero, 15)
        hero.addView(View(activity).apply { setBackgroundColor(Color.rgb(70, 90, 76)) },
            LinearLayout.LayoutParams(-1, dp(1)))
        gap(hero, 14)
        val capRow = LinearLayout(activity)
        val remainingCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        remainingCol.addView(label("CAPACITÉ RESTANTE", 10f, Color.rgb(175, 193, 180), spacing = .10f))
        remaining = label("— Ah", 23f, offWhite, family = mono)
        remainingCol.addView(remaining)
        capRow.addView(remainingCol, LinearLayout.LayoutParams(0, -2, 1f))
        val configCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        configCol.addView(label("BMS CONFIGURÉ", 10f, Color.rgb(175, 193, 180), spacing = .10f))
        configured = label("— Ah", 23f, offWhite, family = mono)
        configCol.addView(configured)
        capRow.addView(configCol)
        hero.addView(capRow)
        page.addView(hero)
        gap(page, 29)

        section(page, "MESURES INSTANTANÉES", "02")
        gap(page, 6)
        val rowOne = LinearLayout(activity)
        val voltBlock = metric("TENSION PACK", "V")
        voltage = voltBlock.second
        rowOne.addView(voltBlock.first, LinearLayout.LayoutParams(0, -2, 1f))
        val currentBlock = metric("COURANT", "A")
        current = currentBlock.second
        rowOne.addView(currentBlock.first, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(rowOne)
        page.addView(separator(), LinearLayout.LayoutParams(-1, dp(1)))
        gap(page, 16)
        val rowTwo = LinearLayout(activity)
        val powerBlock = metric("PUISSANCE", "W")
        power = powerBlock.second
        rowTwo.addView(powerBlock.first, LinearLayout.LayoutParams(0, -2, 1f))
        val tempBlock = metric("TEMPÉRATURE", "°C")
        temperature = tempBlock.second
        rowTwo.addView(tempBlock.first, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(rowTwo)
        gap(page, 23)

        section(page, "ÉQUILIBRAGE DES CELLULES", "03")
        gap(page, 8)
        cellDelta = label("ÉCART MAX.     — mV", 11f, muted, family = mono)
        page.addView(cellDelta)
        gap(page, 9)
        cellBars = CellRows(activity)
        page.addView(cellBars, LinearLayout.LayoutParams(-1, dp(182)))
        gap(page, 16)

        section(page, "HISTORIQUE LOCAL", "04")
        gap(page, 12)
        page.addView(label("TENSION    /    DERNIÈRES 90 LECTURES", 10f, muted, spacing = .11f))
        gap(page, 10)
        trend = TrendLine(activity)
        page.addView(trend, LinearLayout.LayoutParams(-1, dp(153)))
        gap(page, 20)

        section(page, "ÉTAT & COMMANDES", "05")
        gap(page, 10)
        chargeMos = stateRow(page, "CHARGE AUTORISÉE")
        chargeControl = mosButton(page, MosSwitch.CHARGE)
        gap(page, 8)
        dischargeMos = stateRow(page, "DÉCHARGE AUTORISÉE")
        dischargeControl = mosButton(page, MosSwitch.DISCHARGE)
        gap(page, 12)
        alarms = stateRow(page, "ALARMES")
        gap(page, 13)
        page.addView(label("UNE CONFIRMATION EST REQUISE AVANT TOUT CHANGEMENT DE MOSFET.", 11f,
            muted, spacing = .02f))
        gap(page, 9)
        mosFeedback = label("", 12f, ink).apply { visibility = View.GONE }
        page.addView(mosFeedback)
        gap(page, 18)
        errors = label("", 12f, clay).apply { visibility = View.GONE }
        page.addView(errors)
        updated = label("AUCUNE MESURE REÇUE   •   ATTENTE DE CONNEXION", 10f, muted, spacing = .06f)
        page.addView(updated)
        gap(page, 14)
        page.addView(label("BOUCAN  /  LIAISON DIRECTE BLE  /  SANS CLOUD", 10f, muted, spacing = .08f))
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
    private fun shape(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * activity.resources.displayMetrics.density
    }

    private fun label(
        value: String, size: Float, color: Int,
        bold: Boolean = false, spacing: Float = 0f, family: Typeface? = null
    ) = TextView(activity).apply {
        text = value
        textSize = size
        typeface = family ?: if (bold) condensedBold else condensed
        setTextColor(color)
        letterSpacing = spacing
        includeFontPadding = false
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun separator() = View(activity).apply { setBackgroundColor(rule) }
    private fun gap(parent: LinearLayout, height: Int) =
        parent.addView(View(activity), LinearLayout.LayoutParams(1, dp(height)))

    private fun section(parent: LinearLayout, name: String, number: String) {
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(name, 16f, ink, bold = true, spacing = .04f),
            LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label(number, 12f, clay, family = mono))
        parent.addView(row)
        gap(parent, 12)
        parent.addView(separator(), LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun metric(title: String, unit: String): Pair<LinearLayout, TextView> {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(15), dp(8), dp(18))
        }
        box.addView(label(title, 10f, muted, bold = true, spacing = .12f))
        gap(box, 11)
        val row = LinearLayout(activity).apply { gravity = Gravity.BOTTOM }
        val value = label("—", 32f, ink, family = mono)
        row.addView(value)
        row.addView(label("  $unit", 15f, muted, family = condensed))
        box.addView(row)
        return box to value
    }

    private fun stateRow(parent: LinearLayout, heading: String): TextView {
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        row.addView(label(heading, 11f, muted, spacing = .09f), LinearLayout.LayoutParams(0, -2, 1f))
        val value = label("—", 12f, muted, bold = true)
        row.addView(value)
        parent.addView(row)
        parent.addView(separator(), LinearLayout.LayoutParams(-1, dp(1)))
        return value
    }

    private fun mosButton(parent: LinearLayout, switch: MosSwitch): Button {
        val button = Button(activity).apply {
            text = "ATTENTE DE MESURE"
            textSize = 12f
            letterSpacing = .08f
            isAllCaps = false
            typeface = condensedBold
            setTextColor(ink)
            background = GradientDrawable().apply {
                setColor(chalk)
                setStroke(dp(1), rule)
                cornerRadius = dp(5).toFloat()
            }
            setPadding(dp(16), dp(7), dp(16), dp(7))
            minimumHeight = 0
            minHeight = 0
            isEnabled = false
            setOnClickListener {
                val reading = lastReading ?: return@setOnClickListener
                if (!isConnected || mosInProgress != null) return@setOnClickListener
                val current = if (switch == MosSwitch.CHARGE)
                    reading.chargingAllowed else reading.dischargingAllowed
                onMosAction?.invoke(switch, !current)
            }
        }
        parent.addView(button, LinearLayout.LayoutParams(-1, dp(43)))
        return button
    }

    private fun refreshMosButtons() {
        for (switch in MosSwitch.values()) {
            val button = if (switch == MosSwitch.CHARGE) chargeControl else dischargeControl
            val current = lastReading?.let {
                if (switch == MosSwitch.CHARGE) it.chargingAllowed else it.dischargingAllowed
            }
            val active = current != null && isConnected && mosInProgress == null
            button.isEnabled = active
            button.alpha = if (active) 1f else .48f
            val action = when {
                mosInProgress == switch -> "ENVOI ET VÉRIFICATION…"
                current == null -> "EN ATTENTE DU BMS"
                current -> "COUPER LA ${switch.label.uppercase(Locale.FRANCE)}"
                else -> "RÉACTIVER LA ${switch.label.uppercase(Locale.FRANCE)}"
            }
            button.text = action
        }
    }

    fun showMosPending(switch: MosSwitch, enabled: Boolean) {
        mosInProgress = switch
        mosFeedback.visibility = View.VISIBLE
        mosFeedback.setTextColor(muted)
        mosFeedback.text = "${switch.label.uppercase(Locale.FRANCE)} : commande en cours, vérification du retour BMS…"
        refreshMosButtons()
    }

    fun showMosResult(switch: MosSwitch, enabled: Boolean, ok: Boolean, message: String) {
        mosInProgress = null
        mosFeedback.visibility = View.VISIBLE
        mosFeedback.setTextColor(if (ok) pine else clay)
        val target = if (enabled) "ACTIVÉE" else "DÉSACTIVÉE"
        mosFeedback.text = "${switch.label.uppercase(Locale.FRANCE)} $target : $message"
        refreshMosButtons()
    }

    fun showState(state: String, connected: Boolean) {
        isConnected = connected
        connectionStatus.text = if (connected) "●  EN DIRECT" else state.uppercase(Locale.FRANCE)
        connectionStatus.setTextColor(if (connected) pine else clay)
        connectButton.text = if (connected) "DÉCONNECTER" else "RECHERCHER"
        if (!connected) {
            lastReading = null
            mosInProgress = null
            updated.text = "HORS LIGNE   •   DERNIÈRES MESURES NON ACTUELLES"
        }
        refreshMosButtons()
        connectButton.contentDescription = if (connected)
            "Déconnecter la batterie" else "Relancer la recherche automatique. Appui long : sélection manuelle"
    }

    private fun fmt(value: Double, digits: Int = 1) =
        String.format(Locale.FRANCE, "%.${digits}f", value)

    fun showSnapshot(value: BatterySnapshot) {
        lastReading = value
        soc.text = fmt(value.socPercent, 1)
        socBar.level = value.socPercent.toFloat().coerceIn(0f, 100f)
        remaining.text = "${fmt(value.remainingAh)} Ah"
        voltage.text = fmt(value.voltageV, 2)
        current.text = fmt(value.currentA)
        power.text = fmt(value.powerW, 0)
        temperature.text = value.temperaturesC.firstOrNull()?.toString() ?: "—"
        if (value.cellsMv.isNotEmpty()) {
            val delta = (value.cellsMv.maxOrNull() ?: 0) - (value.cellsMv.minOrNull() ?: 0)
            cellDelta.text = "ÉCART MAX.     $delta mV"
        }
        cellBars.cells = value.cellsMv
        chargeMos.text = if (value.chargingAllowed) "OUI" else "NON"
        dischargeMos.text = if (value.dischargingAllowed) "OUI" else "NON"
        chargeMos.setTextColor(if (value.chargingAllowed) pine else clay)
        dischargeMos.setTextColor(if (value.dischargingAllowed) pine else clay)
        alarms.text = if (value.alarmBits == 0L) "AUCUNE" else BmsProtocol.alarmsAsHex(value.alarmBits)
        alarms.setTextColor(if (value.alarmBits == 0L) pine else clay)
        refreshMosButtons()
        if (history.size == 90) history.removeAt(0)
        history.add(value.voltageV)
        trend.samples = history.toList()
        val format = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
        updated.text = "ACTUALISÉ À ${format.format(Date(value.timestampMs))}   •   DONNÉES BMS"
        errors.visibility = View.GONE
    }

    fun showCapacity(ah: Double) { configured.text = "${fmt(ah, 0)} Ah" }

    fun showError(message: String) {
        errors.text = "DIAGNOSTIC  /  $message"
        errors.visibility = View.VISIBLE
    }

    /** Deliberately geometric non-lettermark brand glyph. */
    inner class MarkView(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.FILL }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w = width.toFloat()
            val h = height.toFloat()
            val bw = w / 2.6f
            val bh = h / 4.9f
            for (i in 0..3) {
                val y = i * (bh + h / 21f)
                p.color = if (i == 2) clay else ink
                c.drawRect(if (i % 2 == 0) 0f else w * .12f, y, bw + if (i % 2 == 0) w * .12f else w * .18f, y + bh, p)
            }
        }
    }

    inner class ChargeSegments(context: Context) : View(context) {
        var level: Float = 0f
            set(value) { field = value; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val segments = 25
            val gap = dp(4).toFloat()
            val widthEach = (width - gap * (segments - 1)) / segments
            for (i in 0 until segments) {
                p.color = if ((i + 1) * 100f / segments <= level) clay else Color.rgb(67, 91, 80)
                val x = i * (widthEach + gap)
                canvas.drawRect(x, 0f, x + widthEach, height.toFloat(), p)
            }
            contentDescription = "Niveau de charge ${level.roundToInt()} pour cent"
        }
    }

    inner class CellRows(context: Context) : View(context) {
        var cells: List<Int> = emptyList()
            set(value) { field = value; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (cells.isEmpty()) {
                p.color = muted
                p.textSize = dp(13).toFloat()
                p.typeface = condensed
                canvas.drawText("En attente des tensions individuelles…", 0f, height * .45f, p)
                return
            }
            val rows = min(cells.size, 8)
            val rowH = height.toFloat() / rows
            for (i in 0 until rows) {
                val mid = rowH * i + rowH * .50f
                p.typeface = mono
                p.textSize = dp(12).toFloat()
                p.color = muted
                canvas.drawText("C${i + 1}".padEnd(3), 0f, mid + dp(4), p)
                val rightLabel = String.format(Locale.FRANCE, "%.3f V", cells[i] / 1000.0)
                val labelWidth = p.measureText(rightLabel)
                canvas.drawText(rightLabel, width - labelWidth, mid + dp(4), p)
                val left = dp(40).toFloat()
                val right = max(left + dp(30), width - labelWidth - dp(18))
                val v = ((cells[i] - 2800f) / 900f).coerceIn(0f, 1f)
                p.color = paper
                canvas.drawRect(left, mid - dp(4), right, mid + dp(4), p)
                p.color = pine
                canvas.drawRect(left, mid - dp(4), left + (right - left) * v, mid + dp(4), p)
            }
            contentDescription = cells.mapIndexed { index, mv -> "Cellule ${index + 1}, $mv millivolts" }.joinToString(". ")
        }
    }

    inner class TrendLine(context: Context) : View(context) {
        var samples: List<Double> = emptyList()
            set(value) { field = value; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = dp(2).toFloat()
            val right = width - dp(2).toFloat()
            val top = dp(11).toFloat()
            val bottom = height - dp(15).toFloat()
            p.strokeWidth = dp(1).toFloat()
            p.color = rule
            for (i in 0..3) {
                val y = top + (bottom - top) * i / 3f
                canvas.drawLine(left, y, right, y, p)
            }
            if (samples.size < 2) {
                p.color = muted
                p.textSize = dp(12).toFloat()
                p.typeface = condensed
                canvas.drawText("Courbe disponible après deux lectures", left, height / 2f, p)
                return
            }
            val minV = samples.minOrNull() ?: 0.0
            val maxV = samples.maxOrNull() ?: 0.0
            val span = max(0.12, maxV - minV)
            val floor = (minV + maxV - span) / 2.0
            val path = Path()
            samples.forEachIndexed { index, value ->
                val x = left + (right - left) * index / (samples.size - 1).toFloat()
                val y = bottom - ((value - floor) / span).toFloat() * (bottom - top)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            p.color = pine
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(2).toFloat()
            canvas.drawPath(path, p)
            p.style = Paint.Style.FILL
            val lastY = bottom - ((samples.last() - floor) / span).toFloat() * (bottom - top)
            canvas.drawCircle(right, lastY, dp(4).toFloat(), p)
            contentDescription = "Tension de ${samples.first()} à ${samples.last()} volts"
        }
    }
}
