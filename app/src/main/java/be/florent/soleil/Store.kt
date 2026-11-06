package be.florent.soleil

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class Config(val m: Map<String, Double>) {
    val lat: Double get() = m.getValue("lat")
    val lon: Double get() = m.getValue("lon")
    val p1: Double get() = m.getValue("p1")
    val a1: Double get() = m.getValue("a1")
    val p2: Double get() = m.getValue("p2")
    val a2: Double get() = m.getValue("a2")
    val tilt: Double get() = m.getValue("tilt")
    val bat: Double get() = m.getValue("bat")
    val conso: Double get() = m.getValue("conso")
    val corr: Double get() = m.getValue("corr")
}

data class Day(val date: String, val g1: Double, val g2: Double, val sun: Double, val cloud: Double)

object Store {
    val DEFAULTS: LinkedHashMap<String, Double> = linkedMapOf(
        "lat" to 50.41, "lon" to 4.59,
        "p1" to 4.0, "a1" to 150.0,
        "p2" to 4.0, "a2" to 250.0,
        "tilt" to 35.0, "bat" to 16.0, "conso" to 3.0, "corr" to 1.0
    )
    val LABELS = mapOf(
        "lat" to "Latitude", "lon" to "Longitude",
        "p1" to "Champ 1 : puissance (kWc)", "a1" to "Champ 1 : orientation (°, 180 = sud)",
        "p2" to "Champ 2 : puissance (kWc)", "a2" to "Champ 2 : orientation (°, 180 = sud)",
        "tilt" to "Inclinaison des panneaux (°)", "bat" to "Capacité batterie (kWh)",
        "conso" to "Conso maison en journée (kWh)", "corr" to "Facteur de correction"
    )

    // Première mesure réelle donnée : 3,39 kWh le 4 novembre 2026
    private const val FIRST_CALIB_DATE = "2026-11-04"
    private const val FIRST_CALIB_KWH = 3.39

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("soleil", Context.MODE_PRIVATE)

    fun load(ctx: Context): Config {
        val p = prefs(ctx)
        return Config(DEFAULTS.mapValues { (k, d) -> p.getString(k, null)?.toDoubleOrNull() ?: d })
    }

    fun save(ctx: Context, values: Map<String, Double>) {
        val e = prefs(ctx).edit()
        values.forEach { (k, v) -> e.putString(k, v.toString()) }
        e.apply()
    }

    fun set(ctx: Context, key: String, v: Double) = save(ctx, mapOf(key to v))

    fun saveDays(ctx: Context, days: List<Day>) {
        val arr = JSONArray()
        days.forEach {
            arr.put(JSONObject().put("date", it.date).put("g1", it.g1).put("g2", it.g2)
                .put("sun", it.sun).put("cloud", it.cloud))
        }
        prefs(ctx).edit()
            .putString("days", arr.toString())
            .putString("updated", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
            .apply()
    }

    fun days(ctx: Context): List<Day> {
        val s = prefs(ctx).getString("days", null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Day(o.getString("date"), o.getDouble("g1"), o.getDouble("g2"),
                    o.getDouble("sun"), o.getDouble("cloud"))
            }
        } catch (e: Exception) { emptyList() }
    }

    fun placeName(ctx: Context): String? = prefs(ctx).getString("place", null)

    fun setPlace(ctx: Context, p: Place) {
        prefs(ctx).edit()
            .putString("place", p.name)
            .putString("lat", p.lat.toString())
            .putString("lon", p.lon.toString())
            .apply()
    }

    fun clearPlace(ctx: Context) = prefs(ctx).edit().remove("place").apply()

    fun updatedAt(ctx: Context): String = prefs(ctx).getString("updated", "–") ?: "–"

    /** Applique une seule fois la calibration avec la mesure réelle de départ. */
    fun autoCalibrate(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("calib_done", false)) return
        val c = load(ctx)
        val d = days(ctx).find { it.date == FIRST_CALIB_DATE } ?: return
        val base = Forecast.base(c, d)
        if (base <= 0.0) return
        set(ctx, "corr", ((FIRST_CALIB_KWH / base) * 100).roundToInt() / 100.0)
        p.edit().putBoolean("calib_done", true).apply()
    }
}
