package be.florent.soleil

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Cadran de boussole (ou rapporteur d'inclinaison en mode tilt). */
class CompassView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var angle = 0f
        set(v) { field = v; invalidate() }
    var tiltMode = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val fg = 0xFFEAF0F2.toInt()
    private val muted = 0xFF8FA0AA.toInt()
    private val line = 0xFF2A3844.toInt()
    private val sun = 0xFFF2B632.toInt()
    private val card2 = 0xFF22303C.toInt()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (tiltMode) drawTilt(c) else drawCompass(c)
    }

    private fun drawCompass(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 14f * d

        p.style = Paint.Style.FILL; p.color = card2
        c.drawCircle(cx, cy, r, p)

        c.save()
        c.rotate(-angle, cx, cy)
        p.style = Paint.Style.STROKE
        for (deg in 0 until 360 step 5) {
            val major = deg % 30 == 0
            p.color = if (major) fg else line
            p.strokeWidth = if (major) 2.5f * d else 1.5f * d
            val len = if (major) 14f * d else 7f * d
            val a = Math.toRadians(deg.toDouble() - 90)
            c.drawLine(
                cx + (r - 2 * d) * cos(a).toFloat(), cy + (r - 2 * d) * sin(a).toFloat(),
                cx + (r - 2 * d - len) * cos(a).toFloat(), cy + (r - 2 * d - len) * sin(a).toFloat(), p
            )
        }
        p.style = Paint.Style.FILL
        val labels = mapOf(0 to "N", 90 to "E", 180 to "S", 270 to "O")
        for (deg in 0 until 360 step 30) {
            val a = Math.toRadians(deg.toDouble() - 90)
            val rr = r - 34f * d
            val lab = labels[deg]
            t.textSize = if (lab != null) 18f * d else 11f * d
            t.color = when (lab) { "N" -> sun; null -> muted; else -> fg }
            val x = cx + rr * cos(a).toFloat()
            val y = cy + rr * sin(a).toFloat() - (t.descent() + t.ascent()) / 2f
            c.save()
            c.rotate(deg.toFloat(), x, y + (t.descent() + t.ascent()) / 2f)
            c.drawText(lab ?: deg.toString(), x, y, t)
            c.restore()
        }
        c.restore()

        // Repère fixe : direction du haut du téléphone
        p.color = sun
        val path = Path().apply {
            moveTo(cx, cy - r - 10f * d)
            lineTo(cx - 9f * d, cy - r + 8f * d)
            lineTo(cx + 9f * d, cy - r + 8f * d)
            close()
        }
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d
        c.drawLine(cx, cy - r + 10f * d, cx, cy, p)
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, 5f * d, p)
    }

    private fun drawTilt(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val x0 = w * 0.15f
        val y0 = h * 0.78f
        val len = w * 0.7f
        val a = Math.toRadians(angle.coerceIn(0f, 90f).toDouble())

        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.color = muted; p.strokeWidth = 2f * d
        c.drawLine(x0, y0, x0 + len, y0, p)

        val arcR = len * 0.35f
        p.color = sun; p.strokeWidth = 2f * d
        c.drawArc(RectF(x0 - arcR, y0 - arcR, x0 + arcR, y0 + arcR), -angle.coerceIn(0f, 90f), angle.coerceIn(0f, 90f), false, p)

        p.color = fg; p.strokeWidth = 8f * d
        c.drawLine(x0, y0, x0 + len * cos(a).toFloat(), y0 - len * sin(a).toFloat(), p)
        p.strokeCap = Paint.Cap.BUTT

        p.style = Paint.Style.FILL; p.color = sun
        c.drawCircle(x0, y0, 6f * d, p)
    }
}
