package be.florent.soleil

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.roundToInt

/** Jauge de batterie verticale animée. */
class BatteryView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val d = resources.displayMetrics.density
    private val fgColor = 0xFFEAF0F2.toInt()
    private val inkColor = 0xFF0F1720.toInt()
    private val battColor = 0xFF4CC283.toInt()
    private val lowColor = 0xFFF2B632.toInt()

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; color = fgColor
    }
    private val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fgColor }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = battColor }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fgColor
        textAlign = Paint.Align.CENTER
        textSize = 17f * d
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private var target = 0f
    private var shown = 0f
    private var anim: ValueAnimator? = null

    fun setLevel(v: Float) {
        target = v.coerceIn(0f, 1f)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, target).apply {
            duration = 800
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val sw = outline.strokeWidth
        val capH = 7f * d

        canvas.drawRoundRect(RectF(w * 0.3f, 0f, w * 0.7f, capH + sw), 3f * d, 3f * d, cap)

        val body = RectF(sw / 2f, capH + sw / 2f, w - sw / 2f, h - sw / 2f)
        canvas.drawRoundRect(body, 12f * d, 12f * d, outline)

        val inner = RectF(body.left + 5f * d, body.top + 5f * d, body.right - 5f * d, body.bottom - 5f * d)
        val top = inner.bottom - inner.height() * shown
        if (shown > 0.005f) {
            fill.color = if (target < 0.15f) lowColor else battColor
            canvas.drawRoundRect(RectF(inner.left, top, inner.right, inner.bottom), 7f * d, 7f * d, fill)
        }

        val cy = body.centerY()
        text.color = if (top < cy - text.textSize / 2f) inkColor else fgColor
        val label = "${(shown * 100).roundToInt()}%"
        canvas.drawText(label, body.centerX(), cy - (text.descent() + text.ascent()) / 2f, text)
    }
}
