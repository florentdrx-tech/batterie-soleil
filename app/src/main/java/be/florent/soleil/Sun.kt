package be.florent.soleil

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/** Obstacle vu depuis les panneaux : de az1 à az2 (sens horaire, degrés boussole), jusqu'à la hauteur el (°). */
data class Obstacle(val az1: Double, val az2: Double, val el: Double, val leafy: Boolean)

object Sun {
    /** Hauteur et azimut (boussole, 0 = nord) du soleil à un instant UTC. */
    fun pos(lat: Double, lon: Double, utcMillis: Long): DoubleArray {
        val n = utcMillis / 86400000.0 + 2440587.5 - 2451545.0
        val l = (280.460 + 0.9856474 * n).mod(360.0)
        val g = Math.toRadians((357.528 + 0.9856003 * n).mod(360.0))
        val lam = Math.toRadians(l + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val eps = Math.toRadians(23.439 - 0.0000004 * n)
        val ra = atan2(cos(eps) * sin(lam), cos(lam))
        val dec = asin(sin(eps) * sin(lam))
        val gmst = (18.697374558 + 24.06570982441908 * n).mod(24.0)
        val ha = Math.toRadians(gmst * 15 + lon) - ra
        val la = Math.toRadians(lat)
        val el = asin(sin(la) * sin(dec) + cos(la) * cos(dec) * cos(ha))
        val az = atan2(-sin(ha), tan(dec) * cos(la) - sin(la) * cos(ha))
        return doubleArrayOf(Math.toDegrees(el), (Math.toDegrees(az) + 360.0).mod(360.0))
    }

    /** Cosinus de l'angle entre le soleil et la normale d'un panneau (inclinaison, azimut boussole). */
    fun cosInc(el: Double, az: Double, tilt: Double, panelAz: Double): Double {
        val e = Math.toRadians(el); val a = Math.toRadians(az)
        val t = Math.toRadians(tilt); val p = Math.toRadians(panelAz)
        return sin(a) * cos(e) * sin(p) * sin(t) + cos(a) * cos(e) * cos(p) * sin(t) + sin(e) * cos(t)
    }
}

object Shading {
    private val WINTER = setOf(11, 12, 1, 2, 3)

    fun span(o: Obstacle): Double = (o.az2 - o.az1 + 360.0).mod(360.0)

    private fun covers(o: Obstacle, az: Double): Boolean = (az - o.az1 + 360.0).mod(360.0) <= span(o)

    /** Part du soleil direct bloquée (0 à 1) pour un soleil à (az, el). */
    fun blocked(az: Double, el: Double, obs: List<Obstacle>, month: Int): Double {
        var b = 0.0
        for (o in obs) {
            if (el <= o.el && covers(o, az)) {
                // Un arbre sans feuilles en hiver laisse passer environ la moitié du soleil
                val f = if (o.leafy && month in WINTER) 0.5 else 1.0
                b = max(b, f)
            }
        }
        return b
    }

    /** Retire le soleil direct caché par les obstacles, heure par heure. */
    fun apply(c: Config, obs: List<Obstacle>, d: Day): Day {
        if (obs.isEmpty() || d.dni.size < 24 || d.h1.size < 24 || d.h2.size < 24) return d
        val date = LocalDate.parse(d.date)
        val start = date.atStartOfDay().toEpochSecond(ZoneOffset.ofTotalSeconds(d.off)) * 1000L
        val n1 = DoubleArray(24); val n2 = DoubleArray(24)
        for (i in 0 until 24) {
            n1[i] = d.h1[i]; n2[i] = d.h2[i]
            // La valeur de HH:00 couvre HH-1 → HH : soleil pris au milieu de l'heure
            val p = Sun.pos(c.lat, c.lon, start + (i * 3600L - 1800L) * 1000L)
            if (p[0] <= 0.0) continue
            val b = blocked(p[1], p[0], obs, date.monthValue)
            if (b <= 0.0) continue
            n1[i] = max(0.0, d.h1[i] - b * d.dni[i] * max(0.0, Sun.cosInc(p[0], p[1], c.tilt, c.a1)))
            n2[i] = max(0.0, d.h2[i] - b * d.dni[i] * max(0.0, Sun.cosInc(p[0], p[1], c.tilt, c.a2)))
        }
        return d.copy(g1 = n1.sum(), g2 = n2.sum(), h1 = n1.toList(), h2 = n2.toList())
    }

    /** Part du soleil direct perdue par ciel clair un jour donné (0 à 1). */
    fun directLoss(c: Config, obs: List<Obstacle>, date: LocalDate): Double {
        if (obs.isEmpty()) return 0.0
        val start = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1000L
        var tot = 0.0
        var lost = 0.0
        for (k in 0 until 24 * 6) {
            val p = Sun.pos(c.lat, c.lon, start + k * 600_000L)
            if (p[0] <= 1.0) continue
            val am = 1.0 / sin(Math.toRadians(p[0]))
            val dni = 0.7.pow(am.pow(0.678))
            val w = dni * (c.p1 * max(0.0, Sun.cosInc(p[0], p[1], c.tilt, c.a1)) +
                c.p2 * max(0.0, Sun.cosInc(p[0], p[1], c.tilt, c.a2)))
            tot += w
            lost += w * blocked(p[1], p[0], obs, date.monthValue)
        }
        return if (tot > 0) lost / tot else 0.0
    }
}
