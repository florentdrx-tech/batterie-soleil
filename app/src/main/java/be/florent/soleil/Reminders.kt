package be.florent.soleil

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Rappels du matin (niveau de batterie) et du soir (production du jour). */
object Reminders {

    const val MORNING = "morning"
    const val EVENING = "evening"
    private const val CHANNEL = "rappels"
    const val EXTRA_OPEN = "open"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("soleil", Context.MODE_PRIVATE)

    fun enabled(ctx: Context, kind: String) = prefs(ctx).getBoolean("rem_${kind}_on", false)
    fun hour(ctx: Context, kind: String) = prefs(ctx).getInt("rem_${kind}_h", if (kind == MORNING) 8 else 20)
    fun minute(ctx: Context, kind: String) = prefs(ctx).getInt("rem_${kind}_m", 0)

    fun set(ctx: Context, kind: String, on: Boolean, h: Int, m: Int) {
        prefs(ctx).edit().putBoolean("rem_${kind}_on", on)
            .putInt("rem_${kind}_h", h).putInt("rem_${kind}_m", m).apply()
        schedule(ctx)
    }

    private fun pending(ctx: Context, kind: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction("be.florent.soleil.REMIND_$kind")
        return PendingIntent.getBroadcast(
            ctx, if (kind == MORNING) 101 else 102, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Programme le prochain rappel de chaque type (fenêtre de 10 min, pas besoin d'alarme exacte). */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (kind in listOf(MORNING, EVENING)) {
            val pi = pending(ctx, kind)
            am.cancel(pi)
            if (!enabled(ctx, kind)) continue
            val now = LocalDateTime.now()
            var t = now.toLocalDate().atTime(hour(ctx, kind), minute(ctx, kind))
            if (!t.isAfter(now.plusMinutes(1))) t = t.plusDays(1)
            val ms = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            am.setWindow(AlarmManager.RTC_WAKEUP, ms, 10 * 60 * 1000L, pi)
        }
    }

    private fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, L.t("Rappels", "Herinneringen"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = L.t("Rappels pour encoder le niveau de batterie et la production", "Herinneringen om batterijniveau en productie in te geven")
                }
            )
        }
    }

    fun notify(ctx: Context, kind: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return

        // Pas de rappel si c'est déjà fait aujourd'hui
        val today = LocalDate.now().toString()
        if (kind == MORNING && Store.level(ctx) != null) return
        if (kind == EVENING && Store.calibs(ctx).any { it.date == today }) return

        channel(ctx)
        val c = Store.load(ctx)
        val fut = Store.days(ctx).filter { it.date >= today }

        val title: String
        val text: String
        if (kind == MORNING) {
            title = L.t("Quel est le niveau de ta batterie ?", "Wat is het niveau van je batterij?")
            val k = fut.firstOrNull()?.let { Forecast.est(c, it) }
            val tip = Appliances.headline(c, Tariffs.load(ctx), fut, Appliances.all(ctx))
            text = listOfNotNull(
                k?.let { String.format(L.loc, L.t("☀ %.1f kWh prévus aujourd'hui.", "☀ %.1f kWh verwacht vandaag."), it) },
                tip?.let { "💡 $it" },
                L.t("Touche pour entrer le %.", "Tik om het % in te geven.")
            ).joinToString(" ")
        } else {
            title = L.t("Combien as-tu produit aujourd'hui ?", "Hoeveel heb je vandaag geproduceerd?")
            text = L.t("Entre la production de ton onduleur pour affiner les prévisions.", "Geef de productie van je omvormer in om de voorspellingen te verfijnen.")
        }

        val open = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_OPEN, kind)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            ctx, if (kind == MORNING) 201 else 202, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFFF2B632.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(if (kind == MORNING) 1 else 2, n)
    }

    fun needsPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            "be.florent.soleil.REMIND_${Reminders.MORNING}" -> Reminders.notify(ctx, Reminders.MORNING)
            "be.florent.soleil.REMIND_${Reminders.EVENING}" -> Reminders.notify(ctx, Reminders.EVENING)
        }
        // Reprogrammer le rappel suivant (aussi après redémarrage ou mise à jour)
        Reminders.schedule(ctx)
    }
}
