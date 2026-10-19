package com.thomas.bibisaya

import android.app.Activity
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.thomas.bibisaya.engine.Slot
import com.thomas.bibisaya.engine.Times

/** Les réglages : l'heure des 3 rappels, le rappel de la série, et tout recommencer à zéro. */
class SettingsActivity : Activity() {
    private lateinit var store: Store
    private lateinit var blocked: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        store = Store(this)

        val root = vbox()
        val top = View(this).apply { setBackgroundColor(C.YELLOW) }
        root.addView(top, lp(MATCH, 0))
        val header = hbox(14).apply {
            setBackgroundColor(C.YELLOW)
            pad(20, 22, 20, 16)
        }
        header.addView(squareButton(R.drawable.ic_back, "Retour à l'accueil", shadow = true) { finish() })
        header.addView(text("RÉGLAGES", 30f, 800, line = 0.95f))
        root.addView(header, lp())
        root.addView(stripes())

        val body = vbox(14).apply { pad(20, 20, 20, 16) }
        body.addView(label("NOTIFICATIONS", 14f))
        blocked = blockedCard()
        body.addView(blocked, lp())
        body.addView(notificationsCard(), lp())
        body.addView(label("MA PROGRESSION", 14f).apply { pad(0, 6, 0, 0) })
        body.addView(progressCard(), lp())
        body.addView(View(this), lp(MATCH, 0, 1f))
        body.addView(text("BiBisaya · version ${version()}", 13f, 600, C.GRAY).apply { gravity = Gravity.CENTER }, lp())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        root.addView(scroll, lp(MATCH, 0, 1f))
        setContentView(root)
        edgeToEdge(root, top, body, lightNavigation = true)
    }

    override fun onResume() {
        super.onResume()
        // Si les notifications sont bloquées dans le téléphone, on le dit (et on aide à les remettre).
        val enabled = getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: true
        blocked.visibility = if (enabled || Store.demo) View.GONE else View.VISIBLE
    }

    private fun row(vertical: Int): LinearLayout = hbox(12).apply { pad(14, vertical, 14, vertical) }

    private fun notificationsCard(): View {
        val card = vbox().apply {
            pad(3, 3, 3, 3)
            box(C.WHITE, 18, C.NAVY, 3)
        }
        val reminders = row(12)
        reminders.addView(text("Rappels activés", 17f, 800), lp(0, WRAP, 1f))
        reminders.addView(Toggle(this, store.reminders) { on ->
            store.reminders = on
            Notify.schedule(this)
        }.apply { contentDescription = "Rappels activés" })
        card.addView(reminders, lp())

        val times = store.times
        val names = mapOf(Slot.MATIN to "vocabulaire", Slot.MIDI to "phrases simples", Slot.SOIR to "en alternance")
        for (slot in Slot.values()) {
            card.addView(dashes())
            val r = row(10)
            r.addView(text("${slot.label} · ${names[slot]}", 16f, 700), lp(0, WRAP, 1f))
            val button = text("", 17f, 800).apply {
                gravity = Gravity.CENTER
                box(C.CREAM, 12, C.NAVY, 3)
                squishy()
            }
            showTime(button, slot, times.of(slot))
            button.onTap { pickTime(slot, button) }
            r.addView(button, LinearLayout.LayoutParams(dp(84), dp(44)))
            card.addView(r, lp())
        }

        card.addView(dashes())
        val streak = row(12)
        val words = vbox()
        words.addView(text("Rappel pour ma série", 16f, 700))
        words.addView(text("le soir, si je n'ai fait aucune séance", 13f, 600, C.GRAY, line = 1.2f))
        streak.addView(words, lp(0, WRAP, 1f))
        streak.addView(Toggle(this, store.streakReminder) { on ->
            store.streakReminder = on
            Notify.schedule(this)
        }.apply { contentDescription = "Rappel pour ma série" })
        card.addView(streak, lp())
        return card
    }

    private fun showTime(button: TextView, slot: Slot, minutes: Int) {
        button.text = Times.clock(minutes)
        button.contentDescription = "Heure de la séance ${slot.adjective} : ${Times.spoken(minutes)}"
    }

    private fun pickTime(slot: Slot, button: TextView) {
        val minutes = store.times.of(slot)
        TimePickerDialog(this, R.style.Theme_BiBisaya_TimePicker, { _, hour, minute ->
            val chosen = hour * 60 + minute
            store.times = store.times.with(slot, chosen)
            showTime(button, slot, chosen)
            Notify.schedule(this)
        }, minutes / 60, minutes % 60, true).show()
    }

    /** Affichée seulement quand les notifications de l'appli sont coupées dans le téléphone. */
    private fun blockedCard(): View {
        val card = vbox(10).apply {
            pad(14, 14, 14, 14)
            box(C.WRONG_BG, 18, C.LABEL, 3)
        }
        card.addView(text("Les rappels sont bloqués sur ce téléphone", 16f, 800, C.WRONG_TEXT, line = 1.2f))
        card.addView(text("Sans eux, pas de notification pour tes séances du matin, du midi et du soir.", 14f, 600, C.WRONG_TEXT, line = 1.3f))
        card.addView(primaryButton("AUTORISER LES RAPPELS", 48, 16f) { openNotificationSettings() }, lp())
        return card
    }

    private fun openNotificationSettings() {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(i)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun progressCard(): View {
        val card = vbox(10).apply {
            pad(14, 14, 14, 14)
            box(C.WHITE, 18, C.NAVY, 3)
        }
        card.addView(text("Tout est gardé sur ton téléphone, sans compte et sans internet.", 15f, 500, line = 1.35f))
        card.addView(outlineButton("Tout recommencer à zéro", C.LABEL, 48, 16f) {
            confirm(
                "Tout recommencer ?",
                "Tes points, ta série, tes badges et tous tes mots seront effacés. Impossible de revenir en arrière.",
                "TOUT EFFACER", "Annuler",
                danger = true,
            ) {
                store.reset()
                Data.running = null
                Notify.schedule(this)
                Toast.makeText(this, "C'est reparti de zéro. Sakay na !", Toast.LENGTH_LONG).show()
            }
        }.apply { typeface = Fonts.get(800, 16f) }, lp())
        return card
    }

    private fun version(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }
}
