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
    private lateinit var appDays: LinearLayout
    private lateinit var appSummary: TextView
    private lateinit var appList: LinearLayout
    private lateinit var appManage: TextView
    private var appDay = 0
    private lateinit var tip: TextView
    private lateinit var horizon: HorizonView
    private lateinit var shadeImpact: TextView
    private lateinit var obsList: LinearLayout
    private lateinit var diagStatus: TextView
    private lateinit var diagText: TextView
    private lateinit var diagSeason: TextView
    private lateinit var diagCauses: LinearLayout
    private var manageAll = false

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
        appDays = findViewById(R.id.appDays)
        appSummary = findViewById(R.id.appSummary)
        appList = findViewById(R.id.appList)
        appManage = findViewById(R.id.appManage)
        tip = findViewById(R.id.tip)
        horizon = findViewById(R.id.horizon)
        shadeImpact = findViewById(R.id.shadeImpact)
        obsList = findViewById(R.id.obsList)
        diagStatus = findViewById(R.id.diagStatus)
        diagText = findViewById(R.id.diagText)
        diagSeason = findViewById(R.id.diagSeason)
        diagCauses = findViewById(R.id.diagCauses)
        findViewById<Button>(R.id.obsAdd).setOnClickListener {
            ObstacleDialog(this) { o ->
                Store.saveObstacles(this, Store.obstacles(this) + o)
                afterShadeChange()
            }.show()
        }
        setupReminders()
        setupTariff()
        appManage.setOnClickListener { manageAll = !manageAll; renderAppliances() }
        findViewById<View>(R.id.batteryBox).setOnClickListener { editLevel() }

        buildSettings()

        refreshBtn.setOnClickListener { refresh() }
        findViewById<Button>(R.id.findBtn).setOnClickListener { findAddress() }
        address.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { findAddress(); true } else false
        }
        findViewById<Button>(R.id.calBtn).setOnClickListener { calibrate() }
        findViewById<Button>(R.id.saveBtn).setOnClickListener { save() }
        findViewById<View>(R.id.tabHome).setOnClickListener { showTab(0) }
        findViewById<View>(R.id.tabProd).setOnClickListener { showTab(1) }
        findViewById<View>(R.id.tabSettings).setOnClickListener { showTab(2) }
        showTab(savedInstanceState?.getInt("tab") ?: 0)

        // Le soir on encode la production du jour, le matin celle de la veille
        calDate.setText(
            (if (java.time.LocalTime.now().hour >= 16) LocalDate.now() else LocalDate.now().minusDays(1)).toString()
        )
        calDate.setOnClickListener { pickDate() }

        fillFields()
        show()
        refresh()
        UpdateWorker.schedule(this)
        Reminders.schedule(this)
        handleOpen(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpen(intent)
    }

    // ---------- Onglets ----------

    private var tab = 0

    private fun showTab(i: Int) {
        tab = i
        val pages = listOf(R.id.pageHome, R.id.pageProd, R.id.pageSettings)
        val tabs = listOf("tabHome", "tabProd", "tabSettings")
        pages.forEachIndexed { k, id -> findViewById<View>(id).visibility = if (k == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { k, name ->
            val col = getColor(if (k == i) R.color.sun else R.color.muted)
            findViewById<TextView>(resources.getIdentifier("${name}Label", "id", packageName)).setTextColor(col)
            findViewById<TextView>(resources.getIdentifier("${name}Icon", "id", packageName)).alpha = if (k == i) 1f else 0.6f
        }
        findViewById<View>(R.id.refresh).visibility = if (i == 0) View.VISIBLE else View.INVISIBLE
        findViewById<android.widget.ScrollView>(R.id.scroll).scrollTo(0, 0)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("tab", tab)
    }

    /** Ouverture depuis une notification : saisie du niveau ou de la production. */
    private fun handleOpen(i: android.content.Intent?) {
        val kind = i?.getStringExtra(Reminders.EXTRA_OPEN) ?: return
        i.removeExtra(Reminders.EXTRA_OPEN)
        if (kind == Reminders.MORNING) {
            showTab(0)
            editLevel()
        } else {
            showTab(1)
            calDate.setText(LocalDate.now().toString())
            val scroll = findViewById<android.widget.ScrollView>(R.id.scroll)
            scroll.post {
                calKwh.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(calKwh, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    // ---------- Tarif ----------

    private fun setupTariff() {
        val bi = findViewById<android.widget.RadioButton>(R.id.tarBi)
        val mono = findViewById<android.widget.RadioButton>(R.id.tarMono)
        val biBox = findViewById<View>(R.id.tarBiBox)
        val monoBox = findViewById<View>(R.id.tarMonoBox)
        val r1s = findViewById<EditText>(R.id.tarR1s); val r1e = findViewById<EditText>(R.id.tarR1e)
        val r2s = findViewById<EditText>(R.id.tarR2s); val r2e = findViewById<EditText>(R.id.tarR2e)
        val we = findViewById<android.widget.CheckBox>(R.id.tarWeekend)
        val hp = findViewById<EditText>(R.id.tarHp); val hc = findViewById<EditText>(R.id.tarHc)
        val mp = findViewById<EditText>(R.id.tarMonoPrice); val inj = findViewById<EditText>(R.id.tarInj)
        val out = findViewById<TextView>(R.id.tarOut)

        fun hr(v: Int) = if (v < 0) "" else v.toString()
        fun price(v: Double) = String.format(fr, "%.2f", v)
        fun fill(t: Tariff) {
            bi.isChecked = t.bi; mono.isChecked = !t.bi
            biBox.visibility = if (t.bi) View.VISIBLE else View.GONE
            monoBox.visibility = if (t.bi) View.GONE else View.VISIBLE
            r1s.setText(hr(t.r1s)); r1e.setText(hr(t.r1e)); r2s.setText(hr(t.r2s)); r2e.setText(hr(t.r2e))
            we.isChecked = t.weekend
            hp.setText(price(t.hp)); hc.setText(price(t.hc)); mp.setText(price(t.mono)); inj.setText(price(t.inj))
        }
        fill(Tariffs.load(this))

        findViewById<android.widget.RadioGroup>(R.id.tarType).setOnCheckedChangeListener { _, id ->
            val isBi = id == R.id.tarBi
            biBox.visibility = if (isBi) View.VISIBLE else View.GONE
            monoBox.visibility = if (isBi) View.GONE else View.VISIBLE
        }
        fun preset(p: Tariff) {
            r1s.setText(hr(p.r1s)); r1e.setText(hr(p.r1e)); r2s.setText(hr(p.r2s)); r2e.setText(hr(p.r2e))
            we.isChecked = p.weekend
        }
        findViewById<View>(R.id.tarPresetWal).setOnClickListener { preset(Tariffs.WALLONIE) }
        findViewById<View>(R.id.tarPresetClassic).setOnClickListener { preset(Tariffs.CLASSIQUE) }

        findViewById<Button>(R.id.tarSave).setOnClickListener {
            fun h(e: EditText): Int = e.text.toString().trim().toIntOrNull()?.takeIf { it in 0..24 }?.let { it % 24 } ?: -1
            val d = Tariffs.load(this)
            val t = Tariff(
                bi.isChecked, h(r1s), h(r1e), h(r2s), h(r2e), we.isChecked,
                num(hp) ?: d.hp, num(hc) ?: d.hc, num(mp) ?: d.mono, num(inj) ?: d.inj
            )
            Tariffs.save(this, t)
            fill(t)
            out.visibility = View.VISIBLE
            out.text = "✓ Tarif enregistré : les coûts et les conseils sont recalculés."
            show()
            SolarWidget.refreshAll(this)
        }
    }

    // ---------- Rappels ----------

    private fun setupReminders() {
        val rows = listOf(
            Triple(Reminders.MORNING, R.id.remMorningOn, R.id.remMorningTime),
            Triple(Reminders.EVENING, R.id.remEveningOn, R.id.remEveningTime)
        )
        for ((kind, swId, timeId) in rows) {
            val sw = findViewById<android.widget.Switch>(swId)
            val time = findViewById<TextView>(timeId)
            fun label() {
                time.text = String.format(fr, "%02d:%02d", Reminders.hour(this, kind), Reminders.minute(this, kind)) + "  ✎"
            }
            label()
            sw.isChecked = Reminders.enabled(this, kind)
            sw.setOnCheckedChangeListener { _, on ->
                Reminders.set(this, kind, on, Reminders.hour(this, kind), Reminders.minute(this, kind))
                if (on && Reminders.needsPermission(this)) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
                }
            }
            time.setOnClickListener {
                android.app.TimePickerDialog(this, { _, h, m ->
                    Reminders.set(this, kind, true, h, m)
                    sw.isChecked = true
                    label()
                    if (Reminders.needsPermission(this)) {
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
                    }
                }, Reminders.hour(this, kind), Reminders.minute(this, kind), true).show()
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 7 && results.isNotEmpty() && results[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            status.text = "Notifications refusées : active-les dans les réglages Android pour recevoir les rappels."
        }
    }

    // ---------- Mise en forme ----------

    private fun f1(v: Double) = String.format(fr, "%.1f", v)

    private fun eur(v: Double) = String.format(fr, "%.2f €", v)

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
        renderShade()
        renderDiag()
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
            tip.visibility = View.GONE
            renderAppliances()
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

        status.text = "Mis à jour à ${Store.updatedAt(this)} · correction ×${String.format(fr, "%.2f", Forecast.factorFor(c, today))}"
        val h = Appliances.headline(c, Tariffs.load(this), fut, Appliances.all(this))
        tip.visibility = if (h == null) View.GONE else View.VISIBLE
        tip.text = "💡 ${h ?: ""}"
        renderAppliances()
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

    // ---------- Que lancer ? ----------

    private fun dayLabel(i: Int, date: String): String = when (i) {
        0 -> "Aujourd'hui"
        1 -> "Demain"
        else -> LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE d", fr))
            .replace(".", "").replaceFirstChar { it.uppercase() }
    }

    private fun renderAppliances() {
        val c = Store.load(this)
        val today = LocalDate.now().toString()
        val fut = Store.days(this).filter { it.date >= today }
        val dp = resources.displayMetrics.density
        appDays.removeAllViews()
        appList.removeAllViews()
        if (fut.isEmpty()) {
            appSummary.text = "Les conseils apparaîtront une fois la météo chargée."
            appManage.visibility = View.GONE
            return
        }
        appManage.visibility = View.VISIBLE
        if (appDay >= fut.size) appDay = 0

        // Sélecteur de jour
        fut.forEachIndexed { i, d ->
            val chip = TextView(this).apply {
                text = "${sky(d)} ${dayLabel(i, d.date)}"
                textSize = 13f
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                setBackgroundResource(if (i == appDay) R.drawable.chip_on else R.drawable.chip_off)
                setTextColor(getColor(if (i == appDay) R.color.ink else R.color.fg))
                setOnClickListener { appDay = i; renderAppliances() }
            }
            appDays.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (i > 0) marginStart = (8 * dp).toInt() })
        }

        val d = fut[appDay]
        val all = Appliances.all(this)
        val surplus = Appliances.surplus(c, d)
        val combo = Appliances.combo(c, d, all)
        val win = Appliances.bestWindow(c, d)
        val sb = StringBuilder()
        sb.append("☀ Surplus solaire prévu : ${f1(surplus)} kWh (après ${f1(c.conso)} kWh de conso habituelle)")
        if (win != null && surplus > 0.2) {
            sb.append("\n🕐 Meilleur moment : ${win.first} h – ${win.first + 2} h")
        }
        sb.append(
            if (combo.isEmpty()) "\nPas assez de soleil pour lancer un gros appareil sans tirer sur le réseau ou la batterie."
            else "\nDe quoi faire tourner : " + combo.joinToString(", ") { it.name }
        )
        appSummary.text = sb.toString()

        if (manageAll) {
            appManage.text = "Terminé ▴"
            appSummary.text = "Touche un appareil pour l'activer ou modifier sa consommation."
            all.forEach { addApplianceRow(it, null, fut, dp) }
        } else {
            appManage.text = "Gérer mes appareils ▾"
            val recos = Appliances.advise(c, Tariffs.load(this), fut, appDay, all)
            if (recos.isEmpty()) {
                appList.addView(TextView(this).apply {
                    text = "Aucun appareil sélectionné."
                    setTextColor(getColor(R.color.muted)); textSize = 13f
                    setPadding(0, (10 * dp).toInt(), 0, 0)
                })
            }
            recos.forEach { addApplianceRow(it.app, it, fut, dp) }
        }
    }

    private fun addApplianceRow(app: Appliance, r: Reco?, fut: List<Day>, dp: Float) {
        appList.addView(View(this).apply { setBackgroundColor(getColor(R.color.line)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1))
                .apply { topMargin = (8 * dp).toInt() })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, 0)
            background = getDrawable(android.R.drawable.list_selector_background)
            setOnClickListener { editAppliance(app) }
            alpha = if (r == null && !app.on) 0.45f else 1f
        }
        row.addView(TextView(this).apply {
            text = app.emoji; textSize = 22f; gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams((40 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = app.name; setTextColor(getColor(R.color.fg)); textSize = 14f
        })
        val detail = StringBuilder("${f1(app.kwh)} kWh · ${f1(app.kw)} kW")
        if (r != null) {
            val slot = Appliances.slotLabel(fut, r)
            detail.append("\n")
            when {
                r.advice == Advice.YES -> detail.append("☀ $slot")
                r.planOffPeak -> detail.append("🌙 Heures creuses $slot")
                r.betterDay != null -> detail.append("☀ Mieux $slot")
                else -> detail.append("≈ ${(r.coverage * 100).roundToInt()} % solaire, $slot")
            }
            detail.append(" · ≈ ${eur(r.cost)}")
            if (r.costPeak - r.cost > 0.05) detail.append(" au lieu de ${eur(r.costPeak)}")
        }
        if (r == null) detail.append(if (app.on) " · activé" else " · désactivé")
        texts.addView(TextView(this).apply {
            text = detail; setTextColor(getColor(R.color.muted)); textSize = 12f
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (r != null) {
            val (label, bg, col) = when (r.advice) {
                Advice.YES -> Triple("✓ Oui", R.drawable.pill_good, R.color.batt)
                Advice.PARTIAL -> Triple("En partie", R.drawable.pill_low, R.color.sun)
                Advice.LATER -> Triple("À reporter", R.drawable.pill_bad, R.color.bad)
                Advice.OFFPEAK -> Triple("Heures creuses", R.drawable.pill_night, R.color.night)
            }
            row.addView(TextView(this).apply {
                text = label; textSize = 12f
                setTextColor(getColor(col)); setBackgroundResource(bg)
                setPadding((10 * dp).toInt(), (5 * dp).toInt(), (10 * dp).toInt(), (5 * dp).toInt())
            })
        } else {
            row.addView(TextView(this).apply {
                text = if (app.on) "✓" else "+"; textSize = 18f
                setTextColor(getColor(if (app.on) R.color.batt else R.color.muted))
                setPadding((10 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            })
        }
        appList.addView(row)
    }

    private fun editAppliance(app: Appliance) {
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(R.layout.dialog_appliance)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT)
        val def = Appliances.default(app.id)
        dlg.findViewById<TextView>(R.id.aTitle).text = "${app.emoji}  ${app.name}"
        dlg.findViewById<TextView>(R.id.aInfo).text =
            "Puissance typique ${f1(app.kw)} kW · valeur moyenne ${f1(def?.kwh ?: app.kwh)} kWh par utilisation"
        val kwhField = dlg.findViewById<EditText>(R.id.aKwh)
        kwhField.setText(fmtField(app.kwh))
        val sw = dlg.findViewById<android.widget.Switch>(R.id.aOn)
        sw.isChecked = app.on
        dlg.findViewById<Button>(R.id.aReset).setOnClickListener {
            kwhField.setText(fmtField(def?.kwh ?: app.kwh))
        }
        dlg.findViewById<Button>(R.id.aSave).setOnClickListener {
            val v = num(kwhField)?.takeIf { it >= 0 } ?: app.kwh
            Appliances.save(this, app.id, v, sw.isChecked)
            show()
            SolarWidget.refreshAll(this)
            dlg.dismiss()
        }
        dlg.show()
    }

    // ---------- Ombres ----------

    private fun afterShadeChange() {
        fillFields()
        show()
        SolarWidget.refreshAll(this)
    }

    private fun renderShade() {
        val c = Store.load(this)
        val obs = Store.obstacles(this)
        horizon.set(c.lat, c.lon, obs)
        obsList.removeAllViews()
        if (obs.isEmpty()) {
            shadeImpact.text = "Aucun obstacle encodé : l'appli considère que rien ne fait d'ombre à tes panneaux."
            return
        }
        val y = LocalDate.now().year
        fun pct(m: Int, d: Int) = (Shading.directLoss(c, obs, LocalDate.of(y, m, d)) * 100).roundToInt()
        val today = LocalDate.now().toString()
        val raw = Store.days(this, false).find { it.date == today }
        val sh = Store.days(this).find { it.date == today }
        val sb = StringBuilder("Soleil direct perdu par ciel clair :\n21 déc. −${pct(12, 21)} %  ·  21 mars −${pct(3, 20)} %  ·  21 juin −${pct(6, 21)} %")
        if (raw != null && sh != null) {
            val lost = Forecast.est(c, raw) - Forecast.est(c, sh)
            if (lost > 0.05) sb.append("\nAujourd'hui : −${f1(lost)} kWh sur la prévision.")
        }
        shadeImpact.text = sb.toString()

        val dp = resources.displayMetrics.density
        obs.forEachIndexed { i, o ->
            obsList.addView(View(this).apply { setBackgroundColor(getColor(R.color.line)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1))
                    .apply { topMargin = (8 * dp).toInt() })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (8 * dp).toInt(), 0, 0)
            }
            row.addView(TextView(this).apply {
                text = if (o.leafy) "🌳" else "🏠"; textSize = 20f; gravity = android.view.Gravity.CENTER
            }, LinearLayout.LayoutParams((36 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val mid = ((o.az1 + Shading.span(o) / 2) % 360).roundToInt()
            texts.addView(TextView(this).apply {
                text = (if (o.leafy) "Arbre (feuilles caduques)" else "Bâtiment ou arbre persistant") +
                    " · ${MeasureDialog.cardinal(mid)}"
                setTextColor(getColor(R.color.fg)); textSize = 14f
            })
            texts.addView(TextView(this).apply {
                text = "De ${o.az1.roundToInt()}° à ${o.az2.roundToInt()}° · hauteur ${o.el.roundToInt()}°"
                setTextColor(getColor(R.color.muted)); textSize = 12f
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "✕"; textSize = 16f; gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.muted))
                setBackgroundResource(R.drawable.round_btn)
                contentDescription = "Supprimer cet obstacle"
                setOnClickListener {
                    Store.saveObstacles(this@MainActivity, Store.obstacles(this@MainActivity).filterIndexed { k, _ -> k != i })
                    afterShadeChange()
                }
            }, LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()))
            obsList.addView(row)
        }
    }

    // ---------- Diagnostic ----------

    private fun ratio(l: List<Calib>): Double {
        val b = l.sumOf { it.base }
        return if (b > 0) l.sumOf { it.real } / b else 0.0
    }

    private fun renderDiag() {
        val c = Store.load(this)
        val list = c.calibs.take(Store.CALIB_WINDOW)
        val kwc = c.p1 + c.p2
        val today = LocalDate.now().toString()
        diagCauses.removeAllViews()

        val (fNow, nSeason) = Forecast.seasonInfo(c, today)
        diagSeason.text = when {
            nSeason > 0 -> "Correction appliquée en ce moment : ×${String.format(fr, "%.2f", fNow)}, calculée sur $nSeason jour${if (nSeason > 1) "s" else ""} de la même saison (±45 jours)."
            c.calibs.isNotEmpty() -> "Correction appliquée : ×${String.format(fr, "%.2f", fNow)}, moyenne de tous tes jours. Encode au moins ${Store.SEASON_MIN} jours dans la saison en cours pour une correction propre à la saison."
            else -> ""
        }

        if (list.isEmpty() || kwc <= 0) {
            diagStatus.setBackgroundResource(R.drawable.pill_low)
            diagStatus.setTextColor(getColor(R.color.sun))
            diagStatus.text = "Pas encore de diagnostic"
            diagText.text = "Encode ta production réelle pendant quelques jours : l'appli comparera avec ce qu'elle attendait et cherchera les causes d'un écart."
            return
        }

        val r = ratio(list)
        val n = list.size
        val realKwc = list.sumOf { it.real } / n / kwc
        val expKwc = list.sumOf { it.base } / n / kwc
        diagText.text = "Sur tes $n dernier${if (n > 1) "s" else ""} jour${if (n > 1) "s" else ""} encodé${if (n > 1) "s" else ""} : " +
            "${String.format(fr, "%.2f", realKwc)} kWh par kWc installé en moyenne, pour ${String.format(fr, "%.2f", expKwc)} attendus, " +
            "soit ${(r * 100).roundToInt()} % de la prévision."
        when {
            r >= 0.9 -> {
                diagStatus.setBackgroundResource(R.drawable.pill_good); diagStatus.setTextColor(getColor(R.color.batt))
                diagStatus.text = "✓ Production conforme aux prévisions"
            }
            r >= 0.7 -> {
                diagStatus.setBackgroundResource(R.drawable.pill_low); diagStatus.setTextColor(getColor(R.color.sun))
                diagStatus.text = "Un peu en dessous des prévisions"
            }
            else -> {
                diagStatus.setBackgroundResource(R.drawable.pill_bad); diagStatus.setTextColor(getColor(R.color.bad))
                diagStatus.text = "Nettement en dessous des prévisions"
            }
        }

        val causes = ArrayList<Pair<String, String>>()
        if (n >= 6) {
            val rr = ratio(list.take(3)); val ro = ratio(list.drop(3))
            if (ro > 0 && rr < ro * 0.75) {
                causes.add("📉" to "Baisse récente : tes 3 derniers jours sont ${((1 - rr / ro) * 100).roundToInt()} % sous les précédents. Regarde l'appli de l'onduleur : message d'erreur, panneau ou micro-onduleur en défaut.")
            }
        }
        if (r > 1.2) {
            causes.add("🔎" to "Tu produis plus que prévu : vérifie la puissance (kWc), l'orientation et l'inclinaison encodées dans Paramètres.")
        }
        if (r < 0.9) {
            val obs = Store.obstacles(this)
            causes.add("🌳" to if (obs.isEmpty())
                "Ombres : aucun obstacle encodé. Un arbre ou une maison voisine peut couper une grande partie de la production en hiver, quand le soleil est bas. Relève-les dans Paramètres › Ombres."
            else "Ombres : ${obs.size} obstacle${if (obs.size > 1) "s" else ""} pris en compte. S'il en manque (cheminée, antenne, haie, maison voisine), ajoute-les.")
            causes.add("🧽" to if (c.tilt < 15)
                "Saleté : avec une inclinaison faible (${c.tilt.roundToInt()}°), la pluie nettoie mal. Nettoie les panneaux, puis compare quelques jours avant et après."
            else "Saleté : pollen, fientes, mousse, poussière d'un champ ou d'une route. Nettoie les panneaux, puis compare quelques jours avant et après.")
            causes.add("🧭" to "Orientation et inclinaison : vérifie-les avec la boussole 🧭 et l'inclinomètre 📐 dans Paramètres. Une erreur de 90° fausse fortement la prévision.")
            causes.add("🔋" to "Batterie pleine et injection bridée : si ton onduleur limite l'injection sur le réseau, il réduit la production dès que la batterie est pleine. Encode plutôt des jours où elle n'était pas pleine.")
            causes.add("⚡" to "Panne partielle : si une chaîne de panneaux ne produit plus, la production plafonne même par grand soleil. Compare la production par chaîne ou par panneau dans l'appli de l'onduleur.")
            if (LocalDate.now().monthValue in listOf(11, 12, 1, 2)) {
                causes.add("❄️" to "Givre ou neige : en hiver, les panneaux peuvent rester couverts une partie de la matinée.")
            }
        } else if (causes.isEmpty()) {
            causes.add("👍" to "Continue d'encoder ta production de temps en temps, surtout aux changements de saison, pour garder des prévisions justes.")
        }

        val dp = resources.displayMetrics.density
        causes.forEach { (icon, txt) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (10 * dp).toInt(), 0, 0)
            }
            row.addView(TextView(this).apply { text = icon; textSize = 18f },
                LinearLayout.LayoutParams((34 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply {
                text = txt; textSize = 13f; setTextColor(getColor(R.color.fg)); setLineSpacing(0f, 1.15f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            diagCauses.addView(row)
        }
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
        val value = dlg.findViewById<EditText>(R.id.lValue)
        var syncing = false
        seek.progress = Store.level(this)?.first?.roundToInt() ?: 50
        value.setText(seek.progress.toString())
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar, v: Int, fromUser: Boolean) {
                if (syncing) return
                syncing = true
                value.setText(v.toString())
                value.setSelection(value.text.length)
                syncing = false
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
        })
        value.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                if (syncing) return
                val v = e?.toString()?.toIntOrNull() ?: return
                syncing = true
                seek.progress = v.coerceIn(0, 100)
                syncing = false
            }
        })
        fun current(): Int = (value.text.toString().toIntOrNull() ?: seek.progress).coerceIn(0, 100)
        fun saveLevel() {
            Store.setLevel(this, current().toDouble()); show(); SolarWidget.refreshAll(this); dlg.dismiss()
        }
        value.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { saveLevel(); true } else false
        }
        dlg.findViewById<Button>(R.id.lMinus).setOnClickListener { seek.progress = (current() - 5).coerceAtLeast(0) }
        dlg.findViewById<Button>(R.id.lPlus).setOnClickListener { seek.progress = (current() + 5).coerceAtMost(100) }
        dlg.findViewById<Button>(R.id.lClear).setOnClickListener {
            Store.clearLevel(this); show(); SolarWidget.refreshAll(this); dlg.dismiss()
        }
        dlg.findViewById<Button>(R.id.lSave).setOnClickListener { saveLevel() }
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        value.requestFocus()
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
        fillFields()
        placeOut.text = "✓ Réglages enregistrés, prévisions recalculées."
        refresh()
    }
}
