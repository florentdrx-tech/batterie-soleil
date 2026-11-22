package be.florent.soleil

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.TextView
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Mesure l'orientation (boussole) ou l'inclinaison avec les capteurs du téléphone. */
class MeasureDialog(
    private val act: Activity,
    private val tilt: Boolean,
    private val onPick: (Double) -> Unit
) : SensorEventListener {

    private val sm = act.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val dlg = Dialog(act)
    private lateinit var dial: CompassView
    private lateinit var value: TextView
    private lateinit var sub: TextView
    private lateinit var acc: TextView
    private var cur = Double.NaN
    private var decl = 0f
    private val rot = FloatArray(9)
    private val ori = FloatArray(3)

    fun show() {
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(R.layout.dialog_measure)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout(
            (act.resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dial = dlg.findViewById(R.id.dial)
        value = dlg.findViewById(R.id.mValue)
        sub = dlg.findViewById(R.id.mSub)
        acc = dlg.findViewById(R.id.mAcc)
        val title = dlg.findViewById<TextView>(R.id.mTitle)
        val hint = dlg.findViewById<TextView>(R.id.mHint)
        val use = dlg.findViewById<Button>(R.id.mUse)
        dial.tiltMode = tilt

        if (tilt) {
            title.text = L.t("Mesurer l'inclinaison", "Hellingshoek meten")
            hint.text = L.t("Pose le téléphone à plat sur un panneau (ou sur la toiture), dans le sens de la pente. Attends que la valeur se stabilise.",
                "Leg de telefoon plat op een paneel (of op het dak), in de richting van de helling. Wacht tot de waarde stabiel is.")
        } else {
            title.text = L.t("Mesurer l'orientation", "Oriëntatie meten")
            hint.text = L.t("Tiens le téléphone à plat, le haut pointé dans la direction où regardent les panneaux : mets-toi dos à la toiture, ou pose-le au bas d'un panneau. Éloigne-toi des objets métalliques.",
                "Houd de telefoon plat, met de bovenkant in de richting waarin de panelen kijken: ga met je rug naar het dak staan, of leg hem onderaan een paneel. Blijf uit de buurt van metalen voorwerpen.")
        }

        val c = Store.load(act)
        decl = GeomagneticField(c.lat.toFloat(), c.lon.toFloat(), 0f, System.currentTimeMillis()).declination

        val sensor = if (tilt) {
            sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        } else {
            sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        }

        if (sensor == null) {
            value.text = "—"
            sub.text = if (tilt) L.t("Pas de capteur d'inclinaison sur ce téléphone", "Geen hellingssensor op deze telefoon")
                       else L.t("Pas de boussole sur ce téléphone", "Geen kompas op deze telefoon")
            use.isEnabled = false
            use.alpha = 0.4f
        } else {
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            if (!tilt) {
                sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                    sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
                }
            }
        }

        dlg.findViewById<Button>(R.id.mCancel).setOnClickListener { dlg.dismiss() }
        use.setOnClickListener {
            if (!cur.isNaN()) onPick(cur.roundToInt().toDouble())
            dlg.dismiss()
        }
        dlg.setOnDismissListener { sm.unregisterListener(this) }
        dlg.show()
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> return
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val gx = e.values[0]; val gy = e.values[1]; val gz = e.values[2]
                val raw = Math.toDegrees(atan2(sqrt(gx * gx + gy * gy).toDouble(), abs(gz).toDouble()))
                cur = if (cur.isNaN()) raw else cur + 0.15 * (raw - cur)
                dial.angle = cur.toFloat()
                value.text = "${cur.roundToInt()}°"
                sub.text = L.t("d'inclinaison", "hellingshoek")
            }
            else -> {
                val v = if (e.values.size > 4) e.values.copyOf(4) else e.values
                SensorManager.getRotationMatrixFromVector(rot, v)
                SensorManager.getOrientation(rot, ori)
                val raw = (Math.toDegrees(ori[0].toDouble()) + decl + 360.0) % 360.0
                cur = if (cur.isNaN()) raw else {
                    val diff = ((raw - cur + 540.0) % 360.0) - 180.0
                    (cur + 0.15 * diff + 360.0) % 360.0
                }
                dial.angle = cur.toFloat()
                val deg = cur.roundToInt() % 360
                value.text = "$deg°"
                sub.text = cardinal(deg)
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor, a: Int) {
        if (s.type == Sensor.TYPE_MAGNETIC_FIELD) {
            acc.visibility = if (a <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) View.VISIBLE else View.GONE
        }
    }

    companion object {
        fun cardinal(deg: Int): String {
            val names = if (L.nl) listOf("noord", "noordoost", "oost", "zuidoost", "zuid", "zuidwest", "west", "noordwest")
                else listOf("nord", "nord-est", "est", "sud-est", "sud", "sud-ouest", "ouest", "nord-ouest")
            return names[((deg + 22.5) / 45.0).toInt() % 8]
        }
    }
}
