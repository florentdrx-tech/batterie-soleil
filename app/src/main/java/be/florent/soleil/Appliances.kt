package be.florent.soleil

import android.content.Context
import kotlin.math.max
import kotlin.math.min

/** Appareil ménager : consommation moyenne par utilisation (kWh) et puissance (kW). */
data class Appliance(
    val id: String, val emoji: String, val name: String,
    val kwh: Double, val kw: Double, val on: Boolean, val short: String = name
)

enum class Advice { YES, PARTIAL, LATER, OFFPEAK }

/**
 * Conseil pour un appareil : statut pour le jour affiché, part couverte par le soleil ce jour-là,
 * meilleur plan trouvé (jour, heure de début, solaire ou heures creuses) et coût estimé.
 */
data class Reco(
    val app: Appliance, val advice: Advice, val coverage: Double, val betterDay: Int?,
    val planDay: Int, val planTime: java.time.LocalDateTime?, val planOffPeak: Boolean,
    val cost: Double, val costPeak: Double
)

object Appliances {

    /**
     * Valeurs moyennes indicatives par cycle ou par utilisation.
     * Uniquement des appareils gourmands dont on peut décaler l'usage
     * (pas de cuisson ni de petits appareils à la conso négligeable).
     */
    private val CATALOG get() = listOf(
        Appliance("ll40", "🧺", L.t("Lave-linge 40 °C", "Wasmachine 40 °C"), 0.8, 2.0, true, L.t("le lave-linge", "de wasmachine")),
        Appliance("ll60", "🧺", L.t("Lave-linge 60 °C", "Wasmachine 60 °C"), 1.3, 2.2, false, L.t("le lave-linge 60°", "de wasmachine op 60°")),
        Appliance("lv", "🍽️", L.t("Lave-vaisselle (éco)", "Vaatwasser (eco)"), 0.9, 2.0, true, L.t("le lave-vaisselle", "de vaatwasser")),
        Appliance("sl_res", "🔥", L.t("Séchoir à condensation", "Condensdroogkast"), 3.5, 2.5, true, L.t("le séchoir", "de droogkast")),
        Appliance("sl_pac", "🌀", L.t("Séchoir pompe à chaleur", "Warmtepompdroogkast"), 1.5, 0.8, false, L.t("le séchoir", "de droogkast")),
        Appliance("boiler", "🚿", L.t("Boiler électrique (chauffe)", "Elektrische boiler (opwarmen)"), 3.5, 2.4, false, L.t("le boiler", "de boiler")),
        Appliance("ve", "🚗", L.t("Voiture électrique (+100 km)", "Elektrische auto (+100 km)"), 17.0, 3.7, false, L.t("la recharge de la voiture", "het laden van de auto")),
        Appliance("clim", "❄️", L.t("Climatisation (3 h)", "Airco (3 u)"), 3.0, 1.0, false, L.t("la clim", "de airco")),
        Appliance("piscine", "🏊", L.t("Pompe de piscine (8 h)", "Zwembadpomp (8 u)"), 6.0, 0.75, false, L.t("la pompe de piscine", "de zwembadpomp")),
        Appliance("fer", "👕", L.t("Repassage (1 h)", "Strijken (1 u)"), 1.0, 2.0, false, L.t("le repassage", "het strijken"))
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

    /** Horizon de recherche d'un meilleur moment : le jour affiché et les 3 suivants. */
    private const val HORIZON = 4

    /** Pénalité par jour d'attente (€), pour préférer le plus tôt à coût presque égal. */
    private const val WAIT_PENALTY = 0.03

    /**
     * Conseil pour chaque appareil actif, pour le jour d'index [dayIndex] dans [days].
     * Compare le meilleur créneau solaire des 4 prochains jours avec les heures creuses.
     */
    fun advise(c: Config, t: Tariff, days: List<Day>, dayIndex: Int, list: List<Appliance>): List<Reco> {
        val now = java.time.LocalDateTime.now()
        return list.filter { it.on }.map { app ->
            val cov = coverage(app, surplus(c, days[dayIndex]))
            val peakCost = app.kwh * Tariffs.peak(t)

            var bestScore = Double.MAX_VALUE
            var bestCost = peakCost
            var bestDay = dayIndex
            var bestTime: java.time.LocalDateTime? = null
            var bestOff = false

            // Options solaires : meilleur créneau de chaque jour
            for (i in dayIndex until minOf(days.size, dayIndex + HORIZON)) {
                val d = days[i]
                val date = java.time.LocalDate.parse(d.date)
                val h = bestWindow(c, d)?.first ?: 12
                var at = date.atTime(h, 0)
                if (at.plusHours(1).isBefore(now)) {
                    if (date == now.toLocalDate()) continue  // créneau du jour déjà passé
                }
                if (at.isBefore(now)) at = now
                val ci = coverage(app, surplus(c, d))
                val cost = ci * app.kwh * t.inj + (1 - ci) * app.kwh * Tariffs.price(t, at)
                val score = cost + (i - dayIndex) * WAIT_PENALTY
                if (score < bestScore) {
                    bestScore = score; bestCost = cost; bestDay = i; bestTime = at; bestOff = false
                }
            }

            // Option heures creuses, à partir du jour affiché
            if (t.bi) {
                val first = java.time.LocalDate.parse(days[dayIndex].date)
                val from = if (first == now.toLocalDate()) now else first.atTime(7, 0)
                val slot = Tariffs.nextCheap(t, from)
                if (slot != null) {
                    // Si le créneau creux tombe en journée, le soleil en couvre une partie
                    val idx = days.indexOfFirst { it.date == slot.toLocalDate().toString() }
                    val ci = if (idx >= 0 && slot.hour in 9..16) coverage(app, surplus(c, days[idx])) else 0.0
                    val cost = ci * app.kwh * t.inj + (1 - ci) * app.kwh * t.hc
                    val waitDays = java.time.temporal.ChronoUnit.DAYS.between(first, slot.toLocalDate()).toInt()
                    val score = cost + waitDays * WAIT_PENALTY
                    if (score < bestScore - 0.005) {
                        bestScore = score; bestCost = cost; bestTime = slot; bestOff = ci < 0.5
                        bestDay = if (idx >= 0) idx else dayIndex
                    }
                }
            }

            // Le meilleur créneau tombe-t-il en heures creuses avec peu de soleil ? (ex. 11 h–17 h en Wallonie)
            val planCov = coverage(app, surplus(c, days[bestDay]))
            val cheapPlan = bestTime?.let { Tariffs.cheap(t, it) } == true && planCov < 0.5
            val advice = when {
                cov >= 0.9 -> Advice.YES
                bestDay > dayIndex && !bestOff && !cheapPlan -> Advice.LATER
                bestOff || cheapPlan -> Advice.OFFPEAK
                cov >= 0.4 -> Advice.PARTIAL
                else -> Advice.LATER
            }
            val off = advice == Advice.OFFPEAK
            Reco(app, advice, cov, if (bestDay > dayIndex && !off) bestDay else null,
                bestDay, bestTime, off, bestCost, peakCost)
        }
    }

    /** Libellé court d'un créneau : « ce soir dès 22 h », « demain dès 11 h », « jeudi vers 12 h ». */
    fun slotLabel(days: List<Day>, r: Reco): String {
        val t = r.planTime ?: return dayName(r.planDay, days)
        val today = java.time.LocalDate.now()
        val now = java.time.LocalDateTime.now()
        val h = L.t("${t.hour} h", "${t.hour}u")
        val day = when (val diff = java.time.temporal.ChronoUnit.DAYS.between(today, t.toLocalDate()).toInt()) {
            0 -> if (t.hour >= 17) L.t("ce soir", "vanavond") else L.t("aujourd'hui", "vandaag")
            1 -> if (t.hour < 7) L.t("cette nuit", "vannacht") else L.t("demain", "morgen")
            else -> java.time.LocalDate.now().plusDays(diff.toLong())
                .format(java.time.format.DateTimeFormatter.ofPattern("EEEE", L.loc))
        }
        return when {
            !t.isAfter(now.plusMinutes(5)) -> L.t("maintenant", "nu")
            r.planOffPeak -> L.t("$day dès $h", "$day vanaf $h")
            else -> L.t("$day vers $h", "$day rond $h")
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
            ((d.h1[i] / 1000.0) * c.p1 + (d.h2[i] / 1000.0) * c.p2) * Forecast.PR * Forecast.factorFor(c, d.date)
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

    private fun joinFr(l: List<String>): String = when (l.size) {
        0 -> ""
        1 -> l[0]
        else -> l.dropLast(1).joinToString(", ") + L.t(" et ", " en ") + l.last()
    }

    private fun dayName(i: Int, days: List<Day>): String = when (i) {
        0 -> L.t("aujourd'hui", "vandaag")
        1 -> L.t("demain", "morgen")
        else -> java.time.LocalDate.parse(days[i].date)
            .format(java.time.format.DateTimeFormatter.ofPattern("EEEE", L.loc))
    }

    /** Conseil du jour en une phrase, pour l'appli, le widget et la notification du matin. */
    fun headline(c: Config, t: Tariff, days: List<Day>, list: List<Appliance>): String? {
        if (days.isEmpty()) return null
        val recos = advise(c, t, days, 0, list)
        if (recos.isEmpty()) return null
        val yes = recos.filter { it.advice == Advice.YES }.map { it.app.short }.distinct()
        val wait = recos.filter { it.advice != Advice.YES && !it.planOffPeak && it.betterDay != null }
            .groupBy { it.betterDay!! }.minByOrNull { it.key }
        val waitTxt = wait?.let { (d, rs) ->
            L.t("attends ${dayName(d, days)} pour ${joinFr(rs.map { it.app.short }.distinct())}",
                "wacht tot ${dayName(d, days)} voor ${joinFr(rs.map { it.app.short }.distinct())}")
        }
        val off = recos.filter { it.advice == Advice.OFFPEAK }
        val offTxt = if (off.isEmpty()) null else
            L.t("${joinFr(off.map { it.app.short }.distinct())} en heures creuses (${slotLabel(days, off.first())})",
                "${joinFr(off.map { it.app.short }.distinct())} tijdens de daluren (${slotLabel(days, off.first())})")
        return when {
            yes.isNotEmpty() -> L.t("Lance ${joinFr(yes)} aujourd'hui", "Zet ${joinFr(yes)} vandaag aan") +
                (if (t.capa && yes.size > 1) L.t(" (l'un après l'autre)", " (na elkaar)") else "") +
                listOfNotNull(waitTxt, offTxt?.let { L.t("et $it", "en $it") }).joinToString(", ", prefix = if (waitTxt != null || offTxt != null) ", " else "")
            waitTxt != null -> waitTxt.replaceFirstChar { it.uppercase() } + (offTxt?.let { L.t(", et $it", ", en $it") } ?: "")
            offTxt != null -> L.t("Peu de soleil ces prochains jours : lance $offTxt", "Weinig zon de komende dagen: gebruik $offTxt")
            else -> L.t("Peu de soleil cette semaine : pas de jour idéal pour les machines", "Weinig zon deze week: geen ideale dag voor de toestellen")
        }
    }
}
