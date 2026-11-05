package be.florent.soleil

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var status: TextView
    private lateinit var out: TextView
    private lateinit var calDate: EditText
    private lateinit var calKwh: EditText
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = (16 * resources.displayMetrics.density).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 2)
        }

        heading(col, "Prévisions sur 7 jours")
        status = TextView(this).apply { textSize = 13f }
        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        col.addView(status)
        col.addView(out)
        col.addView(button("Actualiser") { refresh() })

        heading(col, "Calibrer avec la production réelle")
        col.addView(TextView(this).apply {
            text = "Entre ce que l'onduleur a vraiment produit un jour des 7 derniers jours. " +
                "L'appli corrige toutes ses estimations (ombrage, saleté, rendement réel)."
            textSize = 13f
        })
        calDate = field(col, "Date (AAAA-MM-JJ)", LocalDate.now().minusDays(1).toString(), true)
        calKwh = field(col, "Produit réel (kWh)", "", false)
        col.addView(button("Calibrer") { calibrate() })

        heading(col, "Mon installation")
        Store.DEFAULTS.keys.forEach { k ->
            fields[k] = field(col, Store.LABELS.getValue(k), "", false)
        }
        col.addView(button("Enregistrer et recalculer") { save() })

        setContentView(ScrollView(this).apply { addView(col) })
        fillFields()
        show()
        refresh()
        UpdateWorker.schedule(this)
    }

    private fun heading(col: LinearLayout, t: String) {
        col.addView(TextView(this).apply {
            text = t
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad, 0, pad / 2)
        })
    }

    private fun field(col: LinearLayout, label: String, value: String, isText: Boolean): EditText {
        col.addView(TextView(this).apply { text = label; textSize = 12f })
        val e = EditText(this).apply {
            setText(value)
            inputType = if (isText) InputType.TYPE_CLASS_TEXT
            else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        col.addView(e)
        return e
    }

    private fun button(t: String, action: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { action() }
    }

    private fun num(e: EditText): Double? = e.text.toString().trim().replace(',', '.').toDoubleOrNull()

    private fun fmt(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    private fun fillFields() {
        val c = Store.load(this)
        fields.forEach { (k, e) -> e.setText(fmt(c.m.getValue(k))) }
    }

    private fun show() {
        val c = Store.load(this)
        val days = Store.days(this)
        val today = LocalDate.now().toString()
        val fut = days.filter { it.date >= today }
        if (fut.isEmpty()) {
            status.text = "Pas encore de données. Appuie sur Actualiser."
            out.text = ""
            return
        }
        status.text = "Mis à jour à ${Store.updatedAt(this)} · correction ×${"%.2f".format(c.corr)}"
        val df = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)
        val lines = fut.joinToString("\n") { d ->
            val k = Forecast.est(c, d)
            val p = (Forecast.battPct(c, k) * 100).roundToInt()
            val name = LocalDate.parse(d.date).format(df)
            "%-11s %5.1f kWh  batt %3d %%".format(name, k, p)
        }
        out.text = lines + "\n\n" + Forecast.verdict(c, Forecast.est(c, fut[0]))
    }

    private fun refresh() {
        status.text = "Chargement des prévisions…"
        val act = this
        Thread {
            val r = runCatching {
                Store.saveDays(act, Forecast.fetch(Store.load(act)))
                Store.autoCalibrate(act)
            }
            runOnUiThread {
                if (r.isFailure) {
                    status.text = "Impossible de charger la météo. Vérifie la connexion internet."
                } else {
                    fillFields()
                    show()
                }
                SolarWidget.refreshAll(act)
            }
        }.start()
    }

    private fun calibrate() {
        val date = calDate.text.toString().trim()
        val kwh = num(calKwh)
        val d = Store.days(this).find { it.date == date }
        if (kwh == null || kwh <= 0.0 || d == null) {
            status.text = "Calibration impossible : date hors des 7 derniers jours ou valeur vide."
            return
        }
        val base = Forecast.base(Store.load(this), d)
        if (base <= 0.0) {
            status.text = "Calibration impossible pour ce jour (aucun rayonnement prévu)."
            return
        }
        Store.set(this, "corr", ((kwh / base) * 100).roundToInt() / 100.0)
        fillFields()
        show()
        SolarWidget.refreshAll(this)
    }

    private fun save() {
        val values = fields.mapValues { (k, e) -> num(e) ?: Store.DEFAULTS.getValue(k) }
        Store.save(this, values)
        refresh()
    }
}
