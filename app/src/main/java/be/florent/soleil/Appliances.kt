package be.florent.soleil

import android.content.Context
import kotlin.math.max
import kotlin.math.min

/** Appareil ménager : consommation moyenne par utilisation (kWh) et puissance (kW). */
data class Appliance(
    val id: String, val emoji: String, val name: String,
    val kwh: Double, val kw: Double, val on: Boolean
)

enum class Advice { YES, PARTIAL, LATER }

data class Reco(val app: Appliance, val advice: Advice, val coverage: Double, val betterDay: Int?)

object Appliances {

    /**
     * Valeurs moyennes indicatives par cycle ou par utilisation.
     * Uniquement des appareils gourmands dont on peut décaler l'usage
     * (pas de cuisson ni de petits appareils à la conso négligeable).
     */
    private val CATALOG = listOf(
        Appliance("ll40", "🧺", "Lave-linge 40 °C", 0.8, 2.0, true),
        Appliance("ll60", "🧺", "Lave-linge 60 °C", 1.3, 2.2, false),
        Appliance("lv", "🍽️", "Lave-vaisselle (éco)", 0.9, 2.0, true),
        Appliance("sl_res", "🔥", "Séchoir à condensation", 3.5, 2.5, true),
        Appliance("sl_pac", "🌀", "Séchoir pompe à chaleur", 1.5, 0.8, false),
        Appliance("boiler", "🚿", "Boiler électrique (chauffe)", 3.5, 2.4, false),
        Appliance("ve", "🚗", "Voiture électrique (+100 km)", 17.0, 3.7, false),
        Appliance("clim", "❄️", "Climatisation (3 h)", 3.0, 1.0, false),
        Appliance("piscine", "🏊", "Pompe de piscine (8 h)", 6.0, 0.75, false),
        Appliance("fer", "👕", "Repassage (1 h)", 1.0, 2.0, false)
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("soleil", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Appliance> {
        val p = prefs(ctx)
        return CATALOG.map {
            it.copy(
                kwh = p.getString("app_${it.id}_kwh", null)?.toDoubleOrNull() ?: it.kwh,
                on = p.getBoolean("app_${it.id}_on", it.on)
            )
        }
    }

    fun default(id: String): Appliance? = CATALOG.find { it.id == id }

    fun save(ctx: Context, id: String, kwh: Double, on: Boolean) {
        prefs(ctx).edit().putString("app_${id}_kwh", kwh.toString()).putBoolean("app_${id}_on", on).apply()
    }

    /** Énergie solaire disponible pour les appareils ce jour-là, après la conso habituelle. */
    fun surplus(c: Config, d: Day): Double = max(0.0, Forecast.est(c, d) - c.conso)

    private fun coverage(app: Appliance, surplus: Double) =
        if (app.kwh <= 0) 1.0 else min(1.0, surplus / app.kwh)

    /** Conseil pour chaque appareil actif, pour le jour d'index [dayIndex] dans [days]. */
    fun advise(c: Config, days: List<Day>, dayIndex: Int, list: List<Appliance>): List<Reco> {
        val s = surplus(c, days[dayIndex])
        return list.filter { it.on }.map { app ->
            val cov = coverage(app, s)
            val advice = when {
                cov >= 0.9 -> Advice.YES
                cov >= 0.4 -> Advice.PARTIAL
                else -> Advice.LATER
            }
            var better: Int? = null
            if (advice != Advice.YES) {
                // Meilleur jour à venir (après le jour affiché) nettement mieux couvert
                var best = cov
                for (i in (dayIndex + 1) until days.size) {
                    val ci = coverage(app, surplus(c, days[i]))
                    if (ci > best + 0.15) { best = ci; better = i }
                }
            }
            Reco(app, advice, cov, better)
        }
    }

    /** Appareils qui tiennent ensemble dans le surplus, dans l'ordre de la liste. */
    fun combo(c: Config, d: Day, list: List<Appliance>): List<Appliance> {
        var left = surplus(c, d)
        val out = ArrayList<Appliance>()
        list.filter { it.on }.sortedBy { it.kwh }.forEach {
            if (it.kwh <= left * 1.05) { out.add(it); left -= it.kwh }
        }
        return out
    }

    /** Meilleur créneau de 2 h (heure de début, kWh produits sur le créneau), si données horaires. */
    fun bestWindow(c: Config, d: Day): Pair<Int, Double>? {
        if (d.h1.size < 24 || d.h2.size < 24) return null
        val e = DoubleArray(24) { i ->
            ((d.h1[i] / 1000.0) * c.p1 + (d.h2[i] / 1000.0) * c.p2) * Forecast.PR * c.corr
        }
        // La valeur de HH:00 couvre HH-1 → HH : un créneau [h, h+2] = valeurs h+1 et h+2
        var bestStart = -1
        var bestVal = 0.0
        for (h in 5..20) {
            val v = e[h + 1] + e[h + 2]
            if (v > bestVal) { bestVal = v; bestStart = h }
        }
        return if (bestStart < 0 || bestVal < 0.05) null else Pair(bestStart, bestVal)
    }
}
