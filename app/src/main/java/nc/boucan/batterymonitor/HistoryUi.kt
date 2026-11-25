package nc.boucan.batterymonitor

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Journal display only: no changes to stored records or the BLE protocol. */
class HistoryUi(
    private val activity: Activity,
    onClose: () -> Unit,
    onExport: () -> Unit
) {
    private val chalk = Color.rgb(244,242,236)
    private val ink = Color.rgb(23,42,37)
    private val muted = Color.rgb(102,117,107)
    private val pine = Color.rgb(45,111,85)
    private val clay = Color.rgb(159,76,42)
    private val rule = Color.rgb(210,216,207)
    private val paper = Color.rgb(233,235,226)
    private val locale = Locale.FRANCE
    private val dateFormat = DateTimeFormatter.ofPattern("d MMMM uuuu", locale)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", locale)
    private val dayShort = DateTimeFormatter.ofPattern("d MMM", locale)
    private enum class Flow(val title: String) { ALL("Tous"), CHARGE("Charge"), DISCHARGE("Décharge"), REST("Repos") }
    private enum class Metric(val title: String, val unit: String) { SOC("Charge", "%"), VOLTAGE("Tension", "V"), CURRENT("Courant", "A") }
    private sealed class Row {
        data class Day(val date: LocalDate): Row()
        data class Event(val entry: JournalEntry): Row()
    }
    private var all = emptyList<JournalEntry>()
    private var rows = emptyList<Row>()
    private var filter = Flow.ALL
    private var metric = Metric.SOC
    private var chartMode = false
    private var message = ""
    private val filters = mutableListOf<Button>()
    private val metrics = mutableListOf<Button>()
    private val rootBody = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(chalk) }
    private val status = text("", 13f, muted)
    private val eventsTab = button("Événements")
    private val chartTab = button("Courbes")
    private val filterRow = LinearLayout(activity)
    private val metricRow = LinearLayout(activity)
    private val empty = text("Aucun événement", 16f, muted).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(36), dp(16), dp(36))
    }
    private val plot = JournalPlot()
    private val plotCaption = text("Points enregistrés par le BMS, sans interpolation.", 13f, muted)
    private val list = ListView(activity).apply {
        divider = null
        setPadding(dp(20), 0, dp(20), dp(12))
        clipToPadding = false
    }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = rows[position] is Row.Event
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Day) 0 else 1
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val row = rows[position]) {
                is Row.Day -> text(dayTitle(row.date), 17f, ink, true).apply {
                    setPadding(0, dp(23), 0, dp(9))
                }
                is Row.Event -> eventView(row.entry)
            }
    }

    init {
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(10))
        }
        header.addView(button("‹").apply {
            textSize = 28f
            contentDescription = "Retour"
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(text("Historique", 25f, ink, true), LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("⋮").apply {
            textSize = 25f
            contentDescription = "Options de l’historique"
            setOnClickListener { anchor ->
                PopupMenu(activity, anchor).apply {
                    menu.add("Exporter CSV")
                    setOnMenuItemClickListener { onExport(); true }
                    show()
                }
            }
        }, LinearLayout.LayoutParams(dp(48),dp(48)))
        root.addView(header)
        root.addView(status, LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(20),0,dp(20),dp(14)) })
        val tabs = LinearLayout(activity).apply { setPadding(dp(20),0,dp(20),dp(9)) }
        tabs.addView(eventsTab, LinearLayout.LayoutParams(0,dp(44),1f))
        tabs.addView(chartTab, LinearLayout.LayoutParams(0,dp(44),1f))
        root.addView(tabs)
        eventsTab.setOnClickListener { chartMode = false; render() }
        chartTab.setOnClickListener { chartMode = true; render() }
        root.addView(line())
        filterRow.setPadding(dp(16),dp(8),dp(16),0)
        Flow.values().forEach { f ->
            val b = button(f.title).apply { textSize = 13f; setOnClickListener { filter = f; render() } }
            filters.add(b)
            filterRow.addView(b, LinearLayout.LayoutParams(0,dp(44),1f))
        }
        root.addView(filterRow)
        metricRow.setPadding(dp(16),dp(8),dp(16),0)
        Metric.values().forEach { m ->
            val b = button(m.title).apply { setOnClickListener { metric = m; render() } }
            metrics.add(b)
            metricRow.addView(b, LinearLayout.LayoutParams(0,dp(44),1f))
        }
        root.addView(metricRow)
        root.addView(rootBody, LinearLayout.LayoutParams(-1,0,1f))
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            (rows[position] as? Row.Event)?.let { detail(it.entry) }
        }
        render()
    }

    fun update(entries: List<JournalEntry>, sync: String) {
        val sorted = entries.sortedByDescending { it.date }
        val changed = sorted != all
        all = sorted
        message = sync
        status.text = if (all.isEmpty()) sync else "${all.size} événements · $sync"
        if (changed) render(preserveScroll = true)
    }

    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).roundToInt()
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(activity).apply {
            text = s
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
        }
    private fun button(s: String) = Button(activity).apply {
        text = s; isAllCaps = false; textSize = 15f; setTextColor(ink)
        letterSpacing = 0f
        minimumWidth = 0; minWidth = 0; minimumHeight = 0; minHeight = 0
        setPadding(dp(3),0,dp(3),0)
        background = shape(Color.TRANSPARENT)
    }
    private fun shape(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(5).toFloat()
    }
    private fun line() = View(activity).apply {
        setBackgroundColor(rule)
        layoutParams = LinearLayout.LayoutParams(-1,dp(1))
    }
    private fun selected(b: Button, active: Boolean) {
        b.background = shape(if (active) ink else Color.TRANSPARENT)
        b.setTextColor(if (active) chalk else muted)
        b.isSelected = active
    }
    private fun flow(e: JournalEntry) = when {
        e.current > 1.0 -> Flow.CHARGE
        e.current < -1.0 -> Flow.DISCHARGE
        else -> Flow.REST
    }
    private fun tint(f: Flow) = when (f) {
        Flow.CHARGE -> pine
        Flow.DISCHARGE -> clay
        else -> muted
    }
    private fun date(e: JournalEntry) = LocalDateTime.parse(e.date)
    private fun dayTitle(d: LocalDate): String = when (d) {
        LocalDate.now() -> "Aujourd’hui"
        LocalDate.now().minusDays(1) -> "Hier"
        else -> d.format(dateFormat)
    }
    private fun fmt(n: Double, signed: Boolean = false) =
        String.format(locale, if (signed && n > 0) "+%.1f" else "%.1f", n)

    private fun render(preserveScroll: Boolean = false) {
        val previous = if (preserveScroll && list.firstVisiblePosition in rows.indices) rows[list.firstVisiblePosition] else null
        val offset = list.getChildAt(0)?.top ?: 0
        selected(eventsTab,!chartMode); selected(chartTab,chartMode)
        filterRow.visibility = if (chartMode) View.GONE else View.VISIBLE
        metricRow.visibility = if (chartMode) View.VISIBLE else View.GONE
        filters.forEachIndexed { i,b -> selected(b,Flow.values()[i] == filter) }
        metrics.forEachIndexed { i,b -> selected(b,Metric.values()[i] == metric) }
        rootBody.removeAllViews()
        if (chartMode) {
            if (all.isEmpty()) rootBody.addView(empty,LinearLayout.LayoutParams(-1,-2))
            else {
                plot.entries = all.sortedBy { it.date }
                rootBody.addView(plot, LinearLayout.LayoutParams(-1,0,1f))
                rootBody.addView(plotCaption, LinearLayout.LayoutParams(-1,-2).apply {
                    setMargins(dp(20),dp(4),dp(20),dp(18))
                })
                plot.invalidate()
            }
        } else {
            val filtered = all.filter { filter == Flow.ALL || flow(it) == filter }
            rows = buildList {
                filtered.groupBy { date(it).toLocalDate() }.forEach { (d, items) ->
                    add(Row.Day(d)); items.forEach { add(Row.Event(it)) }
                }
            }
            adapter.notifyDataSetChanged()
            if (rows.isEmpty()) rootBody.addView(empty, LinearLayout.LayoutParams(-1,-2))
            else {
                rootBody.addView(list, LinearLayout.LayoutParams(-1,-1))
                val index = if (previous == null) 0 else rows.indexOf(previous).coerceAtLeast(0)
                list.setSelectionFromTop(index, if (previous == null) 0 else offset)
            }
        }
    }

    private fun eventView(e: JournalEntry): View {
        val f = flow(e)
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0,dp(12),0,dp(12)) }
        val strip = View(activity).apply { setBackgroundColor(tint(f)) }
        row.addView(strip,LinearLayout.LayoutParams(dp(3),dp(46)).apply { rightMargin = dp(12) })
        val left = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text(date(e).format(timeFormat),15f,ink,true))
        left.addView(text(f.title,13f,tint(f)).apply { setPadding(0,dp(5),0,0) })
        row.addView(left,LinearLayout.LayoutParams(0,-2,1f))
        val right = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        right.addView(text("${fmt(e.soc)} %",20f,ink,true))
        right.addView(text("${fmt(e.voltage)} V  ·  ${fmt(e.current,true)} A",14f,muted).apply {
            setPadding(0,dp(5),0,0)
        })
        row.addView(right)
        box.addView(row)
        box.addView(line())
        return box
    }
    private fun detail(e: JournalEntry) {
        val temperatures = if (e.temperatures.isEmpty()) "—" else e.temperatures.joinToString(" / ") { "$it °C" }
        AlertDialog.Builder(activity)
            .setTitle(date(e).format(DateTimeFormatter.ofPattern("d MMMM · HH:mm:ss",locale)))
            .setMessage("Charge : ${fmt(e.soc)} %\nTension : ${fmt(e.voltage)} V\nCourant : ${fmt(e.current,true)} A\n\nCellules : ${e.cells.joinToString(" / ")} mV\nTempératures : $temperatures")
            .setPositiveButton("Fermer",null).show()
    }

    private inner class JournalPlot : View(activity) {
        var entries = emptyList<JournalEntry>()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private fun value(e: JournalEntry) = when (metric) {
            Metric.SOC -> e.soc
            Metric.VOLTAGE -> e.voltage
            Metric.CURRENT -> e.current
        }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (entries.isEmpty()) return
            val left = dp(62).toFloat()
            val right = width - dp(22).toFloat()
            val top = dp(38).toFloat()
            val bottom = height - dp(54).toFloat()
            if (right <= left || bottom <= top) return
            val values = entries.map { value(it) }
            val vmin = values.minOrNull()!!
            val vmax = values.maxOrNull()!!
            val span = max(vmax-vmin, if (metric == Metric.VOLTAGE) 0.2 else 2.0)
            val low = if (metric == Metric.SOC) 0.0 else (vmin+vmax-span)/2
            val high = if (metric == Metric.SOC) 100.0 else low+span
            p.typeface = Typeface.create("sans-serif",Typeface.NORMAL)
            p.textSize = dp(12).toFloat()
            p.strokeWidth = dp(1).toFloat()
            for (i in 0..4) {
                val y = bottom - (bottom-top)*i/4f
                p.color = rule; c.drawLine(left,y,right,y,p)
                p.color = muted
                val label = fmt(low+(high-low)*i/4) + " " + metric.unit
                c.drawText(label,left-dp(7)-p.measureText(label),y+dp(4),p)
            }
            val first = date(entries.first())
            val last = date(entries.last())
            val seconds = Duration.between(first,last).seconds
            entries.forEach { e ->
                val x = if (seconds == 0L) (left+right)/2 else left+(right-left)*
                    (Duration.between(first,date(e)).seconds.toDouble()/seconds).toFloat()
                val y = bottom-(bottom-top)*((value(e)-low)/(high-low)).toFloat()
                p.color = if (metric == Metric.CURRENT) tint(flow(e)) else pine
                c.drawCircle(x,y.coerceIn(top,bottom),dp(3).toFloat(),p)
            }
            p.color = muted
            val a = first.format(dayShort)
            val b = last.format(dayShort)
            if (a == b) {
                val label = "$a · ${first.format(DateTimeFormatter.ofPattern("HH:mm"))}–${last.format(DateTimeFormatter.ofPattern("HH:mm"))}"
                c.drawText(label,left,bottom+dp(24),p)
            } else {
                c.drawText(a,left,bottom+dp(24),p)
                c.drawText(b,right-p.measureText(b),bottom+dp(24),p)
            }
            contentDescription = "${metric.title} : ${entries.size} points, minimum ${fmt(vmin)}, maximum ${fmt(vmax)} ${metric.unit}"
        }
    }
}
