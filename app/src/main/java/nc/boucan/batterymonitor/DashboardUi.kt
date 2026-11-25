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
    val journalButton: Button
    private val journalStatus: TextView
    private val errors: TextView


    init {
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
        }
        val symbol = MarkView(activity)
        header.addView(symbol, LinearLayout.LayoutParams(dp(31), dp(38)).apply { rightMargin = dp(13) })
        val titleBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(label("BOUCAN", 23f, ink, bold = true, spacing = 0.10f))
        titleBox.addView(label("Battery monitor", 10f, muted, spacing = 0.13f))
        header.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        connectButton = Button(activity).apply {
            text = "Connecter"
            textSize = 11f
            letterSpacing = 0f
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
        connectionStatus = label("Hors ligne", 10f, clay, bold = true, spacing = 0f)
        mast.addView(connectionStatus, LinearLayout.LayoutParams(-1, -2))
        page.addView(mast)
        gap(page, 15)

        // One intentional hero surface. Everything else uses a rule-based layout.
        val hero = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(ink, 9f)
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }
        hero.addView(label("Charge", 11f, Color.rgb(176, 197, 181), bold = true, spacing = 0f))
        gap(hero, 15)
        val socRow = LinearLayout(activity).apply { gravity = Gravity.BOTTOM }
        soc = label("—", 62f, offWhite, family = mono).apply { setSingleLine(true) }
        socRow.addView(soc, LinearLayout.LayoutParams(0, -2, 1f))
        socCaption = label("%", 33f, clay, family = condensedBold).apply { gravity = Gravity.BOTTOM }
        socRow.addView(socCaption, LinearLayout.LayoutParams(dp(35), dp(66)))
        hero.addView(socRow)
        gap(hero, 3)
        gap(hero, 11)
        socBar = ChargeSegments(activity)
        hero.addView(socBar, LinearLayout.LayoutParams(-1, dp(22)))
        gap(hero, 15)
        hero.addView(View(activity).apply { setBackgroundColor(Color.rgb(70, 90, 76)) },
            LinearLayout.LayoutParams(-1, dp(1)))
        gap(hero, 14)
        val capRow = LinearLayout(activity)
        val remainingCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        remainingCol.addView(label("Restant", 10f, Color.rgb(175, 193, 180), spacing = 0f))
        remaining = label("— Ah", 23f, offWhite, family = mono)
        remainingCol.addView(remaining)
        capRow.addView(remainingCol, LinearLayout.LayoutParams(0, -2, 1f))
        val configCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        configCol.addView(label("Capacité configurée", 10f, Color.rgb(175, 193, 180), spacing = 0f))
        configured = label("— Ah", 23f, offWhite, family = mono)
        configCol.addView(configured)
        capRow.addView(configCol)
        hero.addView(capRow)
        page.addView(hero)
        gap(page, 23)

        section(page, "Mesures")
        gap(page, 6)
        val rowOne = LinearLayout(activity)
        val voltBlock = metric("Tension", "V")
        voltage = voltBlock.second
        rowOne.addView(voltBlock.first, LinearLayout.LayoutParams(0, -2, 1f))
        val currentBlock = metric("Courant", "A")
        current = currentBlock.second
        rowOne.addView(currentBlock.first, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(rowOne)
        page.addView(separator(), LinearLayout.LayoutParams(-1, dp(1)))
        gap(page, 16)
        val rowTwo = LinearLayout(activity)
        val powerBlock = metric("Puissance", "W")
        power = powerBlock.second
        rowTwo.addView(powerBlock.first, LinearLayout.LayoutParams(0, -2, 1f))
        val tempBlock = metric("Température", "°C")
        temperature = tempBlock.second
        rowTwo.addView(tempBlock.first, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(rowTwo)
        gap(page, 23)

        section(page, "Cellules")
        gap(page, 8)
        cellDelta = label("Écart : — mV", 11f, muted, family = mono)
        page.addView(cellDelta)
        gap(page, 9)
        cellBars = CellRows(activity)
        page.addView(cellBars, LinearLayout.LayoutParams(-1, dp(144)))
        gap(page, 16)

        section(page, "Historique")
        gap(page, 10)
        journalStatus = label("En attente de connexion", 13f, muted)
        page.addView(journalStatus)
        journalButton = Button(activity).apply {
            text = "Ouvrir l’historique"
            isAllCaps = false
            textSize = 15f
            setTextColor(ink)
            background = shape(paper, 6f)
        }
        page.addView(journalButton, LinearLayout.LayoutParams(-1, dp(48)))
        gap(page, 20)

        section(page, "Commandes")
        gap(page, 10)
        chargeMos = stateRow(page, "Charge")
        chargeControl = mosButton(page, MosSwitch.CHARGE)
        gap(page, 8)
        dischargeMos = stateRow(page, "Décharge")
        dischargeControl = mosButton(page, MosSwitch.DISCHARGE)
        gap(page, 12)
        alarms = stateRow(page, "Alarmes")
        gap(page, 13)
        gap(page, 9)
        mosFeedback = label("", 12f, ink).apply { visibility = View.GONE }
        page.addView(mosFeedback)
        gap(page, 18)
        errors = label("", 12f, clay).apply { visibility = View.GONE }
        page.addView(errors)
        updated = label("En attente de connexion", 10f, muted, spacing = .06f)
        page.addView(updated)
        gap(page, 14)
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

    private fun section(parent: LinearLayout, name: String) {
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(name, 19f, ink, bold = true, spacing = 0f),
            LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row)
        gap(parent, 12)
        parent.addView(separator(), LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun metric(title: String, unit: String): Pair<LinearLayout, TextView> {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(15), dp(8), dp(18))
        }
        box.addView(label(title, 13f, muted, bold = true, spacing = 0f))
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
        row.addView(label(heading, 14f, muted, spacing = 0f), LinearLayout.LayoutParams(0, -2, 1f))
        val value = label("—", 12f, muted, bold = true)
        row.addView(value)
        parent.addView(row)
        parent.addView(separator(), LinearLayout.LayoutParams(-1, dp(1)))
        return value
    }

    private fun mosButton(parent: LinearLayout, switch: MosSwitch): Button {
        val button = Button(activity).apply {
            text = "En attente"
            textSize = 12f
            letterSpacing = 0f
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
                mosInProgress == switch -> "Vérification…"
                current == null -> "En attente"
                current -> "Couper la ${switch.label}"
                else -> "Réactiver la ${switch.label}"
            }
            button.text = action
        }
    }

    fun showMosPending(switch: MosSwitch, enabled: Boolean) {
        mosInProgress = switch
        mosFeedback.visibility = View.VISIBLE
        mosFeedback.setTextColor(muted)
        mosFeedback.text = "${switch.label} : vérification…"
        refreshMosButtons()
    }

    fun showMosResult(switch: MosSwitch, enabled: Boolean, ok: Boolean, message: String) {
        mosInProgress = null
        if (!ok) lastReading = null // force fresh status before any further command
        mosFeedback.visibility = View.VISIBLE
        mosFeedback.setTextColor(if (ok) pine else clay)
        val target = if (enabled) "ACTIVÉE" else "DÉSACTIVÉE"
        mosFeedback.text = "${switch.label} $target : $message"
        refreshMosButtons()
    }

    fun showState(state: String, connected: Boolean) {
        isConnected = connected
        connectionStatus.text = if (connected) "● Connecté" else state
        connectionStatus.setTextColor(if (connected) pine else clay)
        connectButton.text = if (connected) "Déconnecter" else "Rechercher"
        if (!connected) {
            lastReading = null
            mosInProgress = null
            updated.text = "Hors ligne · mesures anciennes"
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
            cellDelta.text = "Écart : $delta mV"
        }
        cellBars.cells = value.cellsMv
        chargeMos.text = if (value.chargingAllowed) "Autorisée" else "Coupée"
        dischargeMos.text = if (value.dischargingAllowed) "Autorisée" else "Coupée"
        chargeMos.setTextColor(if (value.chargingAllowed) pine else clay)
        dischargeMos.setTextColor(if (value.dischargingAllowed) pine else clay)
        alarms.text = if (value.alarmBits == 0L) "Aucune" else BmsProtocol.alarmsAsHex(value.alarmBits)
        alarms.setTextColor(if (value.alarmBits == 0L) pine else clay)
        refreshMosButtons()
        val format = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
        updated.text = "Mis à jour à ${format.format(Date(value.timestampMs))}"
        errors.visibility = View.GONE
    }

    fun showJournal(message: String, count: Int) {
        journalStatus.text = if (count == 0) message else "$count événements · $message"
    }

    fun showCapacity(ah: Double) { configured.text = "${fmt(ah, 0)} Ah" }

    fun showError(message: String) {
        errors.text = "$message"
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
                canvas.drawText("En attente de mesures", 0f, height * .45f, p)
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
                canvas.drawText("En attente de mesures", left, height / 2f, p)
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
