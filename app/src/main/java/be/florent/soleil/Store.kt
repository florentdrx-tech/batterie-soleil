package be.florent.soleil

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class Config(val m: Map<String, Double>, val calibs: List<Calib> = emptyList()) {
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

/** Une saisie de production réelle : réel mesuré et prévision brute (sans correction) du même jour. */
data class Calib(val date: String, val real: Double, val base: Double)

data class Day(
    val date: String, val g1: Double, val g2: Double, val sun: Double, val cloud: Double,
    /** Irradiance horaire (Wh/m²), index = heure de fin de l'intervalle (valeur de HH:00 = HH-1 → HH). */
    val h1: List<Double> = emptyList(), val h2: List<Double> = emptyList(),
    /** Rayonnement direct normal horaire (W/m²) et décalage UTC du fuseau des heures. */
    val dni: List<Double> = emptyList(), val off: Int = 3600
)

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

    /** Réglages de l'installation. Version gratuite : un seul champ (le champ 2 compte pour 0), sauf si raw. */
    fun load(ctx: Context, raw: Boolean = false): Config {
        val p = prefs(ctx)
        var m = DEFAULTS.mapValues { (k, d) -> p.getString(k, null)?.toDoubleOrNull() ?: d }
        if (!raw && !Pro.isPro(ctx)) m = m + ("p2" to 0.0)
        return Config(m, calibs(ctx))
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
                .put("sun", it.sun).put("cloud", it.cloud)
                .put("h1", JSONArray(it.h1)).put("h2", JSONArray(it.h2))
                .put("dni", JSONArray(it.dni)).put("off", it.off))
        }
        prefs(ctx).edit()
            .putString("days", arr.toString())
            .putString("updated", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
            .apply()
    }

    /** Jours de prévision, avec les ombres des obstacles appliquées (sauf si shaded = false). */
    fun days(ctx: Context, shaded: Boolean = true): List<Day> {
        val raw = rawDays(ctx)
        if (!shaded) return raw
        val obs = obstacles(ctx)
        if (obs.isEmpty() || !Pro.isPro(ctx)) return raw
        val c = load(ctx)
        return raw.map { Shading.apply(c, obs, it) }
    }

    private fun rawDays(ctx: Context): List<Day> {
        val s = prefs(ctx).getString("days", null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Day(o.getString("date"), o.getDouble("g1"), o.getDouble("g2"),
                    o.getDouble("sun"), o.getDouble("cloud"),
                    list(o.optJSONArray("h1")), list(o.optJSONArray("h2")),
                    list(o.optJSONArray("dni")), o.optInt("off", 3600))
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

    private fun list(a: JSONArray?): List<Double> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optDouble(it, 0.0) }

    /** Niveau de batterie saisi à la main : valable seulement le jour même. */
    fun setLevel(ctx: Context, pct: Double) {
        prefs(ctx).edit().putString("lvl", pct.toString())
            .putLong("lvl_t", System.currentTimeMillis()).apply()
    }

    fun clearLevel(ctx: Context) = prefs(ctx).edit().remove("lvl").remove("lvl_t").apply()

    fun level(ctx: Context): Pair<Double, Long>? {
        val p = prefs(ctx)
        val v = p.getString("lvl", null)?.toDoubleOrNull() ?: return null
        val t = p.getLong("lvl_t", 0L)
        val day = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return if (day == java.time.LocalDate.now()) Pair(v, t) else null
    }

    fun updatedAt(ctx: Context): String = prefs(ctx).getString("updated", "–") ?: "–"

    /** Ajoute une seule fois la mesure réelle de départ à l'historique. */
    fun autoCalibrate(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("calib_done", false)) return
        val c = load(ctx)
        val d = days(ctx).find { it.date == FIRST_CALIB_DATE } ?: return
        val base = Forecast.base(c, d)
        if (base <= 0.0) return
        addCalib(ctx, Calib(FIRST_CALIB_DATE, FIRST_CALIB_KWH, base))
        p.edit().putBoolean("calib_done", true).apply()
    }

    // ---------- Historique de calibration ----------

    const val CALIB_WINDOW = 15

    fun calibs(ctx: Context): List<Calib> {
        val s = prefs(ctx).getString("calibs", null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Calib(o.getString("date"), o.getDouble("real"), o.getDouble("base"))
            }.sortedByDescending { it.date }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveCalibs(ctx: Context, list: List<Calib>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("date", it.date).put("real", it.real).put("base", it.base)) }
        prefs(ctx).edit().putString("calibs", a.toString()).apply()
        recompute(ctx, list)
    }

    /** Ajoute ou remplace la saisie de ce jour. */
    fun addCalib(ctx: Context, c: Calib) =
        saveCalibs(ctx, calibs(ctx).filter { it.date != c.date } + c)

    fun removeCalib(ctx: Context, date: String) =
        saveCalibs(ctx, calibs(ctx).filter { it.date != date })

    /** Facteur = total réel ÷ total prévu sur les dernières saisies (les beaux jours pèsent plus). */
    fun factor(list: List<Calib>): Double? {
        val used = list.sortedByDescending { it.date }.take(CALIB_WINDOW)
        val b = used.sumOf { it.base }
        if (used.isEmpty() || b <= 0.0) return null
        return ((used.sumOf { it.real } / b) * 100).roundToInt() / 100.0
    }

    private fun recompute(ctx: Context, list: List<Calib>) {
        set(ctx, "corr", factor(list) ?: 1.0)
    }

    // ---------- Saisons ----------

    const val SEASON_DAYS = 45
    const val SEASON_MIN = 3

    /** Saisies faites à la même période de l'année (±45 jours, toutes années confondues). */
    fun seasonal(list: List<Calib>, date: String): List<Calib> {
        val doy = java.time.LocalDate.parse(date).dayOfYear
        return list.filter {
            val d = kotlin.math.abs(java.time.LocalDate.parse(it.date).dayOfYear - doy)
            minOf(d, 365 - d) <= SEASON_DAYS
        }
    }

    // ---------- Obstacles (ombres) ----------

    fun obstacles(ctx: Context): List<Obstacle> {
        val s = prefs(ctx).getString("obstacles", null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Obstacle(o.getDouble("az1"), o.getDouble("az2"), o.getDouble("el"), o.optBoolean("leafy", false))
            }
        } catch (e: Exception) { emptyList() }
    }

    fun saveObstacles(ctx: Context, list: List<Obstacle>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("az1", it.az1).put("az2", it.az2).put("el", it.el).put("leafy", it.leafy))
        }
        prefs(ctx).edit().putString("obstacles", a.toString()).apply()
        rebaseCalibs(ctx)
    }

    /** Recalcule la prévision brute des saisies encore en mémoire, pour ne pas compter les ombres deux fois. */
    private fun rebaseCalibs(ctx: Context) {
        val c = load(ctx)
        val ds = days(ctx)
        val list = calibs(ctx).map { cal ->
            val d = ds.find { it.date == cal.date } ?: return@map cal
            val b = Forecast.base(c, d)
            if (b > 0.0) cal.copy(base = b) else cal
        }
        if (list.isNotEmpty()) saveCalibs(ctx, list)
    }
}
