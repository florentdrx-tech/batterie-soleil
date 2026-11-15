package be.florent.soleil

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Tarif d'électricité. Heures creuses : plages [r1s, r1e[ et [r2s, r2e[ (heures entières, -1 = pas de plage),
 * plus tout le week-end si weekend = true. Prix tout compris en €/kWh.
 */
data class Tariff(
    val bi: Boolean,
    val r1s: Int, val r1e: Int, val r2s: Int, val r2e: Int,
    val weekend: Boolean,
    val hp: Double, val hc: Double, val mono: Double, val inj: Double
)

object Tariffs {
    /** Bi-horaire wallon depuis le 1er janvier 2026 : creuses 22 h–7 h et 11 h–17 h, 7 jours sur 7. */
    val WALLONIE = Tariff(true, 22, 7, 11, 17, false, 0.33, 0.25, 0.30, 0.04)
    /** Ancien schéma courant : creuses 22 h–7 h en semaine et tout le week-end. */
    val CLASSIQUE = WALLONIE.copy(r2s = -1, r2e = -1, weekend = true)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("soleil", Context.MODE_PRIVATE)

    fun load(ctx: Context): Tariff {
        val p = prefs(ctx)
        val d = WALLONIE
        fun dbl(k: String, v: Double) = p.getString(k, null)?.toDoubleOrNull() ?: v
        return Tariff(
            p.getBoolean("tar_bi", d.bi),
            p.getInt("tar_r1s", d.r1s), p.getInt("tar_r1e", d.r1e),
            p.getInt("tar_r2s", d.r2s), p.getInt("tar_r2e", d.r2e),
            p.getBoolean("tar_we", d.weekend),
            dbl("tar_hp", d.hp), dbl("tar_hc", d.hc), dbl("tar_mono", d.mono), dbl("tar_inj", d.inj)
        )
    }

    fun save(ctx: Context, t: Tariff) {
        prefs(ctx).edit()
            .putBoolean("tar_bi", t.bi)
            .putInt("tar_r1s", t.r1s).putInt("tar_r1e", t.r1e)
            .putInt("tar_r2s", t.r2s).putInt("tar_r2e", t.r2e)
            .putBoolean("tar_we", t.weekend)
            .putString("tar_hp", t.hp.toString()).putString("tar_hc", t.hc.toString())
            .putString("tar_mono", t.mono.toString()).putString("tar_inj", t.inj.toString())
            .apply()
    }

    private fun inRange(h: Int, s: Int, e: Int): Boolean {
        if (s < 0 || e < 0 || s == e) return false
        return if (s < e) h in s until e else h >= s || h < e
    }

    fun cheap(t: Tariff, dt: LocalDateTime): Boolean {
        if (!t.bi) return false
        val we = dt.dayOfWeek == DayOfWeek.SATURDAY || dt.dayOfWeek == DayOfWeek.SUNDAY
        return (t.weekend && we) || inRange(dt.hour, t.r1s, t.r1e) || inRange(dt.hour, t.r2s, t.r2e)
    }

    fun price(t: Tariff, dt: LocalDateTime): Double = when {
        !t.bi -> t.mono
        cheap(t, dt) -> t.hc
        else -> t.hp
    }

    /** Début du prochain créneau d'heures creuses à partir de [from] (dans les 48 h), ou null. */
    fun nextCheap(t: Tariff, from: LocalDateTime): LocalDateTime? {
        if (!t.bi) return null
        if (cheap(t, from)) return from
        var dt = from.withMinute(0).withSecond(0).withNano(0).plusHours(1)
        repeat(48) {
            if (cheap(t, dt)) return dt
            dt = dt.plusHours(1)
        }
        return null
    }

    /** Prix le plus élevé, pour montrer l'économie réalisée. */
    fun peak(t: Tariff) = if (t.bi) t.hp else t.mono
}
