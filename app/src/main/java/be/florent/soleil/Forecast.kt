package be.florent.soleil

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

object Forecast {
    /** Pertes onduleur, câbles, température. */
    const val PR = 0.82

    private fun fetchArray(c: Config, compassAzimuth: Double): JSONObject {
        // Open-Meteo : 0 = sud, -90 = est, 90 = ouest
        val omAz = compassAzimuth - 180.0
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=${c.lat}&longitude=${c.lon}" +
                "&hourly=global_tilted_irradiance&tilt=${c.tilt}&azimuth=$omAz" +
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

    /** Somme de l'irradiance horaire par jour, en Wh/m². */
    private fun perDay(j: JSONObject): Map<String, Double> {
        val h = j.getJSONObject("hourly")
        val t = h.getJSONArray("time")
        val g = h.getJSONArray("global_tilted_irradiance")
        val m = LinkedHashMap<String, Double>()
        for (i in 0 until t.length()) {
            val d = t.getString(i).substring(0, 10)
            val v = if (g.isNull(i)) 0.0 else g.getDouble(i)
            m[d] = (m[d] ?: 0.0) + v
        }
        return m
    }

    fun fetch(c: Config): List<Day> {
        val j1 = fetchArray(c, c.a1)
        val j2 = fetchArray(c, c.a2)
        val g1 = perDay(j1)
        val g2 = perDay(j2)
        val daily = j1.getJSONObject("daily")
        val time = daily.getJSONArray("time")
        val sun = daily.getJSONArray("sunshine_duration")
        val cloud = daily.getJSONArray("cloud_cover_mean")
        return (0 until time.length()).map { i ->
            val d = time.getString(i)
            Day(
                date = d,
                g1 = g1[d] ?: 0.0,
                g2 = g2[d] ?: 0.0,
                sun = if (sun.isNull(i)) 0.0 else sun.getDouble(i) / 3600.0,
                cloud = if (cloud.isNull(i)) 0.0 else cloud.getDouble(i)
            )
        }
    }

    fun base(c: Config, d: Day): Double = ((d.g1 / 1000.0) * c.p1 + (d.g2 / 1000.0) * c.p2) * PR

    fun est(c: Config, d: Day): Double = base(c, d) * c.corr

    fun battPct(c: Config, kwh: Double): Double {
        if (c.bat <= 0.0) return 0.0
        return ((kwh - c.conso) / c.bat).coerceIn(0.0, 1.0)
    }

    fun verdict(c: Config, kwh: Double): String {
        val surplus = max(0.0, kwh - c.conso)
        return when {
            battPct(c, kwh) >= 1.0 -> "Batterie pleine possible, avec surplus injecté"
            surplus > 0.5 -> "≈ %.1f kWh stockés après la conso de journée".format(surplus)
            else -> "Production absorbée par la maison : presque rien pour la batterie"
        }
    }
}
