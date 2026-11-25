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

    private val shortLabels = if (L.nl) linkedMapOf(
        "p1" to "Veld 1 (kWp)", "a1" to "Oriëntatie 1 (°)",
        "p2" to "Veld 2 (kWp)", "a2" to "Oriëntatie 2 (°)",
        "tilt" to "Hellingshoek (°)", "bat" to "Batterij (kWh)",
        "conso" to "Dagverbruik (kWh)", "corr" to "Correctie (×)",
        "lat" to "Breedtegraad", "lon" to "Lengtegraad"
    ) else linkedMapOf(
        "p1" to "Champ 1 (kWc)", "a1" to "Orientation 1 (°)",
        "p2" to "Champ 2 (kWc)", "a2" to "Orientation 2 (°)",
        "tilt" to "Inclinaison (°)", "bat" to "Batterie (kWh)",
        "conso" to "Conso journée (kWh)", "corr" to "Correction (×)",
        "lat" to "Latitude", "lon" to "Longitude"
    )
    private val fields = LinkedHashMap<String, EditText>()
    private val fr get() = L.loc

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
    private lateinit var homeAlert: TextView
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
        homeAlert = findViewById(R.id.homeAlert)
        homeAlert.setOnClickListener { showTab(1) }
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
        Pro.onChange = { applyPro(); fillFields(); show(); SolarWidget.refreshAll(this) }
        Pro.start(this)
        findViewById<View>(R.id.proBadge).setOnClickListener { Pro.showPaywall(this) }
        setupProCard()
        applyPro()
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

    // ---------- Version Pro ----------

    private val lockSaved = HashMap<View, Int>()

    /** Masque le contenu d'une carte (sauf son titre) et affiche une invitation à passer à Pro. */
    private fun lockCard(id: Int, locked: Boolean, teaser: String) {
        val card = findViewById<LinearLayout>(id)
        val existing = (0 until card.childCount).map { card.getChildAt(it) }.filter { it.tag == "lockui" }
        if (!locked) {
            existing.forEach { card.removeView(it) }
            (1 until card.childCount).map { card.getChildAt(it) }.forEach { v ->
                lockSaved.remove(v)?.let { v.visibility = it }
            }
            return
        }
        if (existing.isNotEmpty()) return
        (1 until card.childCount).map { card.getChildAt(it) }.forEach { v ->
            lockSaved[v] = v.visibility
            v.visibility = View.GONE
        }
        val dp = resources.displayMetrics.density
        card.addView(TextView(this).apply {
            tag = "lockui"
            text = "🔒 $teaser"
            setTextColor(getColor(R.color.muted)); textSize = 13f; setLineSpacing(0f, 1.15f)
            setPadding(0, (6 * dp).toInt(), 0, 0)
        })
        card.addView(Button(this, null, 0, R.style.PrimaryButton).apply {
            tag = "lockui"
            text = L.t("⭐ Découvrir Pro", "⭐ Ontdek Pro")
            setOnClickListener { Pro.showPaywall(this@MainActivity) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt()).apply { topMargin = (12 * dp).toInt() })
    }

    private fun applyPro() {
        val pro = Pro.isPro(this)
        findViewById<View>(R.id.proBadge).visibility = if (pro) View.GONE else View.VISIBLE
        findViewById<View>(R.id.batteryBox).visibility = if (pro) View.VISIBLE else View.GONE
        lockCard(R.id.cardApps, !pro, L.t(
            "Quels appareils lancer aujourd'hui, à quelle heure et pour combien : conseils par appareil avec ton tarif.",
            "Welke toestellen vandaag aanzetten, op welk uur en voor hoeveel: advies per toestel met jouw tarief."))
        lockCard(R.id.cardDiag, !pro, L.t(
            "Compare ta production à ce qu'elle devrait être et reçois des conseils quand elle est trop basse.",
            "Vergelijk je productie met wat ze zou moeten zijn en krijg advies als ze te laag is."))
        lockCard(R.id.cardRem, !pro, L.t(
            "Un rappel le matin pour la batterie et le soir pour la production.",
            "Een herinnering 's ochtends voor de batterij en 's avonds voor de productie."))
        lockCard(R.id.cardTar, !pro, L.t(
            "Encode ton tarif (bi-horaire, heures creuses, tarif capacitaire) pour connaître le coût de chaque appareil.",
            "Geef je tarief in (dag/nacht, daluren, capaciteitstarief) om de kost van elk toestel te kennen."))
        lockCard(R.id.cardShade, !pro, L.t(
            "Relève les arbres et bâtiments qui font de l'ombre : la prévision en tient compte selon la saison.",
            "Meet de bomen en gebouwen op die schaduw werpen: de voorspelling houdt er rekening mee per seizoen."))
        // Version gratuite : un seul champ de panneaux
        listOf("p2", "a2").forEach { k ->
            val f = fields[k]
            if (f != null) {
                f.isEnabled = pro
                val holder = f.parent as? LinearLayout
                if (holder != null && holder.orientation == LinearLayout.HORIZONTAL) {
                    holder.alpha = if (pro) 1f else 0.4f
                    for (j in 0 until holder.childCount) holder.getChildAt(j).isEnabled = pro
                } else {
                    f.alpha = if (pro) 1f else 0.4f
                }
            }
        }
        if (!pro && placeOut.text.isNullOrEmpty()) {
            placeOut.text = L.t("Version gratuite : un seul champ de panneaux. Si tu as deux orientations, encode toute ta puissance dans le champ 1 ou passe à Pro.",
                "Gratis versie: één panelenveld. Heb je twee oriëntaties, geef dan je totale vermogen in veld 1 in of ga naar Pro.")
        }
        updateProCard()
    }

    private fun setupProCard() {
        findViewById<Button>(R.id.proBtn).setOnClickListener { Pro.showPaywall(this) }
        findViewById<TextView>(R.id.proManage).setOnClickListener {
            if (Pro.isPro(this) && !Pro.isBeta()) {
                runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(Pro.MANAGE_URL))) }
            } else {
                Pro.start(this); Pro.refresh()
            }
        }
        val sim = findViewById<android.widget.CheckBox>(R.id.proSimFree)
        sim.visibility = if (Pro.isBeta()) View.VISIBLE else View.GONE
        sim.text = L.t("Mode test : simuler la version gratuite", "Testmodus: gratis versie simuleren")
        sim.isChecked = Pro.simFree(this)
        sim.setOnCheckedChangeListener { _, v -> Pro.setSimFree(this, v) }
    }

    private fun updateProCard() {
        val pro = Pro.isPro(this)
        val title = findViewById<TextView>(R.id.proTitle)
        val text = findViewById<TextView>(R.id.proText)
        val btn = findViewById<Button>(R.id.proBtn)
        val manage = findViewById<TextView>(R.id.proManage)
        if (pro) {
            title.text = L.t("⭐ Pro actif", "⭐ Pro actief")
            text.text = if (Pro.isBeta()) L.t("Version de test : toutes les fonctions Pro sont débloquées.", "Testversie: alle Pro-functies zijn ontgrendeld.")
                else L.t("Merci pour ton soutien ! Ton abonnement se gère dans Google Play.", "Bedankt voor je steun! Je abonnement beheer je in Google Play.")
            btn.visibility = View.GONE
            manage.visibility = if (Pro.isBeta()) View.GONE else View.VISIBLE
            manage.text = L.t("Gérer l'abonnement", "Abonnement beheren")
        } else {
            title.text = L.t("Version gratuite", "Gratis versie")
            text.text = L.t(
                "Passe à Pro pour la batterie, les conseils d'appareils, les tarifs, les ombres, le diagnostic, deux champs de panneaux et les prévisions sur 7 jours. 14 jours d'essai gratuit, puis ${Pro.price()} par an.",
                "Ga naar Pro voor de batterij, het toestellenadvies, de tarieven, de schaduw, de diagnose, twee panelenvelden en de voorspelling voor 7 dagen. 14 dagen gratis, daarna ${Pro.price()} per jaar.")
            btn.visibility = View.VISIBLE
            btn.text = L.t("⭐ Découvrir Pro", "⭐ Ontdek Pro")
            manage.visibility = View.VISIBLE
            manage.text = L.t("Restaurer mes achats", "Aankopen herstellen")
        }
    }

    override fun onResume() {
        super.onResume()
        Pro.refresh()
    }

    override fun onDestroy() {
        if (Pro.onChange != null) Pro.onChange = null
        super.onDestroy()
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
        val capa = findViewById<android.widget.CheckBox>(R.id.tarCapa)

        fun hr(v: Int) = if (v < 0) "" else v.toString()
        fun price(v: Double) = String.format(fr, "%.2f", v)
        fun fill(t: Tariff) {
            bi.isChecked = t.bi; mono.isChecked = !t.bi
            biBox.visibility = if (t.bi) View.VISIBLE else View.GONE
            monoBox.visibility = if (t.bi) View.GONE else View.VISIBLE
            r1s.setText(hr(t.r1s)); r1e.setText(hr(t.r1e)); r2s.setText(hr(t.r2s)); r2e.setText(hr(t.r2e))
            we.isChecked = t.weekend
            hp.setText(price(t.hp)); hc.setText(price(t.hc)); mp.setText(price(t.mono)); inj.setText(price(t.inj))
            capa.isChecked = t.capa
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
        findViewById<View>(R.id.tarPresetWal).setOnClickListener { preset(Tariffs.WALLONIE); capa.isChecked = false }
        findViewById<View>(R.id.tarPresetClassic).setOnClickListener { preset(Tariffs.CLASSIQUE); capa.isChecked = true }
        findViewById<View>(R.id.tarPresetFl21).setOnClickListener { preset(Tariffs.FLANDRE_21); capa.isChecked = true }

        findViewById<Button>(R.id.tarSave).setOnClickListener {
            fun h(e: EditText): Int = e.text.toString().trim().toIntOrNull()?.takeIf { it in 0..24 }?.let { it % 24 } ?: -1
            val d = Tariffs.load(this)
            val t = Tariff(
                bi.isChecked, h(r1s), h(r1e), h(r2s), h(r2e), we.isChecked,
                num(hp) ?: d.hp, num(hc) ?: d.hc, num(mp) ?: d.mono, num(inj) ?: d.inj,
                capa.isChecked
            )
            Tariffs.save(this, t)
            fill(t)
            out.visibility = View.VISIBLE
            out.text = L.t("✓ Tarif enregistré : les coûts et les conseils sont recalculés.", "✓ Tarief opgeslagen: kosten en adviezen worden herberekend.")
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
            status.text = L.t("Notifications refusées : active-les dans les réglages Android pour recevoir les rappels.", "Meldingen geweigerd: zet ze aan in de Android-instellingen om herinneringen te krijgen.")
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
                        contentDescription = if (key == "tilt") L.t("Mesurer l'inclinaison", "Hellingshoek meten") else L.t("Mesurer l'orientation", "Oriëntatie meten")
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
        val raw = Store.load(this, true)
        fields.forEach { (k, e) -> e.setText(fmtField(raw.m.getValue(k))) }
        subtitle.text = L.t("${place(c)} · ${f1(c.p1 + c.p2)} kWc · batterie ${f1(c.bat)} kWh", "${place(c)} · ${f1(c.p1 + c.p2)} kWp · batterij ${f1(c.bat)} kWh")
        placeOut.text = "📍 ${place(c)} (${String.format(fr, "%.4f, %.4f", c.lat, c.lon)})"
        renderCalibs()
        renderShade()
        renderDiag()
        settingsSummary.text = L.t("${f1(c.p1)} kWc à ${c.a1.roundToInt()}° + ${f1(c.p2)} kWc à ${c.a2.roundToInt()}°", "${f1(c.p1)} kWp op ${c.a1.roundToInt()}° + ${f1(c.p2)} kWp op ${c.a2.roundToInt()}°")
    }

    // ---------- Affichage ----------

    private fun show() {
        val c = Store.load(this)
        val today = LocalDate.now().toString()
        val fut = Store.days(this).filter { it.date >= today }
        days.removeAllViews()

        if (fut.isEmpty()) {
            kwh.text = "–"
            meta.text = L.t("En attente des prévisions", "Wachten op de voorspelling")
            battery.setLevel(0f)
            verdict.text = L.t("Touche ↻ pour charger la météo (internet requis)", "Tik op ↻ om het weer te laden (internet nodig)")
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
        meta.text = L.t("${sky(t)}  ${f1(t.sun)} h de soleil · ${t.cloud.roundToInt()} % de nuages", "${sky(t)}  ${f1(t.sun)} u zon · ${t.cloud.roundToInt()} % bewolking")
        val pro = Pro.isPro(this)
        val lvl = if (pro) Store.level(this) else null
        val end = lvl?.let { Forecast.endLevel(c, t, it.first / 100.0, it.second) }
        val good: Boolean
        if (!pro) {
            verdict.text = Forecast.freeVerdict(c, k)
            good = k / (c.p1 + c.p2).coerceAtLeast(0.1) >= 1.5
        } else if (lvl != null && end != null) {
            val start = lvl.first / 100.0
            battery.setLevel(end.toFloat())
            batteryLabel.text = L.t("ce soir · ✎", "vanavond · ✎")
            verdict.text = Forecast.levelVerdict(start, end)
            good = end > start + 0.01 || end >= 0.995
        } else {
            battery.setLevel(p.toFloat())
            batteryLabel.text = L.t("✎ Régler", "✎ Instellen")
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

        val shown = if (pro) fut else fut.take(2)
        shown.forEachIndexed { i, d ->
            if (i > 0) {
                days.addView(View(this).apply { setBackgroundColor(getColor(R.color.line)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1)))
            }
            val row = inf.inflate(R.layout.item_day, days, false)
            val date = LocalDate.parse(d.date)
            val e = Forecast.est(c, d)
            val bp = (Forecast.battPct(c, e) * 100).roundToInt()

            val name = when (i) {
                0 -> L.t("Auj.", "Vand.")
                1 -> L.t("Demain", "Morgen")
                else -> date.format(dayFmt).trimEnd('.').replaceFirstChar { it.uppercase() }
            }
            row.findViewById<TextView>(R.id.dName).apply {
                text = name
                setTextColor(getColor(if (i == 0) R.color.sun else R.color.fg))
            }
            row.findViewById<TextView>(R.id.dDate).text = date.format(dateFmt)
            row.findViewById<TextView>(R.id.dSky).text = sky(d)
            row.findViewById<ProgressBar>(R.id.dBar).progress = ((e / max) * 1000).roundToInt()
            row.findViewById<TextView>(R.id.dMeta).text = L.t("${f1(d.sun)} h de soleil", "${f1(d.sun)} u zon")
            row.findViewById<TextView>(R.id.dKwh).text = f1(e)
            row.findViewById<TextView>(R.id.dBatt).apply {
                text = if (pro) "🔋 $bp %" else ""
                setTextColor(getColor(if (bp > 0) R.color.batt else R.color.muted))
            }
            days.addView(row)
        }
        if (!pro && fut.size > 2) {
            days.addView(View(this).apply { setBackgroundColor(getColor(R.color.line)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1)))
            days.addView(TextView(this).apply {
                text = L.t("🔒 ${fut.size - 2} jours de plus avec Pro", "🔒 ${fut.size - 2} dagen meer met Pro")
                setTextColor(getColor(R.color.sun)); textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
                setOnClickListener { Pro.showPaywall(this@MainActivity) }
            })
        }

        status.text = L.t("Mis à jour à ", "Bijgewerkt om ") + Store.updatedAt(this) + L.t(" · correction ×", " · correctie ×") + String.format(fr, "%.2f", Forecast.factorFor(c, today))
        val h = if (pro) Appliances.headline(c, Tariffs.load(this), fut, Appliances.all(this)) else null
        tip.visibility = if (h == null) View.GONE else View.VISIBLE
        tip.text = "💡 ${h ?: ""}"
        renderAppliances()
    }

    // ---------- Actions ----------

    private fun refresh() {
        status.text = L.t("Chargement des prévisions…", "Voorspelling laden…")
        refreshBtn.animate().rotationBy(360f).setDuration(700).start()
        val act = this
        Thread {
            val r = runCatching {
                Store.saveDays(act, Forecast.fetch(Store.load(act)))
                Store.autoCalibrate(act)
            }
            runOnUiThread {
                if (r.isFailure) {
                    status.text = L.t("Météo indisponible. Vérifie la connexion internet.", "Weer niet beschikbaar. Controleer de internetverbinding.")
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
            placeOut.text = L.t("Entre une adresse ou une commune.", "Geef een adres of gemeente in.")
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(address.windowToken, 0)
        placeOut.text = L.t("Recherche de l'adresse…", "Adres zoeken…")
        val act = this
        Thread {
            val p = Geo.find(act, q)
            runOnUiThread {
                if (p == null) {
                    placeOut.text = L.t("Adresse introuvable. Essaie avec la commune et le code postal.", "Adres niet gevonden. Probeer met de gemeente en de postcode.")
                } else {
                    Store.setPlace(act, p)
                    address.setText("")
                    fillFields()
                    placeOut.text = "📍 ${p.name} — " + L.t("enregistré", "opgeslagen") + " (${String.format(fr, "%.4f, %.4f", p.lat, p.lon)})"
                    refresh()
                }
            }
        }.start()
    }

    // ---------- Que lancer ? ----------

    private fun dayLabel(i: Int, date: String): String = when (i) {
        0 -> L.t("Aujourd'hui", "Vandaag")
        1 -> L.t("Demain", "Morgen")
        else -> LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE d", fr))
            .replace(".", "").replaceFirstChar { it.uppercase() }
    }

    private fun renderAppliances() {
        if (!Pro.isPro(this)) return
        val c = Store.load(this)
        val today = LocalDate.now().toString()
        val fut = Store.days(this).filter { it.date >= today }
        val dp = resources.displayMetrics.density
        appDays.removeAllViews()
        appList.removeAllViews()
        if (fut.isEmpty()) {
            appSummary.text = L.t("Les conseils apparaîtront une fois la météo chargée.", "De adviezen verschijnen zodra het weer geladen is.")
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
        sb.append(L.t("☀ Surplus solaire prévu : ${f1(surplus)} kWh (après ${f1(c.conso)} kWh de conso habituelle)", "☀ Verwacht zonne-overschot: ${f1(surplus)} kWh (na ${f1(c.conso)} kWh gewoon verbruik)"))
        if (win != null && surplus > 0.2) {
            sb.append(L.t("\n🕐 Meilleur moment : ${win.first} h – ${win.first + 2} h", "\n🕐 Beste moment: ${win.first}u – ${win.first + 2}u"))
        }
        sb.append(
            if (combo.isEmpty()) L.t("\nPas assez de soleil pour lancer un gros appareil sans tirer sur le réseau ou la batterie.", "\nNiet genoeg zon om een groot toestel te laten draaien zonder net of batterij.")
            else L.t("\nDe quoi faire tourner : ", "\nGenoeg voor: ") + combo.joinToString(", ") { it.name } +
                (if (Tariffs.load(this).capa && combo.size > 1) L.t("\n⚡ Tarif capacitaire : lance-les l'un après l'autre pour ne pas augmenter ton pic mensuel.", "\n⚡ Capaciteitstarief: zet ze na elkaar aan om je maandpiek niet te verhogen.") else "")
        )
        appSummary.text = sb.toString()

        if (manageAll) {
            appManage.text = L.t("Terminé ▴", "Klaar ▴")
            appSummary.text = L.t("Touche un appareil pour l'activer ou modifier sa consommation.", "Tik op een toestel om het aan te zetten of het verbruik te wijzigen.")
            all.forEach { addApplianceRow(it, null, fut, dp) }
        } else {
            appManage.text = L.t("Gérer mes appareils ▾", "Mijn toestellen beheren ▾")
            val recos = Appliances.advise(c, Tariffs.load(this), fut, appDay, all)
            if (recos.isEmpty()) {
                appList.addView(TextView(this).apply {
                    text = L.t("Aucun appareil sélectionné.", "Geen toestel geselecteerd.")
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
                r.planOffPeak -> detail.append(L.t("🌙 Heures creuses $slot", "🌙 Daluren $slot"))
                r.betterDay != null -> detail.append(L.t("☀ Mieux $slot", "☀ Beter $slot"))
                r.advice == Advice.LATER -> detail.append(L.t("Pas de bon jour d'ici 4 jours", "Geen goede dag binnen 4 dagen"))
                else -> detail.append(L.t("≈ ${(r.coverage * 100).roundToInt()} % solaire, $slot", "≈ ${(r.coverage * 100).roundToInt()} % zon, $slot"))
            }
            detail.append(" · ≈ ${eur(r.cost)}")
            if (r.costPeak - r.cost > 0.05) detail.append(L.t(" au lieu de ", " in plaats van ") + eur(r.costPeak))
        }
        if (r == null) detail.append(if (app.on) L.t(" · activé", " · aan") else L.t(" · désactivé", " · uit"))
        texts.addView(TextView(this).apply {
            text = detail; setTextColor(getColor(R.color.muted)); textSize = 12f
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (r != null) {
            val (label, bg, col) = when (r.advice) {
                Advice.YES -> Triple(L.t("✓ Oui", "✓ Ja"), R.drawable.pill_good, R.color.batt)
                Advice.PARTIAL -> Triple(L.t("En partie", "Gedeeltelijk"), R.drawable.pill_low, R.color.sun)
                Advice.LATER -> if (r.betterDay != null) Triple(L.t("À reporter", "Uitstellen"), R.drawable.pill_bad, R.color.bad)
                    else Triple(L.t("Peu de soleil", "Weinig zon"), R.drawable.pill_bad, R.color.bad)
                Advice.OFFPEAK -> Triple(L.t("Heures creuses", "Daluren"), R.drawable.pill_night, R.color.night)
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
            L.t("Puissance typique ${f1(app.kw)} kW · valeur moyenne ${f1(def?.kwh ?: app.kwh)} kWh par utilisation", "Typisch vermogen ${f1(app.kw)} kW · gemiddeld ${f1(def?.kwh ?: app.kwh)} kWh per gebruik")
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
            shadeImpact.text = L.t("Aucun obstacle encodé : l'appli considère que rien ne fait d'ombre à tes panneaux.", "Geen obstakels ingegeven: de app gaat ervan uit dat niets schaduw op je panelen werpt.")
            return
        }
        val y = LocalDate.now().year
        fun pct(m: Int, d: Int) = (Shading.directLoss(c, obs, LocalDate.of(y, m, d)) * 100).roundToInt()
        val today = LocalDate.now().toString()
        val raw = Store.days(this, false).find { it.date == today }
        val sh = Store.days(this).find { it.date == today }
        val sb = StringBuilder(L.t("Soleil direct perdu par ciel clair :\n21 déc. −${pct(12, 21)} %  ·  21 mars −${pct(3, 20)} %  ·  21 juin −${pct(6, 21)} %", "Verloren directe zon bij heldere hemel:\n21 dec. −${pct(12, 21)} %  ·  21 maart −${pct(3, 20)} %  ·  21 juni −${pct(6, 21)} %"))
        if (raw != null && sh != null) {
            val lost = Forecast.est(c, raw) - Forecast.est(c, sh)
            if (lost > 0.05) sb.append(L.t("\nAujourd'hui : −${f1(lost)} kWh sur la prévision.", "\nVandaag: −${f1(lost)} kWh op de voorspelling."))
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
                text = (if (o.leafy) L.t("Arbre (feuilles caduques)", "Loofboom") else L.t("Bâtiment ou arbre persistant", "Gebouw of groenblijvende boom")) +
                    " · ${MeasureDialog.cardinal(mid)}"
                setTextColor(getColor(R.color.fg)); textSize = 14f
            })
            texts.addView(TextView(this).apply {
                text = L.t("De ${o.az1.roundToInt()}° à ${o.az2.roundToInt()}° · hauteur ${o.el.roundToInt()}°", "Van ${o.az1.roundToInt()}° tot ${o.az2.roundToInt()}° · hoogte ${o.el.roundToInt()}°")
                setTextColor(getColor(R.color.muted)); textSize = 12f
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "✕"; textSize = 16f; gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.muted))
                setBackgroundResource(R.drawable.round_btn)
                contentDescription = L.t("Supprimer cet obstacle", "Obstakel verwijderen")
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
        homeAlert.visibility = View.GONE
        if (!Pro.isPro(this)) return

        val (fNow, nSeason) = Forecast.seasonInfo(c, today)
        diagSeason.text = when {
            nSeason > 0 -> if (L.nl) "Huidige correctie: ×${String.format(fr, "%.2f", fNow)}, berekend op $nSeason ${if (nSeason > 1) "dagen" else "dag"} uit hetzelfde seizoen (±45 dagen)."
                else "Correction appliquée en ce moment : ×${String.format(fr, "%.2f", fNow)}, calculée sur $nSeason jour${if (nSeason > 1) "s" else ""} de la même saison (±45 jours)."
            c.calibs.isNotEmpty() -> L.t("Correction appliquée : ×${String.format(fr, "%.2f", fNow)}, moyenne de tous tes jours. Encode au moins ${Store.SEASON_MIN} jours dans la saison en cours pour une correction propre à la saison.", "Toegepaste correctie: ×${String.format(fr, "%.2f", fNow)}, gemiddelde van al je dagen. Geef minstens ${Store.SEASON_MIN} dagen in dit seizoen in voor een seizoenscorrectie.")
            else -> ""
        }

        if (list.isEmpty() || kwc <= 0) {
            diagStatus.setBackgroundResource(R.drawable.pill_low)
            diagStatus.setTextColor(getColor(R.color.sun))
            diagStatus.text = L.t("Pas encore de diagnostic", "Nog geen diagnose")
            diagText.text = L.t("Encode ta production réelle pendant quelques jours : l'appli comparera avec ce qu'elle attendait et cherchera les causes d'un écart.", "Geef enkele dagen je werkelijke productie in: de app vergelijkt met wat ze verwachtte en zoekt de oorzaken van een verschil.")
            return
        }

        val r = ratio(list)
        val n = list.size
        val realKwc = list.sumOf { it.real } / n / kwc
        val expKwc = list.sumOf { it.base } / n / kwc
        diagText.text = if (L.nl) "Over je laatste $n ingegeven ${if (n > 1) "dagen" else "dag"}: " +
            "gemiddeld ${String.format(fr, "%.2f", realKwc)} kWh per geïnstalleerde kWp, voor ${String.format(fr, "%.2f", expKwc)} verwacht, " +
            "of ${(r * 100).roundToInt()} % van de voorspelling."
        else "Sur tes $n dernier${if (n > 1) "s" else ""} jour${if (n > 1) "s" else ""} encodé${if (n > 1) "s" else ""} : " +
            "${String.format(fr, "%.2f", realKwc)} kWh par kWc installé en moyenne, pour ${String.format(fr, "%.2f", expKwc)} attendus, " +
            "soit ${(r * 100).roundToInt()} % de la prévision."
        when {
            r >= 0.9 -> {
                diagStatus.setBackgroundResource(R.drawable.pill_good); diagStatus.setTextColor(getColor(R.color.batt))
                diagStatus.text = L.t("✓ Production conforme aux prévisions", "✓ Productie volgens verwachting")
            }
            r >= 0.7 -> {
                diagStatus.setBackgroundResource(R.drawable.pill_low); diagStatus.setTextColor(getColor(R.color.sun))
                diagStatus.text = L.t("Un peu en dessous des prévisions", "Iets onder de verwachting")
            }
            else -> {
                diagStatus.setBackgroundResource(R.drawable.pill_bad); diagStatus.setTextColor(getColor(R.color.bad))
                diagStatus.text = L.t("Nettement en dessous des prévisions", "Duidelijk onder de verwachting")
            }
        }

        // Conseils : icône, titre, explication
        val tips = ArrayList<Triple<String, String, String>>()
        var drop = 0
        if (n >= 6) {
            val rr = ratio(list.take(3)); val ro = ratio(list.drop(3))
            if (ro > 0 && rr < ro * 0.75) {
                drop = ((1 - rr / ro) * 100).roundToInt()
                tips.add(Triple("📉", L.t("Baisse récente de $drop %", "Recente daling van $drop %"),
                    L.t("Tes 3 derniers jours sont nettement sous les précédents. Une baisse brutale vient rarement de la saleté : regarde d'abord l'écran ou l'appli de l'onduleur (message d'erreur, chaîne de panneaux à zéro).", "Je laatste 3 dagen liggen duidelijk lager dan de vorige. Een plotse daling komt zelden door vuil: kijk eerst op het scherm of in de app van de omvormer (foutmelding, string op nul).")))
            }
        }
        if (r > 1.2) {
            tips.add(Triple("🔎", L.t("Vérifie tes réglages", "Controleer je instellingen"),
                L.t("Tu produis plus que prévu : la puissance (kWc), l'orientation ou l'inclinaison encodées dans Paramètres sont peut-être sous-estimées.", "Je produceert meer dan verwacht: het vermogen (kWp), de oriëntatie of de hellingshoek in Instellingen zijn misschien te laag ingeschat.")))
        }
        if (r < 0.9) {
            val obs = Store.obstacles(this)
            tips.add(Triple("🧽", L.t("Nettoie les panneaux", "Reinig de panelen"),
                (if (c.tilt < 15) L.t("Avec une inclinaison faible (${c.tilt.roundToInt()}°), la pluie nettoie mal et la saleté s'accumule. ", "Bij een kleine hellingshoek (${c.tilt.roundToInt()}°) spoelt regen slecht en hoopt vuil zich op. ") else
                    L.t("Pollen, fientes, mousse ou poussière d'un champ ou d'une route peuvent faire perdre 5 à 15 %. ", "Stuifmeel, vogelpoep, mos of stof van een veld of weg kunnen 5 tot 15 % kosten. ")) +
                L.t("Utilise de l'eau claire (idéalement déminéralisée) et une brosse douce ou une raclette à manche télescopique, tôt le matin ou le soir quand les panneaux sont froids. ", "Gebruik zuiver water (liefst gedemineraliseerd) en een zachte borstel of een trekker met telescoopsteel, vroeg in de ochtend of 's avonds als de panelen koel zijn. ") +
                L.t("Pas de nettoyeur haute pression ni de détergent. Ne monte pas sur le toit : si l'accès est risqué, fais appel à un professionnel. ", "Geen hogedrukreiniger of zeep. Klim niet op het dak: is het onveilig, doe dan een beroep op een vakman. ") +
                L.t("Encode ensuite quelques jours pour comparer avant et après.", "Geef daarna enkele dagen in om voor en na te vergelijken.")))
            tips.add(Triple("🌳", if (obs.isEmpty()) L.t("Relève les ombres", "Meet de schaduw op") else L.t("Complète les ombres", "Vul de schaduw aan"),
                if (obs.isEmpty()) L.t("Aucun obstacle n'est encodé. En hiver, le soleil est bas : un arbre ou une maison voisine peut couper une grande partie de la production. Relève-les dans Paramètres › Ombres. Si l'arbre est chez toi, un élagage peut faire gagner beaucoup.", "Er zijn geen obstakels ingegeven. In de winter staat de zon laag: een boom of een buurhuis kan een groot deel van de productie wegnemen. Meet ze op in Instellingen › Schaduw. Staat de boom bij jou, dan kan snoeien veel opleveren.")
                else if (L.nl) "${obs.size} ${if (obs.size > 1) "obstakels" else "obstakel"} meegerekend. Ontbreekt er een (schoorsteen, antenne, haag, buurhuis), voeg het toe. Werpt een boom bij jou schaduw, dan kan snoeien veel opleveren."
                else "${obs.size} obstacle${if (obs.size > 1) "s" else ""} pris en compte. S'il en manque (cheminée, antenne, haie, maison voisine), ajoute-les. Si un arbre chez toi fait de l'ombre, un élagage peut faire gagner beaucoup."))
            tips.add(Triple("⚡", L.t("Contrôle l'onduleur", "Controleer de omvormer"),
                L.t("Regarde la production par chaîne ou par panneau dans l'appli de l'onduleur. Si une chaîne produit beaucoup moins que l'autre par grand soleil, un câble, un connecteur ou un optimiseur est peut-être en défaut : contacte ton installateur, c'est souvent sous garantie.", "Bekijk de productie per string of per paneel in de app van de omvormer. Produceert een string bij volle zon veel minder dan de andere, dan is misschien een kabel, connector of optimizer defect: contacteer je installateur, vaak valt dit onder garantie.")))
            tips.add(Triple("🧭", L.t("Vérifie l'orientation et l'inclinaison", "Controleer oriëntatie en hellingshoek"),
                L.t("Mesure-les avec la boussole 🧭 et l'inclinomètre 📐 dans Paramètres. Une erreur de 90° fausse fortement la prévision.", "Meet ze met het kompas 🧭 en de hellingsmeter 📐 in Instellingen. Een fout van 90° vertekent de voorspelling sterk.")))
            tips.add(Triple("🔋", L.t("Batterie pleine et injection bridée", "Volle batterij en beperkte injectie"),
                L.t("Si ton onduleur limite l'injection sur le réseau, il réduit la production dès que la batterie est pleine. Encode plutôt des jours où la batterie n'était pas pleine.", "Als je omvormer de injectie op het net beperkt, verlaagt hij de productie zodra de batterij vol is. Geef liever dagen in waarop de batterij niet vol was.")))
            if (LocalDate.now().monthValue in listOf(11, 12, 1, 2, 3)) {
                tips.add(Triple("❄️", L.t("Givre ou neige", "Rijp of sneeuw"),
                    L.t("En hiver, les panneaux peuvent rester couverts une partie de la matinée. Ne les gratte pas et n'y verse pas d'eau chaude : attends que ça fonde.", "In de winter kunnen de panelen een deel van de ochtend bedekt blijven. Krab ze niet en giet er geen warm water op: wacht tot het smelt.")))
            }
            if (r < 0.7) {
                tips.add(Triple("🛠️", L.t("Fais contrôler l'installation", "Laat de installatie nakijken"),
                    L.t("Si rien de tout ça n'explique l'écart, ton installateur peut mesurer chaque chaîne ou faire une thermographie pour repérer un panneau défectueux.", "Verklaart niets hiervan het verschil, dan kan je installateur elke string meten of een thermografie doen om een defect paneel op te sporen.")))
            }
        } else if (tips.isEmpty()) {
            tips.add(Triple("👍", L.t("Tout va bien", "Alles in orde"),
                L.t("Continue d'encoder ta production de temps en temps, surtout aux changements de saison, pour garder des prévisions justes. Un nettoyage par an (au printemps, après le pollen) suffit en général.", "Geef af en toe je productie in, vooral bij seizoenswissels, om de voorspellingen juist te houden. Eén reiniging per jaar (in de lente, na het stuifmeel) volstaat meestal.")))
        }

        // Bandeau sur l'accueil si la production est anormalement basse
        if ((n >= 3 && r < 0.8) || drop > 0) {
            homeAlert.visibility = View.VISIBLE
            homeAlert.text = if (drop > 0) L.t("⚠ Ta production a baissé de $drop % ces derniers jours. Touche pour voir les conseils.", "⚠ Je productie is de laatste dagen met $drop % gedaald. Tik voor de adviezen.")
                else L.t("⚠ Tes panneaux produisent ${(r * 100).roundToInt()} % de ce qu'ils devraient. Touche pour voir les conseils.", "⚠ Je panelen produceren ${(r * 100).roundToInt()} % van wat ze zouden moeten. Tik voor de adviezen.")
        }

        val dp = resources.displayMetrics.density
        tips.forEach { (icon, title, txt) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (12 * dp).toInt(), 0, 0)
            }
            row.addView(TextView(this).apply { text = icon; textSize = 18f },
                LinearLayout.LayoutParams((34 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = title; textSize = 14f; setTextColor(getColor(R.color.fg))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
            col.addView(TextView(this).apply {
                text = txt; textSize = 13f; setTextColor(getColor(R.color.muted)); setLineSpacing(0f, 1.15f)
                setPadding(0, (2 * dp).toInt(), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        calSummary.text = (if (L.nl) "Correctie ×${String.format(fr, "%.2f", f)} · gemiddelde over $n ${if (n > 1) "dagen" else "dag"}"
            else "Correction ×${String.format(fr, "%.2f", f)} · moyenne sur $n jour${if (n > 1) "s" else ""}") +
            if (n < 5) L.t("\nAjoute quelques jours de plus pour une correction fiable.", "\nVoeg nog enkele dagen toe voor een betrouwbare correctie.") else ""

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
                text = L.t("Réel ${f1(c.real)} kWh · prévu ${f1(c.base)} kWh", "Werkelijk ${f1(c.real)} kWh · verwacht ${f1(c.base)} kWh")
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
                contentDescription = L.t("Supprimer cette saisie", "Deze invoer verwijderen")
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
        placeOut.text = if (key == "tilt") L.t("Inclinaison enregistrée : ${v.roundToInt()}°", "Hellingshoek opgeslagen: ${v.roundToInt()}°")
            else L.t("Orientation enregistrée : ", "Oriëntatie opgeslagen: ") + "${v.roundToInt()}° (${MeasureDialog.cardinal(v.roundToInt())})"
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
            calOut.text = L.t("Choisis un jour de la semaine passée et entre une production.", "Kies een dag van de afgelopen week en geef een productie in.")
            return
        }
        val base = Forecast.base(Store.load(this), d)
        if (base <= 0.0) {
            calOut.text = L.t("Pas de rayonnement prévu ce jour-là, choisis un autre jour.", "Geen zoninstraling voorspeld die dag, kies een andere dag.")
            return
        }
        Store.addCalib(this, Calib(date, value, base))
        val n = Store.calibs(this).size.coerceAtMost(Store.CALIB_WINDOW)
        val dayPct = (value / base * 100).roundToInt()
        calOut.text = if (dayPct < 60)
            L.t("Enregistré. Ce jour-là, tu as produit $dayPct % de la prévision. Si le ciel était dégagé, regarde les conseils du diagnostic ci-dessus.", "Opgeslagen. Die dag produceerde je $dayPct % van de voorspelling. Was de hemel helder, bekijk dan de adviezen van de diagnose hierboven.")
        else if (L.nl) "Opgeslagen. Correctie berekend op $n ${if (n > 1) "dagen" else "dag"}."
        else "Enregistré. Correction calculée sur $n jour${if (n > 1) "s" else ""}."
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
        placeOut.text = L.t("✓ Réglages enregistrés, prévisions recalculées.", "✓ Instellingen opgeslagen, voorspellingen herberekend.")
        refresh()
    }
}
