package be.florent.soleil

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Relevé d'un obstacle en 3 visées, téléphone tenu comme pour une photo :
 * bord gauche, bord droit, puis sommet.
 */
class ObstacleDialog(
    private val act: Activity,
    private val onDone: (Obstacle) -> Unit
) : SensorEventListener {

    private val sm = act.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val dlg = Dialog(act)
    private lateinit var step: TextView
    private lateinit var value: TextView
    private lateinit var sub: TextView
    private lateinit var capture: Button
    private lateinit var leafy: CheckBox
    private var az = Double.NaN
    private var el = Double.NaN
    private var decl = 0f
    private val rot = FloatArray(9)
    private var stage = 0
    private var az1 = 0.0
    private var az2 = 0.0

    private val steps = if (L.nl) listOf(
        "1/3 · Richt op de LINKERRAND van het obstakel (boom, huis…), met de telefoon rechtop zoals voor een foto, en tik op Vastleggen.",
        "2/3 · Richt nu op de RECHTERRAND van het obstakel en tik op Vastleggen.",
        "3/3 · Richt op de TOP van het obstakel (kruin van de boom, nok van het dak) en tik op Vastleggen."
    ) else listOf(
        "1/3 · Vise le BORD GAUCHE de l'obstacle (arbre, maison…), téléphone tenu debout comme pour une photo, puis touche Capturer.",
        "2/3 · Vise maintenant le BORD DROIT de l'obstacle, puis touche Capturer.",
        "3/3 · Vise le SOMMET de l'obstacle (la cime de l'arbre, le faîte du toit), puis touche Capturer."
    )
    private val buttons = if (L.nl) listOf("Linkerrand vastleggen", "Rechterrand vastleggen", "Top vastleggen")
        else listOf("Capturer le bord gauche", "Capturer le bord droit", "Capturer le sommet")

    fun show() {
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(R.layout.dialog_obstacle)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout(
            (act.resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT
        )
        step = dlg.findViewById(R.id.oStep)
        value = dlg.findViewById(R.id.oValue)
        sub = dlg.findViewById(R.id.oSub)
        capture = dlg.findViewById(R.id.oCapture)
        leafy = dlg.findViewById(R.id.oLeafy)

        val c = Store.load(act)
        decl = GeomagneticField(c.lat.toFloat(), c.lon.toFloat(), 0f, System.currentTimeMillis()).declination

        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        if (sensor == null) {
            value.text = "—"
            sub.text = L.t("Ce téléphone n'a pas de boussole : relevé impossible.", "Deze telefoon heeft geen kompas: opmeten onmogelijk.")
            capture.isEnabled = false
            capture.alpha = 0.4f
        } else {
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        updateStep()

        dlg.findViewById<Button>(R.id.oCancel).setOnClickListener { dlg.dismiss() }
        capture.setOnClickListener { next() }
        dlg.setOnDismissListener { sm.unregisterListener(this) }
        dlg.show()
    }

    private fun updateStep() {
        step.text = steps[stage]
        capture.text = buttons[stage]
        leafy.visibility = if (stage == 2) View.VISIBLE else View.GONE
    }

    private fun next() {
        if (az.isNaN()) return
        when (stage) {
            0 -> { az1 = az; stage = 1; updateStep() }
            1 -> { az2 = az; stage = 2; updateStep() }
            else -> {
                // Si la largeur dépasse 180°, les bords ont sans doute été visés à l'envers
                var a = az1; var b = az2
                if ((b - a + 360.0).mod(360.0) > 180.0) { val t = a; a = b; b = t }
                onDone(Obstacle(a.roundToInt().toDouble(), b.roundToInt().toDouble(),
                    el.coerceIn(0.0, 89.0).roundToInt().toDouble(), leafy.isChecked))
                dlg.dismiss()
            }
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val v = if (e.values.size > 4) e.values.copyOf(4) else e.values
        SensorManager.getRotationMatrixFromVector(rot, v)
        // Direction visée = axe de l'appareil photo (–Z du téléphone) dans le repère est/nord/haut
        val x = -rot[2].toDouble(); val y = -rot[5].toDouble(); val z = -rot[8].toDouble()
        val rawAz = (Math.toDegrees(atan2(x, y)) + decl + 360.0).mod(360.0)
        val rawEl = Math.toDegrees(asin(z.coerceIn(-1.0, 1.0)))
        if (az.isNaN()) { az = rawAz; el = rawEl } else {
            val diff = ((rawAz - az + 540.0).mod(360.0)) - 180.0
            az = (az + 0.15 * diff + 360.0).mod(360.0)
            el += 0.15 * (rawEl - el)
        }
        val a = az.roundToInt() % 360
        value.text = "$a° ${MeasureDialog.cardinal(a)}"
        sub.text = L.t("hauteur ${el.roundToInt()}° au-dessus de l'horizon", "hoogte ${el.roundToInt()}° boven de horizon")
    }

    override fun onAccuracyChanged(s: Sensor, a: Int) {}
}
