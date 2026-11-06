package be.florent.soleil

import android.app.Activity
import android.app.DatePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
    private lateinit var address: EditText
    private lateinit var placeOut: TextView
    private lateinit var batteryLabel: TextView
    private lateinit var calSummary: TextView
    private lateinit var calList: LinearLayout

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
        address = findViewById(R.id.address)
        placeOut = findViewById(R.id.placeOut)
        batteryLabel = findViewById(R.id.batteryLabel)
        calSummary = findViewById(R.id.calSummary)
        calList = findViewById(R.id.calList)
        findViewById<View>(R.id.batteryBox).setOnClickListener { editLevel() }

        buildSettings()

        refreshBtn.setOnClickListener { refresh() }
        findViewById<Button>(R.id.findBtn).setOnClickListener { findAddress() }
        address.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { findAddress(); true } else false
        }
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

    private fun place(c: Config): String = Store.placeName(this) ?:
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
                // Gonflé depuis XML pour garder le style de saisie Android (focus, clavier, curseur)
                val field = LayoutInflater.from(this)
                    .inflate(R.layout.item_field, cell, false) as EditText
                cell.addView(label)
                val icon = when (key) { "a1", "a2" -> "🧭"; "tilt" -> "📐"; else -> null }
                if (icon == null) {
                    cell.addView(field, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                } else {
                    val line = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    line.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    val btn = TextView(this).apply {
                        text = icon
                        textSize = 20f
                        gravity = android.view.Gravity.CENTER
                        setBackgroundResource(R.drawable.round_btn)
                        contentDescription = if (key == "tilt") "Mesurer l'inclinaison" else "Mesurer l'orientation"
                        setOnClickListener {
                            MeasureDialog(this@MainActivity, key == "tilt") { v -> applyMeasure(key, v) }.show()
                        }
                    }
                    val size = (44 * dp).toInt()
                    line.addView(btn, LinearLayout.LayoutParams(size, size).apply { marginStart = (6 * dp).toInt() })
                    cell.addView(line, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
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
        placeOut.text = "📍 ${place(c)} (${String.format(fr, "%.4f, %.4f", c.lat, c.lon)})"
        renderCalibs()
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
        val lvl = Store.level(this)
        val end = lvl?.let { Forecast.endLevel(c, t, it.first / 100.0, it.second) }
        val good: Boolean
        if (lvl != null && end != null) {
            val start = lvl.first / 100.0
            battery.setLevel(end.toFloat())
            batteryLabel.text = "ce soir · ✎"
            verdict.text = Forecast.levelVerdict(start, end)
            good = end > start + 0.01 || end >= 0.995
        } else {
            battery.setLevel(p.toFloat())
            batteryLabel.text = "✎ Régler"
            verdict.text = Forecast.verdict(c, k)
            good = k - c.conso > 0.5
        }
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

    private fun findAddress() {
        val q = address.text.toString().trim()
        if (q.isEmpty()) {
            placeOut.text = "Entre une adresse ou une commune."
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(address.windowToken, 0)
        placeOut.text = "Recherche de l'adresse…"
        val act = this
        Thread {
            val p = Geo.find(act, q)
            runOnUiThread {
                if (p == null) {
                    placeOut.text = "Adresse introuvable. Essaie avec la commune et le code postal."
                } else {
                    Store.setPlace(act, p)
                    address.setText("")
                    fillFields()
                    placeOut.text = "📍 ${p.name} — enregistré (${String.format(fr, "%.4f, %.4f", p.lat, p.lon)})"
                    refresh()
                }
            }
        }.start()
    }

    private fun renderCalibs() {
        calList.removeAllViews()
        val list = Store.calibs(this)
        val f = Store.factor(list)
        if (list.isEmpty() || f == null) {
            calSummary.visibility = View.GONE
            return
        }
        val n = list.size.coerceAtMost(Store.CALIB_WINDOW)
        calSummary.visibility = View.VISIBLE
        calSummary.text = "Correction ×${String.format(fr, "%.2f", f)} · moyenne sur $n jour${if (n > 1) "s" else ""}" +
            if (n < 5) "\nAjoute quelques jours de plus pour une correction fiable." else ""

        val dp = resources.displayMetrics.density
        val fmt = DateTimeFormatter.ofPattern("EEE d MMM", fr)
        list.take(Store.CALIB_WINDOW).forEach { c ->
            calList.addView(View(this).apply { setBackgroundColor(getColor(R.color.line)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1))
                    .apply { topMargin = (8 * dp).toInt() })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (8 * dp).toInt(), 0, 0)
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = LocalDate.parse(c.date).format(fmt).replaceFirstChar { it.uppercase() }
                setTextColor(getColor(R.color.fg))
                textSize = 14f
            })
            texts.addView(TextView(this).apply {
                text = "Réel ${f1(c.real)} kWh · prévu ${f1(c.base)} kWh"
                setTextColor(getColor(R.color.muted))
                textSize = 12f
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = if (c.base > 0) "×${String.format(fr, "%.2f", c.real / c.base)}" else "–"
                setTextColor(getColor(R.color.sun))
                textSize = 14f
                setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            })
            row.addView(TextView(this).apply {
                text = "✕"
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.muted))
                setBackgroundResource(R.drawable.round_btn)
                contentDescription = "Supprimer cette saisie"
                setOnClickListener {
                    Store.removeCalib(this@MainActivity, c.date)
                    fillFields(); show(); SolarWidget.refreshAll(this@MainActivity)
                }
            }, LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()))
            calList.addView(row)
        }
    }

    private fun editLevel() {
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(R.layout.dialog_level)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT)
        val seek = dlg.findViewById<android.widget.SeekBar>(R.id.lSeek)
        val value = dlg.findViewById<TextView>(R.id.lValue)
        seek.progress = Store.level(this)?.first?.roundToInt() ?: 50
        value.text = "${seek.progress} %"
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar, v: Int, fromUser: Boolean) { value.text = "$v %" }
            override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
        })
        dlg.findViewById<Button>(R.id.lMinus).setOnClickListener { seek.progress = (seek.progress - 5).coerceAtLeast(0) }
        dlg.findViewById<Button>(R.id.lPlus).setOnClickListener { seek.progress = (seek.progress + 5).coerceAtMost(100) }
        dlg.findViewById<Button>(R.id.lClear).setOnClickListener {
            Store.clearLevel(this); show(); SolarWidget.refreshAll(this); dlg.dismiss()
        }
        dlg.findViewById<Button>(R.id.lSave).setOnClickListener {
            Store.setLevel(this, seek.progress.toDouble()); show(); SolarWidget.refreshAll(this); dlg.dismiss()
        }
        dlg.show()
    }

    private fun applyMeasure(key: String, v: Double) {
        Store.set(this, key, v)
        fillFields()
        placeOut.text = if (key == "tilt") "Inclinaison enregistrée : ${v.roundToInt()}°"
            else "Orientation enregistrée : ${v.roundToInt()}° (${MeasureDialog.cardinal(v.roundToInt())})"
        refresh()
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
        Store.addCalib(this, Calib(date, value, base))
        val n = Store.calibs(this).size.coerceAtMost(Store.CALIB_WINDOW)
        calOut.text = "Enregistré. Correction calculée sur $n jour${if (n > 1) "s" else ""}."
        calKwh.setText("")
        fillFields()
        show()
        SolarWidget.refreshAll(this)
    }

    private fun save() {
        val values = fields.mapValues { (k, e) -> num(e) ?: Store.DEFAULTS.getValue(k) }
        val old = Store.load(this)
        Store.save(this, values)
        if (abs(old.lat - values.getValue("lat")) > 0.0005 || abs(old.lon - values.getValue("lon")) > 0.0005) {
            Store.clearPlace(this)
        }
        settingsBody.visibility = View.GONE
        chevron.text = "▾"
        fillFields()
        refresh()
    }
}
