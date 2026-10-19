package com.thomas.bibisaya

import android.content.Context
import com.thomas.bibisaya.engine.Content
import com.thomas.bibisaya.engine.Exercise
import com.thomas.bibisaya.engine.Kind
import com.thomas.bibisaya.engine.Line
import com.thomas.bibisaya.engine.Outcome
import com.thomas.bibisaya.engine.Plan
import com.thomas.bibisaya.engine.Planner
import com.thomas.bibisaya.engine.Progress
import com.thomas.bibisaya.engine.Session
import com.thomas.bibisaya.engine.Slot
import com.thomas.bibisaya.engine.Times
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/** Tout ce que l'appli garde sur le téléphone : la progression et les réglages. */
class Store(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences(if (demo) "demo" else "bibisaya", Context.MODE_PRIVATE)

    /** La progression ; le tout premier jour est noté à la première ouverture. */
    fun progress(): Progress {
        val pr = Progress.decode(p.getString("progress", null))
        if (pr.installDay < 0) {
            pr.installDay = today()
            save(pr)
        }
        return pr
    }

    fun save(pr: Progress) {
        p.edit().putString("progress", pr.encode()).apply()
    }

    /** « Tout recommencer à zéro » : la progression s'efface, les réglages restent. */
    fun reset() {
        p.edit().remove("progress").commit()
    }

    var times: Times
        get() = Times(p.getInt("t_matin", 8 * 60), p.getInt("t_midi", 12 * 60 + 30), p.getInt("t_soir", 19 * 60))
        set(v) {
            p.edit().putInt("t_matin", v.matin).putInt("t_midi", v.midi).putInt("t_soir", v.soir).apply()
        }

    var reminders: Boolean
        get() = p.getBoolean("reminders", true)
        set(v) {
            p.edit().putBoolean("reminders", v).apply()
        }

    var streakReminder: Boolean
        get() = p.getBoolean("streak_reminder", true)
        set(v) {
            p.edit().putBoolean("streak_reminder", v).apply()
        }

    var askedNotifications: Boolean
        get() = p.getBoolean("asked_notifications", false)
        set(v) {
            p.edit().putBoolean("asked_notifications", v).apply()
        }

    companion object {
        /** Mode « démo » : des données d'exemple, pour les captures d'écran (jamais utilisé par Thomas). */
        var demo = false

        fun today(): Long = LocalDate.now().toEpochDay()

        fun minuteOfDay(): Int = LocalTime.now().let { it.hour * 60 + it.minute }
    }
}

/** La séance en cours, gardée tant que l'appli est ouverte (même si l'écran est recréé). */
class Running(
    val slot: Slot?,
    val bonus: Boolean,
    val day: Long,
    val progress: Progress,
    val session: Session,
) {
    var outcome: Outcome? = null
}

/** Ce qui est partagé par tous les écrans. */
object Data {
    @Volatile
    private var content: Content? = null
    var running: Running? = null

    fun content(ctx: Context): Content = content ?: synchronized(this) {
        content ?: Content.parse(ctx.assets.open("contenu.txt").bufferedReader().use { it.readText() }).also { content = it }
    }

    /** Fausse progression de 12 jours, pour montrer l'appli dans un état réaliste. */
    fun fillDemo(store: Store, content: Content) {
        val pr = Progress.decode(null)
        val today = Store.today()
        pr.installDay = today - 11
        val random = Random(7)
        for (day in pr.installDay..today) {
            for (slot in Slot.values()) {
                if (day == today && slot == Slot.SOIR) break
                val s = Planner.build(content, pr, slot, Plan.lineFor(slot, day, pr.installDay), day, random)
                while (!s.finished) {
                    val step = s.current!!
                    s.onShown(step)
                    if (step is Exercise) {
                        val ok = random.nextDouble() < 0.88
                        val wrong = if (step.kind == Kind.MATCH && !ok) setOf(step.items.first()) else emptySet()
                        s.submit(step, ok, wrong)
                    }
                    s.next()
                }
                pr.finishSession(content, slot, day, s.correctFirst, s.gradedTotal)
            }
        }
        store.save(pr)
    }

    /** La ligne travaillée par une séance (null : les 3 lignes mélangées). */
    fun lineOf(content: Content, pr: Progress, slot: Slot?, day: Long): Line? =
        if (slot == null) null else Plan.effectiveLine(content, pr, slot, day)
}
