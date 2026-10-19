package com.thomas.bibisaya

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import com.thomas.bibisaya.engine.Plan
import com.thomas.bibisaya.engine.Slot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Les 3 rappels de la journée, et le rappel du soir pour garder sa série. */
object Notify {
    private const val CHANNEL = "seances"
    const val ACTION_SLOT = "com.thomas.bibisaya.SEANCE"
    const val ACTION_LATER = "com.thomas.bibisaya.PLUS_TARD"
    const val EXTRA_SLOT = "slot"
    private const val SNOOZE_MINUTES = 60

    fun id(slot: Slot) = 100 + slot.ordinal

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Séances du jour", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Les rappels du matin, du midi et du soir"
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)
    }

    /** (Re)programme les 3 rappels, selon les réglages. */
    fun schedule(ctx: Context) {
        if (Store.demo) return
        val store = Store(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val times = store.times
        for (slot in Slot.values()) {
            val pi = alarmIntent(ctx, slot, ACTION_SLOT, slot.ordinal)
            val wanted = store.reminders || (slot == Slot.SOIR && store.streakReminder)
            if (!wanted) {
                am.cancel(pi)
                continue
            }
            val minutes = times.of(slot)
            var at = LocalDate.now().atTime(minutes / 60, minutes % 60)
            if (!at.isAfter(LocalDateTime.now())) at = at.plusDays(1)
            setAlarm(ctx, am, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi)
        }
    }

    private fun setAlarm(ctx: Context, am: AlarmManager, at: Long, pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun alarmIntent(ctx: Context, slot: Slot, action: String, code: Int): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_SLOT, slot.code)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** « Plus tard » : le rappel revient dans une heure (pas après 23 h). */
    fun snooze(ctx: Context, slot: Slot) {
        cancel(ctx, slot)
        val at = LocalDateTime.now().plusMinutes(SNOOZE_MINUTES.toLong())
        if (at.toLocalDate() != LocalDate.now() || at.hour >= 23) return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        setAlarm(ctx, am, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), alarmIntent(ctx, slot, ACTION_SLOT, 10 + slot.ordinal))
    }

    fun cancel(ctx: Context, slot: Slot) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(id(slot))
    }

    /** Affiche le rappel d'une séance, sauf si elle est déjà faite. */
    fun show(ctx: Context, slot: Slot) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val store = Store(ctx)
        val pr = store.progress()
        val today = Store.today()
        if (slot in pr.slotsDone(today)) return
        val content = Data.content(ctx)
        val streak = pr.currentStreak(today)
        val streakCase = slot == Slot.SOIR && store.streakReminder && pr.slotsDone(today).isEmpty() && streak >= 1
        if (!store.reminders && !streakCase) return

        val title: String
        val body: String
        if (streakCase) {
            title = "Ta série de ${Fr.count(streak, "jour", "jours")} t'attend !"
            body = "Une seule séance suffit pour la garder. Magtuon ta !"
        } else {
            val line = Data.lineOf(content, pr, slot, today)
            title = Plan.sessionTitle(slot, line)
            val teaser = Plan.teaser(content, pr, line, today)
            body = (if (teaser != null) "Unsa ang « ${teaser.fr} » sa Bisaya ? " else "") + "Magtuon ta ! 10 exercices, 4 minutes."
        }

        ensureChannel(ctx)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val home = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val start = PendingIntent.getActivities(
            ctx, 20 + slot.ordinal,
            arrayOf(home, Intent(ctx, SessionActivity::class.java).putExtra(SessionActivity.EXTRA_SLOT, slot.code)),
            flags,
        )
        val open = PendingIntent.getActivity(
            ctx, 30 + slot.ordinal,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags,
        )
        val later = PendingIntent.getBroadcast(
            ctx, 40 + slot.ordinal,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_LATER).putExtra(EXTRA_SLOT, slot.code),
            flags,
        )
        val icon = Icon.createWithResource(ctx, R.drawable.ic_notif)
        // La séance reste disponible jusqu'à minuit : le rappel disparaît à minuit.
        val midnight = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(C.NAVY)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setTimeoutAfter((midnight - System.currentTimeMillis()).coerceAtLeast(60_000L))
            .addAction(Notification.Action.Builder(icon, "COMMENCER", start).build())
            .addAction(Notification.Action.Builder(icon, "Plus tard", later).build())
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify(id(slot), n)
    }
}

/** Reçoit l'heure d'une séance (ou « Plus tard »). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val slot = Slot.of(intent.getStringExtra(Notify.EXTRA_SLOT) ?: "") ?: return
        when (intent.action) {
            Notify.ACTION_SLOT -> {
                try {
                    Notify.show(ctx, slot)
                } finally {
                    Notify.schedule(ctx)
                }
            }
            Notify.ACTION_LATER -> Notify.snooze(ctx, slot)
        }
    }
}

/** Après un redémarrage, une mise à jour ou un changement d'heure : on reprogramme les rappels. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notify.schedule(ctx)
    }
}
