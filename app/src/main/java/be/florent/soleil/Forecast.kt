package be.florent.soleil

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

object Forecast {
    /** Pertes onduleur, câbles, température. */
    const val PR = 0.82

    private fun fetchArray(c: Config, compassAzimuth: Double, withDni: Boolean): JSONObject {
        // Open-Meteo : 0 = sud, -90 = est, 90 = ouest
        val omAz = compassAzimuth - 180.0
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=${c.lat}&longitude=${c.lon}" +
                "&hourly=global_tilted_irradiance${if (withDni) ",direct_normal_irradiance" else ""}" +
                "&tilt=${c.tilt}&azimuth=$omAz" +
                "&daily=sunshine_duration,cloud_cover_mean&timezone=auto&past_days=7&forecast_days=7"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Irradiance horaire par jour (24 valeurs, Wh/m²). */
    private fun hourly(j: JSONObject, key: String = "global_tilted_irradiance"): Map<String, DoubleArray> {
        val h = j.getJSONObject("hourly")
        val t = h.getJSONArray("time")
        val g = h.optJSONArray(key) ?: return emptyMap()
        val m = LinkedHashMap<String, DoubleArray>()
        for (i in 0 until t.length()) {
            val ts = t.getString(i)
            val d = ts.substring(0, 10)
            val hour = ts.substring(11, 13).toInt()
            val v = if (g.isNull(i)) 0.0 else g.getDouble(i)
            m.getOrPut(d) { DoubleArray(24) }[hour] = v
        }
        return m
    }

    fun fetch(c: Config): List<Day> {
        val j1 = fetchArray(c, c.a1, true)
        val j2 = fetchArray(c, c.a2, false)
        val g1 = hourly(j1)
        val g2 = hourly(j2)
        val dni = hourly(j1, "direct_normal_irradiance")
        val off = j1.optInt("utc_offset_seconds", 3600)
        val daily = j1.getJSONObject("daily")
        val time = daily.getJSONArray("time")
        val sun = daily.getJSONArray("sunshine_duration")
        val cloud = daily.getJSONArray("cloud_cover_mean")
        return (0 until time.length()).map { i ->
            val d = time.getString(i)
            Day(
                date = d,
                g1 = g1[d]?.sum() ?: 0.0,
                g2 = g2[d]?.sum() ?: 0.0,
                sun = if (sun.isNull(i)) 0.0 else sun.getDouble(i) / 3600.0,
                cloud = if (cloud.isNull(i)) 0.0 else cloud.getDouble(i),
                h1 = g1[d]?.toList() ?: emptyList(),
                h2 = g2[d]?.toList() ?: emptyList(),
                dni = dni[d]?.toList() ?: emptyList(),
                off = off
            )
        }
    }

    fun base(c: Config, d: Day): Double = ((d.g1 / 1000.0) * c.p1 + (d.g2 / 1000.0) * c.p2) * PR

    fun est(c: Config, d: Day): Double = base(c, d) * factorFor(c, d.date)

    /**
     * Correction à appliquer un jour donné : celle de la même saison si au moins 3 saisies
     * à ±45 jours, sinon la moyenne générale. Renvoie (facteur, nombre de jours de saison utilisés).
     */
    fun seasonInfo(c: Config, date: String): Pair<Double, Int> {
        val near = Store.seasonal(c.calibs, date).take(Store.CALIB_WINDOW)
        val f = if (near.size >= Store.SEASON_MIN) Store.factor(near) else null
        return if (f != null) Pair(f, near.size) else Pair(c.corr, 0)
    }

    fun factorFor(c: Config, date: String): Double = seasonInfo(c, date).first

    fun battPct(c: Config, kwh: Double): Double {
        if (c.bat <= 0.0) return 0.0
        return ((kwh - c.conso) / c.bat).coerceIn(0.0, 1.0)
    }

    /**
     * Niveau prévu en fin de journée à partir d'un niveau saisi à un moment donné.
     * Ne compte que le soleil restant après ce moment, et la part de conso correspondante.
     */
    fun endLevel(c: Config, d: Day, startPct: Double, startMillis: Long): Double? {
        if (d.h1.size < 24 || d.h2.size < 24 || c.bat <= 0.0) return null
        val t = java.time.Instant.ofEpochMilli(startMillis).atZone(java.time.ZoneId.systemDefault())
        val startH = t.hour + t.minute / 60.0
        var total = 0.0
        var rem = 0.0
        for (i in 0 until 24) {
            val e = (d.h1[i] / 1000.0) * c.p1 + (d.h2[i] / 1000.0) * c.p2
            total += e
            rem += e * (i - startH).coerceIn(0.0, 1.0)
        }
        val prodRem = rem * PR * factorFor(c, d.date)
        val consoRem = if (total > 0) c.conso * (rem / total) else 0.0
        return (startPct + (prodRem - consoRem) / c.bat).coerceIn(0.0, 1.0)
    }

    fun levelVerdict(startPct: Double, end: Double): String {
        val s = (startPct * 100).roundToInt()
        val e = (end * 100).roundToInt()
        return when {
            end >= 0.995 -> L.t("Batterie à $s % : pleine avant ce soir", "Batterij op $s %: vol voor vanavond")
            e > s + 1 -> L.t("Batterie à $s % → ≈ $e % ce soir", "Batterij op $s % → ≈ $e % vanavond")
            else -> L.t("Batterie à $s % : pas de recharge d'ici ce soir", "Batterij op $s %: niet opgeladen voor vanavond")
        }
    }

    /** Version gratuite : qualité de la journée, sans parler de batterie. */
    fun freeVerdict(c: Config, kwh: Double): String {
        val perKwc = if (c.p1 + c.p2 > 0) kwh / (c.p1 + c.p2) else 0.0
        return when {
            perKwc >= 3.0 -> L.t("☀ Excellente journée solaire", "☀ Uitstekende zonnedag")
            perKwc >= 1.5 -> L.t("Bonne journée solaire", "Goede zonnedag")
            perKwc >= 0.5 -> L.t("Journée moyenne", "Gemiddelde dag")
            else -> L.t("Peu de soleil aujourd'hui", "Weinig zon vandaag")
        }
    }

    fun verdict(c: Config, kwh: Double): String {
        val surplus = max(0.0, kwh - c.conso)
        return when {
            battPct(c, kwh) >= 1.0 -> L.t("Batterie pleine possible, avec surplus injecté", "Batterij kan vol, met overschot naar het net")
            surplus > 0.5 -> String.format(L.loc, L.t("≈ %.1f kWh stockés après la conso de journée", "≈ %.1f kWh opgeslagen na het dagverbruik"), surplus)
            else -> L.t("Production absorbée par la maison : presque rien pour la batterie", "Productie gaat op aan het huis: bijna niets voor de batterij")
        }
    }
}
