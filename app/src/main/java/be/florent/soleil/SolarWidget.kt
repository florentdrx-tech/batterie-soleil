package be.florent.soleil

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.LocalDate
import kotlin.math.roundToInt

class SolarWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val views = build(ctx)
        ids.forEach { mgr.updateAppWidget(it, views) }
        UpdateWorker.schedule(ctx)
        UpdateWorker.now(ctx)
    }

    override fun onEnabled(ctx: Context) {
        UpdateWorker.schedule(ctx)
    }

    companion object {
        fun build(ctx: Context): RemoteViews {
            val c = Store.load(ctx)
            val today = LocalDate.now().toString()
            val fut = Store.days(ctx).filter { it.date >= today }
            val v = RemoteViews(ctx.packageName, R.layout.widget)

            if (fut.isEmpty()) {
                v.setTextViewText(R.id.title, "Aujourd'hui")
                v.setTextViewText(R.id.kwh, "– kWh")
                v.setTextViewText(R.id.pct, "")
                v.setProgressBar(R.id.bar, 100, 0, false)
                v.setTextViewText(R.id.verdict, "Mise à jour… (connexion internet requise)")
                v.setTextViewText(R.id.next, "")
            } else {
                val t = fut[0]
                val k = Forecast.est(c, t)
                val lvl = Store.level(ctx)
                val end = lvl?.let { Forecast.endLevel(c, t, it.first / 100.0, it.second) }
                v.setTextViewText(R.id.title, "Aujourd'hui · ☀ %.1f h · màj %s".format(t.sun, Store.updatedAt(ctx)))
                v.setTextViewText(R.id.kwh, "%.1f kWh".format(k))
                if (lvl != null && end != null) {
                    val e = (end * 100).roundToInt()
                    v.setTextViewText(R.id.pct, "ce soir ≈ $e %")
                    v.setProgressBar(R.id.bar, 100, e, false)
                    v.setTextViewText(R.id.verdict, Forecast.levelVerdict(lvl.first / 100.0, end))
                } else {
                    val p = (Forecast.battPct(c, k) * 100).roundToInt()
                    v.setTextViewText(R.id.pct, "batterie $p %")
                    v.setProgressBar(R.id.bar, 100, p, false)
                    v.setTextViewText(R.id.verdict, Forecast.verdict(c, k))
                }
                val n = fut.getOrNull(1)
                if (n != null) {
                    val nk = Forecast.est(c, n)
                    val np = (Forecast.battPct(c, nk) * 100).roundToInt()
                    v.setTextViewText(R.id.next, "Demain : %.1f kWh · batterie %d %%".format(nk, np))
                } else {
                    v.setTextViewText(R.id.next, "")
                }
            }

            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.root, pi)
            return v
        }

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SolarWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(ctx)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }
    }
}
