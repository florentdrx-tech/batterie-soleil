package be.florent.soleil

import android.app.Activity
import android.app.DatePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val shortLabels = linkedMapOf(
        "p1" to "Champ 1 (kWc)", "a1" to "Orientation 1 (°)",
        "p2" to "Champ 2 (kWc)", "a2" to "Orientation 2 (°)",
        "tilt" to "Inclinaison (°)", "bat" to "Batterie (kWh)",
        "conso" to "Conso journée (kWh)", "corr" to "Correction (×)",
        "lat" to "Latitude", "lon" to "Longitude"
    )
    private val fields = LinkedHashMap<String, EditText>()
    private val fr = Locale.FRENCH

    private lateinit var subtitle: TextView
    private lateinit var kwh: TextView
    private lateinit var meta: TextView
    private lateinit var battery: BatteryView
    private lateinit var verdict: TextView
    private lateinit var days: LinearLayout
    private lateinit var status: TextView
    private lateinit var calDate: EditText
    private lateinit var calKwh: EditText
    private lateinit var calOut: TextView
    private lateinit var settingsBody: LinearLayout
    private lateinit var settingsSummary: TextView
    private lateinit var chevron: TextView
    private lateinit var refreshBtn: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        subtitle = findViewById(R.id.subtitle)
        kwh = findViewById(R.id.kwh)
        meta = findViewById(R.id.meta)
        battery = findViewById(R.id.battery)
        verdict = findViewById(R.id.verdict)
        days = findViewById(R.id.days)
        status = findViewById(R.id.status)
        calDate = findViewById(R.id.calDate)
        calKwh = findViewById(R.id.calKwh)
        calOut = findViewById(R.id.calOut)
        settingsBody = findViewById(R.id.settingsBody)
        settingsSummary = findViewById(R.id.settingsSummary)
        chevron = findViewById(R.id.chevron)
        refreshBtn = findViewById(R.id.refresh)

        buildSettings()

        refreshBtn.setOnClickListener { refresh() }
        findViewById<Button>(R.id.calBtn).setOnClickListener { calibrate() }
        findViewById<Button>(R.id.saveBtn).setOnClickListener { save() }
        findViewById<View>(R.id.settingsHeader).setOnClickListener {
            val open = settingsBody.visibility != View.VISIBLE
            settingsBody.visibility = if (open) View.VISIBLE else View.GONE
            chevron.text = if (open) "▴" else "▾"
        }

        calDate.setText(LocalDate.now().minusDays(1).toString())
        calDate.setOnClickListener { pickDate() }

        fillFields()
        show()
        refresh()
        UpdateWorker.schedule(this)
    }

    // ---------- Mise en forme ----------

    private fun f1(v: Double) = String.format(fr, "%.1f", v)

    private fun fmtField(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else v.toString().replace('.', ',')

    private fun num(e: EditText): Double? =
        e.text.toString().trim().replace(',', '.').toDoubleOrNull()

    private fun sky(d: Day): String = when {
        d.cloud < 25 && d.sun > 3 -> "☀️"
        d.cloud < 60 -> "⛅"
        d.cloud < 85 -> "🌥️"
        else -> "☁️"
    }

    private fun place(c: Config): String =
        if (abs(c.lat - 50.41) < 0.05 && abs(c.lon - 4.59) < 0.05) "Aiseau"
        else String.format(fr, "%.2f, %.2f", c.lat, c.lon)

    // ---------- Réglages ----------

    private fun buildSettings() {
        val grid = findViewById<LinearLayout>(R.id.settingsGrid)
        val keys = shortLabels.keys.toList()
        val dp = resources.displayMetrics.density
        keys.chunked(2).forEachIndexed { rowIndex, pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (rowIndex > 0) setPadding(0, (12 * dp).toInt(), 0, 0)
            }
            pair.forEachIndexed { i, key ->
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (i == 0) lp.marginEnd = (6 * dp).toInt() else lp.marginStart = (6 * dp).toInt()
                val label = TextView(this, null, 0, R.style.Label).apply {
                    text = shortLabels.getValue(key)
                    setPadding(0, 0, 0, (6 * dp).toInt())
                }
                val field = EditText(this, null, 0, R.style.Field).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                    setSingleLine(true)
                }
                cell.addView(label)
                cell.addView(field, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(cell, lp)
                fields[key] = field
            }
            grid.addView(row)
        }
    }

    private fun fillFields() {
        val c = Store.load(this)
        fields.forEach { (k, e) -> e.setText(fmtField(c.m.getValue(k))) }
        subtitle.text = "${place(c)} · ${f1(c.p1 + c.p2)} kWc · batterie ${f1(c.bat)} kWh"
        settingsSummary.text = "${f1(c.p1)} kWc à ${c.a1.roundToInt()}° + ${f1(c.p2)} kWc à ${c.a2.roundToInt()}°"
    }

    // ---------- Affichage ----------

    private fun show() {
        val c = Store.load(this)
        val today = LocalDate.now().toString()
        val fut = Store.days(this).filter { it.date >= today }
        days.removeAllViews()

        if (fut.isEmpty()) {
            kwh.text = "–"
            meta.text = "En attente des prévisions"
            battery.setLevel(0f)
            verdict.text = "Touche ↻ pour charger la météo (internet requis)"
            verdict.setBackgroundResource(R.drawable.pill_low)
            verdict.setTextColor(getColor(R.color.sun))
            status.text = ""
            return
        }

        val t = fut[0]
        val k = Forecast.est(c, t)
        val p = Forecast.battPct(c, k)
        kwh.text = f1(k)
        meta.text = "${sky(t)}  ${f1(t.sun)} h de soleil · ${t.cloud.roundToInt()} % de nuages"
        battery.setLevel(p.toFloat())
        verdict.text = Forecast.verdict(c, k)
        val good = k - c.conso > 0.5
        verdict.setBackgroundResource(if (good) R.drawable.pill_good else R.drawable.pill_low)
        verdict.setTextColor(getColor(if (good) R.color.batt else R.color.sun))

        val max = maxOf(c.conso + c.bat, fut.maxOf { Forecast.est(c, it) })
        val inf = LayoutInflater.from(this)
        val dayFmt = DateTimeFormatter.ofPattern("EEE", fr)
        val dateFmt = DateTimeFormatter.ofPattern("d MMM", fr)
        val dp = resources.displayMetrics.density

        fut.forEachIndexed { i, d ->
            if (i > 0) {
                days.addView(View(this).apply { setBackgroundColor(getColor(R.color.line)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1)))
            }
            val row = inf.inflate(R.layout.item_day, days, false)
            val date = LocalDate.parse(d.date)
            val e = Forecast.est(c, d)
            val bp = (Forecast.battPct(c, e) * 100).roundToInt()

            val name = when (i) {
                0 -> "Auj."
                1 -> "Demain"
                else -> date.format(dayFmt).trimEnd('.').replaceFirstChar { it.uppercase() }
            }
            row.findViewById<TextView>(R.id.dName).apply {
                text = name
                setTextColor(getColor(if (i == 0) R.color.sun else R.color.fg))
            }
            row.findViewById<TextView>(R.id.dDate).text = date.format(dateFmt)
            row.findViewById<TextView>(R.id.dSky).text = sky(d)
            row.findViewById<ProgressBar>(R.id.dBar).progress = ((e / max) * 1000).roundToInt()
            row.findViewById<TextView>(R.id.dMeta).text = "${f1(d.sun)} h de soleil"
            row.findViewById<TextView>(R.id.dKwh).text = f1(e)
            row.findViewById<TextView>(R.id.dBatt).apply {
                text = "🔋 $bp %"
                setTextColor(getColor(if (bp > 0) R.color.batt else R.color.muted))
            }
            days.addView(row)
        }

        status.text = "Mis à jour à ${Store.updatedAt(this)} · correction ×${String.format(fr, "%.2f", c.corr)}"
    }

    // ---------- Actions ----------

    private fun refresh() {
        status.text = "Chargement des prévisions…"
        refreshBtn.animate().rotationBy(360f).setDuration(700).start()
        val act = this
        Thread {
            val r = runCatching {
                Store.saveDays(act, Forecast.fetch(Store.load(act)))
                Store.autoCalibrate(act)
            }
            runOnUiThread {
                if (r.isFailure) {
                    status.text = "Météo indisponible. Vérifie la connexion internet."
                } else {
                    fillFields()
                    show()
                }
                SolarWidget.refreshAll(act)
            }
        }.start()
    }

    private fun pickDate() {
        val cur = runCatching { LocalDate.parse(calDate.text.toString()) }.getOrNull()
            ?: LocalDate.now().minusDays(1)
        val dlg = DatePickerDialog(this, { _, y, m, dd ->
            calDate.setText(LocalDate.of(y, m + 1, dd).toString())
        }, cur.year, cur.monthValue - 1, cur.dayOfMonth)
        dlg.datePicker.maxDate = System.currentTimeMillis()
        dlg.datePicker.minDate = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        dlg.show()
    }

    private fun calibrate() {
        val date = calDate.text.toString().trim()
        val value = num(calKwh)
        val d = Store.days(this).find { it.date == date }
        calOut.visibility = View.VISIBLE
        if (value == null || value <= 0.0 || d == null) {
            calOut.text = "Choisis un jour de la semaine passée et entre une production."
            return
        }
        val base = Forecast.base(Store.load(this), d)
        if (base <= 0.0) {
            calOut.text = "Pas de rayonnement prévu ce jour-là, choisis un autre jour."
            return
        }
        val corr = ((value / base) * 100).roundToInt() / 100.0
        Store.set(this, "corr", corr)
        calOut.text = "Prévisions ajustées (×${String.format(fr, "%.2f", corr)})."
        calKwh.setText("")
        fillFields()
        show()
        SolarWidget.refreshAll(this)
    }

    private fun save() {
        val values = fields.mapValues { (k, e) -> num(e) ?: Store.DEFAULTS.getValue(k) }
        Store.save(this, values)
        settingsBody.visibility = View.GONE
        chevron.text = "▾"
        fillFields()
        refresh()
    }
}
