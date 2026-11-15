package be.florent.soleil

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.time.LocalDate
import java.time.ZoneOffset

/** Diagramme d'horizon : trajets du soleil (hiver, équinoxe, été) et obstacles encodés. */
class HorizonView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val d = resources.displayMetrics.density
    private var lat = 50.41
    private var lon = 4.59
    private var obs: List<Obstacle> = emptyList()
    private var paths: List<List<DoubleArray>> = emptyList()

    private val azMin = 45.0
    private val azMax = 315.0
    private val elMax = 70.0

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A3844.toInt(); strokeWidth = 1f * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8FA0AA.toInt(); textSize = 10f * d; textAlign = Paint.Align.CENTER
    }
    private val sunP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND
    }
    private val tree = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA4CC283.toInt() }
    private val build = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA8FA0AA.toInt() }

    fun set(lat: Double, lon: Double, obs: List<Obstacle>) {
        this.lat = lat; this.lon = lon; this.obs = obs
        val y = LocalDate.now().year
        paths = listOf(LocalDate.of(y, 6, 21), LocalDate.of(y, 3, 20), LocalDate.of(y, 12, 21)).map { day ->
            val start = day.atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1000L
            (0 until 24 * 6).map { Sun.pos(lat, lon, start + it * 600_000L) }.filter { it[0] > 0 }
        }
        invalidate()
    }

    private val left get() = 8f * d
    private val right get() = width - 8f * d
    private val top get() = 8f * d
    private val bottom get() = height - 18f * d

    private fun x(az: Double) = (left + (az - azMin) / (azMax - azMin) * (right - left)).toFloat()
    private fun y(el: Double) = (bottom - el / elMax * (bottom - top)).toFloat()

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        for (el in listOf(0.0, 20.0, 40.0, 60.0)) c.drawLine(left, y(el), right, y(el), grid)
        for ((az, name) in listOf(90.0 to "E", 135.0 to "SE", 180.0 to "S", 225.0 to "SO", 270.0 to "O")) {
            c.drawLine(x(az), top, x(az), bottom, grid)
            c.drawText(name, x(az), height - 4f * d, label)
        }
        label.textAlign = Paint.Align.LEFT
        for (el in listOf(20.0, 40.0, 60.0)) c.drawText("${el.toInt()}°", left + 2f * d, y(el) - 3f * d, label)
        label.textAlign = Paint.Align.CENTER

        // Obstacles
        for (o in obs) {
            val p = if (o.leafy) tree else build
            val a1 = o.az1; val a2 = o.az1 + Shading.span(o)
            fun rect(s: Double, e: Double) {
                val l = x(s.coerceIn(azMin, azMax)); val r = x(e.coerceIn(azMin, azMax))
                if (r > l) c.drawRect(l, y(o.el.coerceAtMost(elMax)), r, y(0.0), p)
            }
            rect(a1, a2)
            if (a2 > 360) rect(a1 - 360, a2 - 360)
        }

        // Trajets du soleil : été, équinoxe, hiver
        val colors = listOf(0xFFF2B632.toInt(), 0xBBF2B632.toInt(), 0xFFE8961A.toInt())
        paths.forEachIndexed { i, pts ->
            sunP.color = colors[i]
            val path = Path()
            var started = false
            for (p in pts) {
                if (p[1] < azMin || p[1] > azMax) { started = false; continue }
                if (!started) { path.moveTo(x(p[1]), y(p[0])); started = true } else path.lineTo(x(p[1]), y(p[0]))
            }
            c.drawPath(path, sunP)
        }
    }
}
